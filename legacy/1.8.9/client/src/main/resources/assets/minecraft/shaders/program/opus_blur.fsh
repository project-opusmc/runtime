#version 120

uniform sampler2D DiffuseSampler;

varying vec2 texCoord;
varying vec2 oneTexel;

uniform vec2 BlurDir;
uniform float Radius;

void main() {
    vec4 color = vec4(0.0);
    float samples = 0.0;
    for (float radius = -Radius; radius <= Radius; radius += 1.0) {
        color += texture2D(DiffuseSampler, texCoord + oneTexel * radius * BlurDir);
        samples += 1.0;
    }
    gl_FragColor = color / samples;
}
