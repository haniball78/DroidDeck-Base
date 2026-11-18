#define _GNU_SOURCE
#define VK_USE_PLATFORM_ANDROID_KHR
#include "vk_encode.h"
#include "vk_fit_rect.h"
#include "vk_present.h"
#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/* Own loader table (the compositor's g_vk is a single global that
 * vk_loader_load_instance/device rewrites, so the encoder keeps its own). */
struct vk_enc_fns {
    PFN_vkCreateInstance CreateInstance;
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties;
    PFN_vkCreateAndroidSurfaceKHR CreateAndroidSurfaceKHR;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
    PFN_vkEnumerateDeviceExtensionProperties EnumerateDeviceExtensionProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkCreateCommandPool CreateCommandPool;
    PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers;
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
    PFN_vkResetCommandBuffer ResetCommandBuffer;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdBlitImage CmdBlitImage;
    PFN_vkCmdClearColorImage CmdClearColorImage;
    PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkResetFences ResetFences;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAndroidHardwareBufferPropertiesANDROID;
};

static struct {
    void *lib;
    struct vk_enc_fns vk;
    VkInstance instance;
    VkSurfaceKHR surface;
    VkPhysicalDevice phys;
    VkDevice device;
    VkQueue queue;
    VkSwapchainKHR swapchain;
    VkImage *swap_images;
    uint32_t swap_n;
    VkFormat swap_format;
    VkExtent2D swap_extent;
    VkFilter blit_filter;
    VkCommandPool cmd_pool;
    VkCommandBuffer cmd;
    VkFence fence;
    ANativeWindow *window;
    int width, height;
    int started;
} g_enc;

static int g_fmt_logged;

static void teardown(void) {
    struct vk_enc_fns *vk = &g_enc.vk;
    if (g_enc.device != VK_NULL_HANDLE) vk->DeviceWaitIdle(g_enc.device);
    if (g_enc.fence != VK_NULL_HANDLE) vk->DestroyFence(g_enc.device, g_enc.fence, NULL);
    if (g_enc.cmd_pool != VK_NULL_HANDLE) vk->DestroyCommandPool(g_enc.device, g_enc.cmd_pool, NULL);
    if (g_enc.swapchain != VK_NULL_HANDLE) vk->DestroySwapchainKHR(g_enc.device, g_enc.swapchain, NULL);
    free(g_enc.swap_images);
    if (g_enc.surface != VK_NULL_HANDLE) vk->DestroySurfaceKHR(g_enc.instance, g_enc.surface, NULL);
    if (g_enc.device != VK_NULL_HANDLE) vk->DestroyDevice(g_enc.device, NULL);
    if (g_enc.instance != VK_NULL_HANDLE) vk->DestroyInstance(g_enc.instance, NULL);
    if (g_enc.window) ANativeWindow_release(g_enc.window);
    if (g_enc.lib) dlclose(g_enc.lib);
    memset(&g_enc, 0, sizeof(g_enc));
}

void vk_encode_stop(void) {
    if (!g_enc.started) return;
    teardown();
}

int vk_encode_start(ANativeWindow *window, int width, int height) {
    if (g_enc.started) vk_encode_stop();
    if (!window || width <= 0 || height <= 0) return -1;
    memset(&g_enc, 0, sizeof(g_enc));
    g_enc.window = window;
    g_enc.width = width;
    g_enc.height = height;
    struct vk_enc_fns *vk = &g_enc.vk;

    g_enc.lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_enc.lib) {
        droiddeck_log("tv", "vk-encode: dlopen(libvulkan.so) failed");
        teardown();
        return -1;
    }
    PFN_vkGetInstanceProcAddr gipa =
            (PFN_vkGetInstanceProcAddr)dlsym(g_enc.lib, "vkGetInstanceProcAddr");
    if (!gipa) {
        droiddeck_log("tv", "vk-encode: no vkGetInstanceProcAddr");
        teardown();
        return -1;
    }
    vk->CreateInstance = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
    if (!vk->CreateInstance) {
        droiddeck_log("tv", "vk-encode: no vkCreateInstance");
        teardown();
        return -1;
    }
    {
        const char *exts[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
        VkApplicationInfo appInfo = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                                     .pApplicationName = "DroidDeck",
                                     .apiVersion = VK_API_VERSION_1_1};
        VkInstanceCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                    .pApplicationInfo = &appInfo,
                                    .enabledExtensionCount = 2, .ppEnabledExtensionNames = exts};
        if (vk->CreateInstance(&ici, NULL, &g_enc.instance) != VK_SUCCESS) {
            droiddeck_log("tv", "vk-encode: vkCreateInstance failed");
            teardown();
            return -1;
        }
    }
