#version 450

// Building fragment: flat per-face shading (WS-A).
//
// The face normal is constant across the triangle (the CPU gives all three vertices the same
// one), so lighting it against a single fixed direction gives every face one flat tone - the
// S3DB look, and no shadows. The colour is the per-vertex ARGB the wall/roof carries; blending
// is the pipeline's job, so it passes straight through with its own alpha.
layout(location = 0) in vec3 vNormal;
layout(location = 1) in vec4 vColor;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    // The palette's building colour, for geometry the archive gave none. See below.
    vec4 color;
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;
layout(location = 0) out vec4 outColor;
void main() {
    vec3 n = normalize(vNormal);
    // A fixed light from above and the north-west, in tile-local space (x east, y south, z up),
    // so roofs read brightest and the walls facing away sit a shade darker.
    vec3 lightDir = normalize(vec3(-0.4, -0.4, 1.0));
    float diffuse = max(dot(n, lightDir), 0.0);
    // Generous ambient so a shadowed wall stays legible rather than going black.
    float light = 0.55 + 0.45 * diffuse;
    // Zero alpha is the tessellator's "this wall or roof has no colour of its own" marker (see
    // `extrude_building`): fall back to the pushed palette colour. Vertex colour is baked when the
    // tile is tessellated, where the palette is not known, so this is what lets a light/dark
    // switch recolour untagged buildings without re-tessellating them. A building that carries an
    // OSM `building:colour` has a real alpha and keeps it in both palettes.
    vec4 base = vColor.a < 0.5 ? push.color : vColor;
    outColor = vec4(base.rgb * light, base.a);
}
