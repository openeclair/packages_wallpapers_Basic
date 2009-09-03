// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma version(1)
#pragma stateVertex(PVBackground)
#pragma stateFragment(PFBackground)
#pragma stateFragmentStore(PFSBackground)

#define RSID_STATE 0
#define RSID_BLADES 1
#define RSID_BLADES_BUFFER 2

#define BLADE_STRUCT_FIELDS_COUNT 13
#define BLADE_STRUCT_ANGLE 0
#define BLADE_STRUCT_SIZE 1
#define BLADE_STRUCT_XPOS 2
#define BLADE_STRUCT_YPOS 3
#define BLADE_STRUCT_OFFSET 4
#define BLADE_STRUCT_SCALE 5
#define BLADE_STRUCT_LENGTHX 6
#define BLADE_STRUCT_LENGTHY 7
#define BLADE_STRUCT_HARDNESS 8
#define BLADE_STRUCT_H 9
#define BLADE_STRUCT_S 10
#define BLADE_STRUCT_B 11
#define BLADE_STRUCT_TURBULENCEX 12

#define TESSELATION 0.5f
#define HALF_TESSELATION 0.25f

#define MAX_BEND 0.09f

#define MIDNIGHT 0.0f
#define MORNING 0.375f
#define AFTERNOON 0.6f
#define DUSK 0.8f

#define SECONDS_IN_DAY 86400.0f

#define PI 3.1415926f
#define HALF_PI 1.570796326f

#define REAL_TIME 0

float time() {
    if (REAL_TIME) {
        return (hour() * 3600.0f + minute() * 60.0f + second()) / SECONDS_IN_DAY;
    }
    float t = uptimeMillis() / 20000.0f;
    return t - (int) t;
}

void alpha(float a) {
    color(1.0f, 1.0f, 1.0f, a);
}

void drawNight(int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TNight);
    drawQuadTexCoords(
            0.0f, -32.0f, 0.0f,
            0.0f, 1.0f,
            width, -32.0f, 0.0f,
            2.0f, 1.0f,
            width, 1024.0f - 32.0f, 0.0f,
            2.0f, 0.0f,
            0.0f, 1024.0f - 32.0f, 0.0f,
            0.0f, 0.0f);
}

void drawSunrise(int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TSunrise);
    drawRect(0.0f, 0.0f, width, height, 0.0f);
}

void drawNoon(int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TSky);
    drawRect(0.0f, 0.0f, width, height, 0.0f);
}

void drawSunset(int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TSunset);
    drawRect(0.0f, 0.0f, width, height, 0.0f);
}

