#ifndef VK_FIT_RECT_H
#define VK_FIT_RECT_H

/* Pure aspect-fit geometry for the encoder blit: fits the source rect into
 * the destination rect, preserving aspect, centered. A 16:9 source fills the
 * frame exactly; a wider source (e.g. 1560x720 into 1920x1080) scales by the
 * width and letterboxes top/bottom. No Vulkan dependency, so the geometry is
 * checkable with a plain host compiler. */
static inline void vk_fit_rect(int src_w, int src_h, int dst_w, int dst_h,
                               int *x0, int *y0, int *x1, int *y1) {
    if (src_w <= 0 || src_h <= 0 || dst_w <= 0 || dst_h <= 0) {
        *x0 = 0;
        *y0 = 0;
        *x1 = dst_w;
        *y1 = dst_h;
        return;
    }
    int fit_w, fit_h;
    if ((long long)dst_w * src_h <= (long long)dst_h * src_w) {
        fit_w = dst_w;
        fit_h = (int)((long long)dst_w * src_h / src_w);
    } else {
        fit_h = dst_h;
        fit_w = (int)((long long)dst_h * src_w / src_h);
    }
    *x0 = (dst_w - fit_w) / 2;
    *y0 = (dst_h - fit_h) / 2;
    *x1 = *x0 + fit_w;
    *y1 = *y0 + fit_h;
}

#endif
