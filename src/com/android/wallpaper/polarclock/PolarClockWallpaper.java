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
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.text.format.Time;
import android.util.MathUtils;

import java.util.TimeZone;

public class PolarClockWallpaper extends WallpaperService {
    public static final String SHARED_PREFS_NAME = "polar_clock_settings";
    
    public static final String PREF_SHOW_SECONDS = "show_seconds";
    public static final String PREF_VARIABLE_LINE_WIDTH = "variable_line_width";
    public static final String PREF_CYCLE_COLORS = "cycle_colors";

    public static final int BACKGROUND_COLOR = 0xffffffff;
    
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

    class ClockEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private static final float SATURATION = 0.8f;
        private static final float BRIGHTNESS = 0.9f;

        private static final float SMALL_RING_THICKNESS = 8.0f;
        private static final float MEDIUM_RING_THICKNESS = 16.0f;
        private static final float LARGE_RING_THICKNESS = 32.0f;

        private static final float DEFAULT_RING_THICKNESS = 24.0f;

        private static final float SMALL_GAP = 14.0f;
        private static final float LARGE_GAP = 38.0f;

        private static final int COLORS_CACHE_COUNT = 720;

        class ColorPalette {
            ColorPalette(String name, int bg, int s, int m, int h, int d, int o) {
                this.name = name; this.bg = bg;
                second = s; minute = m; hour = h; day = d; month = o;
            }
            public final String name;
            public final int bg;
            public final int second;
            public final int minute;
            public final int hour;
            public final int day;
            public final int month;
        }
        
        // XXX: make this an array of named palettes, selectable in prefs 
        //      via a spinner (bonus points: move to XML)
        private final ColorPalette mPalette = new ColorPalette(
            "MutedAndroid",
            0xFF555555, 
            0xFF00FF00, 0xFF333333, 0xFF000000,
            0xFF888888, 0xFFAAAAAA
        );

        private SharedPreferences mPrefs;
        private boolean mShowSeconds;
        private boolean mVariableLineWidth;
        private boolean mCycleColors;
        
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

            mPrefs = PolarClockWallpaper.this.getSharedPreferences(SHARED_PREFS_NAME, 0);
            mPrefs.registerOnSharedPreferenceChangeListener(this);
            onSharedPreferenceChanged(mPrefs, null);

            mCalendar = new Time();
            mCalendar.setToNow();
            mStartTime = mCalendar.second * 1000.0f;

