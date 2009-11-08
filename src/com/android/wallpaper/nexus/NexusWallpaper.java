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

package com.android.wallpaper.nexus;

import android.service.wallpaper.WallpaperService;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.SurfaceHolder;
import android.view.animation.AnimationUtils;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;

import android.os.Handler;
import android.os.SystemClock;
import android.text.format.Time;
import android.util.MathUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.TimeZone;
import java.io.IOException;

import org.apache.harmony.misc.SystemUtils;
import org.xmlpull.v1.XmlPullParserException;
import static org.xmlpull.v1.XmlPullParser.*;

import com.android.wallpaper.R;

public class NexusWallpaper extends WallpaperService {
   
    private static final int NUM_PULSES = 20;
    private static final int PULSE_SIZE_MIN = 30;
    private static final int PULSE_SIZE_EXTRA = 20;
    private static final int PULSE_SPEED = 20; // Pulse travels at 1000 / PULSE_SPEED cells/sec
    private static final int MAX_ALPHA = 80; // 0..255
    private static final int PULSE_DELAY = 4000;
    private static final float ALPHA_DECAY = 0.97f;
    
    private static final String LOG_TAG = "Nexus";

    private final Handler mHandler = new Handler();

    public Engine onCreateEngine() {
        return new NexusEngine();
    }    
    
    class NexusEngine extends Engine {

        class Collision {
            int x;
            int y;
            long startTime;
            public Bitmap led;
        }
        
        class Pulse {
            boolean vertical;
            boolean reverse;
            int x;
            int y;
            long startTime;
            int length;
            Bitmap led;
            
            public void reset(long now, int width, int height) {
                vertical = Math.random() > 0.5;
                reverse = Math.random() > 0.5;
                
                startTime = now + (long)(Math.random() * PULSE_DELAY);
                if (vertical) {
                    x = (int) (Math.random() * (width / mCellSize));
                } else {
                    y = (int) (Math.random() * (height / mCellSize));
                }
                length = PULSE_SIZE_MIN + (int)(Math.random() * PULSE_SIZE_EXTRA);
                final double color = Math.random();
                if (color < 0.25) {
                    led = mBlueLed;
                } else if (color < 0.5) {
                    led = mRedLed;
                } else if (color < 0.75) {
                    led = mGreenLed;
                } else {
                    led = mYellowLed;
                }
                
            }

            public void clearState(int[][] state) {
                if (vertical) {
                    int rowCount = state[0].length;
                    for (int row = 0; row < rowCount; row++) {
                        state[x][row] = 0;
                    }
                } else {
                    int colCount = state.length;
                    for (int col = 0; col < colCount; col++) {
                        state[col][y] = 0;
                    }
                }
            }
        }
        
        Paint mPaint = new Paint();

        private final Runnable mDrawNexus = new Runnable() {
            public void run() {
                drawFrame();
            }
        };

        private boolean mVisible;

        private float mOffsetX;
        
        private Bitmap mBackground;
        
        private Bitmap mBlueLed;
        private Bitmap mRedLed;
        private Bitmap mYellowLed;
        private Bitmap mGreenLed;
        
        private ArrayList<Pulse> mPulses = new ArrayList<Pulse>();
        
        private ArrayList<Collision> mCollisions = new ArrayList<Collision>();

        private int[][] mState = null;

        private int mColumnCount;

        private int mRowCount;

        private int mCellSize;
        
        NexusEngine() {
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);

            final Paint paint = mPaint;

            Resources r = getResources();
            
            mBackground = BitmapFactory.decodeResource(r, R.drawable.pyramid_background, null);
            mBlueLed = BitmapFactory.decodeResource(r, R.drawable.led_blue, null);
            mRedLed = BitmapFactory.decodeResource(r, R.drawable.led_red, null);
            mYellowLed = BitmapFactory.decodeResource(r, R.drawable.led_yellow, null);
            mGreenLed = BitmapFactory.decodeResource(r, R.drawable.led_green, null);
            
            mCellSize = mGreenLed.getWidth();
            
            for (int i=0; i<NUM_PULSES; i++) {
                Pulse p = new Pulse();
                mPulses.add(p);
            }
            
            if (isPreview()) {
                mOffsetX = 0.5f;            
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            mHandler.removeCallbacks(mDrawNexus);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            mVisible = visible;
            if (!visible) {             
                mHandler.removeCallbacks(mDrawNexus);
            }
            drawFrame();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            drawFrame();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            mVisible = false;
            mHandler.removeCallbacks(mDrawNexus);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, int xPixels, int yPixels) {
            mOffsetX = xOffset;
            drawFrame();
        }
        
