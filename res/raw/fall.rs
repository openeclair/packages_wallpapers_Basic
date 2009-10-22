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

#define LEAVES_TEXTURES_COUNT 4
#define LEAF_SIZE 0.55f

float skyOffsetX;
float skyOffsetY;

struct vert_s {
    float x;
    float y;
    float z;
    float s;
    float t;
    float nx;
    float ny;
    float nz;
};

struct drop_s {
    float amp;
    float spread;
    float spread2;
    float invSpread;
    float invSpread2;
    float x;
    float y;
};
struct drop_s gDrops[10];
int gNextDrop;
int gMaxDrops;

void init() {
    int ct;
    gMaxDrops = 10;
    for (ct=0; ct<gMaxDrops; ct++) {
        gDrops[ct].amp = 0;
        gDrops[ct].spread = 1;
        gDrops[ct].spread2 = gDrops[ct].spread * gDrops[ct].spread;
        gDrops[ct].invSpread = 1 / gDrops[ct].spread;
        gDrops[ct].invSpread2 = gDrops[ct].invSpread * gDrops[ct].invSpread;
    }
    gNextDrop = 0;
}

void initLeaves() {
    struct Leaves_s *leaf = Leaves;
    int leavesCount = State->leavesCount;
    float width = State->glWidth * 2;
    float height = State->glHeight;

    int i;
    for (i = 0; i < leavesCount; i ++) {
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
        leaf->deltaX = randf2(-0.02f, 0.02f) / 60.0f;
        leaf->deltaY = -0.08f * randf2(0.9f, 1.1f) / 60.0f;
        leaf++;
    }
}

void drop(int x, int y, float s) {
    gDrops[gNextDrop].amp = s;
    gDrops[gNextDrop].spread = 0.5f;
    gDrops[gNextDrop].x = x;
    gDrops[gNextDrop].y = State->meshHeight - y - 1;
    gNextDrop++;
    if (gNextDrop >= gMaxDrops)
        gNextDrop = 0;
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
    {
        int x, y, ct;
        struct vert_s *vtx = vert;
        for (y=0; y < height; y++) {
            for (x=0; x < width; x++) {
                struct drop_s * d = &gDrops[0];
                float z = 0;

                for (ct = 0; ct < gMaxDrops; ct++) {
                    if (d->amp > 0.01f) {
                        float dx = (d->x - xShift) - x;
                        float dy = d->y - y;
                        float dist2 = dx*dx + dy*dy;
                        if (dist2 < d->spread2) {
                            float dist = sqrtf(dist2);
                            float a = d->amp * dist * d->invSpread2;
                            z += sinf(d->spread - dist) * a;
                        }
                    }
                    d++;
                }
                vtx->s = (float)x * fw;
                vtx->t = (float)y * fh;
                vtx->z = z;
                vtx ++;
            }
        }
        for (ct = 0; ct < gMaxDrops; ct++) {
            gDrops[ct].spread += 1;
            gDrops[ct].spread2 = gDrops[ct].spread * gDrops[ct].spread;
            gDrops[ct].invSpread = 1 / gDrops[ct].spread;
            gDrops[ct].invSpread2 = gDrops[ct].invSpread * gDrops[ct].invSpread;
            gDrops[ct].amp = maxf(gDrops[ct].amp - 0.01f, 0);
        }
    }

    // Compute the normals for lighting
    int y = 0;
    for ( ; y < (height-1); y += 1) {
        int x = 0;
        int yOffset = y * width;
        struct vert_s *v = vert;
        v += y * width;

        for ( ; x < (width-1); x += 1) {
            struct vec3_s n1, n2, n3;
            vec3Sub(&n1, (struct vec3_s *)&(v+1)->x, (struct vec3_s *)&v->x);
            vec3Sub(&n2, (struct vec3_s *)&(v+width)->x, (struct vec3_s *)&v->x);
            vec3Cross(&n3, &n1, &n2);
            vec3Norm(&n3);

            // Average of previous normal and N1 x N2
            vec3Sub(&n1, (struct vec3_s *)&(v+width+1)->x, (struct vec3_s *)&v->x);
            vec3Cross(&n2, &n1, &n2);
            vec3Add(&n3, &n3, &n2);
            vec3Norm(&n3);

            v->nx = n3.x;
            v->ny = n3.y;
            v->nz = -n3.z;
            v->s += v->nx * 0.005;
            v->t += v->ny * 0.005;
            v += 1;

            // reset Z
            //vertices[(yOffset + x) << 3 + 7] = 0.0f;
        }
    }
}

