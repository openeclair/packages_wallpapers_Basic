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
#pragma stateVertex(PVSky)
#pragma stateFragment(PFBackground)
#pragma stateStore(PFSBackground)

#define LEAVES_TEXTURES_COUNT 8
#define LEAF_SIZE 0.55f
#define LEAVES_COUNT 14

float skyOffsetX;
float skyOffsetY;
float g_DT;
int g_LastTime;

struct vert_s {
    float x;
    float y;
    float z;
    float s;
    float t;
};

struct drop_s {
    float ampS;
    float ampE;
    float spread;
    float spread2;
    float invSpread;
    float invSpread2;
    float x;
    float y;
};
struct drop_s gDrops[10];
int gMaxDrops;

struct Leaves_s {
    float x;
    float y;
    float scale;
    float angle;
    float spin;
    float u1;
    float u2;
    float altitude;
    float rippled;
    float deltaX;
    float deltaY;
    int newLeaf;
};

struct Leaves_s gLeavesStore[LEAVES_COUNT];

struct Leaves_s* gLeaves[LEAVES_COUNT];

struct Leaves_s* gNextLeaves[LEAVES_COUNT];

void init() {
    int ct;
    gMaxDrops = 10;
    for (ct=0; ct<gMaxDrops; ct++) {
        gDrops[ct].ampS = 0;
        gDrops[ct].ampE = 0;
        gDrops[ct].spread = 1;
        gDrops[ct].spread2 = gDrops[ct].spread * gDrops[ct].spread;
        gDrops[ct].invSpread = 1 / gDrops[ct].spread;
        gDrops[ct].invSpread2 = gDrops[ct].invSpread * gDrops[ct].invSpread;
    }
}

void initLeaves() {
    struct Leaves_s *leaf = gLeavesStore;
    float width = State->glWidth * 2;
    float height = State->glHeight;

    int i;
    for (i = 0; i < LEAVES_COUNT; i ++) {
        gLeaves[i] = leaf;
        int sprite = randf(LEAVES_TEXTURES_COUNT);
        leaf->x = randf2(-width * 0.5f, width * 0.5f);
        leaf->y = randf2(-height * 0.5f, height * 0.5f);
        leaf->scale = randf2(0.4f, 0.5f);
        leaf->angle = randf2(0.0f, 360.0f);
        leaf->spin = degf(randf2(-0.02f, 0.02f)) * 0.25f;
        leaf->u1 = sprite / (float) LEAVES_TEXTURES_COUNT;
        leaf->u2 = (sprite + 1) / (float) LEAVES_TEXTURES_COUNT;
        leaf->altitude = -1.0f;
        leaf->rippled = 1.0f;
        leaf->deltaX = randf2(-0.02f, 0.02f) / 2.0f;
        leaf->deltaY = -0.08f * randf2(0.9f, 1.1f) / 2.0f;
        leaf++;
    }
}

void updateDrop(int ct) {
    gDrops[ct].spread += 30.f * g_DT;
    gDrops[ct].spread2 = gDrops[ct].spread * gDrops[ct].spread;
    gDrops[ct].invSpread = 1 / gDrops[ct].spread;
    gDrops[ct].invSpread2 = gDrops[ct].invSpread * gDrops[ct].invSpread;
    gDrops[ct].ampE = gDrops[ct].ampS * gDrops[ct].invSpread;
}

void drop(int x, int y, float s) {
    int ct;
    int iMin = 0;
    float minAmp = 10000.f;
    for (ct = 0; ct < gMaxDrops; ct++) {
        if (gDrops[ct].ampE < minAmp) {
            iMin = ct;
            minAmp = gDrops[ct].ampE;
        }
    }
    gDrops[iMin].ampS = s;
    gDrops[iMin].spread = 0;
    gDrops[iMin].x = x;
    gDrops[iMin].y = State->meshHeight - y - 1;
    updateDrop(iMin);
}

void generateRipples() {
    int rippleMapSize = State->rippleMapSize;
    int width = State->meshWidth;
    int height = State->meshHeight;
    int index = State->rippleIndex;
    float ratio = (float)State->meshWidth / State->glWidth;
    float xShift = State->xOffset * ratio * 2;

    float *vertices = loadSimpleMeshVerticesF(NAMED_WaterMesh, 0);
    struct vert_s *vert = (struct vert_s *)vertices;

    float fw = 1.0f / width;
    float fh = 1.0f / height;
    int x, y, ct;
    struct vert_s *v = vert;
    for (y=0; y < height; y++) {
        for (x=0; x < width; x++) {
            struct drop_s * d = &gDrops[0];
            float z = 0;

            for (ct = 0; ct < gMaxDrops; ct++) {
                if (d->ampE > 0.01f) {
                    float dx = (d->x - xShift) - x;
                    float dy = d->y - y;
                    float dist2 = dx*dx + dy*dy;
                    if (dist2 < d->spread2) {
                        float dist = sqrtf(dist2);
                        float a = d->ampE * (dist * d->invSpread);
                        z += sinf(d->spread - dist) * a;
                    }
                }
                d++;
            }
            v->z = z;
            v ++;
        }
    }
    for (ct = 0; ct < gMaxDrops; ct++) {
        updateDrop(ct);
    }

    v = vert;
    for (y = 0; y < height; y += 1) {
        for (x = 0; x < width; x += 1) {
            struct vec3_s n1, n2, n3;
            vec3Sub(&n1, (struct vec3_s *)&(v+1)->x, (struct vec3_s *)&v->x);
            vec3Sub(&n2, (struct vec3_s *)&(v+width)->x, (struct vec3_s *)&v->x);
            vec3Cross(&n3, &n1, &n2);

            // Average of previous normal and N1 x N2
            vec3Sub(&n1, (struct vec3_s *)&(v+width+1)->x, (struct vec3_s *)&v->x);
            vec3Cross(&n2, &n1, &n2);
            vec3Add(&n3, &n3, &n2);
            //vec3Norm(&n3);  // Not necessary for our constrained mesh.

            v->s = (float)x * fw + n3.x;// * 0.2;
            v->t = (float)y * fh + n3.y;// * 0.2;
            v->z = 0;
            v += 1;
        }
    }
}

