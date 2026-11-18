#ifndef VK_ENCODE_H
#define VK_ENCODE_H
#include <android/hardware_buffer.h>
#include <android/native_window.h>

/* Presents tapped game frames to a MediaCodec input surface through Vulkan,
 * the way EncodeProbe2 proved on this device: the pool AHB is imported with
 * VK_ANDROID_external_memory_android_hardware_buffer and blitted once into a
 * swapchain on the encoder surface. The EGLImage import path is dead on this
 * device (eglCreateImageKHR fails for every AHB), so this replaces it.
 *
 * All three calls run on the encoder thread only. vk_encode_start takes
 * ownership of the window reference passed in (it releases it on failure and
 * in vk_encode_stop); vk_encode_stop is idempotent. */

/* Creates the Vulkan instance/device/swapchain on window (width x height).
 * The surface extent must match width x height; a mismatch fails setup
 * instead of silently stretching. Returns 0 on success, <0 on failure. */
int vk_encode_start(ANativeWindow *window, int width, int height);

/* Presents one tapped frame. The AHB is only borrowed for the call; fence_fd
 * (or -1) is always consumed (closed) here. Returns 1 when a frame was
 * presented, 0 when the frame was skipped (unsupported format, fence timeout,
 * transient acquire failure), <0 on a fatal error (device/surface lost). */
int vk_encode_present_frame(AHardwareBuffer *ahb, int fence_fd);

void vk_encode_stop(void);

#endif
