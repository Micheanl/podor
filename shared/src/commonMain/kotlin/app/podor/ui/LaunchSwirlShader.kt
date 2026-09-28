package app.podor.ui

// Adapted from Paper Shaders (Apache-2.0): Swirl only, SkSL/AGSL coordinates and Bayer arithmetic;
// see licenses/paper-shaders.
internal const val launchSwirlShader =
    """
uniform float2 resolution;
uniform float pixelSize;
uniform float time;
uniform float4 colorBack;
uniform float4 colorFront;

float bayer2(float2 p) {
    return 2.0 * p.x + 3.0 * p.y - 4.0 * p.x * p.y;
}

half4 main(float2 coordinate) {
    float2 grid = (float2(coordinate.x, resolution.y - coordinate.y) - 0.5 * resolution) / pixelSize;
    float2 cell = floor(grid);
    float2 uv = cell * pixelSize / min(resolution.x, resolution.y);
    float radius = max(length(uv), 0.000001);
    float angle = 6.0 * atan(uv.y, uv.x) + 2.0 * time;
    float radial = pow(radius, 1.2);
    float phase = 1.0 / radial + angle / 6.28318530718;
    float shape = fract(phase) * smoothstep(0.0, 1.0, radial);
    float2 low = mod(cell, 2.0);
    float2 high = floor(mod(cell, 4.0) / 2.0);
    float threshold = (4.0 * bayer2(low) + bayer2(high)) / 16.0;
    return half4(mix(colorBack, colorFront, step(1.0, shape + threshold)));
}
"""
