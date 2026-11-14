#version 330
#extension GL_ARB_separate_shader_objects : require

// Billboards overlay shader: texture * vertex colour, without vanilla's alpha cut-offs, so overlays fade smoothly.
// LIT: the colour is multiplied by the lightmap, like vanilla text displays.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
#ifdef LIT
layout(location = 3) in ivec2 UV2;

uniform sampler2D Sampler2;

// Same as vanilla's sample_lightmap.glsl.
vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp((uv / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}
#endif

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
#ifdef LIT
    // Lit like vanilla text displays: the colour times the lightmap at the vertex's block/sky light.
    vertexColor = Color * vec4(sample_lightmap(Sampler2, UV2).rgb, 1.0);
#else
    vertexColor = Color;
#endif
}
