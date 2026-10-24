// OpenCL 1.2; byte-exact device write/read verification, not a photo renderer.
__kernel void famara_rgb_smoke(__global uchar *rgb) {
    const int pixel = get_global_id(0);
    if (pixel >= 16384) return;
    rgb[pixel * 3] = (uchar)(pixel & 127);
    rgb[pixel * 3 + 1] = (uchar)(pixel >> 7);
    rgb[pixel * 3 + 2] = (uchar)42;
}
