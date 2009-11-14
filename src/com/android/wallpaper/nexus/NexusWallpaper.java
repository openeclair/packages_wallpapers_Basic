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
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.MathUtils;
import android.view.SurfaceHolder;
import android.view.animation.AnimationUtils;

import java.util.Set;
import java.util.HashSet;

import com.android.wallpaper.R;

public class NexusWallpaper extends WallpaperService {

    private static final int NUM_PULSES = 12;
    private static final int MAX_PULSES = 32;
    private static final int PULSE_SIZE = 16;
    private static final int MAX_ALPHA = 128; // 0..255
    private static final int PULSE_DELAY = 5000; // random restart time, in ms
    private static final float ALPHA_DECAY = 0.85f;

    private static final boolean ACCEPTS_TAP = true;

    private static final int ANIMATION_PERIOD = 1000/50; // in ms^-1

    private static final int[] PULSE_COLORS = {
        0xFF0066CC, 0xDDFF0000, 0xBBFFCC00, 0xEE009900,
    };

    private static final String LOG_TAG = "Nexus";

    private final Handler mHandler = new Handler();

    public Engine onCreateEngine() {
        return new NexusEngine();
    }

    class NexusEngine extends Engine {

        class Automaton {
            public void step(long now) { }
            public void draw(Canvas c) { }
        }

        class Pulse extends Automaton {
            Point v;
            Point[] pts;
            int start, len; // pointers into pts
            Paint paint;
            Paint glowPaint;
            long startTime;
            boolean started;

            public float zagProb = 0.007f;
            public int speed = 1;

            public Pulse() {
                v = new Point(0,0);
                pts = new Point[PULSE_SIZE];
                for (int i=0; i<pts.length; i++) {
                    pts[i] = new Point(0,0);
                }
                paint = new Paint(Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
                paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));

                glowPaint = new Paint(paint);
                glowPaint.setAlpha((int)(MAX_ALPHA*0.7f));

                start = len = 0;
            }
            public Pulse(long now, int x, int y, int dx, int dy) {
                this();
                start(now, x, y, dx, dy);
            }

            boolean isDiagonal() {
                return v.x != 0 && v.y != 0;
            }

            public void zag() {
                // take a random 90-degree turn
                if (isDiagonal()) {
                    if (Math.random() < 0.5) {
                        v.x *= -1;
                    } else {
                        v.y *= -1;
                    }
                } else {
                    int t = v.x; v.x = v.y; v.y = t;
                    if (Math.random() < 0.5) {
                        v.negate();
                    }
                }
            }

            public void start(long now, int x, int y, int dx, int dy) {
                start = 0;
                len = 1;
                pts[start].set(x, y);
                v.x = dx;
                v.y = dy;
                startTime = now;
                setColor(PULSE_COLORS[(int)Math.floor(Math.random()*PULSE_COLORS.length)]);
                started = false;
            }

            public void setColor(int c) {
                paint.setColor(c);
                glowPaint.setColorFilter(new LightingColorFilter(paint.getColor(), 0));
            }

            public void startRandomEdge(long now, boolean diag) {
                int x, y;
                if (Math.random() < 0.5) {
                    // top or bottom edge
                    x = (int)(Math.random() * mColumnCount);
                    if (Math.random() < 0.5) {
                        v.y = 1;
                        y = 0;
                    } else {
                        v.y = -1;
                        y = mRowCount;
                    }
                    v.x = diag ? ((Math.random() < 0.5) ? 1 : -1) : 0;
                } else {
                    // left or right edge
                    y = (int)(Math.random() * mRowCount);
                    if (Math.random() < 0.5) {
                        v.set(1, 1);
                        x = 0;
                    } else {
                        v.set(-1, 1);
                        x = mColumnCount;
                    }
                    v.y = diag ? ((Math.random() < 0.5) ? 1 : -1) : 0;
                }
                start = 0;
                len = 1;
                pts[start].set(x, y);

                startTime = now + (long)(Math.random() * PULSE_DELAY);

                /* random colors
                final float hsv[] = {(float)Math.random()*360, 0.75f, 1.0f};
                int color = Color.HSVToColor(hsv);
                */
                // select colors
                setColor(PULSE_COLORS[(int)Math.floor(Math.random()*PULSE_COLORS.length)]);
                started = false;
            }

            public Point getHead() {
                return pts[(start+len-1)%PULSE_SIZE];
            }
            public Point getTail() {
                return pts[start];
            }

            public void step(long now) {
                if (now < startTime) return;
                started = true;

                for (int i=0; i<speed; i++) {
                    final Point neck = getHead();
                    if (len < PULSE_SIZE) len++;
                    else start = (start+1)%PULSE_SIZE;

                    getHead().set(neck.x + v.x,
                                  neck.y + v.y);
                }

                if (Math.random() < zagProb) {
                    zag();
                }
            }

