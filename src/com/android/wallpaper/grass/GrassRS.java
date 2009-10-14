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

import android.renderscript.Sampler;
import static android.renderscript.ProgramFragment.EnvMode.*;
import static android.renderscript.ProgramStore.DepthFunc.*;
import static android.renderscript.ProgramStore.BlendSrcFunc;
import static android.renderscript.ProgramStore.BlendDstFunc;
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
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.location.LocationManager;
import android.location.LocationListener;
import android.location.Location;
import android.os.Bundle;
import android.text.format.Time;
import com.android.wallpaper.R;
import com.android.wallpaper.RenderScriptScene;

import java.util.TimeZone;
import java.util.Calendar;

class GrassRS extends RenderScriptScene {
    private static final int LOCATION_UPDATE_MIN_TIME = 60 * 60 * 1000; // 1 hour
    private static final int LOCATION_UPDATE_MIN_DISTANCE = 150 * 1000; // 150 km

    private static final float TESSELATION = 0.5f;
    private static final int TEXTURES_COUNT = 5;

    private static final int RSID_STATE = 0;
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

    @SuppressWarnings({ "FieldCanBeLocal" })
    private ProgramFragment mPfBackground;
    @SuppressWarnings({ "FieldCanBeLocal" })
    private ProgramStore mPfsBackground;
    @SuppressWarnings({ "FieldCanBeLocal" })
    private ProgramVertex mPvBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Sampler mSampler;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;

    @SuppressWarnings({ "FieldCanBeLocal" })
    private Allocation[] mTextures;

    private Type mStateType;
    private Allocation mState;

    private Allocation mBlades;
    private Allocation mBladesBuffer;
    @SuppressWarnings({"FieldCanBeLocal"})
    private SimpleMesh mBladesMesh;

    private int mTriangles;
    private float[] mBladesData;
    private final float[] mFloatData5 = new float[5];

    private WorldState mWorldState;

    private final Context mContext;
    private final LocationManager mLocationManager;

    private LocationUpdater mLocationUpdater;
    private GrassRS.TimezoneTracker mTimezoneTracker;

    GrassRS(Context context, int width, int height) {
        super(width, height);

        mContext = context;
        mLocationManager = (LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public void start() {
        super.start();

        if (mTimezoneTracker == null) {
            mTimezoneTracker = new TimezoneTracker();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    
            mContext.registerReceiver(mTimezoneTracker, filter);
        }

        if (mLocationUpdater == null) {
            mLocationUpdater = new LocationUpdater();
            mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    LOCATION_UPDATE_MIN_TIME, LOCATION_UPDATE_MIN_DISTANCE, mLocationUpdater);
        }

        updateLocation();
    }

    @Override
    public void stop() {
        super.stop();

        if (mTimezoneTracker != null) {
            mContext.unregisterReceiver(mTimezoneTracker);
            mTimezoneTracker = null;
        }
        
        if (mLocationUpdater != null) {
            mLocationManager.removeUpdates(mLocationUpdater);
            mLocationUpdater = null;
        }
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);

        mWorldState.width = width;
        mWorldState.height = height;
        mState.data(mWorldState);

        final float[] blades = mBladesData;
        for (int i = 0; i < blades.length; i+= BLADE_STRUCT_FIELDS_COUNT) {
            updateBlade(blades, i);
        }
        mBlades.data(blades);

        mPvOrthoAlloc.setupOrthoWindow(width, height);        
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
        sb.setScript(mResources, R.raw.grass);
        sb.setRoot(true);

        ScriptC script = sb.create();
        script.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        script.setTimeZone(TimeZone.getDefault().getID());

        script.bindAllocation(mState, RSID_STATE);
        script.bindAllocation(mBlades, RSID_BLADES);
        script.bindAllocation(mBladesBuffer, RSID_BLADES_BUFFER);

        return script;
    }

    private void createScriptStructures() {
        createBlades();
        createState();
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mWorldState.xOffset = xOffset;
        mState.data(mWorldState);
    }

    static class WorldState {
        public int bladesCount;
        public int trianglesCount;
        public int width;
        public int height;
        public float xOffset;
        public float dawn;
        public float morning;
        public float afternoon;
        public float dusk;
        public int isPreview;
    }

