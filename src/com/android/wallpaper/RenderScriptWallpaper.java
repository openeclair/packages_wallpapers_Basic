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

package com.android.wallpaper;

import android.os.Bundle;
import android.renderscript.RenderScript;
import android.service.wallpaper.WallpaperService;
import android.view.Surface;
import android.view.SurfaceHolder;

public abstract class RenderScriptWallpaper<T extends RenderScriptScene> extends WallpaperService {
    public Engine onCreateEngine() {
        return new RenderScriptEngine();
    }

    protected abstract T createScene(int width, int height);

    private class RenderScriptEngine extends Engine {
        private RenderScript mRs;
        private T mRenderer;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(false);
            surfaceHolder.setSizeFromLayout();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            destroyRenderer();
        }

        private void destroyRenderer() {
            if (mRenderer != null) {
                mRenderer.stop();
                mRenderer = null;
            }
            if (mRs != null) {
                mRs.destroy();
                mRs = null;
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (mRenderer != null) {
                if (visible) {
                    initRendererIfDirty();
                    mRenderer.start();
                } else {
                    mRenderer.stop();
                }
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (mRs != null) {
                mRs.contextSetSurface(width, height, holder.getSurface());
            }
            if (mRenderer == null || mRenderer.isDirty()) {
                if (mRenderer == null) {
                    mRenderer = createScene(width, height);
                    mRenderer.init(mRs, getResources(), isPreview());
                } else {
                    initRendererIfDirty();
                }
                mRenderer.start();
            } else {
                mRenderer.resize(width, height);
            }
        }

        private synchronized void initRendererIfDirty() {
            if (mRenderer != null && mRenderer.isDirty()) {
                mRenderer.stop();
                mRenderer.destroyScript();
                mRenderer.setDirty(false);
                mRenderer.init(mRs, getResources(), isPreview());
            }
        }
        
        @Override
        public void onOffsetsChanged(float xOffset, float yOffset,
                float xStep, float yStep, int xPixels, int yPixels) {
            mRenderer.setOffset(xOffset, yOffset, xPixels, yPixels);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);

            Surface surface = null;
            while (surface == null) {
                surface = holder.getSurface();
            }
            mRs = new RenderScript(false, false);
            mRs.contextSetPriority(RenderScript.Priority.LOW);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            destroyRenderer();
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z,
                Bundle extras, boolean resultRequested) {
            return mRenderer.onCommand(action, x, y, z, extras, resultRequested);
        }

    }
}
