#version 450
// Sprite fragment: a POI icon, sampled straight out of the RGBA sheet.
//
// Deliberately not `symbol.frag`. A glyph is a signed distance field that has to be
// thresholded, smoothed and haloed; an icon is already the picture. Sampling it through
// the SDF path would read its red channel as a distance and draw a smeared silhouette.
//
// Pairs with `symbol_billboard.vert` for POI icons (which billboard under tilt like the text
// beside them) and with `symbol.vert` for app markers (already resolved to clip space on the
// CPU). Same pipeline layout, same push block prefix, same descriptor set slot in both cases —
// only the bound atlas and this shader differ from the glyph path.
layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    // rgb is the layer's TEXT colour and is deliberately unused here: the reference style
    // sets no `icon-color`, so an icon draws in its own colours. Only `.a` is read, which
    // carries the layer's opacity ramp.
    vec4 color;
    vec4 line;
    vec4 misc;
} push;
layout(set = 0, binding = 0) uniform sampler2D atlas;
void main() {
    vec4 texel = texture(atlas, inUv);
    float alpha = texel.a * push.color.a;
    // A sprite sheet is mostly empty space between icons, and the pipeline has no depth
    // test, so discarding fully-transparent fragments is what keeps one icon's padding
    // from blending over the label beside it.
    if (alpha <= 0.001) discard;
    // The sheet stores straight (unpremultiplied) alpha and the pipeline blends
    // SRC_ALPHA / ONE_MINUS_SRC_ALPHA, so the colour must not be premultiplied here.
    outColor = vec4(texel.rgb, alpha);
}