        void drawFrame() {
            final SurfaceHolder holder = getSurfaceHolder();
            final Rect frame = holder.getSurfaceFrame();
            final int width = frame.width();
            final int height = frame.height();

            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) {
                    if (mState == null && width > 0 && height > 0) {
                        mColumnCount = (width * 2) / mCellSize;
                        mRowCount = height / mCellSize;
                        mState = new int[mColumnCount][mRowCount];
                    }
                    
                    c.translate(-MathUtils.lerp(0, width, mOffsetX), 0);
                    c.drawBitmap(mBackground, 0, 0, null);
                    final long now = AnimationUtils.currentAnimationTimeMillis();
                    drawPulses(c, now, width, height);
                    drawCollisions(c, now);
                    clearState();
                }
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }

            mHandler.removeCallbacks(mDrawNexus);
            if (mVisible) {
                mHandler.postDelayed(mDrawNexus, 1000 / 25);
            }
        }

        
        private void drawCollisions(Canvas c, final long now) {
            final int count = mCollisions.size();
            for (int i=count - 1; i > 0; i--) {
                Collision collision = mCollisions.get(i);
                final long age = now - collision.startTime;
                if (age > 1000) {
                    mCollisions.remove(i);
                } else {
                    mPaint.setAlpha(MAX_ALPHA*2 - (int)(MAX_ALPHA*2*((float)age/1000)));
                    c.drawBitmap(collision.led, collision.x * mCellSize, collision.y * mCellSize, mPaint);
                }
            }
        }
                
        private void drawPulses(Canvas c, final long now, final int width,
                final int height) {
            for (int i=0; i<NUM_PULSES; i++) {
                Pulse p = mPulses.get(i);
                final long startTime = p.startTime;
                final Bitmap led = p.led;
                
                if (startTime > 0 && now > startTime) {
                    final int x = p.x;
                    final int y = p.y;
                    final int length = p.length;
                    int alpha = MAX_ALPHA;
                    int lastOffset;
                    
                    int offset = (int) ((AnimationUtils.currentAnimationTimeMillis() - startTime) / PULSE_SPEED);
                    
                    if (p.vertical) {

                        if (p.reverse) {
                            offset = mRowCount - offset;
                        }
                        lastOffset = offset;

                        for (int j = 0; j < length; j++) {
                            mPaint.setAlpha(alpha);
                            if (p.reverse) {
                                lastOffset = offset + j;
                            } else {
                                lastOffset = offset - j;
                            }
                            c.drawBitmap(led, x * mCellSize, lastOffset * mCellSize, mPaint);
                            detectCollision(now, led, x, lastOffset, alpha);
                            alpha *= ALPHA_DECAY;
                        }
                        if (p.reverse) {
                            if (lastOffset < 0) {
                                p.reset(now, width, height);
                            }
                        } else {
                            if (lastOffset > mRowCount) {
                                p.reset(now, width, height);
                            }
                        }

                    } else {
                        
                        if (p.reverse) {
                            offset = mColumnCount - offset;
                        }
                        lastOffset = offset;
                        
                        for (int j=0; j<length; j++) {
                            mPaint.setAlpha(alpha);
                            if (p.reverse) {
                                lastOffset = offset + j;
                            } else {
                                lastOffset = offset - j;
                            }
                            c.drawBitmap(led, lastOffset * mCellSize, y * mCellSize, mPaint);
                            alpha *= ALPHA_DECAY;
                            detectCollision(now, led, lastOffset, y, alpha);
                        }
                        if (p.reverse) {
                            if (lastOffset < 0) {
                                p.reset(now, width * 2, height); 
                            }
                        } else {
                            if (lastOffset > mColumnCount) {
                                p.reset(now, width * 2, height); 
                            }
                        }
                    }
                } else if (startTime == 0) {
                    p.reset(now, width * 2, height);
                }
            }
        }

        private void detectCollision(long now, Bitmap led, int x, int y, int alpha) {
            final int[][] state = mState;
            if (x >= 0 && y >= 0 && x < state.length && y < state[x].length) {
                
                if ((alpha > MAX_ALPHA / 2) &&  (state[x][y] > MAX_ALPHA / 2)) {
                    
                    boolean found = false;
                    final int count = mCollisions.size();
                    for (int i=count - 1; i > 0; i--) {
                        Collision collision = mCollisions.get(i);
                        if (x == collision.x && y == collision.y) {
                            found = true;
                            break;
                        }
                    }
                        
                    if (!found) {
                        Collision c = new Collision();
                        c.startTime = now;
                        c.x = x;
                        c.y = y;
                        c.led = led;
                        mCollisions.add(c);
                    }
                } else {
                    state[x][y] = alpha;
                }
            }

        }

        private void clearState() {
            if (mState != null) {
                for (int i = 0; i < NUM_PULSES; i++) {
                    Pulse p = mPulses.get(i);
                    p.clearState(mState);
                }
            }
            
        }
    }
}
