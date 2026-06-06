#include <jni.h>
#include <vulkan/vulkan.h>

#include <android/log.h>

namespace {
constexpr const char* kTag = "TianyinVulkan";
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_00024Companion_nativeIsVulkanAvailable(
        JNIEnv*, jobject) {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "TianYinWallpaper";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "TianYinGaussian";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    const VkResult result = vkCreateInstance(&createInfo, nullptr, &instance);
    if (result != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateInstance failed result=%d", result);
        return JNI_FALSE;
    }

    uint32_t deviceCount = 0;
    const VkResult enumerateResult = vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    vkDestroyInstance(instance, nullptr);

    if (enumerateResult != VK_SUCCESS || deviceCount == 0) {
        __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "vkEnumeratePhysicalDevices failed result=%d count=%u",
                enumerateResult,
                deviceCount);
        return JNI_FALSE;
    }

    return JNI_TRUE;
}
