#version 330
#extension GL_ARB_separate_shader_objects : require

// Billboards overlay shader: texture * vertex colour, without vanilla's alpha cut-offs, so overlays fade smoothly.
// Variants (shader defines):
//   GRAYSCALE   - single-channel glyph atlases (TTF fonts from resource packs): coverage is in the red channel
//   SILHOUETTE  - the texture only gives the shape (alpha); the colour comes from the vertex (shadows, outlines, glows)
//   BLUR        - gaussian blur in texture space; the radius in UV units is in TextureMat[0][0] / TextureMat[1][1]
//   PREMULTIPLY - output premultiplied colour, for the additive / multiply / screen blend modes
//   FOG         - vanilla's world fog (as on text displays); the other blend modes fade out instead of tinting

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

#ifdef FOG
layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;

// Same as vanilla's fog.glsl.
float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 0.0;
    } else if (vertexDistance >= fogEnd) {
        return 1.0;
    }
    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}

float fog_value() {
    return max(linear_fog_value(sphericalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd),
               linear_fog_value(cylindricalVertexDistance, FogRenderDistanceStart, FogRenderDistanceEnd)) * FogColor.a;
}
#endif

layout(location = 0) out vec4 fragColor;

vec4 sampleTexture(vec2 uv) {
#ifdef GRAYSCALE
    return texture(Sampler0, uv).rrrr;
#else
    return texture(Sampler0, uv);
#endif
}

void main() {
#ifdef BLUR
    vec2 radius = vec2(TextureMat[0][0], TextureMat[1][1]);
    vec4 sum = vec4(0.0);
    float total = 0.0;
    for (int x = -3; x <= 3; x++) {
        for (int y = -3; y <= 3; y++) {
            float weight = exp(-float(x * x + y * y) / 4.5);
            vec2 uv = texCoord0 + vec2(float(x), float(y)) / 3.0 * radius;
            // The quad is grown by the radius; outside the picture counts as transparent.
            vec4 c = (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) ? vec4(0.0) : sampleTexture(uv);
            sum += vec4(c.rgb * c.a, c.a) * weight;
            total += weight;
        }
    }
    vec4 texColor = sum.a > 0.0 ? vec4(sum.rgb / sum.a, sum.a / total) : vec4(0.0);
#else
    vec4 texColor = sampleTexture(texCoord0);
#endif

#ifdef SILHOUETTE
    vec4 color = vec4(vertexColor.rgb, texColor.a * vertexColor.a) * ColorModulator;
#else
    vec4 color = texColor * vertexColor * ColorModulator;
#endif
    if (color.a <= 0.0) {
        discard;
    }
#ifdef FOG
#ifdef PREMULTIPLY
    // Additive / multiply / screen: blending in the fog colour would add or remove light, so fade out instead.
    color.a *= 1.0 - fog_value();
#else
    color.rgb = mix(color.rgb, FogColor.rgb, fog_value());
#endif
#endif
#ifdef PREMULTIPLY
    color.rgb *= color.a;
#endif
    fragColor = color;
}
