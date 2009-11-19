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
import static android.renderscript.Sampler.Value.WRAP;
import static android.renderscript.ProgramStore.DepthFunc.*;
import static android.renderscript.ProgramStore.BlendDstFunc;
import static android.renderscript.ProgramStore.BlendSrcFunc;
import static android.renderscript.ProgramFragment.EnvMode.*;
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

    private static final int TEXTURES_COUNT = 2;
    private static final int RSID_TEXTURE_RIVERBED = 0;
    private static final int RSID_TEXTURE_LEAVES = 1;
    private static final int RSID_TEXTURE_SKY = 2;

    private static final int RSID_RIPPLE_MAP = 1;
    private static final int RSID_LEAVES = 3;
    private static final int RSID_DROP = 4;

    private static final int LEAVES_COUNT = 14;

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
    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Sampler mSampler;

    private Allocation mState;
    private Allocation mDropState;
    private DropState mDrop;
    private Type mStateType;
    private Type mDropType;
    private int mMeshWidth;

    private int mMeshHeight;
    @SuppressWarnings({"FieldCanBeLocal"})
    private SimpleMesh mMesh;
    private WorldState mWorldState;

    private Allocation mRippleMap;

    private Allocation mLeaves;
    private Type mLeavesType;

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
        createProgramVertex();
        createProgramFragmentStore();
        createProgramFragment();
        createMesh();
        createScriptStructures();
        loadTextures();

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setType(mStateType, "State", RSID_STATE);
        sb.setType(mDropType, "Drop", RSID_DROP);
        sb.setType(mLeavesType, "Leaves", RSID_LEAVES);
        sb.setScript(mResources, R.raw.fall);
        Script.Invokable invokable = sb.addInvokable("initLeaves");
        sb.setRoot(true);

        ScriptC script = sb.create();
        script.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        script.setTimeZone(TimeZone.getDefault().getID());

        script.bindAllocation(mState, RSID_STATE);
        script.bindAllocation(mRippleMap, RSID_RIPPLE_MAP);
        script.bindAllocation(mLeaves, RSID_LEAVES);
        script.bindAllocation(mDropState, RSID_DROP);

        invokable.execute();

        return script;
    }

    private void createMesh() {
        SimpleMesh.TriangleMeshBuilder tmb = new SimpleMesh.TriangleMeshBuilder(mRS, 3,
                SimpleMesh.TriangleMeshBuilder.TEXTURE_0);

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
            final float yOffset = y * quadHeight - glHeight / 2.0f - quadHeight;
            final float t = 1.0f - y / (float) hResolution;
            for (int x = 0; x <= wResolution; x++) {
                tmb.setTexture(x / (float) wResolution, t);
                tmb.addVertex(-1.0f + x * quadWidth - quadWidth, yOffset, 0.0f);
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

    private void createScriptStructures() {
        final int rippleMapSize = (mMeshWidth + 2) * (mMeshHeight + 2);

        createState(rippleMapSize);
        createRippleMap(rippleMapSize);
        createLeaves();
    }

    private void createLeaves() {
        mLeavesType = Type.createFromClass(mRS, Leaf.class, LEAVES_COUNT, "Leaf");
        mLeaves = Allocation.createTyped(mRS, mLeavesType);
    }

    private void createRippleMap(int rippleMapSize) {
        final int[] rippleMap = new int[rippleMapSize * 2];
        mRippleMap = Allocation.createSized(mRS, USER_I32(mRS), rippleMap.length);
        mRippleMap.data(rippleMap);
    }

    static class WorldState {
        public int frameCount;
        public int width;
        public int height;
        public int meshWidth;
        public int meshHeight;
        public int rippleMapSize;
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

    private void createState(int rippleMapSize) {
        mWorldState = new WorldState();
        mWorldState.width = mWidth;
        mWorldState.height = mHeight;
        mWorldState.meshWidth = mMeshWidth;
        mWorldState.meshHeight = mMeshHeight;
        mWorldState.rippleMapSize = rippleMapSize;
        mWorldState.rippleIndex = 0;
        mWorldState.leavesCount = LEAVES_COUNT;
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

    static class Leaf {
        public float x;
        public float y;
        public float scale;
        public float angle;
        public float spin;
        public float u1;
        public float u2;
        public float altitude;
        public float rippled;
        public float deltaX;
        public float deltaY;
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
        sampleBuilder.setWrapS(WRAP);
        sampleBuilder.setWrapT(WRAP);
        mSampler = sampleBuilder.create();

        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(REPLACE, 0);
        mPfBackground = builder.create();
        mPfBackground.setName("PFBackground");
        mPfBackground.bindSampler(mSampler, 0);

        builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(MODULATE, 0);
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
        builder.setTextureMatrixEnable(true);
        mPvSky = builder.create();
        mPvSky.bindAllocation(mPvOrthoAlloc);
        mPvSky.setName("PVSky");
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