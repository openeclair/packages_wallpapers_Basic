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

package com.android.wallpaper.galaxy;

import android.renderscript.ScriptC;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.Allocation;
import android.renderscript.Sampler;
import android.renderscript.Element;
import android.renderscript.SimpleMesh;
import android.renderscript.Primitive;
import android.renderscript.Type;
import static android.renderscript.Sampler.Value.LINEAR;
import static android.renderscript.Sampler.Value.NEAREST;
import static android.renderscript.Sampler.Value.WRAP;
import static android.renderscript.ProgramStore.DepthFunc.*;
import static android.renderscript.ProgramStore.BlendDstFunc;
import static android.renderscript.ProgramStore.BlendSrcFunc;
import static android.renderscript.ProgramFragment.EnvMode.*;
import static android.renderscript.Element.*;
import static android.util.MathUtils.*;

import java.util.TimeZone;

import com.android.wallpaper.R;
import com.android.wallpaper.RenderScriptScene;

class GalaxyRS extends RenderScriptScene {
    private static final int GALAXY_RADIUS = 300;
    private static final int PARTICLES_COUNT = 12000;
    private static final float ELLIPSE_TWIST = 0.023333333f;

    private static final int RSID_STATE = 0;
    private static final int RSID_PARTICLES = 1;
    private static final int RSID_PARTICLES_BUFFER = 2;

    private static final int TEXTURES_COUNT = 3;
    private static final int RSID_TEXTURE_SPACE = 0;
    private static final int RSID_TEXTURE_LIGHT1 = 1;
    private static final int RSID_TEXTURE_FLARES = 2;

    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramFragment mPfBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramFragment mPfStars;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramStore mPfsBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramStore mPfsLights;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex mPvBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex mPvStars;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Sampler mSampler;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Sampler mStarSampler;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex.MatrixAllocation mPvProjectionAlloc;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Allocation[] mTextures;

    private GalaxyState mGalaxyState;
    private Type mStateType;
    private Allocation mState;
    private Allocation mParticles;
    private Type mParticlesType;
    private Allocation mParticlesBuffer;
    @SuppressWarnings({"FieldCanBeLocal"})
    private SimpleMesh mParticlesMesh;

    private final float[] mFloatData = new float[5];

    GalaxyRS(int width, int height) {
        super(width, height);
    }

    @Override
    protected ScriptC createScript() {
        createProgramVertex();
        createProgramFragmentStore();
        createProgramFragment();
        createScriptStructures();
        loadTextures();

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setType(mStateType, "State", RSID_STATE);
        sb.setType(mParticlesMesh.getVertexType(0), "Particles", RSID_PARTICLES_BUFFER);
        sb.setType(mParticlesType, "Stars", RSID_PARTICLES);
        sb.setScript(mResources, R.raw.galaxy);
        sb.setRoot(true);

        ScriptC script = sb.create();
        script.setClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        script.setTimeZone(TimeZone.getDefault().getID());

        script.bindAllocation(mState, RSID_STATE);
        script.bindAllocation(mParticles, RSID_PARTICLES);
        script.bindAllocation(mParticlesBuffer, RSID_PARTICLES_BUFFER);

        return script;
    }

    private void createScriptStructures() {
        createState();
        createParticlesMesh();
        createParticles();
    }

    private void createParticlesMesh() {
        final Builder elementBuilder = new Builder(mRS);
        elementBuilder.addUNorm8RGBA("");
        elementBuilder.addFloatXYZ("");
        elementBuilder.addFloatPointSize("");
        final Element vertexElement = elementBuilder.create();

        final SimpleMesh.Builder meshBuilder = new SimpleMesh.Builder(mRS);
        final int vertexSlot = meshBuilder.addVertexType(vertexElement, PARTICLES_COUNT);
        meshBuilder.setPrimitive(Primitive.POINT);
        mParticlesMesh = meshBuilder.create();
        mParticlesMesh.setName("ParticlesMesh");

        mParticlesBuffer = mParticlesMesh.createVertexAllocation(vertexSlot);
        mParticlesBuffer.setName("ParticlesBuffer");
        mParticlesMesh.bindVertexAllocation(mParticlesBuffer, 0);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mGalaxyState.xOffset = xOffset;
        mState.data(mGalaxyState);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);

