/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wallpaper.grass;

import android.content.res.Resources;
import android.renderscript.Sampler;
import static android.renderscript.ProgramFragment.EnvMode.*;
import static android.renderscript.ProgramStore.DepthFunc.*;
import static android.renderscript.ProgramStore.BlendSrcFunc;
import static android.renderscript.ProgramStore.BlendDstFunc;
import android.renderscript.RenderScript;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.Allocation;
import android.renderscript.ProgramVertex;
import static android.renderscript.Element.*;
import static android.util.MathUtils.*;
import android.renderscript.ScriptC;
import android.renderscript.Type;
import android.renderscript.Dimension;
import android.renderscript.Element;
import android.renderscript.SimpleMesh;
import android.renderscript.Primitive;
import static android.renderscript.Sampler.Value.*;
import com.android.wallpaper.R;

import java.util.TimeZone;

class GrassRS {
    private static final float TESSELATION = 0.5f;
    
    private static final int RSID_STATE = 0;

    private static final int TEXTURES_COUNT = 5;

    private static final int RSID_BLADES = 1;
    private static final int BLADES_COUNT = 200;
    private static final int BLADE_STRUCT_FIELDS_COUNT = 13;
    private static final int BLADE_STRUCT_ANGLE = 0;
    private static final int BLADE_STRUCT_SIZE = 1;
    private static final int BLADE_STRUCT_XPOS = 2;
    private static final int BLADE_STRUCT_YPOS = 3;
    private static final int BLADE_STRUCT_OFFSET = 4;
    private static final int BLADE_STRUCT_SCALE = 5;
    private static final int BLADE_STRUCT_LENGTHX = 6;
    private static final int BLADE_STRUCT_LENGTHY = 7;
    private static final int BLADE_STRUCT_HARDNESS = 8;
    private static final int BLADE_STRUCT_H = 9;
    private static final int BLADE_STRUCT_S = 10;
    private static final int BLADE_STRUCT_B = 11;
    private static final int BLADE_STRUCT_TURBULENCEX = 12;

    private static final int RSID_BLADES_BUFFER = 2;

    private Resources mResources;
    private RenderScript mRS;

    private final int mWidth;
    private final int mHeight;

    @SuppressWarnings({ "FieldCanBeLocal" })
    private ScriptC mScript;
    @SuppressWarnings({ "FieldCanBeLocal" })
    private ProgramFragment mPfBackground;
    @SuppressWarnings({ "FieldCanBeLocal" })
    private ProgramStore mPfsBackground;
    @SuppressWarnings({ "FieldCanBeLocal" })
    private ProgramVertex mPvBackground;

    @SuppressWarnings({ "FieldCanBeLocal" })
    private Allocation[] mTextures;

    private Type mStateType;
    private Allocation mState;

    private Allocation mBlades;
    private Allocation mBladesBuffer;

    private int mTriangles;
    private final float[] mFloatData5 = new float[5];
    private SimpleMesh mBladesMesh;

