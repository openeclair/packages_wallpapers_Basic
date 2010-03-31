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

package com.android.wallpaper.fall;

import android.os.Bundle;
import android.renderscript.Element;
import android.renderscript.ScriptC;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.Allocation;
import android.renderscript.Sampler;
import android.renderscript.Light;
import android.renderscript.Type;
import android.renderscript.SimpleMesh;
import android.renderscript.Script;
import static android.renderscript.Sampler.Value.LINEAR;
import static android.renderscript.Sampler.Value.CLAMP;
import static android.renderscript.ProgramStore.DepthFunc.*;
import static android.renderscript.ProgramStore.BlendDstFunc;
import static android.renderscript.ProgramStore.BlendSrcFunc;
import static android.renderscript.Element.*;

import android.app.WallpaperManager;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import static android.util.MathUtils.*;

import java.util.TimeZone;

import com.android.wallpaper.R;
import com.android.wallpaper.RenderScriptScene;

class FallRS extends RenderScriptScene {
    private static final int MESH_RESOLUTION = 48;

    private static final int RSID_STATE = 0;
    private static final int RSID_CONSTANTS = 1;
    private static final int RSID_DROP = 2;

    private static final int TEXTURES_COUNT = 2;
    private static final int RSID_TEXTURE_RIVERBED = 0;
    private static final int RSID_TEXTURE_LEAVES = 1;
    private static final int RSID_TEXTURE_SKY = 2;



    static class Defines {

    };

    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramFragment mPfBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramFragment mPfSky;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramStore mPfsBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramStore mPfsLeaf;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex mPvSky;
    private ProgramVertex mPvWater;
    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Sampler mSampler;

    private Allocation mState;
    private Allocation mDropState;
    private DropState mDrop;
    private Type mStateType;
    private Type mDropType;
    private int mMeshWidth;
    private Allocation mUniformAlloc;

    private int mMeshHeight;
    @SuppressWarnings({"FieldCanBeLocal"})
    private SimpleMesh mMesh;
    private WorldState mWorldState;

    private float mGlHeight;

    public FallRS(int width, int height) {
        super(width, height);

        mOptionsARGB.inScaled = false;
        mOptionsARGB.inPreferredConfig = Bitmap.Config.ARGB_8888;
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mWorldState.xOffset = xOffset;
        mState.data(mWorldState);
    }

    @Override
    public Bundle onCommand(String action, int x, int y, int z, Bundle extras,
            boolean resultRequested) {
        if (WallpaperManager.COMMAND_TAP.equals(action)) {
            addDrop(x + (mWorldState.width * mWorldState.xOffset), y);
        } else if (WallpaperManager.COMMAND_DROP.equals(action)) {
            addDrop(x + (mWorldState.width * mWorldState.xOffset), y);
        }
        return null;
    }

    @Override
    public void start() {
        super.start();
        final WorldState worldState = mWorldState;
        final int width = worldState.width;
        final int x = width / 4 + (int)(Math.random() * (width / 2));
        final int y = worldState.height / 4 + (int)(Math.random() * (worldState.height / 2));
        addDrop(x + (width * worldState.xOffset), y);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);

        mWorldState.width = width;
        mWorldState.height = height;
        mWorldState.rotate = width > height ? 1 : 0;
        mState.data(mWorldState);