#define LOAD_INSTANCE(n) vk->n = (PFN_vk##n)gipa(g_enc.instance, "vk" #n)
    LOAD_INSTANCE(DestroyInstance);
    LOAD_INSTANCE(EnumeratePhysicalDevices);
    LOAD_INSTANCE(GetPhysicalDeviceProperties);
    LOAD_INSTANCE(GetPhysicalDeviceQueueFamilyProperties);
    LOAD_INSTANCE(GetPhysicalDeviceSurfaceSupportKHR);
    LOAD_INSTANCE(GetPhysicalDeviceSurfaceCapabilitiesKHR);
    LOAD_INSTANCE(GetPhysicalDeviceSurfaceFormatsKHR);
    LOAD_INSTANCE(GetPhysicalDeviceFormatProperties);
    LOAD_INSTANCE(CreateAndroidSurfaceKHR);
    LOAD_INSTANCE(DestroySurfaceKHR);
    LOAD_INSTANCE(CreateDevice);
    LOAD_INSTANCE(GetDeviceProcAddr);
    LOAD_INSTANCE(EnumerateDeviceExtensionProperties);
    LOAD_INSTANCE(GetPhysicalDeviceMemoryProperties);
#undef LOAD_INSTANCE
    if (!vk->DestroyInstance || !vk->EnumeratePhysicalDevices || !vk->GetPhysicalDeviceProperties ||
        !vk->GetPhysicalDeviceQueueFamilyProperties || !vk->GetPhysicalDeviceSurfaceSupportKHR ||
        !vk->GetPhysicalDeviceSurfaceCapabilitiesKHR || !vk->GetPhysicalDeviceSurfaceFormatsKHR ||
        !vk->GetPhysicalDeviceFormatProperties || !vk->CreateAndroidSurfaceKHR || !vk->DestroySurfaceKHR ||
        !vk->CreateDevice || !vk->GetDeviceProcAddr || !vk->EnumerateDeviceExtensionProperties ||
        !vk->GetPhysicalDeviceMemoryProperties) {
        droiddeck_log("tv", "vk-encode: missing instance function");
        teardown();
        return -1;
    }
    {
        VkAndroidSurfaceCreateInfoKHR sci = {.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
                                             .window = window};
        if (vk->CreateAndroidSurfaceKHR(g_enc.instance, &sci, NULL, &g_enc.surface) != VK_SUCCESS) {
            droiddeck_log("tv", "vk-encode: vkCreateAndroidSurfaceKHR failed");
            teardown();
            return -1;
        }
    }
    uint32_t queue_family = 0;
    char device_name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE] = "";
    {
        uint32_t ndev = 0;
        vk->EnumeratePhysicalDevices(g_enc.instance, &ndev, NULL);
        if (ndev == 0) {
            droiddeck_log("tv", "vk-encode: no physical devices");
            teardown();
            return -1;
        }
        VkPhysicalDevice *devs = malloc(sizeof(VkPhysicalDevice) * ndev);
        if (!devs) {
            teardown();
            return -1;
        }
        vk->EnumeratePhysicalDevices(g_enc.instance, &ndev, devs);
        for (uint32_t d = 0; d < ndev && !g_enc.phys; d++) {
            VkPhysicalDeviceProperties props;
            vk->GetPhysicalDeviceProperties(devs[d], &props);
            uint32_t nfam = 0;
            vk->GetPhysicalDeviceQueueFamilyProperties(devs[d], &nfam, NULL);
            VkQueueFamilyProperties *fams = malloc(sizeof(VkQueueFamilyProperties) * nfam);
            if (!fams) continue;
            vk->GetPhysicalDeviceQueueFamilyProperties(devs[d], &nfam, fams);
            for (uint32_t f = 0; f < nfam; f++) {
                if (!(fams[f].queueFlags & VK_QUEUE_GRAPHICS_BIT)) continue;
                VkBool32 present = VK_FALSE;
                vk->GetPhysicalDeviceSurfaceSupportKHR(devs[d], f, g_enc.surface, &present);
                if (present) {
                    g_enc.phys = devs[d];
                    queue_family = f;
                    strncpy(device_name, props.deviceName, sizeof(device_name) - 1);
                    break;
                }
            }
            free(fams);
        }
        free(devs);
        if (!g_enc.phys) {
            droiddeck_log("tv", "vk-encode: no device with a graphics+present queue");
            teardown();
            return -1;
        }
    }
    {
        uint32_t next = 0;
        vk->EnumerateDeviceExtensionProperties(g_enc.phys, NULL, &next, NULL);
        VkExtensionProperties *exts = malloc(sizeof(VkExtensionProperties) * (next ? next : 1));
        if (!exts) {
            teardown();
            return -1;
        }
        vk->EnumerateDeviceExtensionProperties(g_enc.phys, NULL, &next, exts);
        int has_swap = 0, has_ahb = 0;
        for (uint32_t i = 0; i < next; i++) {
            if (!strcmp(exts[i].extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) has_swap = 1;
            if (!strcmp(exts[i].extensionName,
                       VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)) has_ahb = 1;
        }
        free(exts);
        if (!has_swap || !has_ahb) {
            droiddeck_log("tv", "vk-encode: device lacks swapchain (%d) or AHB import (%d)", has_swap, has_ahb);
            teardown();
            return -1;
        }
    }
    {
        float prio = 1.0f;
        VkDeviceQueueCreateInfo qci = {.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                       .queueFamilyIndex = queue_family, .queueCount = 1,
                                       .pQueuePriorities = &prio};
        const char *dexts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                               VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME};
        VkDeviceCreateInfo dci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                                  .queueCreateInfoCount = 1, .pQueueCreateInfos = &qci,
                                  .enabledExtensionCount = 2, .ppEnabledExtensionNames = dexts};
        if (vk->CreateDevice(g_enc.phys, &dci, NULL, &g_enc.device) != VK_SUCCESS) {
            droiddeck_log("tv", "vk-encode: vkCreateDevice failed");
            teardown();
            return -1;
        }
    }