int drawBlade(float *bladeStruct, float *bladeBuffer, int *bladeColor, float now, float xOffset) {
    float offset = bladeStruct[BLADE_STRUCT_OFFSET];
    float scale = bladeStruct[BLADE_STRUCT_SCALE];
    float angle = bladeStruct[BLADE_STRUCT_ANGLE];
    float hardness = bladeStruct[BLADE_STRUCT_HARDNESS];
    float turbulenceX = bladeStruct[BLADE_STRUCT_TURBULENCEX];

    float xpos = bladeStruct[BLADE_STRUCT_XPOS] + xOffset;
    float ypos = bladeStruct[BLADE_STRUCT_YPOS];

    float lengthX = bladeStruct[BLADE_STRUCT_LENGTHX];
    float lengthY = bladeStruct[BLADE_STRUCT_LENGTHY];

    int size = bladeStruct[BLADE_STRUCT_SIZE];

    float h = bladeStruct[BLADE_STRUCT_H];
    float s = bladeStruct[BLADE_STRUCT_S];
    float b = bladeStruct[BLADE_STRUCT_B];

    float newB = 1.0f;
    if (now >= MIDNIGHT && now < MORNING) {
        newB = now / MORNING;
    }

    if (now >= AFTERNOON && now < DUSK) {
        newB = 1.0f - normf(AFTERNOON, DUSK, now);
    }

    if (now >= DUSK) {
        newB = 0.0f;
    }

    int color = hsbToAbgr(h, s, lerpf(0, b, newB), 1.0f);

    float newAngle = turbulencef2(turbulenceX, uptimeMillis() * 0.00004f, 4.0f) - 0.5f;
    newAngle *= 0.5f;
    angle = clampf(angle + (newAngle + offset - angle) * 0.15f, -MAX_BEND, MAX_BEND);

    float currentAngle = HALF_PI;

    float bottomX = xpos;
    float bottomY = ypos;

    float d = angle * hardness;

    int triangles = size * 2;

    for ( ; size > 0; size -= 1) {
        float topX = bottomX - cosf_fast(currentAngle) * lengthX;
        float topY = bottomY - sinf_fast(currentAngle) * lengthY;

        float si = size * scale;
        float spi = si - scale;

        float bottomLeft = bottomX - si;
        float bottomRight = bottomX + si;
        float topLeft = topX - spi;
        float topRight = topX + spi;
        float bottom = bottomY + HALF_TESSELATION;

        // First triangle
        bladeColor[0] = color;                          // V1.ABGR

        bladeBuffer[1] = bottomLeft;                    // V1.X
        bladeBuffer[2] = bottom;                        // V1.Y

        bladeColor[5] = color;                          // V1.ABGR

        bladeBuffer[6] = topLeft;                       // V2.X
        bladeBuffer[7] = topY;                          // V2.Y

        bladeColor[10] = color;                         // V3.ABGR

        bladeBuffer[11] = topRight;                     // V3.X
        bladeBuffer[12] = topY;                         // V3.Y

        // Second triangle
        bladeBuffer += 15;
        bladeColor += 15;

        bladeColor[0] = color;                          // V1.ABGR

        bladeBuffer[1] = bottomLeft;                    // V1.X
        bladeBuffer[2] = bottom;                        // V1.Y

        bladeColor[5] = color;                          // V2.ABGR

        bladeBuffer[6] = topRight;                      // V2.X
        bladeBuffer[7] = topY;                          // V2.Y

        bladeColor[10] = color;                         // V3.ABGR

        bladeBuffer[11] = bottomRight;                  // V3.X
        bladeBuffer[12] = bottom;                       // V3.Y

        bladeBuffer += 15;
        bladeColor += 15;

        bottomX = topX;
        bottomY = topY;

        currentAngle += d;
    }

    bladeStruct[BLADE_STRUCT_ANGLE] = angle;

    // 3 vertices per triangle, 5 properties per vertex (RGBA, X, Y, S, T)
    return triangles * 15;
}

void drawBlades(float now, float xOffset) {
    // For anti-aliasing
    bindTexture(NAMED_PFBackground, 0, NAMED_TAa);

    int bladesCount = State->bladesCount;
    int trianglesCount = State->trianglesCount;

    int i = 0;
    float *bladeStruct = loadArrayF(RSID_BLADES, 0);
    float *bladeBuffer = loadArrayF(RSID_BLADES_BUFFER, 0);
    int *bladeColor = loadArrayI32(RSID_BLADES_BUFFER, 0);

    for ( ; i < bladesCount; i += 1) {
        int offset = drawBlade(bladeStruct, bladeBuffer, bladeColor, now, xOffset);
        bladeBuffer += offset;
        bladeColor += offset;
        bladeStruct += BLADE_STRUCT_FIELDS_COUNT;
    }

    uploadToBufferObject(NAMED_BladesBuffer);
    drawSimpleMeshRange(NAMED_BladesMesh, 0, trianglesCount * 3);
}

int main(int launchID) {
    int width = State->width;
    int height = State->height;

    float x = lerpf(width, 0, State->xOffset);

    float now = time();
    alpha(1.0f);

    if (now >= MIDNIGHT && now < MORNING) {
        drawNight(width, height);
        alpha(normf(MIDNIGHT, MORNING, now));
        drawSunrise(width, height);
    } else if (now >= MORNING && now < AFTERNOON) {
        drawSunrise(width, height);
        alpha(normf(MORNING, AFTERNOON, now));
        drawNoon(width, height);
    } else if (now >= AFTERNOON && now < DUSK) {
        drawNoon(width, height);
        alpha(normf(AFTERNOON, DUSK, now));
        drawSunset(width, height);
    } else if (now >= DUSK) {
        drawNight(width, height);
        alpha(1.0f - normf(DUSK, 1.0f, now));
        drawSunset(width, height);
    }

    drawBlades(now, x);

    return 1;
}