        mGalaxyState.width = width;
        mGalaxyState.height = height;
        mState.data(mGalaxyState);

        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);
    }

    static class GalaxyState {
        public int width;
        public int height;
        public int particlesCount;
        public int galaxyRadius;
        public float xOffset;
    }

    static class GalaxyParticle {
        public float angle;
        public float distance;
        public float speed;
        public float s;
        public float t;
    }

    private void createState() {
        mGalaxyState = new GalaxyState();
        mGalaxyState.width = mWidth;
        mGalaxyState.height = mHeight;
        mGalaxyState.particlesCount = PARTICLES_COUNT;
        mGalaxyState.galaxyRadius = GALAXY_RADIUS;

        mStateType = Type.createFromClass(mRS, GalaxyState.class, 1, "GalaxyState");
        mState = Allocation.createTyped(mRS, mStateType);
        mState.data(mGalaxyState);
    }

    private void createParticles() {
        GalaxyParticle gp = new GalaxyParticle();
        mParticlesType = Type.createFromClass(mRS, GalaxyParticle.class, PARTICLES_COUNT, "Particle");
        mParticles = Allocation.createTyped(mRS, mParticlesType);

        final float scale = GALAXY_RADIUS / (mWidth * 0.5f);

        for (int i = 0; i < PARTICLES_COUNT; i ++) {
            createParticle(gp, i, scale);
        }
    }

    @SuppressWarnings({"PointlessArithmeticExpression"})
    private void createParticle(GalaxyParticle gp, int index, float scale) {
        float d = abs(randomGauss()) * GALAXY_RADIUS / 2.0f + random(-4.0f, 4.0f);
        float z = randomGauss() * 0.5f * 0.8f * ((GALAXY_RADIUS - d) / (float) GALAXY_RADIUS);
        float p = -d * ELLIPSE_TWIST;

        final float nd = d / (float) GALAXY_RADIUS;

        int red, green, blue, alpha;
        if (d < GALAXY_RADIUS / 3.0f) {
            red = (int) (220 + nd * 35);
            green = 220;
            blue = 220;
        } else {
            red = 180;
            green = 180;
            blue = (int) constrain(140 + nd * 115, 140, 255);
        }
        alpha = (int) (40 + nd * 215);
        int color = red | green << 8 | blue << 16 | alpha << 24;

        // Map to the projection coordinates (viewport.x = -1.0 -> 1.0)
        d = map(-4.0f, GALAXY_RADIUS + 4.0f, 0.0f, scale, d);

        gp.angle = random(0.0f, (float) (Math.PI * 2.0));
        gp.distance = d;
        gp.speed = random(0.0015f, 0.0025f) * (0.5f + (scale / d)) * 0.8f;
        gp.s = (float) Math.cos(p);
        gp.t = (float) Math.sin(p);

        final float[] floatData = mFloatData;
        floatData[0] = Float.intBitsToFloat(color);
        floatData[3] = z / 5.0f;
        floatData[4] = random(1.2f, 2.1f) * 6f;

        mParticlesBuffer.subData1D(index, 1, floatData);
        mParticles.subData(index, gp);
    }

    private static float randomGauss() {
        float x1;
        float x2;
        float w;

        do {
            x1 = 2.0f * random(0.0f, 1.0f) - 1.0f;
            x2 = 2.0f * random(0.0f, 1.0f) - 1.0f;
            w = x1 * x1 + x2 * x2;
        } while (w >= 1.0f);

        w = (float) Math.sqrt(-2.0 * log(w) / w);

        return x1 * w;
    }

    private void loadTextures() {
        mTextures = new Allocation[TEXTURES_COUNT];

        final Allocation[] textures = mTextures;
        textures[RSID_TEXTURE_SPACE] = loadTexture(R.drawable.space, "TSpace");
        textures[RSID_TEXTURE_LIGHT1] = loadTexture(R.drawable.light1, "TLight1");
        textures[RSID_TEXTURE_FLARES] = loadTexture(R.drawable.flares, "TFlares");

        final int count = textures.length;
        for (int i = 0; i < count; i++) {
            textures[i].uploadToTexture(0);
        }
    }

    private Allocation loadTexture(int id, String name) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mResources,
                id, RGB_565, false);
        allocation.setName(name);
        return allocation;
    }

    private void createProgramFragment() {
        Sampler.Builder samplerBuilder = new Sampler.Builder(mRS);
        samplerBuilder.setMin(NEAREST);
        samplerBuilder.setMag(NEAREST);
        samplerBuilder.setWrapS(WRAP);
        samplerBuilder.setWrapT(WRAP);
        mSampler = samplerBuilder.create();

        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(REPLACE, 0);
        mPfBackground = builder.create();
        mPfBackground.setName("PFBackground");
        mPfBackground.bindSampler(mSampler, 0);

        samplerBuilder = new Sampler.Builder(mRS);
        samplerBuilder.setMin(LINEAR);
        samplerBuilder.setMag(LINEAR);
        samplerBuilder.setWrapS(WRAP);
        samplerBuilder.setWrapT(WRAP);
        mStarSampler = samplerBuilder.create();

        builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setPointSpriteTexCoordinateReplacement(true);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(MODULATE, 0);
        mPfStars = builder.create();
        mPfStars.setName("PFStars");
        mPfBackground.bindSampler(mStarSampler, 0);        
    }

    private void createProgramFragmentStore() {
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
        builder.setDitherEnable(false);
        builder.setDepthMask(false);
        mPfsBackground = builder.create();
        mPfsBackground.setName("PFSBackground");

        builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE);
        builder.setDitherEnable(false);
        mPfsLights = builder.create();
        mPfsLights.setName("PFSLights");
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);

        ProgramVertex.Builder builder = new ProgramVertex.Builder(mRS, null, null);
        mPvBackground = builder.create();
        mPvBackground.bindAllocation(mPvOrthoAlloc);
        mPvBackground.setName("PVBackground");
        
        mPvProjectionAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvProjectionAlloc.setupProjectionNormalized(mWidth, mHeight);

        builder = new ProgramVertex.Builder(mRS, null, null);
        mPvStars = builder.create();
        mPvStars.bindAllocation(mPvProjectionAlloc);
        mPvStars.setName("PVStars");
    }
}