#define LOAD_DEVICE(n) vk->n = (PFN_vk##n)vk->GetDeviceProcAddr(g_enc.device, "vk" #n)
    LOAD_DEVICE(GetDeviceQueue);
    LOAD_DEVICE(CreateSwapchainKHR);
    LOAD_DEVICE(DestroySwapchainKHR);
    LOAD_DEVICE(GetSwapchainImagesKHR);
    LOAD_DEVICE(CreateCommandPool);
    LOAD_DEVICE(DestroyCommandPool);
    LOAD_DEVICE(AllocateCommandBuffers);
    LOAD_DEVICE(CreateFence);
    LOAD_DEVICE(DestroyFence);
    LOAD_DEVICE(DeviceWaitIdle);
    LOAD_DEVICE(DestroyDevice);
    LOAD_DEVICE(CreateImage);
    LOAD_DEVICE(DestroyImage);
    LOAD_DEVICE(GetImageMemoryRequirements);
    LOAD_DEVICE(AllocateMemory);
    LOAD_DEVICE(FreeMemory);
    LOAD_DEVICE(BindImageMemory);
    LOAD_DEVICE(AcquireNextImageKHR);
    LOAD_DEVICE(ResetCommandBuffer);
    LOAD_DEVICE(BeginCommandBuffer);
    LOAD_DEVICE(CmdPipelineBarrier);
    LOAD_DEVICE(CmdBlitImage);
    LOAD_DEVICE(CmdClearColorImage);
    LOAD_DEVICE(EndCommandBuffer);
    LOAD_DEVICE(ResetFences);
    LOAD_DEVICE(QueueSubmit);
    LOAD_DEVICE(QueuePresentKHR);
    LOAD_DEVICE(WaitForFences);
    LOAD_DEVICE(GetAndroidHardwareBufferPropertiesANDROID);