        mPvOrthoAlloc.setupProjectionNormalized(mWidth, mHeight);
    }

    @Override
    protected ScriptC createScript() {
        createMesh();
        createState();
        createProgramVertex();
        createProgramFragmentStore();
        createProgramFragment();
        loadTextures();



        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setType(mStateType, "State", RSID_STATE);
        sb.setType(mDropType, "Drop", RSID_DROP);
        sb.setType(mUniformAlloc.getType(), "Constants", RSID_CONSTANTS);
        sb.setScript(mResources, R.raw.fall);
        Script.Invokable invokable = sb.addInvokable("initLeaves");
        sb.setRoot(true);

        ScriptC script = sb.create();
        script.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        script.setTimeZone(TimeZone.getDefault().getID());

        script.bindAllocation(mState, RSID_STATE);
        script.bindAllocation(mUniformAlloc, RSID_CONSTANTS);
        script.bindAllocation(mDropState, RSID_DROP);

        invokable.execute();

        return script;
    }

    private void createMesh() {
        SimpleMesh.TriangleMeshBuilder tmb = new SimpleMesh.TriangleMeshBuilder(mRS, 2, 0);

        final int width = mWidth > mHeight ? mHeight : mWidth;
        final int height = mWidth > mHeight ? mWidth : mHeight;

        int wResolution = MESH_RESOLUTION;
        int hResolution = (int) (MESH_RESOLUTION * height / (float) width);

        mGlHeight = 2.0f * height / (float) width;
        final float glHeight = mGlHeight;

        float quadWidth = 2.0f / (float) wResolution;
        float quadHeight = glHeight / (float) hResolution;

        wResolution += 2;
        hResolution += 2;

        for (int y = 0; y <= hResolution; y++) {
            final float yOffset = (((float)y / hResolution) * 2.f - 1.f) * height / width;
            for (int x = 0; x <= wResolution; x++) {
                tmb.addVertex(((float)x / wResolution) * 2.f - 1.f, yOffset);
            }
        }

        for (int y = 0; y < hResolution; y++) {
            final boolean shift = (y & 0x1) == 0;
            final int yOffset = y * (wResolution + 1);
            for (int x = 0; x < wResolution; x++) {
                final int index = yOffset + x;
                final int iWR1 = index + wResolution + 1;
                if (shift) {
                    tmb.addTriangle(index, index + 1, iWR1);
                    tmb.addTriangle(index + 1, iWR1 + 1, iWR1);
                } else {
                    tmb.addTriangle(index, iWR1 + 1, iWR1);
                    tmb.addTriangle(index, index + 1, iWR1 + 1);
                }
            }
        }

        mMesh = tmb.create();
        mMesh.setName("WaterMesh");

        mMeshWidth = wResolution + 1;
        mMeshHeight = hResolution + 1;
    }

    static class WorldState {
        public int frameCount;
        public int width;
        public int height;
        public int meshWidth;
        public int meshHeight;
        public int rippleIndex;
        public int leavesCount;
        public float glWidth;
        public float glHeight;
        public float skySpeedX;
        public float skySpeedY;
        public int rotate;
        public int isPreview;
        public float xOffset;
    }

    static class DropState {
        public int dropX;
        public int dropY;
    }

    private void createState() {
        mWorldState = new WorldState();
        mWorldState.width = mWidth;
        mWorldState.height = mHeight;
        mWorldState.meshWidth = mMeshWidth;
        mWorldState.meshHeight = mMeshHeight;
        mWorldState.rippleIndex = 0;
        mWorldState.glWidth = 2.0f;
        mWorldState.glHeight = mGlHeight;
        mWorldState.skySpeedX = random(-0.001f, 0.001f);
        mWorldState.skySpeedY = random(0.00008f, 0.0002f);
        mWorldState.rotate = mWidth > mHeight ? 1 : 0;
        mWorldState.isPreview = isPreview() ? 1 : 0;

        mStateType = Type.createFromClass(mRS, WorldState.class, 1, "WorldState");
        mState = Allocation.createTyped(mRS, mStateType);
        mState.data(mWorldState);

        mDrop = new DropState();
        mDrop.dropX = -1;
        mDrop.dropY = -1;

        mDropType = Type.createFromClass(mRS, DropState.class, 1, "DropState");
        mDropState = Allocation.createTyped(mRS, mDropType);
        mDropState.data(mDrop);
    }

    private void loadTextures() {
        final Allocation[] textures = new Allocation[TEXTURES_COUNT];
        textures[RSID_TEXTURE_RIVERBED] = loadTexture(R.drawable.pond, "TRiverbed");
        textures[RSID_TEXTURE_LEAVES] = loadTextureARGB(R.drawable.leaves, "TLeaves");
        // textures[RSID_TEXTURE_SKY] = loadTextureARGB(R.drawable.clouds, "TSky");

        final int count = textures.length;
        for (int i = 0; i < count; i++) {
            textures[i].uploadToTexture(0);
        }
    }

    private Allocation loadTexture(int id, String name) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mResources,
                id, RGB_565(mRS), false);
        allocation.setName(name);
        return allocation;
    }

    private Allocation loadTextureARGB(int id, String name) {
        Bitmap b = BitmapFactory.decodeResource(mResources, id, mOptionsARGB);
        final Allocation allocation = Allocation.createFromBitmap(mRS, b, RGBA_8888(mRS), false);
        allocation.setName(name);
        return allocation;
    }

    private void createProgramFragment() {
        Sampler.Builder sampleBuilder = new Sampler.Builder(mRS);
        sampleBuilder.setMin(LINEAR);
        sampleBuilder.setMag(LINEAR);
        sampleBuilder.setWrapS(CLAMP);
        sampleBuilder.setWrapT(CLAMP);
        mSampler = sampleBuilder.create();

        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS);
        builder.setTexture(ProgramFragment.Builder.EnvMode.REPLACE,
                           ProgramFragment.Builder.Format.RGBA, 0);
        mPfBackground = builder.create();
        mPfBackground.setName("PFBackground");
        mPfBackground.bindSampler(mSampler, 0);

        builder = new ProgramFragment.Builder(mRS);
        builder.setTexture(ProgramFragment.Builder.EnvMode.MODULATE,
                           ProgramFragment.Builder.Format.RGBA, 0);
        mPfSky = builder.create();
        mPfSky.setName("PFSky");
        mPfSky.bindSampler(mSampler, 0);
    }

    private void createProgramFragmentStore() {
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ONE);
        builder.setDitherEnable(false);
        builder.setDepthMask(true);
        mPfsBackground = builder.create();
        mPfsBackground.setName("PFSBackground");

        builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
        builder.setDitherEnable(false);
        builder.setDepthMask(true);
        mPfsLeaf = builder.create();
        mPfsLeaf.setName("PFSLeaf");
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvOrthoAlloc.setupProjectionNormalized(mWidth, mHeight);

        ProgramVertex.Builder builder = new ProgramVertex.Builder(mRS, null, null);
        mPvSky = builder.create();
        mPvSky.bindAllocation(mPvOrthoAlloc);
        mPvSky.setName("PVSky");

        float dw = 480.f / mMeshWidth;
        float dh = 800.f / mMeshHeight;

        Element.Builder eb = new Element.Builder(mRS);
        // Make this an array when we can.
        eb.add(Element.createVector(mRS, Element.DataType.FLOAT_32, 4), "Drop01");
        eb.add(Element.createVector(mRS, Element.DataType.FLOAT_32, 4), "Drop02");
        eb.add(Element.createVector(mRS, Element.DataType.FLOAT_32, 4), "Drop03");
        eb.add(Element.createVector(mRS, Element.DataType.FLOAT_32, 4), "Drop04");
        eb.add(Element.createVector(mRS, Element.DataType.FLOAT_32, 4), "Drop05");
        eb.add(Element.createVector(mRS, Element.DataType.FLOAT_32, 4), "Drop06");
        eb.add(Element.createVector(mRS, Element.DataType.FLOAT_32, 4), "Drop07");
        eb.add(Element.createVector(mRS, Element.DataType.FLOAT_32, 4), "Drop08");
        eb.add(Element.createVector(mRS, Element.DataType.FLOAT_32, 4), "Drop09");
        eb.add(Element.createVector(mRS, Element.DataType.FLOAT_32, 4), "Drop10");
        eb.add(Element.createVector(mRS, Element.DataType.FLOAT_32, 4), "Offset");
        Element e = eb.create();

        mUniformAlloc = Allocation.createSized(mRS, e, 1);


        ProgramVertex.ShaderBuilder sb = new ProgramVertex.ShaderBuilder(mRS);
        String t = new String("void main() {\n" +
                              "  vec4 pos;\n" +
                              "  pos.x = ATTRIB_position.x;\n" +
                              "  pos.y = ATTRIB_position.y;\n" +
                              "  pos.z = 0.0;\n" +
                              "  pos.w = 1.0;\n" +
                              "  gl_Position = pos;\n" +

                              // When we resize the texture we will need to tweak this.
                              "  varTex0.x = (pos.x + 1.0) * 0.25;\n" +
                              "  varTex0.x += UNI_Offset.x * 0.5 * 0.85;\n" +
                              "  varTex0.y = (pos.y + 1.6666) * 0.33;\n" +
                              "  varTex0.w = 0.0;\n" +
                              "  varColor = vec4(1.0, 1.0, 1.0, 1.0);\n" +

                              "  pos.x += UNI_Offset.x * 2.0;\n" +
                              "  pos.x += 1.0;\n" +
                              "  pos.y += 1.0;\n" +
                              "  pos.x *= 25.0;\n" +
                              "  pos.y *= 42.0;\n" +

                              "  vec2 delta;\n" +
                              "  float dist;\n" +
                              "  float amp;\n" +

                              "  delta = UNI_Drop01.xy - pos.xy;\n" +
                              "  dist = length(delta);\n" +
                              "  if (dist < UNI_Drop01.w) { \n" +
                              "    amp = UNI_Drop01.z * dist;\n" +
                              "    amp /= UNI_Drop01.w * UNI_Drop01.w;\n" +
                              "    amp *= sin(UNI_Drop01.w - dist);\n" +
                              "    varTex0.xy += delta * amp;\n" +
                              "  }\n" +

                              "  delta = UNI_Drop02.xy - pos.xy;\n" +
                              "  dist = length(delta);\n" +
                              "  if (dist < UNI_Drop02.w) { \n" +
                              "    amp = UNI_Drop02.z * dist;\n" +
                              "    amp /= UNI_Drop02.w * UNI_Drop02.w;\n" +
                              "    amp *= sin(UNI_Drop02.w - dist);\n" +
                              "    varTex0.xy += delta * amp;\n" +
                              "  }\n" +

                              "  delta = UNI_Drop03.xy - pos.xy;\n" +
                              "  dist = length(delta);\n" +
                              "  if (dist < UNI_Drop03.w) { \n" +
                              "    amp = UNI_Drop03.z * dist;\n" +
                              "    amp /= UNI_Drop03.w * UNI_Drop03.w;\n" +
                              "    amp *= sin(UNI_Drop03.w - dist);\n" +
                              "    varTex0.xy += delta * amp;\n" +
                              "  }\n" +

                              "  delta = UNI_Drop04.xy - pos.xy;\n" +
                              "  dist = length(delta);\n" +
                              "  if (dist < UNI_Drop04.w) { \n" +
                              "    amp = UNI_Drop04.z * dist;\n" +
                              "    amp /= UNI_Drop04.w * UNI_Drop04.w;\n" +
                              "    amp *= sin(UNI_Drop04.w - dist);\n" +
                              "    varTex0.xy += delta * amp;\n" +
                              "  }\n" +

                              "  delta = UNI_Drop05.xy - pos.xy;\n" +
                              "  dist = length(delta);\n" +
                              "  if (dist < UNI_Drop05.w) { \n" +
                              "    amp = UNI_Drop05.z * dist;\n" +
                              "    amp /= UNI_Drop05.w * UNI_Drop05.w;\n" +
                              "    amp *= sin(UNI_Drop05.w - dist);\n" +
                              "    varTex0.xy += delta * amp;\n" +
                              "  }\n" +

                              "  delta = UNI_Drop06.xy - pos.xy;\n" +
                              "  dist = length(delta);\n" +
                              "  if (dist < UNI_Drop06.w) { \n" +
                              "    amp = UNI_Drop06.z * dist;\n" +
                              "    amp /= UNI_Drop06.w * UNI_Drop06.w;\n" +
                              "    amp *= sin(UNI_Drop06.w - dist);\n" +
                              "    varTex0.xy += delta * amp;\n" +
                              "  }\n" +

                              "  delta = UNI_Drop07.xy - pos.xy;\n" +
                              "  dist = length(delta);\n" +
                              "  if (dist < UNI_Drop07.w) { \n" +
                              "    amp = UNI_Drop07.z * dist;\n" +
                              "    amp /= UNI_Drop07.w * UNI_Drop07.w;\n" +
                              "    amp *= sin(UNI_Drop07.w - dist);\n" +
                              "    varTex0.xy += delta * amp;\n" +
                              "  }\n" +

                              "  delta = UNI_Drop08.xy - pos.xy;\n" +
                              "  dist = length(delta);\n" +
                              "  if (dist < UNI_Drop08.w) { \n" +
                              "    amp = UNI_Drop08.z * dist;\n" +
                              "    amp /= UNI_Drop08.w * UNI_Drop08.w;\n" +
                              "    amp *= sin(UNI_Drop08.w - dist);\n" +
                              "    varTex0.xy += delta * amp;\n" +
                              "  }\n" +

                              "  delta = UNI_Drop09.xy - pos.xy;\n" +
                              "  dist = length(delta);\n" +
                              "  if (dist < UNI_Drop09.w) { \n" +
                              "    amp = UNI_Drop09.z * dist;\n" +
                              "    amp /= UNI_Drop09.w * UNI_Drop09.w;\n" +
                              "    amp *= sin(UNI_Drop09.w - dist);\n" +
                              "    varTex0.xy += delta * amp;\n" +
                              "  }\n" +

                              "  delta = UNI_Drop10.xy - pos.xy;\n" +
                              "  dist = length(delta);\n" +
                              "  if (dist < UNI_Drop10.w) { \n" +
                              "    amp = UNI_Drop10.z * dist;\n" +
                              "    amp /= UNI_Drop10.w * UNI_Drop10.w;\n" +
                              "    amp *= sin(UNI_Drop10.w - dist);\n" +
                              "    varTex0.xy += delta * amp;\n" +
                              "  }\n" +


                              "}\n");
        sb.setShader(t);
        sb.addConstant(mUniformAlloc.getType());
        sb.addInput(mMesh.getVertexType(0).getElement());
        mPvWater = sb.create();
        mPvWater.bindAllocation(mPvOrthoAlloc);
        mPvWater.setName("PVWater");
        mPvWater.bindConstants(mUniformAlloc, 1);

    }

    void addDrop(float x, float y) {
        if (mWorldState.rotate == 0) {
            mDrop.dropX = (int) ((x / mWidth) * mMeshWidth);
            mDrop.dropY = (int) ((y / mHeight) * mMeshHeight);
        } else {
            mDrop.dropY = (int) ((x / mWidth) * mMeshHeight);
            mDrop.dropX = mMeshWidth - (int) ((y / mHeight) * mMeshWidth);
        }
        mDropState.data(mDrop);
    }
}