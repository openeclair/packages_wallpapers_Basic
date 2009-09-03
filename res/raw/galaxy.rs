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

#define ELLIPSE_RATIO 0.892f

void drawSpace(float xOffset, int width, int height) {
    bindTexture(NAMED_PFBackground, 0, NAMED_TSpace);
    drawQuadTexCoords(
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
            width, 0.0f, 0.0f, 2.0f, 1.0f,
            width, height, 0.0f, 2.0f, 0.0f,
            0.0f, height, 0.0f, 0.0f, 0.0f);
}

void drawLights(float xOffset, int width, int height) {
    float x = (width - 512.0f) * 0.5f + xOffset;
    float y = (height - 512.0f) * 0.5f;

    // increase the size of the texture by 5% on each side
    x -= 512.0f * 0.05f;

    bindProgramFragment(NAMED_PFBackground);
    bindTexture(NAMED_PFBackground, 0, NAMED_TLight1);
    drawQuad(x + 512.0f * 1.1f, y         , 0.0f,
             x                , y         , 0.0f,
             x                , y + 512.0f, 0.0f,
             x + 512.0f * 1.1f, y + 512.0f, 0.0f);
}

void drawParticles(float xOffset, int width, int height) {
    bindProgramFragment(NAMED_PFBasic);
    bindProgramFragmentStore(NAMED_PFSLights);

    int radius = State->galaxyRadius;
    int particlesCount = State->particlesCount;

    float w = width * 0.5f + xOffset;
    float h = height * 0.5f;

    int i = 0;
    struct Stars_s *star = Stars;
    struct Parts_s *vtx = Parts;
    for ( ; i < particlesCount; i++) {
        float a = star->angle + star->speed;
        float x = star->distance * sinf(a);
        float y = star->distance * cosf(a) * ELLIPSE_RATIO;

        vtx->x = star->t * x + star->s * y + w;
        vtx->y = star->s * x - star->t * y + h;
        star->angle = a;
        star ++;
        vtx ++;
    }

    uploadToBufferObject(NAMED_ParticlesBuffer);
    drawSimpleMeshRange(NAMED_ParticlesMesh, 0, particlesCount);
}

int main(int index) {
    int width = State->width;
    int height = State->height;

    float w = width * 0.5f;
    float x = lerpf(w, -w, State->xOffset);

    drawSpace(x, width, height);
    drawParticles(x, width, height);
    drawLights(x, width, height);
    return 1;
}