#undef LOAD_DEVICE
    if (!vk->GetDeviceQueue || !vk->CreateSwapchainKHR || !vk->DestroySwapchainKHR ||
        !vk->GetSwapchainImagesKHR || !vk->CreateCommandPool || !vk->DestroyCommandPool ||
        !vk->AllocateCommandBuffers || !vk->CreateFence || !vk->DestroyFence || !vk->DeviceWaitIdle ||
        !vk->DestroyDevice || !vk->CreateImage || !vk->DestroyImage || !vk->GetImageMemoryRequirements ||
        !vk->AllocateMemory || !vk->FreeMemory || !vk->BindImageMemory ||
        !vk->AcquireNextImageKHR || !vk->ResetCommandBuffer ||
        !vk->BeginCommandBuffer || !vk->CmdPipelineBarrier || !vk->CmdBlitImage || !vk->CmdClearColorImage ||
        !vk->EndCommandBuffer ||
        !vk->ResetFences || !vk->QueueSubmit || !vk->QueuePresentKHR || !vk->WaitForFences ||
        !vk->GetAndroidHardwareBufferPropertiesANDROID) {
        droiddeck_log("tv", "vk-encode: missing device function");
        teardown();
        return -1;
    }
    vk->GetDeviceQueue(g_enc.device, queue_family, 0, &g_enc.queue);
    {
        VkFormatProperties props;
        vk->GetPhysicalDeviceFormatProperties(g_enc.phys, VK_FORMAT_R8G8B8A8_UNORM, &props);
        if (!(props.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT)) {
            droiddeck_log("tv", "vk-encode: RGBA8 cannot be a blit source on this device");
            teardown();
            return -1;
        }
        g_enc.blit_filter = (props.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)
                                    ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    }
    VkSurfaceFormatKHR chosen;
    {
        VkSurfaceCapabilitiesKHR caps;
        if (vk->GetPhysicalDeviceSurfaceCapabilitiesKHR(g_enc.phys, g_enc.surface, &caps) != VK_SUCCESS) {
            droiddeck_log("tv", "vk-encode: surface capabilities failed");
            teardown();
            return -1;
        }
        uint32_t nfmt = 0;
        vk->GetPhysicalDeviceSurfaceFormatsKHR(g_enc.phys, g_enc.surface, &nfmt, NULL);
        VkSurfaceFormatKHR *fmts = malloc(sizeof(VkSurfaceFormatKHR) * (nfmt ? nfmt : 1));
        if (!fmts) {
            teardown();
            return -1;
        }
        vk->GetPhysicalDeviceSurfaceFormatsKHR(g_enc.phys, g_enc.surface, &nfmt, fmts);
        chosen = fmts[0];
        for (uint32_t i = 0; i < nfmt; i++) {
            if (fmts[i].format == VK_FORMAT_R8G8B8A8_UNORM) { chosen = fmts[i]; break; }
            if (fmts[i].format == VK_FORMAT_B8G8R8A8_UNORM) chosen = fmts[i];
        }
        free(fmts);
        VkFormatProperties dst_props;
        vk->GetPhysicalDeviceFormatProperties(g_enc.phys, chosen.format, &dst_props);
        if (!(dst_props.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_DST_BIT)) {
            droiddeck_log("tv", "vk-encode: swapchain format %d cannot be a blit target", (int)chosen.format);
            teardown();
            return -1;
        }
        g_enc.swap_format = chosen.format;
        g_enc.swap_extent = caps.currentExtent;
        if (g_enc.swap_extent.width == 0xFFFFFFFF) {
            g_enc.swap_extent.width = (uint32_t)width;
            g_enc.swap_extent.height = (uint32_t)height;
        } else if (g_enc.swap_extent.width != (uint32_t)width ||
                   g_enc.swap_extent.height != (uint32_t)height) {
            droiddeck_log("tv", "vk-encode: encoder surface is %ux%u, expected %dx%d",
                       g_enc.swap_extent.width, g_enc.swap_extent.height, width, height);
            teardown();
            return -1;
        }
        uint32_t want = caps.minImageCount + 1;
        if (caps.maxImageCount && want > caps.maxImageCount) want = caps.maxImageCount;
        VkSurfaceTransformFlagBitsKHR pretrans =
                (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                        ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
        VkSwapchainCreateInfoKHR sci = {.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
                                        .surface = g_enc.surface, .minImageCount = want,
                                        .imageFormat = chosen.format, .imageColorSpace = chosen.colorSpace,
                                        .imageExtent = g_enc.swap_extent, .imageArrayLayers = 1,
                                        .imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                                        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
                                        .preTransform = pretrans,
                                        .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
                                        .presentMode = VK_PRESENT_MODE_FIFO_KHR, .clipped = VK_TRUE};
        if (vk->CreateSwapchainKHR(g_enc.device, &sci, NULL, &g_enc.swapchain) != VK_SUCCESS) {
            droiddeck_log("tv", "vk-encode: vkCreateSwapchainKHR failed");
            teardown();
            return -1;
        }
        vk->GetSwapchainImagesKHR(g_enc.device, g_enc.swapchain, &g_enc.swap_n, NULL);
        g_enc.swap_images = malloc(sizeof(VkImage) * g_enc.swap_n);
        if (!g_enc.swap_images) {
            teardown();
            return -1;
        }
        vk->GetSwapchainImagesKHR(g_enc.device, g_enc.swapchain, &g_enc.swap_n, g_enc.swap_images);
    }
    {
        VkCommandPoolCreateInfo pci = {.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                       .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                                       .queueFamilyIndex = queue_family};
        if (vk->CreateCommandPool(g_enc.device, &pci, NULL, &g_enc.cmd_pool) != VK_SUCCESS) {
            droiddeck_log("tv", "vk-encode: vkCreateCommandPool failed");
            teardown();
            return -1;
        }
        VkCommandBufferAllocateInfo cai = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                           .commandPool = g_enc.cmd_pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                                           .commandBufferCount = 1};
        if (vk->AllocateCommandBuffers(g_enc.device, &cai, &g_enc.cmd) != VK_SUCCESS) {
            droiddeck_log("tv", "vk-encode: vkAllocateCommandBuffers failed");
            teardown();
            return -1;
        }
        VkFenceCreateInfo fci = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        if (vk->CreateFence(g_enc.device, &fci, NULL, &g_enc.fence) != VK_SUCCESS) {
            droiddeck_log("tv", "vk-encode: vkCreateFence failed");
            teardown();
            return -1;
        }
    }
    g_enc.started = 1;
    droiddeck_log("tv", "vk-encode: Vulkan encoder path up on %s (%dx%d, swapchain format %d, %u images)",
               device_name, width, height, (int)g_enc.swap_format, g_enc.swap_n);
    return 0;
}