void genLeafDrop(struct Leaves_s *leaf, float amp) {
    float nx = (leaf->x + State->glWidth * 0.5f) / State->glWidth;
    float ny = (leaf->y + State->glHeight * 0.5f) / State->glHeight;
    drop(nx * State->meshWidth, State->meshHeight - ny * State->meshHeight, amp);

}

int drawLeaf(struct Leaves_s *leaf) {

    float x = leaf->x;
    float y = leaf->y;

    float u1 = leaf->u1;
    float u2 = leaf->u2;

    float a = leaf->altitude;
    float s = leaf->scale;
    float r = leaf->angle;

    float tz = 0.0f;
    if (a > 0.0f) {
        tz = -a;
    }

    float matrix[16];
    if (a > 0.0f) {

        float alpha = 1.0f;
        if (a >= 0.4f) alpha = 1.0f - (a - 0.4f) / 0.1f;

        color(0.0f, 0.0f, 0.0f, alpha * 0.15f);

        if (State->rotate) {
            matrixLoadRotate(matrix, 90.0f, 0.0f, 0.0f, 1.0f);
        } else {
            matrixLoadIdentity(matrix);
        }

        float shadowOffet = a / 5;

        matrixTranslate(matrix, (x - State->xOffset * 2) + (shadowOffet / 2), y - shadowOffet, tz);
        matrixScale(matrix, s, s, 1.0f);
        matrixRotate(matrix, r, 0.0f, 0.0f, 1.0f);
        vpLoadModelMatrix(matrix);

        drawQuadTexCoords(-LEAF_SIZE, -LEAF_SIZE, 0, u1, 1.0f,
                           LEAF_SIZE, -LEAF_SIZE, 0, u2, 1.0f,
                           LEAF_SIZE,  LEAF_SIZE, 0, u2, 0.0f,
                          -LEAF_SIZE,  LEAF_SIZE, 0, u1, 0.0f);

        color(1.0f, 1.0f, 1.0f, alpha);
    } else {
        color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    if (State->rotate) {
        matrixLoadRotate(matrix, 90.0f, 0.0f, 0.0f, 1.0f);
    } else {
        matrixLoadIdentity(matrix);
    }
    matrixTranslate(matrix, x - State->xOffset * 2, y, tz);
    matrixScale(matrix, s, s, 1.0f);
    matrixRotate(matrix, r, 0.0f, 0.0f, 1.0f);
    vpLoadModelMatrix(matrix);

    drawQuadTexCoords(-LEAF_SIZE, -LEAF_SIZE, 0, u1, 1.0f,
                       LEAF_SIZE, -LEAF_SIZE, 0, u2, 1.0f,
                       LEAF_SIZE,  LEAF_SIZE, 0, u2, 0.0f,
                      -LEAF_SIZE,  LEAF_SIZE, 0, u1, 0.0f);

    float spin = leaf->spin;
    if (a <= 0.0f) {
        float rippled = leaf->rippled;
        if (rippled < 0.0f) {
            genLeafDrop(leaf, 1.5f);
            //drop(((x + State->glWidth * 0.5f) / State->glWidth) * meshWidth,
            //     meshHeight - ((y + State->glHeight * 0.5f) / State->glHeight) * meshHeight, 1);
            spin /= 4.0f;
            leaf->spin = spin;
            leaf->rippled = 1.0f;
        }
        leaf->x = x + leaf->deltaX * g_DT;
        leaf->y = y + leaf->deltaY * g_DT;
        r += spin;
        leaf->angle = r;
    } else {
        a -= 0.15f * g_DT;
        leaf->altitude = a;
        r += spin * 2.0f;
        leaf->angle = r;
    }

    int newLeaf = 0;
    if (-LEAF_SIZE * s + x > State->glWidth || LEAF_SIZE * s + x < -State->glWidth ||
            LEAF_SIZE * s + y < -State->glHeight / 2.0f) {

        int sprite = randf(LEAVES_TEXTURES_COUNT);
        leaf->x = randf2(-State->glWidth, State->glWidth);
        leaf->y = randf2(-State->glHeight * 0.5f, State->glHeight * 0.5f);

        leaf->scale = randf2(0.4f, 0.5f);
        leaf->spin = degf(randf2(-0.02f, 0.02f)) * 0.35f;
        leaf->u1 = sprite / (float) LEAVES_TEXTURES_COUNT;
        leaf->u2 = (sprite + 1) / (float) LEAVES_TEXTURES_COUNT;
        leaf->altitude = 0.7f;
        leaf->rippled = -1.0f;
        leaf->deltaX = randf2(-0.02f, 0.02f) / 2.0f;
        leaf->deltaY = -0.08f * randf2(0.9f, 1.1f) / 2.0f;
        leaf->newLeaf = 1;
        newLeaf = 1;
    }
    return newLeaf;
}

void drawLeaves() {
    bindProgramFragment(NAMED_PFSky);
    bindProgramFragmentStore(NAMED_PFSLeaf);
    bindProgramVertex(NAMED_PVSky);
    bindTexture(NAMED_PFSky, 0, NAMED_TLeaves);

    color(1.0f, 1.0f, 1.0f, 1.0f);

    int newLeaves = 0;
    int i = 0;
    for ( ; i < LEAVES_COUNT; i += 1) {
        if (drawLeaf(gLeaves[i])) {
            newLeaves = 1;

        }
    }

    if (newLeaves > 0) {
        int index = 0;

        // Copy all the old leaves to the beginning of gNextLeaves
        for (i=0; i < LEAVES_COUNT; i++) {
            if (gLeaves[i]->newLeaf == 0) {
                gNextLeaves[index] = gLeaves[i];
                index++;
            }
        }

        // Now copy all the newly falling leaves to the end of gNextLeaves
        for (i=0; i < LEAVES_COUNT; i++) {
            if (gLeaves[i]->newLeaf > 0) {
                gNextLeaves[index] = gLeaves[i];
                gNextLeaves[index]->newLeaf = 0;
                index++;
            }
        }

        // And move everything in gNextLeaves back to gLeaves
        for (i=0; i < LEAVES_COUNT; i++) {
            gLeaves[i] = gNextLeaves[i];
        }
    }

    float matrix[16];
    matrixLoadIdentity(matrix);
    vpLoadModelMatrix(matrix);
}

void drawRiverbed() {
    bindTexture(NAMED_PFBackground, 0, NAMED_TRiverbed);

    float matrix[16];
    matrixLoadScale(matrix, 0.5f * 960.0f / 1024.0f, -1.0f * 800.0f / 1024.0f, 1.0f);
    matrixTranslate(matrix, State->xOffset, 0.0f, 0.0f);
    vpLoadTextureMatrix(matrix);

    drawSimpleMesh(NAMED_WaterMesh);

    matrixLoadIdentity(matrix);
    vpLoadTextureMatrix(matrix);
}

/*
void drawSky() {
    color(1.0f, 1.0f, 1.0f, 0.5f);

    bindProgramFragment(NAMED_PFSky);
    bindProgramFragmentStore(NAMED_PFSLeaf);
    bindTexture(NAMED_PFSky, 0, NAMED_TSky);

    float x = skyOffsetX + State->skySpeedX;
    float y = skyOffsetY + State->skySpeedY;

    if (x > 1.0f) x = 0.0f;
    if (x < -1.0f) x = 0.0f;
    if (y > 1.0f) y = 0.0f;

    skyOffsetX = x;
    skyOffsetY = y;

    float matrix[16];
    matrixLoadTranslate(matrix, x + State->xOffset, y, 0.0f);
    vpLoadTextureMatrix(matrix);

    drawSimpleMesh(NAMED_WaterMesh);

    matrixLoadIdentity(matrix);
    vpLoadTextureMatrix(matrix);
}
*/

int main(int index) {
    // Compute dt in seconds.
    int newTime = uptimeMillis();
    g_DT = (newTime - g_LastTime) / 1000.f;
    g_LastTime = newTime;
    g_DT = minf(g_DT, 0.2f);


    if (Drop->dropX != -1) {
        drop(Drop->dropX, Drop->dropY, 2);
        Drop->dropX = -1;
        Drop->dropY = -1;
    }

    int ct;
    int add = 0;
    for (ct = 0; ct < gMaxDrops; ct++) {
        if (gDrops[ct].ampE < 0.005f) {
            add = 1;
        }
    }

    if (add) {
        int i = (int)randf(LEAVES_COUNT);
        genLeafDrop(gLeaves[i], randf(0.3f) + 0.1f);
    }

    generateRipples();
    updateSimpleMesh(NAMED_WaterMesh);

    if (State->rotate) {
        float matrix[16];
        matrixLoadRotate(matrix, 90.0f, 0.0f, 0.0f, 1.0f);
        vpLoadModelMatrix(matrix);
    }

    drawRiverbed();
    // drawSky();
    drawLeaves();

    return 30;
}