            final Paint paint = mPaint;
            paint.setAntiAlias(true);
            paint.setStrokeWidth(DEFAULT_RING_THICKNESS);
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

        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                String key) {
            
            boolean changed = false;
            if (null == key || PREF_SHOW_SECONDS.equals(key)) {
                mShowSeconds = sharedPreferences.getBoolean(
                    PREF_SHOW_SECONDS, true);
                changed = true;
            }
            if (null == key || PREF_CYCLE_COLORS.equals(key)) {
                mCycleColors = sharedPreferences.getBoolean(
                    PREF_CYCLE_COLORS, false);
                changed = true;
            }
            if (null == key || PREF_VARIABLE_LINE_WIDTH.equals(key)) {
                mVariableLineWidth = sharedPreferences.getBoolean(
                    PREF_VARIABLE_LINE_WIDTH, true);
                changed = true;
            }

            if (mVisible && changed) {
                drawFrame();
            }
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

                    if (mCycleColors) {
                        c.drawColor(BACKGROUND_COLOR);
                    } else {
                        c.drawColor(mPalette.bg);
                    }
                    c.translate(s + MathUtils.lerp(s, -s, mOffsetX), t);
                    c.rotate(-90.0f);
                    if (height < width) {
                        c.scale(0.9f, 0.9f);
                    }

                    float size = Math.min(width, height) * 0.5f - DEFAULT_RING_THICKNESS;
                    final RectF rect = mRect;
                    rect.set(-size, -size, size, size);
                    float angle;

                    float lastRingThickness = DEFAULT_RING_THICKNESS;
                    
                    if (mShowSeconds) {
                        // Draw seconds  
                        angle = ((mStartTime + SystemClock.elapsedRealtime()) % 60000) / 60000.0f;
                        if (angle < 0) angle = -angle;
                        if (mCycleColors) {
                            paint.setColor(colors[((int) (angle * COLORS_CACHE_COUNT))]);
                        } else {
                            paint.setColor(mPalette.second);
                        }

                        if (mVariableLineWidth) {
                            lastRingThickness = SMALL_RING_THICKNESS;
                            paint.setStrokeWidth(lastRingThickness);
                        }
                        c.drawArc(rect, 0.0f, angle * 360.0f, false, paint);
                    }

                    // Draw minutes
                    size -= (SMALL_GAP + lastRingThickness);
                    rect.set(-size, -size, size, size);

                    angle = ((calendar.minute * 60.0f + calendar.second) % 3600) / 3600.0f;
                    if (mCycleColors) {
                        paint.setColor(colors[((int) (angle * COLORS_CACHE_COUNT))]);                    
                    } else {
                        paint.setColor(mPalette.minute);
                    }

                    if (mVariableLineWidth) {
                        lastRingThickness = MEDIUM_RING_THICKNESS;
                        paint.setStrokeWidth(lastRingThickness);
                    }
                    c.drawArc(rect, 0.0f, angle * 360.0f, false, paint);
                    
                    // Draw hours
                    size -= (SMALL_GAP + lastRingThickness);
                    rect.set(-size, -size, size, size);

                    angle = ((calendar.hour * 60.0f + calendar.minute) % 1440) / 1440.0f;
                    if (mCycleColors) {
                        paint.setColor(colors[((int) (angle * COLORS_CACHE_COUNT))]);                    
                    } else {
                        paint.setColor(mPalette.hour);
                    }
                    if (mVariableLineWidth) {
                        lastRingThickness = LARGE_RING_THICKNESS;
                        paint.setStrokeWidth(lastRingThickness);
                    }
                    c.drawArc(rect, 0.0f, angle * 360.0f, false, paint);

                    // Draw day
                    size -= (LARGE_GAP + lastRingThickness);
                    rect.set(-size, -size, size, size);

                    angle = (calendar.monthDay - 1) /
                            (float) (calendar.getActualMaximum(Time.MONTH_DAY) - 1);
                    if (mCycleColors) {
                        paint.setColor(colors[((int) (angle * COLORS_CACHE_COUNT))]);                    
                    } else {
                        paint.setColor(mPalette.day);
                    }
                    if (mVariableLineWidth) {
                        lastRingThickness = MEDIUM_RING_THICKNESS;
                        paint.setStrokeWidth(lastRingThickness);
                    }
                    c.drawArc(rect, 0.0f, angle * 360.0f, false, paint);

                    // Draw month
                    size -= (SMALL_GAP + lastRingThickness);
                    rect.set(-size, -size, size, size);

                    angle = (calendar.month - 1) / 11.0f;
                    if (mCycleColors) {
                        paint.setColor(colors[((int) (angle * COLORS_CACHE_COUNT))]);                    
                    } else {
                        paint.setColor(mPalette.month);
                    }
                    if (mVariableLineWidth) {
                        lastRingThickness = LARGE_RING_THICKNESS;
                        paint.setStrokeWidth(lastRingThickness);
                    }
                    c.drawArc(rect, 0.0f, angle * 360.0f, false, paint);
                }
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }

            mHandler.removeCallbacks(mDrawClock);
            if (mVisible) {
                if (mShowSeconds) {
                    mHandler.postDelayed(mDrawClock, 1000 / 25);
                } else {
                    // If we aren't showing seconds, we don't need to update
                    // nearly as often.
                    mHandler.postDelayed(mDrawClock, 2000);
                }
            }
        }
    }
}