void drawLeaf(struct Leaves_s *leaf, int meshWidth, int meshHeight, float glWidth, float glHeight,
        int rotate) {

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
        color(0.0f, 0.0f, 0.0f, 0.15f);

        if (rotate) {
            matrixLoadRotate(matrix, 90.0f, 0.0f, 0.0f, 1.0f);
        } else {
            matrixLoadIdentity(matrix);
        }
        matrixTranslate(matrix, x - State->xOffset * 2, y, 0.0f);
        matrixScale(matrix, s, s, 1.0f);
        matrixRotate(matrix, r, 0.0f, 0.0f, 1.0f);
        vpLoadModelMatrix(matrix);

        drawQuadTexCoords(-LEAF_SIZE, -LEAF_SIZE, 0, u1, 1.0f,
                           LEAF_SIZE, -LEAF_SIZE, 0, u2, 1.0f,
                           LEAF_SIZE,  LEAF_SIZE, 0, u2, 0.0f,
                          -LEAF_SIZE,  LEAF_SIZE, 0, u1, 0.0f);

        float alpha = 1.0f;
        if (a >= 0.4f) alpha = 1.0f - (a - 0.5f) / 0.1f;
        color(1.0f, 1.0f, 1.0f, alpha);
    } else {
        color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    if (rotate) {
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
            drop(((x + glWidth * 0.5f) / glWidth) * meshWidth,
                 meshHeight - ((y + glHeight * 0.5f) / glHeight) * meshHeight, 1);
            spin /= 4.0f;
            leaf->spin = spin;
            leaf->rippled = 1.0f;
        }
        leaf->x = x + leaf->deltaX;
        leaf->y = y + leaf->deltaY;
        r += spin;
        leaf->angle = r;
    } else {
        a -= 0.005f;
        leaf->altitude = a;
        r += spin * 2.0f;
        leaf->angle = r;
    }

    if (-LEAF_SIZE * s + x > glWidth || LEAF_SIZE * s + x < -glWidth ||
            LEAF_SIZE * s + y < -glHeight / 2.0f) {

        int sprite = randf(LEAVES_TEXTURES_COUNT);
        leaf->x = randf2(-glWidth, glWidth);
        leaf->y = randf2(-glHeight * 0.5f, glHeight * 0.5f);
        leaf->scale = randf2(0.4f, 0.5f);
        leaf->spin = degf(randf2(-0.02f, 0.02f)) * 0.25f;
        leaf->u1 = sprite / (float) LEAVES_TEXTURES_COUNT;
        leaf->u2 = (sprite + 1) / (float) LEAVES_TEXTURES_COUNT;
        leaf->altitude = 0.6f;
        leaf->rippled = -1.0f;
        leaf->deltaX = randf2(-0.02f, 0.02f) / 60.0f;
        leaf->deltaY = -0.08f * randf2(0.9f, 1.1f) / 60.0f;
    }
}

void drawLeaves() {
    bindProgramFragment(NAMED_PFSky);
    bindProgramFragmentStore(NAMED_PFSLeaf);
    bindProgramVertex(NAMED_PVSky);
    bindTexture(NAMED_PFSky, 0, NAMED_TLeaves);

    color(1.0f, 1.0f, 1.0f, 1.0f);

    int leavesCount = State->leavesCount;
    int width = State->meshWidth;
    int height = State->meshHeight;
    float glWidth = State->glWidth;
    float glHeight = State->glHeight;
    int rotate = State->rotate;

    struct Leaves_s *leaf = Leaves;

    int i = 0;
    for ( ; i < leavesCount; i += 1) {
        drawLeaf(leaf, width, height, glWidth, glHeight, rotate);
        leaf += 1;
    }

    float matrix[16];
    matrixLoadIdentity(matrix);
    vpLoadModelMatrix(matrix);
}

void drawRiverbed() {
    bindTexture(NAMED_PFBackground, 0, NAMED_TRiverbed);

    float matrix[16];
    matrixLoadTranslate(matrix, + State->xOffset, 0.f, 0.0f);
    vpLoadTextureMatrix(matrix);
    drawSimpleMesh(NAMED_WaterMesh);
}

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

void drawLighting() {
    ambient(0.0f, 0.0f, 0.0f, 1.0f);
    diffuse(0.0f, 0.0f, 0.0f, 1.0f);
    specular(0.44f, 0.44f, 0.44f, 1.0f);
    shininess(40.0f);

    bindProgramFragmentStore(NAMED_PFSBackground);
    bindProgramFragment(NAMED_PFLighting);
    bindProgramVertex(NAMED_PVLight);

    drawSimpleMesh(NAMED_WaterMesh);
}

void drawNormals() {
    int width = State->meshWidth;
    int height = State->meshHeight;

    float *vertices = loadSimpleMeshVerticesF(NAMED_WaterMesh, 0);

    bindProgramVertex(NAMED_PVSky);
    bindProgramFragment(NAMED_PFLighting);

    color(1.0f, 0.0f, 0.0f, 1.0f);

    float scale = 1.0f / 10.0f;
    int y = 0;
    for ( ; y < height; y += 1) {
        int yOffset = y * width;
        int x = 0;
        for ( ; x < width; x += 1) {
            int offset = (yOffset + x) << 3;
            float vx = vertices[offset + 5];
            float vy = vertices[offset + 6];
            float vz = vertices[offset + 7];
            float nx = vertices[offset + 0];
            float ny = vertices[offset + 1];
            float nz = vertices[offset + 2];
            drawLine(vx, vy, vz, vx + nx * scale, vy + ny * scale, vz + nz * scale);
        }
    }
}

int main(int index) {
    if (Drop->dropX != -1) {
        drop(Drop->dropX, Drop->dropY, 1);
        Drop->dropX = -1;
        Drop->dropY = -1;
    }

    int ct;
    float amp = 0;
    for (ct = 0; ct < gMaxDrops; ct++) {
        amp += gDrops[ct].amp;
    }

    if (State->isPreview || (amp < 0.2f)) {
        float x = randf(State->meshWidth);
        float y = randf(State->meshHeight);

        if (State->isPreview) {
            drop(x, y, 1.f);
        } else {
            drop(x, y, 0.2f);
        }
    }

    generateRipples();
    updateSimpleMesh(NAMED_WaterMesh);

    if (State->rotate) {
        float matrix[16];
        matrixLoadRotate(matrix, 90.0f, 0.0f, 0.0f, 1.0f);
        vpLoadModelMatrix(matrix);
    }

    drawRiverbed();
    drawSky();
    drawLighting();
    drawLeaves();
    //drawNormals();

    return 1;
}
