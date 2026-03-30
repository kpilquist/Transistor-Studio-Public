#version 430 core

in vec2 fragTexCoord;
out vec4 outColor;

uniform sampler2D waterfallTexture;
uniform float yOffset; // Normalized offset (0.0 to 1.0)
uniform int logScale; // Boolean 0 or 1

// Simple heatmap palette
vec3 heatmap(float v) {
    float r = v * 3.0;
    float g = (v - 0.33) * 3.0;
    float b = (v - 0.66) * 3.0;
    return clamp(vec3(r, g, b), 0.0, 1.0);
}

void main() {
    vec2 uv = fragTexCoord;

    // 1. Logarithmic Frequency Scaling (X-axis)
    if (logScale == 1) {
        // Simple log mapping to emphasize lower frequencies
        float a = 100.0;
        uv.x = (pow(a + 1.0, uv.x) - 1.0) / a;
    }

    // 2. Circular Buffer Offset (Y-axis)
    // We want the newest data at the top (uv.y = 0)
    // yOffset points to the row we just wrote.
    // So uv.y=0 -> yOffset
    // uv.y=1 -> yOffset - 1 (wrapped)
    // Or simply:
    uv.y = mod(yOffset - uv.y, 1.0);

    // Sample magnitude
    float magnitude = texture(waterfallTexture, uv).r;

    // 3. Log Magnitude Scaling (dB)
    // magnitude is linear amplitude.
    // Use 20 * log10(A) for amplitude.
    float db = 20.0 * log(magnitude + 1.0e-8) / log(10.0);

    // Map dB range to [0, 1] for visibility. Slightly wider range to avoid all-black.
    float minDb = -120.0;
    float maxDb = -30.0;
    float normalized = (db - minDb) / (maxDb - minDb);
    normalized = clamp(normalized, 0.0, 1.0);

    outColor = vec4(heatmap(normalized), 1.0);
}