    private void createState() {
        final boolean isPreview = isPreview();

        mWorldState = new WorldState();
        mWorldState.width = mWidth;
        mWorldState.height = mHeight;
        mWorldState.bladesCount = BLADES_COUNT;
        mWorldState.trianglesCount = mTriangles;
        mWorldState.isPreview = isPreview ? 1 : 0;
        if (isPreview) {
            mWorldState.xOffset = 0.5f;
        }

        mStateType = Type.createFromClass(mRS, WorldState.class, 1, "WorldState");
        mState = Allocation.createTyped(mRS, mStateType);
        mState.data(mWorldState);
    }

    private void createBlades() {
        int triangles = 0;

        mBladesData = new float[BLADES_COUNT * BLADE_STRUCT_FIELDS_COUNT];

        final float[] blades = mBladesData;
        for (int i = 0; i < blades.length; i+= BLADE_STRUCT_FIELDS_COUNT) {
            triangles += createBlade(blades, i);
        }

        mBlades = Allocation.createSized(mRS, USER_F32(mRS), blades.length);
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

    private void updateBlade(float[] blades, int index) {
        final int xpos = random(-mWidth, mWidth);
        blades[index + BLADE_STRUCT_XPOS] = xpos;
        blades[index + BLADE_STRUCT_TURBULENCEX] = xpos * 0.006f;
        blades[index + BLADE_STRUCT_YPOS] = mHeight;
    }

    private int createBlade(float[] blades, int index) {
        final float size = random(4.0f) + 4.0f;
        final int xpos = random(-mWidth, mWidth);

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
        final Type.Builder builder = new Type.Builder(mRS, A_8(mRS));
        builder.add(Dimension.X, width);
        builder.add(Dimension.Y, height);

        final Allocation allocation = Allocation.createTyped(mRS, builder.create());
        allocation.data(data);
        allocation.setName(name);
        return allocation;
    }

    private Allocation loadTexture(int id, String name) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mResources,
                id, RGB_565(mRS), false);
        allocation.setName(name);
        return allocation;
    }

    private void createProgramFragment() {
        Sampler.Builder samplerBuilder = new Sampler.Builder(mRS);
        samplerBuilder.setMin(LINEAR);
        samplerBuilder.setMag(LINEAR);
        samplerBuilder.setWrapS(WRAP);
        samplerBuilder.setWrapT(WRAP);
        mSampler = samplerBuilder.create();

        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(REPLACE, 0);
        mPfBackground = builder.create();
        mPfBackground.setName("PFBackground");
        mPfBackground.bindSampler(mSampler, 0);
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
        mPvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);

        ProgramVertex.Builder pvb = new ProgramVertex.Builder(mRS, null, null);
        pvb.setTextureMatrixEnable(true);
        mPvBackground = pvb.create();
        mPvBackground.bindAllocation(mPvOrthoAlloc);
        mPvBackground.setName("PVBackground");
    }

    private void updateLocation() {
        updateLocation(mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
    }

    private void updateLocation(Location location) {
        if (location != null) {
            final String timeZone = Time.getCurrentTimezone();
            final SunCalculator calculator = new SunCalculator(location, timeZone);
            final Calendar now = Calendar.getInstance();

            final double sunrise = calculator.computeSunriseTime(SunCalculator.ZENITH_CIVIL, now);
            mWorldState.dawn = SunCalculator.timeToDayFraction(sunrise);

            final double sunset = calculator.computeSunsetTime(SunCalculator.ZENITH_CIVIL, now);
            mWorldState.dusk = SunCalculator.timeToDayFraction(sunset);
        } else {
            mWorldState.dawn = 0.3f;
            mWorldState.dusk = 0.75f;
        }

        mWorldState.morning = mWorldState.dawn + 1.0f / 12.0f; // 2 hours for sunrise
        mWorldState.afternoon = mWorldState.dusk - 1.0f / 12.0f; // 2 hours for sunset

        // Send the new data to RenderScript
        mState.data(mWorldState);
    }

    private class LocationUpdater implements LocationListener {
        public void onLocationChanged(Location location) {
            updateLocation(location);
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onProviderDisabled(String provider) {
        }
    }

    private class TimezoneTracker extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            getScript().setTimeZone(Time.getCurrentTimezone());
            updateLocation();
        }
    }
}