            public void draw(Canvas c) {
                if (!started) return;
                boolean onScreen = false;
                int a = MAX_ALPHA;

                Point head = getHead();
                c.drawBitmap(mGlow, (head.x-1)*mCellSize, (head.y-1)*mCellSize, glowPaint);

                final Rect r = new Rect(0, 0, mCellSize, mCellSize);
                for (int i=len-1; i>=0; i--) {
                    paint.setAlpha(a);
                    a *= ALPHA_DECAY;
                    if (a < 0.05f) break; // note: you should decrease PULSE_SIZE
                    Point p = pts[(start+i)%PULSE_SIZE];
                    r.offsetTo(p.x * mCellSize, p.y * mCellSize);
                    c.drawRect(r, paint);
                    if (!onScreen)
                        onScreen = !(p.x < 0 || p.x > mColumnCount || p.y < 0 || p.y > mRowCount);
                }


                if (!onScreen) {
                    // Time to die.
                    recycleOrRemovePulse(this);
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
        
        private Bitmap mGlow;

        private Set<Automaton> mPulses = new HashSet<Automaton>();
        private Set<Automaton> mDeadPulses = new HashSet<Automaton>();

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

            mGlow = Bitmap.createBitmap(3*mCellSize, 3*mCellSize, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(mGlow);
            Paint p = new Paint();
            final int halfCell = mCellSize/2;
            p.setMaskFilter(new BlurMaskFilter(halfCell, BlurMaskFilter.Blur.NORMAL));
            p.setColor(Color.WHITE);
            c.drawRect(halfCell, halfCell, 5*halfCell, 5*halfCell, p);

            initializeState();

            if (isPreview()) {
                mOffsetX = 0.5f;
            }
        }

        private void initializeState() {
            mColumnCount = mBackgroundWidth / mCellSize;
            mRowCount = mBackgroundHeight / mCellSize;
            mPulses.clear();
            mDeadPulses.clear();

            final long now = AnimationUtils.currentAnimationTimeMillis();

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
            initializeState();
            drawFrame();
        }

        @Override
        public void onDesiredSizeChanged(int desiredWidth, int desiredHeight) {
            super.onDesiredSizeChanged(desiredWidth, desiredHeight);
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
            super.onOffsetsChanged(xOffset, yOffset, xStep, yStep, xPixels, yPixels);
            mOffsetX = xOffset;
            drawFrame();
        }


        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras,
                boolean resultRequested) {
            if (ACCEPTS_TAP && "android.wallpaper.tap".equals(action)) {

                final SurfaceHolder holder = getSurfaceHolder();
                final Rect frame = holder.getSurfaceFrame();

                final int dw = frame.width();
                final int bw = mBackgroundWidth;
                final int cellX = (int)((x + mOffsetX * (bw-dw)) / mCellSize);
                final int cellY = (int)(y / mCellSize);
                
                int colorIdx = (int)(Math.random() * PULSE_COLORS.length);

                Pulse p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, 0, 1);
                p.setColor(PULSE_COLORS[colorIdx]);
                addPulse(p);
                colorIdx = (colorIdx + 1) % PULSE_COLORS.length;

                p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, 1, 0);
                p.setColor(PULSE_COLORS[colorIdx]);
                addPulse(p);
                colorIdx = (colorIdx + 1) % PULSE_COLORS.length;

                p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, -1, 0);
                p.setColor(PULSE_COLORS[colorIdx]);
                addPulse(p);
                colorIdx = (colorIdx + 1) % PULSE_COLORS.length;

                p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, 0, -1);
                p.setColor(PULSE_COLORS[colorIdx]);
                addPulse(p);
                colorIdx = (colorIdx + 1) % PULSE_COLORS.length;

            } else if ("android.home.drop".equals(action)) {
                final SurfaceHolder holder = getSurfaceHolder();
                final Rect frame = holder.getSurfaceFrame();

                final int dw = frame.width();
                final int bw = mBackgroundWidth;
                final int cellX = (int)((x + mOffsetX * (bw-dw)) / mCellSize);
                final int cellY = (int)(y / mCellSize);
                Pulse p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, 0, 1);
                addPulse(p);
                p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, 1, 0);
                addPulse(p);
                p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, -1, 0);
                addPulse(p);
                p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, 0, -1);
                addPulse(p);

                p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, -1, -1);
                addPulse(p);
                p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, 1, -1);
                addPulse(p);
                p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, 1, 1);
                addPulse(p);
                p = new Pulse();
                p.zagProb = 0;
                p.start(0, cellX, cellY, -1, 1);
                addPulse(p);
            }
            return null;
        }

        void addPulse(Pulse p) {
            if (mPulses.size() > MAX_PULSES) return;
            mPulses.add(p);
        }

        void removePulse(Pulse p) {
            mDeadPulses.add(p);
        }

        void recycleOrRemovePulse(Pulse p) {
            if (mPulses.size() < NUM_PULSES) {
                p.startRandomEdge(AnimationUtils.currentAnimationTimeMillis(), false);
            } else {
                removePulse(p);
            }
        }

        void step() {
            final long now = AnimationUtils.currentAnimationTimeMillis();

            // not enough pulses? add some
            for (int i=mPulses.size(); i<NUM_PULSES; i++) {
                Pulse p = new Pulse();
                p.startRandomEdge(now, false);
                mPulses.add(p);
            }

            for (Automaton p : mDeadPulses) {
                mPulses.remove(p);
            }
            mDeadPulses.clear();

            // update state
            for (Automaton p : mPulses) {
                p.step(now);
            }
        }

        void drawFrame() {
            final SurfaceHolder holder = getSurfaceHolder();
            final Rect frame = holder.getSurfaceFrame();

            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c != null) {
                    final int dw = frame.width();
                    final int bw = mBackgroundWidth;
                    final int availw = dw-bw;
                    final int xPixels = availw < 0 ? (int)(availw*mOffsetX+.5f) : (availw/2);

                    c.translate(xPixels, 0);

                    c.drawBitmap(mBackground, 0, 0, null);
                    for (Automaton p : mPulses) {
                        p.draw(c);
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
