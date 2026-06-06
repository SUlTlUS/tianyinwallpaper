#include <jni.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <android/log.h>
#include <android/native_window_jni.h>

#include <vector>

namespace {
constexpr const char* kTag = "TianyinVulkan";

struct VulkanProbeHandles {
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
};

void destroyProbe(VulkanProbeHandles& handles) {
    if (handles.device != VK_NULL_HANDLE) {
        vkDestroyDevice(handles.device, nullptr);
        handles.device = VK_NULL_HANDLE;
    }
    if (handles.surface != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(handles.instance, handles.surface, nullptr);
        handles.surface = VK_NULL_HANDLE;
    }
    if (handles.instance != VK_NULL_HANDLE) {
        vkDestroyInstance(handles.instance, nullptr);
        handles.instance = VK_NULL_HANDLE;
    }
}

bool createInstance(VulkanProbeHandles& handles, bool requireSurface) {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "TianYinWallpaper";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "TianYinGaussian";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    const char* surfaceExtensions[] = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
    };

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    if (requireSurface) {
        createInfo.enabledExtensionCount = 2;
        createInfo.ppEnabledExtensionNames = surfaceExtensions;
    }

    const VkResult result = vkCreateInstance(&createInfo, nullptr, &handles.instance);
    if (result != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateInstance failed result=%d", result);
        return false;
    }
    return true;
}

bool choosePhysicalDevice(VulkanProbeHandles& handles, bool requireSurface, uint32_t* queueFamilyOut) {
    uint32_t deviceCount = 0;
    VkResult result = vkEnumeratePhysicalDevices(handles.instance, &deviceCount, nullptr);
    if (result != VK_SUCCESS || deviceCount == 0) {
        __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "vkEnumeratePhysicalDevices failed result=%d count=%u",
                result,
                deviceCount);
        return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    result = vkEnumeratePhysicalDevices(handles.instance, &deviceCount, devices.data());
    if (result != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "vkEnumeratePhysicalDevices list failed result=%d", result);
        return false;
    }

    for (VkPhysicalDevice device : devices) {
        uint32_t queueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueFamilyCount, nullptr);
        if (queueFamilyCount == 0) continue;
        std::vector<VkQueueFamilyProperties> families(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueFamilyCount, families.data());

        for (uint32_t family = 0; family < queueFamilyCount; ++family) {
            if ((families[family].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            if (requireSurface) {
                VkBool32 presentSupported = VK_FALSE;
                result = vkGetPhysicalDeviceSurfaceSupportKHR(device, family, handles.surface, &presentSupported);
                if (result != VK_SUCCESS || presentSupported != VK_TRUE) continue;
            }
            handles.physicalDevice = device;
            *queueFamilyOut = family;
            return true;
        }
    }

    __android_log_print(ANDROID_LOG_WARN, kTag, "no Vulkan graphics/present queue family found");
    return false;
}

bool createDevice(VulkanProbeHandles& handles, uint32_t queueFamily) {
    constexpr float kQueuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &kQueuePriority;

    const char* deviceExtensions[] = {
            VK_KHR_SWAPCHAIN_EXTENSION_NAME
    };

    VkDeviceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    createInfo.queueCreateInfoCount = 1;
    createInfo.pQueueCreateInfos = &queueInfo;
    createInfo.enabledExtensionCount = 1;
    createInfo.ppEnabledExtensionNames = deviceExtensions;

    const VkResult result = vkCreateDevice(handles.physicalDevice, &createInfo, nullptr, &handles.device);
    if (result != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateDevice failed result=%d", result);
        return false;
    }
    return true;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_00024Companion_nativeIsVulkanAvailable(
        JNIEnv*, jobject) {
    VulkanProbeHandles handles{};
    if (!createInstance(handles, false)) {
        return JNI_FALSE;
    }
    uint32_t queueFamily = 0;
    const bool ok = choosePhysicalDevice(handles, false, &queueFamily);
    destroyProbe(handles);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_00024Companion_nativeProbeSurface(
        JNIEnv* env, jobject, jobject surface) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "ANativeWindow_fromSurface returned null");
        return JNI_FALSE;
    }

    VulkanProbeHandles handles{};
    bool ok = createInstance(handles, true);
    if (ok) {
        VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
        surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfaceInfo.window = window;
        const VkResult surfaceResult = vkCreateAndroidSurfaceKHR(
                handles.instance,
                &surfaceInfo,
                nullptr,
                &handles.surface);
        if (surfaceResult != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateAndroidSurfaceKHR failed result=%d", surfaceResult);
            ok = false;
        }
    }
    uint32_t queueFamily = 0;
    if (ok) {
        ok = choosePhysicalDevice(handles, true, &queueFamily);
    }
    if (ok) {
        ok = createDevice(handles, queueFamily);
    }

    destroyProbe(handles);
    ANativeWindow_release(window);
    return ok ? JNI_TRUE : JNI_FALSE;
}