    public GrassRS(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    public void init(RenderScript rs, Resources res) {
        mRS = rs;
        mResources = res;
        initRS();
    }

    private void initRS() {
        createProgramVertex();
        createProgramFragmentStore();
        createProgramFragment();
        createScriptStructures();
        loadTextures();

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setType(mStateType, "State", RSID_STATE);
        sb.setScript(mResources, R.raw.grass);
        sb.setRoot(true);

        mScript = sb.create();
        mScript.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        mScript.setTimeZone(TimeZone.getDefault().getID());

        mScript.bindAllocation(mState, RSID_STATE);
        mScript.bindAllocation(mBlades, RSID_BLADES);
        mScript.bindAllocation(mBladesBuffer, RSID_BLADES_BUFFER);

        mRS.contextBindRootScript(mScript);
    }

    private void createScriptStructures() {
        createBlades();
        createState();
    }

    static class WorldState {
        public int frameCount;
        public int bladesCount;
        public int trianglesCount;
        public int width;
        public int height;
    }

    private void createState() {
        WorldState state = new WorldState();
        state.width = mWidth;
        state.height = mHeight;
        state.bladesCount = BLADES_COUNT;
        state.trianglesCount = mTriangles;

        mStateType = Type.createFromClass(mRS, WorldState.class, 1, "WorldState");
        mState = Allocation.createTyped(mRS, mStateType);
        mState.data(state);
    }

    private void createBlades() {
        int triangles = 0;

        final float[] blades = new float[BLADES_COUNT * BLADE_STRUCT_FIELDS_COUNT];
        for (int i = 0; i < blades.length; i+= BLADE_STRUCT_FIELDS_COUNT) {
            triangles += createBlade(blades, i);
        }

        mBlades = Allocation.createSized(mRS, USER_FLOAT, blades.length);
        mBlades.data(blades);

        mTriangles = triangles;

        createMesh(triangles);
    }

    private void createMesh(int triangles) {
        Builder elementBuilder = new Builder(mRS);
        elementBuilder.addUNorm8RGBA();
        elementBuilder.addFloatXY();
        elementBuilder.addFloatST();
        final Element vertexElement = elementBuilder.create();

        final SimpleMesh.Builder meshBuilder = new SimpleMesh.Builder(mRS);
        final int vertexSlot = meshBuilder.addVertexType(vertexElement, triangles * 3);
        meshBuilder.setPrimitive(Primitive.TRIANGLE);
        mBladesMesh = meshBuilder.create();
        mBladesMesh.setName("BladesMesh");

        mBladesBuffer = mBladesMesh.createVertexAllocation(vertexSlot);
        mBladesBuffer.setName("BladesBuffer");
        mBladesMesh.bindVertexAllocation(mBladesBuffer, 0);

        // Assign the texture coordinates of each triangle
        final float[] floatData = mFloatData5;
        final Allocation buffer = mBladesBuffer;

        int bufferIndex = 0;
        for (int i = 0; i < triangles; i += 2) {
            floatData[3] = 0.0f;
            floatData[4] = 1.0f;
            buffer.subData1D(bufferIndex, 1, floatData);
            bufferIndex++;

            floatData[3] = 0.0f;
            floatData[4] = 0.0f;
            buffer.subData1D(bufferIndex, 1, floatData);
            bufferIndex++;

            floatData[3] = 1.0f;
            floatData[4] = 0.0f;
            buffer.subData1D(bufferIndex, 1, floatData);
            bufferIndex++;

            floatData[3] = 0.0f;
            floatData[4] = 0.0f;
            buffer.subData1D(bufferIndex, 1, floatData);
            bufferIndex++;

            floatData[3] = 1.0f;
            floatData[4] = 1.0f;
            buffer.subData1D(bufferIndex, 1, floatData);
            bufferIndex++;

            floatData[3] = 1.0f;
            floatData[4] = 0.0f;
            buffer.subData1D(bufferIndex, 1, floatData);
            bufferIndex++;
        }
    }

    private int createBlade(float[] blades, int index) {
        final float size = random(4.0f) + 4.0f;
        final int xpos = random(mWidth);

        //noinspection PointlessArithmeticExpression
        blades[index + BLADE_STRUCT_ANGLE] = 0.0f;
        blades[index + BLADE_STRUCT_SIZE] = size / TESSELATION;
        blades[index + BLADE_STRUCT_XPOS] = xpos;
        blades[index + BLADE_STRUCT_YPOS] = mHeight;
        blades[index + BLADE_STRUCT_OFFSET] = random(0.2f) - 0.1f;
        blades[index + BLADE_STRUCT_SCALE] = 4.0f / (size / TESSELATION) +
                (random(0.6f) + 0.2f) * TESSELATION;
        blades[index + BLADE_STRUCT_LENGTHX] = (random(4.5f) + 3.0f) * TESSELATION * size;
        blades[index + BLADE_STRUCT_LENGTHY] = (random(5.5f) + 2.0f) * TESSELATION * size;
        blades[index + BLADE_STRUCT_HARDNESS] = (random(1.0f) + 0.2f) * TESSELATION;
        blades[index + BLADE_STRUCT_H] = random(0.02f) + 0.2f;
        blades[index + BLADE_STRUCT_S] = random(0.22f) + 0.78f;
        blades[index + BLADE_STRUCT_B] = random(0.65f) + 0.35f;
        blades[index + BLADE_STRUCT_TURBULENCEX] = xpos * 0.006f;

        // Each blade is made of "size" quads, so we double to count the triangles
        return (int) (blades[index + BLADE_STRUCT_SIZE]) * 2;
    }

    private void loadTextures() {
        mTextures = new Allocation[TEXTURES_COUNT];

        final Allocation[] textures = mTextures;
        textures[0] = loadTexture(R.drawable.night, "TNight");
        textures[1] = loadTexture(R.drawable.sunrise, "TSunrise");
        textures[2] = loadTexture(R.drawable.sky, "TSky");
        textures[3] = loadTexture(R.drawable.sunset, "TSunset");
        textures[4] = generateTextureAlpha(4, 1, new int[] { 0x00FFFF00 }, "TAa");

        final int count = textures.length;
        for (int i = 0; i < count; i++) {
            textures[i].uploadToTexture(0);
        }
    }

    private Allocation generateTextureAlpha(int width, int height, int[] data, String name) {
        final Type.Builder builder = new Type.Builder(mRS, A_8);
        builder.add(Dimension.X, width);
        builder.add(Dimension.Y, height);
        
        final Allocation allocation = Allocation.createTyped(mRS, builder.create());
        allocation.data(data);
        allocation.setName(name);
        return allocation;
    }

    private Allocation loadTexture(int id, String name) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mResources,
                id, RGB_565, false);
        allocation.setName(name);
        return allocation;
    }

    private void createProgramFragment() {
        Sampler.Builder samplerBuilder = new Sampler.Builder(mRS);
        samplerBuilder.setMin(LINEAR);
        samplerBuilder.setMag(LINEAR);
        samplerBuilder.setWrapS(WRAP);
        samplerBuilder.setWrapT(WRAP);
        Sampler sampler = samplerBuilder.create();

        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(REPLACE, 0);
        mPfBackground = builder.create();
        mPfBackground.setName("PFBackground");
        mPfBackground.bindSampler(sampler, 0);
    }

    private void createProgramFragmentStore() {
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        builder.setDitherEnable(false);
        builder.setDepthMask(false);
        mPfsBackground = builder.create();
        mPfsBackground.setName("PFSBackground");
    }

    private void createProgramVertex() {
        ProgramVertex.MatrixAllocation pvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        pvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);

        ProgramVertex.Builder pvb = new ProgramVertex.Builder(mRS, null, null);
        pvb.setTextureMatrixEnable(true);
        mPvBackground = pvb.create();
        mPvBackground.bindAllocation(pvOrthoAlloc);
        mPvBackground.setName("PVBackground");
    }
}
