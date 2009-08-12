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

import java.util.TimeZone;

public class PolarClockWallpaper extends WallpaperService {
    private final Handler mHandler = new Handler();
    private final Runnable mDrawClock = new Runnable() {
        public void run() {
            mEngine.drawFrame(true);
        }
    };

    private TimeWatcher mWatcher;
    private IntentFilter mFilter;
    private ClockEngine mEngine;

    @Override
    public void onCreate() {
        super.onCreate();

        mFilter = new IntentFilter();
        mFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

        mWatcher = new TimeWatcher();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mWatcher);
        mHandler.removeCallbacks(mDrawClock);
    }

    public Engine onCreateEngine() {
        mEngine = new ClockEngine();
        return mEngine;
    }

    class TimeWatcher extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            final String timeZone = intent.getStringExtra("time-zone");
            mEngine.mCalendar = new Time(TimeZone.getTimeZone(timeZone).getID());
            mEngine.drawFrame(true);
        }
    }

    class ClockEngine extends Engine {
        private static final float SATURATION = 0.8f;
        private static final float BRIGHTNESS = 0.9f;

        private static final float RING_THICKNESS = 24.0f;
        private static final float SMALL_GAP = 14.0f;
        private static final float LARGE_GAP = 38.0f;

        private static final int COLORS_CACHE_COUNT = 720;
        
        private float mStartTime;
        private Time mCalendar;

        private final Paint mPaint = new Paint();
        private final RectF mRect = new RectF();
        private final int[] mColors;

        ClockEngine() {
            mColors = new int[COLORS_CACHE_COUNT];

            final int[] colors = mColors;
            final int count = colors.length;

            for (int i = 0; i < count; i++) {
                colors[i] = Color.HSBtoColor(i / (float) COLORS_CACHE_COUNT, SATURATION, BRIGHTNESS);
            }
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);

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
        public void onVisibilityChanged(boolean visible) {
            if (visible) {
                registerReceiver(mWatcher, mFilter, null, mHandler);
                mCalendar = new Time();
                mCalendar.setToNow();
                mStartTime = mCalendar.second * 1000.0f;                
            } else {
                unregisterReceiver(mWatcher);
                mHandler.removeCallbacks(mDrawClock);
            }
            drawFrame(visible);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            drawFrame(true);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            drawFrame(false);
        }
        
        void drawFrame(boolean redraw) {
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

                    c.drawColor(0xffffffff);
                    c.translate(width / 2.0f, height/ 2.0f);
                    c.rotate(-90.0f);

                    // Draw seconds  
                    float size = width / 2.0f / 2.0f - RING_THICKNESS;
                    final RectF rect = mRect;
                    rect.set(-size, -size, size, size);

                    float angle = ((mStartTime + SystemClock.elapsedRealtime()) % 60000) / 60000.0f;
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
            if (redraw) {
                mHandler.postDelayed(mDrawClock, 1000 / 25);
            }
        }
    }
}
