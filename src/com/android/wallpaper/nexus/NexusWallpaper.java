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

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.text.format.Time;
import android.util.Log;
import android.util.MathUtils;
import android.view.SurfaceHolder;
import android.view.animation.AnimationUtils;

import java.util.ArrayList;

import com.android.wallpaper.R;

public class NexusWallpaper extends WallpaperService {

    private static final int NUM_PULSES = 20;
    private static final int PULSE_SIZE = 48;
    private static final int PULSE_SPEED = 48; // Pulse travels at 1000 / PULSE_SPEED cells/sec
    private static final int MAX_ALPHA = 192; // 0..255
    private static final int PULSE_DELAY = 4000;
    private static final float ALPHA_DECAY = 0.9f;

    private static final int ANIMATION_PERIOD = 1000/30; // in ms^-1

    private static final float ZAG_PROB = 0.01f;

    private static final int[] PULSE_COLORS = {
        0xFF0066CC, 0xFFFF0000, 0xFFFFCC00, 0xFF009900,
    };

    private static final String LOG_TAG = "Nexus";

    private final Handler mHandler = new Handler();

    public Engine onCreateEngine() {
        return new NexusEngine();
    }

    class NexusEngine extends Engine {

        class Pulse {
            Point v;
            Point[] pts;
            int start, len; // pointers into pts
            Paint paint;
            long startTime;
            boolean active;

            public Pulse() {
                v = new Point(0,0);
                pts = new Point[PULSE_SIZE];
                for (int i=0; i<pts.length; i++) {
                    pts[i] = new Point(0,0);
                }
                paint = new Paint(Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
                start = len = 0;
            }

            public void zag() {
                // take a random 90-degree turn
                int t = v.x; v.x = v.y; v.y = t;
                if (Math.random() < 0.5) {
                    v.negate();
                }
            }

            public void randomize(long now) {
                int x, y;
                if (Math.random() < 0.5) {
                    // vertical
                    x = (int)(Math.random() * mColumnCount);
                    if (Math.random() < 0.5) {
                        v.set(0, 1);
                        y = 0;
                    } else {
                        v.set(0, -1);
                        y = mRowCount;
                    }
                } else {
                    // horizontal
                    y = (int)(Math.random() * mRowCount);
                    if (Math.random() < 0.5) {
                        v.set(1, 0);
                        x = 0;
                    } else {
                        v.set(-1, 0);
                        x = mColumnCount;
                    }
                }
                start = 0;
                len = 1;
                pts[start].set(x, y);
                active = false;

                startTime = now + (long)(Math.random() * PULSE_DELAY);

                /* random colors
                final float hsv[] = {(float)Math.random()*360, 0.75f, 1.0f};
                int color = Color.HSVToColor(hsv);
                */
                // Google colors
                paint.setColor(PULSE_COLORS[(int)(Math.random()*4)]);

                paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));

            }

            public Point getHead() {
                return pts[(start+len-1)%PULSE_SIZE];
            }
            public Point getTail() {
                return pts[start];
            }

            public void step(long now) {
                if (len == 0) {
                    // not inited
                    randomize(now);
                }
                if (now < startTime) return;
                active = true;
                final Point neck = getHead();
                if (len < PULSE_SIZE) len++;
                else start = (start+1)%PULSE_SIZE;

                if (Math.random() < ZAG_PROB) {
                    zag();
                }

                getHead().set(neck.x + v.x, neck.y + v.y);
            }

            public void draw(Canvas c) {
                if (!active) return;
                boolean onScreen = false;
                int a = MAX_ALPHA;
                final Rect r = new Rect(0, 0, mCellSize, mCellSize);
                for (int i=len-1; i>=0; i--) {
                    paint.setAlpha(a);
                    a *= ALPHA_DECAY;
                    Point p = pts[(start+i)%PULSE_SIZE];
                    r.offsetTo(p.x * mCellSize, p.y * mCellSize);
                    c.drawRect(r, paint);
                    if (!onScreen)
                        onScreen = !(p.x < 0 || p.x > mColumnCount || p.y < 0 || p.y > mRowCount);
                }

                if (!onScreen) {
                    // Time to die.
                    start = len = 0;
                    active = false;
                }
            }
        }

        private final Runnable mDrawNexus = new Runnable() {
            public void run() {
                drawFrame();
                step();
            }
        };

        private boolean mVisible;

        private float mOffsetX;

        private Bitmap mBackground;

        private Bitmap mGreenLed;

        private ArrayList<Pulse> mPulses = new ArrayList<Pulse>();

        private int mColumnCount;
        private int mRowCount;

        private int mCellSize;

        private int mBackgroundWidth;
        private int mBackgroundHeight;

        NexusEngine() {
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);

            Resources r = getResources();

            mBackground = BitmapFactory.decodeResource(r, R.drawable.pyramid_background, null);

            mBackgroundWidth = mBackground.getWidth();
            mBackgroundHeight = mBackground.getHeight();

            mGreenLed = BitmapFactory.decodeResource(r, R.drawable.led_green, null);

            mCellSize = mGreenLed.getWidth();

            initializeState();

            if (isPreview()) {
                mOffsetX = 0.5f;
            }
        }

        private void initializeState() {
            mColumnCount = mBackgroundWidth / mCellSize;
            mRowCount = mBackgroundHeight / mCellSize;
            mPulses = new ArrayList<Pulse>();

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
            initializeState();
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
        public void onOffsetsChanged(float xOffset, float yOffset,
                float xStep, float yStep, int xPixels, int yPixels) {
            mOffsetX = xOffset;
            drawFrame();
        }

        void step() {
            final long now = AnimationUtils.currentAnimationTimeMillis();
            for (int i=0; i<mPulses.size(); i++) {
                Pulse p = mPulses.get(i);
                ((Pulse)mPulses.get(i)).step(now);
            }
        }

        void drawFrame() {
            final SurfaceHolder holder = getSurfaceHolder();
            final Rect frame = holder.getSurfaceFrame();

            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) {
                    c.translate(-MathUtils.lerp(0, frame.width(), mOffsetX), 0);
                    c.drawBitmap(mBackground, 0, 0, null);
                    for (int i=0; i<mPulses.size(); i++) {
                        ((Pulse)mPulses.get(i)).draw(c);
                    }
                }
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }

            mHandler.removeCallbacks(mDrawNexus);
            if (mVisible) {
                mHandler.postDelayed(mDrawNexus, ANIMATION_PERIOD);
            }
        }
    }
}
