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

package com.android.wallpaper.polarclock;

import android.service.wallpaper.WallpaperService;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.SurfaceHolder;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.text.format.Time;
import android.util.Log;
import android.util.MathUtils;

import java.util.TimeZone;

public class PolarClockWallpaper extends WallpaperService {
    private final Handler mHandler = new Handler();

    private IntentFilter mFilter;

    @Override
    public void onCreate() {
        super.onCreate();

        mFilter = new IntentFilter();
        mFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public Engine onCreateEngine() {
        return new ClockEngine();
    }

    class ClockEngine extends Engine {
        private static final float SATURATION = 0.8f;
        private static final float BRIGHTNESS = 0.9f;

        private static final float RING_THICKNESS = 24.0f;
        private static final float SMALL_GAP = 14.0f;
        private static final float LARGE_GAP = 38.0f;

        private static final int COLORS_CACHE_COUNT = 720;
        
        private boolean mWatcherRegistered;
        private float mStartTime;
        private Time mCalendar;

        private final Paint mPaint = new Paint();
        private final RectF mRect = new RectF();
        private final int[] mColors = new int[COLORS_CACHE_COUNT];

        private float mOffsetX;

        private final BroadcastReceiver mWatcher = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                final String timeZone = intent.getStringExtra("time-zone");
                mCalendar = new Time(TimeZone.getTimeZone(timeZone).getID());
                drawFrame();
            }
        };
        
        private final Runnable mDrawClock = new Runnable() {
            public void run() {
                drawFrame();
            }
        };
        private boolean mVisible;

        ClockEngine() {
            final int[] colors = mColors;
            final int count = colors.length;

            float invCount = 1.0f / (float) COLORS_CACHE_COUNT;
            for (int i = 0; i < count; i++) {
                colors[i] = Color.HSBtoColor(i * invCount, SATURATION, BRIGHTNESS);
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            surfaceHolder.setSizeFromLayout();

            mCalendar = new Time();
            mCalendar.setToNow();
            mStartTime = mCalendar.second * 1000.0f;

            final Paint paint = mPaint;
            paint.setAntiAlias(true);
            paint.setStrokeWidth(RING_THICKNESS);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (mWatcherRegistered) {
                mWatcherRegistered = false;
                unregisterReceiver(mWatcher);
            }
            mHandler.removeCallbacks(mDrawClock);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            mVisible = visible;
            if (visible) {
                if (!mWatcherRegistered) {
                    mWatcherRegistered = true;
                    registerReceiver(mWatcher, mFilter, null, mHandler);
                }
                mCalendar = new Time();
                mCalendar.setToNow();
                mStartTime = mCalendar.second * 1000.0f;                
            } else {
                if (mWatcherRegistered) {
                    mWatcherRegistered = false;
                    unregisterReceiver(mWatcher);
                }
                mHandler.removeCallbacks(mDrawClock);
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
            mHandler.removeCallbacks(mDrawClock);
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
                    final Time calendar = mCalendar;
                    final Paint paint = mPaint;
                    final int[] colors = mColors;

                    calendar.setToNow();
                    calendar.normalize(false);

                    int s = width / 2;
                    int t = height / 2;

                    c.drawColor(0xffffffff);
                    c.translate(s + MathUtils.lerp(s, -s, mOffsetX), t);
                    c.rotate(-90.0f);
                    if (height < width) {
                        c.scale(0.9f, 0.9f);
                    }

                    // Draw seconds  
                    float size = Math.min(width, height) * 0.5f - RING_THICKNESS;
                    final RectF rect = mRect;
                    rect.set(-size, -size, size, size);

                    float angle = ((mStartTime + SystemClock.elapsedRealtime()) % 60000) / 60000.0f;
                    if (angle < 0) angle = -angle;
                    paint.setColor(colors[((int) (angle * COLORS_CACHE_COUNT))]);
                    c.drawArc(rect, 0.0f, angle * 360.0f, false, paint);

                    // Draw minutes
                    size -= (SMALL_GAP + RING_THICKNESS);
                    rect.set(-size, -size, size, size);

                    angle = ((calendar.minute * 60.0f + calendar.second) % 3600) / 3600.0f;
                    paint.setColor(colors[((int) (angle * COLORS_CACHE_COUNT))]);                    
                    c.drawArc(rect, 0.0f, angle * 360.0f, false, paint);
                    
                    // Draw hours
                    size -= (SMALL_GAP + RING_THICKNESS);
                    rect.set(-size, -size, size, size);

                    angle = ((calendar.hour * 60.0f + calendar.minute) % 1440) / 1440.0f;
                    paint.setColor(colors[((int) (angle * COLORS_CACHE_COUNT))]);                    
                    c.drawArc(rect, 0.0f, angle * 360.0f, false, paint);

                    // Draw day
                    size -= (LARGE_GAP + RING_THICKNESS);
                    rect.set(-size, -size, size, size);

                    angle = (calendar.monthDay - 1) /
                            (float) (calendar.getActualMaximum(Time.MONTH_DAY) - 1);
                    paint.setColor(colors[((int) (angle * COLORS_CACHE_COUNT))]);                    
                    c.drawArc(rect, 0.0f, angle * 360.0f, false, paint);

                    // Draw month
                    size -= (SMALL_GAP + RING_THICKNESS);
                    rect.set(-size, -size, size, size);

                    angle = (calendar.month - 1) / 11.0f;
                    paint.setColor(colors[((int) (angle * COLORS_CACHE_COUNT))]);                    
                    c.drawArc(rect, 0.0f, angle * 360.0f, false, paint);
                }
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }

            mHandler.removeCallbacks(mDrawClock);
            if (mVisible) {
                mHandler.postDelayed(mDrawClock, 1000 / 25);
            }
        }
    }
}