static int wait_fence_fd(int fd) {
    if (fd < 0) return 0;
    const int64_t deadline = now_ns() + 100000000LL;
    for (;;) {
        struct pollfd p = {.fd = fd, .events = POLLIN};
        int r;
        do { r = poll(&p, 1, 10); } while (r < 0 && errno == EINTR);
        if (r > 0) return 0;
        if (r < 0) return -1;
        if (now_ns() >= deadline) return 1;
    }
}

int vk_encode_present_frame(AHardwareBuffer *ahb, int fence_fd) {
    if (!g_enc.started || !ahb) {
        if (fence_fd >= 0) close(fence_fd);
        return -1;
    }
    if (fence_fd >= 0) {
        int w = wait_fence_fd(fence_fd);
        close(fence_fd);
        if (w != 0) return 0;
    }
    struct vk_enc_fns *vk = &g_enc.vk;
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
        if (!g_fmt_logged) {
            g_fmt_logged = 1;
            droiddeck_log("tv", "vk-encode: skipping a %ux%u frame in AHB format %#x (only RGBA8 is presented)",
                       desc.width, desc.height, desc.format);
        }
        return 0;
    }
    if (desc.width == 0 || desc.height == 0) return 0;

    VkImage imported_img = VK_NULL_HANDLE;
    VkDeviceMemory imported_mem = VK_NULL_HANDLE;
    {
        VkAndroidHardwareBufferPropertiesANDROID props = {
                .sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        if (vk->GetAndroidHardwareBufferPropertiesANDROID(g_enc.device, ahb, &props) != VK_SUCCESS) return 0;
        VkExternalMemoryImageCreateInfo ext_info = {
                .sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO,
                .handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID};
        VkImageCreateInfo img_info = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                                      .pNext = &ext_info,
                                      .imageType = VK_IMAGE_TYPE_2D,
                                      .format = VK_FORMAT_R8G8B8A8_UNORM,
                                      .extent = {desc.width, desc.height, 1},
                                      .mipLevels = 1, .arrayLayers = 1,
                                      .samples = VK_SAMPLE_COUNT_1_BIT,
                                      .tiling = VK_IMAGE_TILING_OPTIMAL,
                                      .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                                      .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
                                      .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
        if (vk->CreateImage(g_enc.device, &img_info, NULL, &imported_img) != VK_SUCCESS) return 0;
        VkMemoryRequirements req;
        vk->GetImageMemoryRequirements(g_enc.device, imported_img, &req);
        VkPhysicalDeviceMemoryProperties mem_props;
        vk->GetPhysicalDeviceMemoryProperties(g_enc.phys, &mem_props);
        int type_index = -1;
        for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
            if ((props.memoryTypeBits & (1u << i)) &&
                (mem_props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
                type_index = (int)i;
                break;
            }
        }
        if (type_index < 0) {
            for (uint32_t i = 0; i < mem_props.memoryTypeCount; i++) {
                if (props.memoryTypeBits & (1u << i)) { type_index = (int)i; break; }
            }
        }
        if (type_index < 0) {
            vk->DestroyImage(g_enc.device, imported_img, NULL);
            return 0;
        }
        VkImportAndroidHardwareBufferInfoANDROID import_info = {
                .sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID,
                .buffer = ahb};
        VkMemoryDedicatedAllocateInfo dedicated = {
                .sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO,
                .pNext = &import_info, .image = imported_img, .buffer = VK_NULL_HANDLE};
        VkMemoryAllocateInfo alloc_info = {.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                           .pNext = &dedicated,
                                           .allocationSize = props.allocationSize,
                                           .memoryTypeIndex = (uint32_t)type_index};
        if (vk->AllocateMemory(g_enc.device, &alloc_info, NULL, &imported_mem) != VK_SUCCESS) {
            vk->DestroyImage(g_enc.device, imported_img, NULL);
            return 0;
        }
        if (vk->BindImageMemory(g_enc.device, imported_img, imported_mem, 0) != VK_SUCCESS) {
            vk->FreeMemory(g_enc.device, imported_mem, NULL);
            vk->DestroyImage(g_enc.device, imported_img, NULL);
            return 0;
        }
    }
    int rc = 0;
    uint32_t img_idx = 0;
    VkResult r = vk->AcquireNextImageKHR(g_enc.device, g_enc.swapchain, 1000000000ULL,
                                         VK_NULL_HANDLE, g_enc.fence, &img_idx);
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
        rc = (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR ||
              r == VK_ERROR_DEVICE_LOST) ? -1 : 0;
        goto out_import;
    }
    vk->WaitForFences(g_enc.device, 1, &g_enc.fence, VK_TRUE, 1000000000ULL);
    vk->ResetFences(g_enc.device, 1, &g_enc.fence);
    {
        vk->ResetCommandBuffer(g_enc.cmd, 0);
        VkCommandBufferBeginInfo bbi = {.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
        vk->BeginCommandBuffer(g_enc.cmd, &bbi);
        VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageMemoryBarrier barriers[2] = {
            {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
             .srcAccessMask = 0, .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
             .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED, .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
             .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
             .image = imported_img, .subresourceRange = range},
            {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
             .srcAccessMask = 0, .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
             .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED, .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
             .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
             .image = g_enc.swap_images[img_idx], .subresourceRange = range}};
        vk->CmdPipelineBarrier(g_enc.cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                               VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 2, barriers);
        VkImageBlit blit;
        memset(&blit, 0, sizeof(blit));
        blit.srcSubresource = (VkImageSubresourceLayers){VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.srcOffsets[0] = (VkOffset3D){0, 0, 0};
        blit.srcOffsets[1] = (VkOffset3D){(int32_t)desc.width, (int32_t)desc.height, 1};
        blit.dstSubresource = (VkImageSubresourceLayers){VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        int dx0, dy0, dx1, dy1;
        vk_fit_rect((int)desc.width, (int)desc.height,
                    (int)g_enc.swap_extent.width, (int)g_enc.swap_extent.height,
                    &dx0, &dy0, &dx1, &dy1);
        /* The bars outside the fitted rect keep the image's previous contents,
         * so clear the image first when the source is not 16:9. */
        if (dx0 > 0 || dy0 > 0) {
            VkClearColorValue black = {.float32 = {0.0f, 0.0f, 0.0f, 1.0f}};
            vk->CmdClearColorImage(g_enc.cmd, g_enc.swap_images[img_idx],
                                  VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &range);
        }
        blit.dstOffsets[0] = (VkOffset3D){dx0, dy0, 0};
        blit.dstOffsets[1] = (VkOffset3D){dx1, dy1, 1};
        vk->CmdBlitImage(g_enc.cmd, imported_img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                         g_enc.swap_images[img_idx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                         1, &blit, g_enc.blit_filter);
        VkImageMemoryBarrier out_barrier = {.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                                            .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = 0,
                                            .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                            .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                                            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                                            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                                            .image = g_enc.swap_images[img_idx],
                                            .subresourceRange = range};
        vk->CmdPipelineBarrier(g_enc.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                               VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &out_barrier);
        vk->EndCommandBuffer(g_enc.cmd);
        VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                           .commandBufferCount = 1, .pCommandBuffers = &g_enc.cmd};
        r = vk->QueueSubmit(g_enc.queue, 1, &si, g_enc.fence);
        if (r != VK_SUCCESS) {
            rc = r == VK_ERROR_DEVICE_LOST ? -1 : 0;
            goto out_import;
        }
        vk->WaitForFences(g_enc.device, 1, &g_enc.fence, VK_TRUE, 1000000000ULL);
        vk->ResetFences(g_enc.device, 1, &g_enc.fence);
        VkPresentInfoKHR pi = {.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                               .swapchainCount = 1, .pSwapchains = &g_enc.swapchain,
                               .pImageIndices = &img_idx};
        r = vk->QueuePresentKHR(g_enc.queue, &pi);
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
            rc = (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR ||
                  r == VK_ERROR_DEVICE_LOST) ? -1 : 0;
            goto out_import;
        }
        rc = 1;
    }
out_import:
    if (imported_img != VK_NULL_HANDLE) vk->DestroyImage(g_enc.device, imported_img, NULL);
    if (imported_mem != VK_NULL_HANDLE) vk->FreeMemory(g_enc.device, imported_mem, NULL);
    return rc;
}
