#include <jni.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <android/log.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <mutex>
#include <vector>

#include "vulkan_gaussian_shaders.h"

namespace {
constexpr const char* kTag = "TianyinVulkan";

struct SceneMetadata {
    int imageWidth = 1;
    int imageHeight = 1;
    float focusDepth = 1.0f;
    float farDepth = 1.0f;
    float backgroundR = 0.015f;
    float backgroundG = 0.018f;
    float backgroundB = 0.028f;
    float sceneCenterX = 0.0f;
    float sceneCenterY = 0.0f;
    float sceneCenterZ = 1.0f;
    float sceneRadius = 1.0f;
    float defaultCameraDistance = 1.0f;
};

struct RenderState {
    float tiltX = 0.0f;
    float tiltY = 0.0f;
    float parallaxStrength = 0.0f;
    float cameraZoom = 1.0f;
    float centerOffsetX = 0.0f;
    float centerOffsetY = 0.0f;
    float focusDepthOffset = 0.25f;
    float splatScale = 1.0f;
    float opacity = 1.0f;
    float alphaFalloff = 1.0f;
};

struct QuadPushConstants {
    float surfaceSize[2];
    float fillScale[2];
    float tilt[2];
    float centerOffset[2];
    float strength;
    float focusDepth;
    float farDepth;
    float sceneCenterX;
    float sceneCenterY;
    float sceneCenterZ;
    float sceneRadius;
    float defaultCameraDistance;
    float tanHalfFov;
    float cameraZoom;
    float focusDepthOffset;
    float pointScale;
    float quadExtent;
    float opacity;
    float alphaFalloff;
};

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
    if (handles.surface != VK_NULL_HANDLE && handles.instance != VK_NULL_HANDLE) {
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
        __android_log_print(ANDROID_LOG_WARN, kTag, "vkEnumeratePhysicalDevices failed result=%d count=%u", result, deviceCount);
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

    const char* deviceExtensions[] = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };

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

class VulkanClearRenderer {
public:
    ~VulkanClearRenderer() {
        stop();
    }

    bool start(JNIEnv* env, jobject surfaceObject) {
        std::lock_guard<std::mutex> lock(mutex_);
        stopLocked();
        window_ = ANativeWindow_fromSurface(env, surfaceObject);
        if (window_ == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "renderer start: null ANativeWindow");
            return false;
        }
        width_ = std::max(1, ANativeWindow_getWidth(window_));
        height_ = std::max(1, ANativeWindow_getHeight(window_));
        if (!createInstance() || !createSurface() || !choosePhysicalDevice() || !createDevice() ||
            !createCommandPool() || !createSyncObjects() || !createQuadCornerBuffer() || !createSwapchain()) {
            stopLocked();
            return false;
        }
        __android_log_print(ANDROID_LOG_INFO, kTag, "Vulkan renderer started %dx%d images=%zu", width_, height_, swapchainImages_.size());
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        stopLocked();
    }

    bool resize(int width, int height) {
        std::lock_guard<std::mutex> lock(mutex_);
        width_ = std::max(1, width);
        height_ = std::max(1, height);
        if (device_ == VK_NULL_HANDLE || surface_ == VK_NULL_HANDLE) return false;
        return recreateSwapchainLocked();
    }

    bool render(float r, float g, float b) {
        std::lock_guard<std::mutex> lock(mutex_);
        return renderLocked(r, g, b, false);
    }

    bool renderScene(int drawCount) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (sceneCount_ <= 0 || quadPipeline_ == VK_NULL_HANDLE || quadCornerBuffer_.buffer == VK_NULL_HANDLE ||
            scenePositions_.buffer == VK_NULL_HANDLE || sceneColors_.buffer == VK_NULL_HANDLE ||
            sceneScales_.buffer == VK_NULL_HANDLE || sceneRotations_.buffer == VK_NULL_HANDLE) {
            return false;
        }
        drawCount_ = std::clamp(drawCount, 0, sceneCount_);
        if (drawCount_ <= 0) return false;
        return renderLocked(sceneMetadata_.backgroundR, sceneMetadata_.backgroundG, sceneMetadata_.backgroundB, true);
    }

    void updateRenderState(RenderState state) {
        std::lock_guard<std::mutex> lock(mutex_);
        renderState_ = state;
    }

private:
    bool renderLocked(float r, float g, float b, bool drawScene) {
        if (device_ == VK_NULL_HANDLE || swapchain_ == VK_NULL_HANDLE || framebuffers_.empty()) return false;

        vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, UINT64_MAX);
        vkResetFences(device_, 1, &inFlightFence_);

        uint32_t imageIndex = 0;
        VkResult result = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX, imageAvailable_, VK_NULL_HANDLE, &imageIndex);
        if (result == VK_ERROR_OUT_OF_DATE_KHR) {
            return recreateSwapchainLocked();
        }
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkAcquireNextImageKHR failed result=%d", result);
            return false;
        }

        VkCommandBuffer commandBuffer = commandBuffers_[imageIndex];
        vkResetCommandBuffer(commandBuffer, 0);

        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkBeginCommandBuffer failed result=%d", result);
            return false;
        }

        VkClearValue clearValue{};
        clearValue.color.float32[0] = std::clamp(r, 0.0f, 1.0f);
        clearValue.color.float32[1] = std::clamp(g, 0.0f, 1.0f);
        clearValue.color.float32[2] = std::clamp(b, 0.0f, 1.0f);
        clearValue.color.float32[3] = 1.0f;

        VkRenderPassBeginInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderPassInfo.renderPass = renderPass_;
        renderPassInfo.framebuffer = framebuffers_[imageIndex];
        renderPassInfo.renderArea.offset = {0, 0};
        renderPassInfo.renderArea.extent = swapchainExtent_;
        renderPassInfo.clearValueCount = 1;
        renderPassInfo.pClearValues = &clearValue;
        vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
        if (drawScene) {
            recordQuadScene(commandBuffer);
        }
        vkCmdEndRenderPass(commandBuffer);

        result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkEndCommandBuffer failed result=%d", result);
            return false;
        }

        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.waitSemaphoreCount = 1;
        submitInfo.pWaitSemaphores = &imageAvailable_;
        submitInfo.pWaitDstStageMask = &waitStage;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commandBuffer;
        submitInfo.signalSemaphoreCount = 1;
        submitInfo.pSignalSemaphores = &renderFinished_;
        result = vkQueueSubmit(queue_, 1, &submitInfo, inFlightFence_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkQueueSubmit failed result=%d", result);
            return false;
        }

        VkPresentInfoKHR presentInfo{};
        presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &renderFinished_;
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &swapchain_;
        presentInfo.pImageIndices = &imageIndex;
        result = vkQueuePresentKHR(queue_, &presentInfo);
        if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
            return recreateSwapchainLocked();
        }
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkQueuePresentKHR failed result=%d", result);
            return false;
        }
        return true;
    }

public:
    bool uploadScene(
            JNIEnv* env,
            jobject positions,
            jobject colors,
            jobject scales,
            jobject rotations,
            int count,
            SceneMetadata metadata) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (device_ == VK_NULL_HANDLE || physicalDevice_ == VK_NULL_HANDLE || count <= 0) return false;
        if (rotations == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "Vulkan quad renderer requires rotation buffer");
            return false;
        }
        destroySceneBuffersLocked();
        const VkBufferUsageFlags usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        const bool ok =
                uploadFloatBuffer(env, positions, static_cast<VkDeviceSize>(count) * 3u * sizeof(float), usage, scenePositions_) &&
                uploadFloatBuffer(env, colors, static_cast<VkDeviceSize>(count) * 4u * sizeof(float), usage, sceneColors_) &&
                uploadFloatBuffer(env, scales, static_cast<VkDeviceSize>(count) * 3u * sizeof(float), usage, sceneScales_) &&
                uploadFloatBuffer(env, rotations, static_cast<VkDeviceSize>(count) * 4u * sizeof(float), usage, sceneRotations_);
        if (!ok) {
            destroySceneBuffersLocked();
            return false;
        }
        sceneCount_ = count;
        sceneMetadata_ = metadata;
        __android_log_print(ANDROID_LOG_INFO, kTag, "Vulkan scene uploaded count=%d", sceneCount_);
        return true;
    }

private:
    bool createInstance() {
        VkApplicationInfo appInfo{};
        appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        appInfo.pApplicationName = "TianYinWallpaper";
        appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.pEngineName = "TianYinGaussian";
        appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
        appInfo.apiVersion = VK_API_VERSION_1_0;

        const char* extensions[] = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME };
        VkInstanceCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        createInfo.pApplicationInfo = &appInfo;
        createInfo.enabledExtensionCount = 2;
        createInfo.ppEnabledExtensionNames = extensions;
        VkResult result = vkCreateInstance(&createInfo, nullptr, &instance_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "renderer vkCreateInstance failed result=%d", result);
            return false;
        }
        return true;
    }

    bool createSurface() {
        VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
        surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        surfaceInfo.window = window_;
        VkResult result = vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "renderer vkCreateAndroidSurfaceKHR failed result=%d", result);
            return false;
        }
        return true;
    }

    bool choosePhysicalDevice() {
        VulkanProbeHandles handles{};
        handles.instance = instance_;
        handles.surface = surface_;
        bool ok = ::choosePhysicalDevice(handles, true, &queueFamily_);
        physicalDevice_ = handles.physicalDevice;
        return ok;
    }

    bool createDevice() {
        VulkanProbeHandles handles{};
        handles.physicalDevice = physicalDevice_;
        if (!::createDevice(handles, queueFamily_)) return false;
        device_ = handles.device;
        vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
        return true;
    }

    bool createCommandPool() {
        VkCommandPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = queueFamily_;
        VkResult result = vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateCommandPool failed result=%d", result);
            return false;
        }
        return true;
    }

    bool createSyncObjects() {
        VkSemaphoreCreateInfo semaphoreInfo{};
        semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &imageAvailable_) != VK_SUCCESS) return false;
        if (vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &renderFinished_) != VK_SUCCESS) return false;
        if (vkCreateFence(device_, &fenceInfo, nullptr, &inFlightFence_) != VK_SUCCESS) return false;
        return true;
    }

    bool createSwapchain() {
        VkSurfaceCapabilitiesKHR caps{};
        VkResult result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice_, surface_, &caps);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed result=%d", result);
            return false;
        }

        uint32_t formatCount = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, nullptr);
        if (formatCount == 0) return false;
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice_, surface_, &formatCount, formats.data());
        surfaceFormat_ = formats[0];
        for (const auto& format : formats) {
            if (format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM) {
                surfaceFormat_ = format;
                break;
            }
        }

        if (caps.currentExtent.width != UINT32_MAX) {
            swapchainExtent_ = caps.currentExtent;
        } else {
            swapchainExtent_.width = std::clamp(static_cast<uint32_t>(width_), caps.minImageExtent.width, caps.maxImageExtent.width);
            swapchainExtent_.height = std::clamp(static_cast<uint32_t>(height_), caps.minImageExtent.height, caps.maxImageExtent.height);
        }

        uint32_t imageCount = caps.minImageCount + 1;
        if (caps.maxImageCount > 0) {
            imageCount = std::min(imageCount, caps.maxImageCount);
        }

        VkSwapchainCreateInfoKHR swapchainInfo{};
        swapchainInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        swapchainInfo.surface = surface_;
        swapchainInfo.minImageCount = imageCount;
        swapchainInfo.imageFormat = surfaceFormat_.format;
        swapchainInfo.imageColorSpace = surfaceFormat_.colorSpace;
        swapchainInfo.imageExtent = swapchainExtent_;
        swapchainInfo.imageArrayLayers = 1;
        swapchainInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        swapchainInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        swapchainInfo.preTransform = caps.currentTransform;
        swapchainInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        swapchainInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        swapchainInfo.clipped = VK_TRUE;
        result = vkCreateSwapchainKHR(device_, &swapchainInfo, nullptr, &swapchain_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateSwapchainKHR failed result=%d", result);
            return false;
        }

        uint32_t actualImageCount = 0;
        vkGetSwapchainImagesKHR(device_, swapchain_, &actualImageCount, nullptr);
        swapchainImages_.resize(actualImageCount);
        vkGetSwapchainImagesKHR(device_, swapchain_, &actualImageCount, swapchainImages_.data());
        return createRenderPass() && createQuadPipeline() && createImageViews() && createFramebuffers() && createCommandBuffers();
    }

    bool createRenderPass() {
        VkAttachmentDescription colorAttachment{};
        colorAttachment.format = surfaceFormat_.format;
        colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
        colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        colorAttachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference colorAttachmentRef{};
        colorAttachmentRef.attachment = 0;
        colorAttachmentRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorAttachmentRef;

        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo renderPassInfo{};
        renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        renderPassInfo.attachmentCount = 1;
        renderPassInfo.pAttachments = &colorAttachment;
        renderPassInfo.subpassCount = 1;
        renderPassInfo.pSubpasses = &subpass;
        renderPassInfo.dependencyCount = 1;
        renderPassInfo.pDependencies = &dependency;
        VkResult result = vkCreateRenderPass(device_, &renderPassInfo, nullptr, &renderPass_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateRenderPass failed result=%d", result);
            return false;
        }
        return true;
    }

    VkShaderModule createShaderModule(const uint32_t* code, size_t size) {
        VkShaderModuleCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        createInfo.codeSize = size;
        createInfo.pCode = code;
        VkShaderModule shaderModule = VK_NULL_HANDLE;
        VkResult result = vkCreateShaderModule(device_, &createInfo, nullptr, &shaderModule);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateShaderModule failed result=%d", result);
            return VK_NULL_HANDLE;
        }
        return shaderModule;
    }

    bool createQuadPipeline() {
        VkShaderModule vertModule = createShaderModule(
                tianyin_vulkan_shaders::kGaussianQuadVert,
                tianyin_vulkan_shaders::kGaussianQuadVertSize);
        VkShaderModule fragModule = createShaderModule(
                tianyin_vulkan_shaders::kGaussianQuadFrag,
                tianyin_vulkan_shaders::kGaussianQuadFragSize);
        if (vertModule == VK_NULL_HANDLE || fragModule == VK_NULL_HANDLE) {
            if (vertModule != VK_NULL_HANDLE) vkDestroyShaderModule(device_, vertModule, nullptr);
            if (fragModule != VK_NULL_HANDLE) vkDestroyShaderModule(device_, fragModule, nullptr);
            return false;
        }

        VkPipelineShaderStageCreateInfo shaderStages[2]{};
        shaderStages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        shaderStages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        shaderStages[0].module = vertModule;
        shaderStages[0].pName = "main";
        shaderStages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        shaderStages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        shaderStages[1].module = fragModule;
        shaderStages[1].pName = "main";

        std::array<VkVertexInputBindingDescription, 5> bindings{};
        bindings[0].binding = 0;
        bindings[0].stride = sizeof(float) * 2;
        bindings[0].inputRate = VK_VERTEX_INPUT_RATE_VERTEX;
        bindings[1].binding = 1;
        bindings[1].stride = sizeof(float) * 3;
        bindings[1].inputRate = VK_VERTEX_INPUT_RATE_INSTANCE;
        bindings[2].binding = 2;
        bindings[2].stride = sizeof(float) * 4;
        bindings[2].inputRate = VK_VERTEX_INPUT_RATE_INSTANCE;
        bindings[3].binding = 3;
        bindings[3].stride = sizeof(float) * 3;
        bindings[3].inputRate = VK_VERTEX_INPUT_RATE_INSTANCE;
        bindings[4].binding = 4;
        bindings[4].stride = sizeof(float) * 4;
        bindings[4].inputRate = VK_VERTEX_INPUT_RATE_INSTANCE;

        std::array<VkVertexInputAttributeDescription, 5> attributes{};
        attributes[0].binding = 0;
        attributes[0].location = 0;
        attributes[0].format = VK_FORMAT_R32G32_SFLOAT;
        attributes[0].offset = 0;
        attributes[1].binding = 1;
        attributes[1].location = 1;
        attributes[1].format = VK_FORMAT_R32G32B32_SFLOAT;
        attributes[1].offset = 0;
        attributes[2].binding = 2;
        attributes[2].location = 2;
        attributes[2].format = VK_FORMAT_R32G32B32A32_SFLOAT;
        attributes[2].offset = 0;
        attributes[3].binding = 3;
        attributes[3].location = 3;
        attributes[3].format = VK_FORMAT_R32G32B32_SFLOAT;
        attributes[3].offset = 0;
        attributes[4].binding = 4;
        attributes[4].location = 4;
        attributes[4].format = VK_FORMAT_R32G32B32A32_SFLOAT;
        attributes[4].offset = 0;

        VkPipelineVertexInputStateCreateInfo vertexInput{};
        vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        vertexInput.vertexBindingDescriptionCount = static_cast<uint32_t>(bindings.size());
        vertexInput.pVertexBindingDescriptions = bindings.data();
        vertexInput.vertexAttributeDescriptionCount = static_cast<uint32_t>(attributes.size());
        vertexInput.pVertexAttributeDescriptions = attributes.data();

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        inputAssembly.primitiveRestartEnable = VK_FALSE;

        VkPipelineViewportStateCreateInfo viewportState{};
        viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewportState.viewportCount = 1;
        viewportState.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo rasterizer{};
        rasterizer.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterizer.depthClampEnable = VK_FALSE;
        rasterizer.rasterizerDiscardEnable = VK_FALSE;
        rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
        rasterizer.lineWidth = 1.0f;
        rasterizer.cullMode = VK_CULL_MODE_NONE;
        rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterizer.depthBiasEnable = VK_FALSE;

        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.sampleShadingEnable = VK_FALSE;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState colorBlendAttachment{};
        colorBlendAttachment.colorWriteMask =
                VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        colorBlendAttachment.blendEnable = VK_TRUE;
        colorBlendAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_ONE;
        colorBlendAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        colorBlendAttachment.colorBlendOp = VK_BLEND_OP_ADD;
        colorBlendAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        colorBlendAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        colorBlendAttachment.alphaBlendOp = VK_BLEND_OP_ADD;

        VkPipelineColorBlendStateCreateInfo colorBlending{};
        colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        colorBlending.logicOpEnable = VK_FALSE;
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &colorBlendAttachment;

        VkDynamicState dynamicStates[] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
        VkPipelineDynamicStateCreateInfo dynamicState{};
        dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamicState.dynamicStateCount = 2;
        dynamicState.pDynamicStates = dynamicStates;

        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        pushRange.offset = 0;
        pushRange.size = sizeof(QuadPushConstants);

        VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
        pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        pipelineLayoutInfo.pushConstantRangeCount = 1;
        pipelineLayoutInfo.pPushConstantRanges = &pushRange;
        VkResult result = vkCreatePipelineLayout(device_, &pipelineLayoutInfo, nullptr, &quadPipelineLayout_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreatePipelineLayout failed result=%d", result);
            vkDestroyShaderModule(device_, vertModule, nullptr);
            vkDestroyShaderModule(device_, fragModule, nullptr);
            return false;
        }

        VkGraphicsPipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipelineInfo.stageCount = 2;
        pipelineInfo.pStages = shaderStages;
        pipelineInfo.pVertexInputState = &vertexInput;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterizer;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.pDynamicState = &dynamicState;
        pipelineInfo.layout = quadPipelineLayout_;
        pipelineInfo.renderPass = renderPass_;
        pipelineInfo.subpass = 0;
        result = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &quadPipeline_);
        vkDestroyShaderModule(device_, vertModule, nullptr);
        vkDestroyShaderModule(device_, fragModule, nullptr);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateGraphicsPipelines failed result=%d", result);
            return false;
        }
        return true;
    }

    bool createImageViews() {
        imageViews_.resize(swapchainImages_.size(), VK_NULL_HANDLE);
        for (size_t i = 0; i < swapchainImages_.size(); ++i) {
            VkImageViewCreateInfo viewInfo{};
            viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            viewInfo.image = swapchainImages_[i];
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = surfaceFormat_.format;
            viewInfo.components = { VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY };
            viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            viewInfo.subresourceRange.baseMipLevel = 0;
            viewInfo.subresourceRange.levelCount = 1;
            viewInfo.subresourceRange.baseArrayLayer = 0;
            viewInfo.subresourceRange.layerCount = 1;
            VkResult result = vkCreateImageView(device_, &viewInfo, nullptr, &imageViews_[i]);
            if (result != VK_SUCCESS) {
                __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateImageView failed result=%d", result);
                return false;
            }
        }
        return true;
    }

    bool createFramebuffers() {
        framebuffers_.resize(imageViews_.size(), VK_NULL_HANDLE);
        for (size_t i = 0; i < imageViews_.size(); ++i) {
            VkImageView attachments[] = { imageViews_[i] };
            VkFramebufferCreateInfo framebufferInfo{};
            framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            framebufferInfo.renderPass = renderPass_;
            framebufferInfo.attachmentCount = 1;
            framebufferInfo.pAttachments = attachments;
            framebufferInfo.width = swapchainExtent_.width;
            framebufferInfo.height = swapchainExtent_.height;
            framebufferInfo.layers = 1;
            VkResult result = vkCreateFramebuffer(device_, &framebufferInfo, nullptr, &framebuffers_[i]);
            if (result != VK_SUCCESS) {
                __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateFramebuffer failed result=%d", result);
                return false;
            }
        }
        return true;
    }

    bool createCommandBuffers() {
        commandBuffers_.resize(framebuffers_.size(), VK_NULL_HANDLE);
        VkCommandBufferAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocInfo.commandPool = commandPool_;
        allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocInfo.commandBufferCount = static_cast<uint32_t>(commandBuffers_.size());
        VkResult result = vkAllocateCommandBuffers(device_, &allocInfo, commandBuffers_.data());
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkAllocateCommandBuffers failed result=%d", result);
            return false;
        }
        return true;
    }

    void recordQuadScene(VkCommandBuffer commandBuffer) {
        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(swapchainExtent_.width);
        viewport.height = static_cast<float>(swapchainExtent_.height);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);

        VkRect2D scissor{};
        scissor.offset = {0, 0};
        scissor.extent = swapchainExtent_;
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);

        const float imageAspect = static_cast<float>(sceneMetadata_.imageWidth) /
                static_cast<float>(std::max(1, sceneMetadata_.imageHeight));
        const float surfaceAspect = static_cast<float>(std::max(1u, swapchainExtent_.width)) /
                static_cast<float>(std::max(1u, swapchainExtent_.height));
        float fillX = 1.0f;
        float fillY = 1.0f;
        if (imageAspect > surfaceAspect) {
            fillX = imageAspect / surfaceAspect;
        } else {
            fillY = surfaceAspect / imageAspect;
        }
        const float textureScale = std::max(
                static_cast<float>(swapchainExtent_.width) / static_cast<float>(std::max(1, sceneMetadata_.imageWidth)),
                static_cast<float>(swapchainExtent_.height) / static_cast<float>(std::max(1, sceneMetadata_.imageHeight)));

        QuadPushConstants push{};
        push.surfaceSize[0] = static_cast<float>(std::max(1u, swapchainExtent_.width));
        push.surfaceSize[1] = static_cast<float>(std::max(1u, swapchainExtent_.height));
        push.fillScale[0] = fillX;
        push.fillScale[1] = fillY;
        push.tilt[0] = renderState_.tiltX;
        push.tilt[1] = renderState_.tiltY;
        push.centerOffset[0] = renderState_.centerOffsetX;
        push.centerOffset[1] = renderState_.centerOffsetY;
        push.strength = renderState_.parallaxStrength;
        push.focusDepth = sceneMetadata_.focusDepth + renderState_.focusDepthOffset;
        push.farDepth = sceneMetadata_.farDepth;
        push.sceneCenterX = sceneMetadata_.sceneCenterX;
        push.sceneCenterY = sceneMetadata_.sceneCenterY;
        push.sceneCenterZ = sceneMetadata_.sceneCenterZ;
        push.sceneRadius = std::max(0.001f, sceneMetadata_.sceneRadius);
        push.defaultCameraDistance = sceneMetadata_.defaultCameraDistance;
        push.tanHalfFov = 0.57735026f;
        push.cameraZoom = std::max(0.001f, renderState_.cameraZoom);
        push.focusDepthOffset = renderState_.focusDepthOffset;
        push.pointScale = std::clamp(textureScale * renderState_.splatScale, 0.35f, 30.0f);
        push.quadExtent = 1.0f;
        push.opacity = renderState_.opacity;
        push.alphaFalloff = renderState_.alphaFalloff;

        VkBuffer vertexBuffers[] = {
                quadCornerBuffer_.buffer,
                scenePositions_.buffer,
                sceneColors_.buffer,
                sceneScales_.buffer,
                sceneRotations_.buffer
        };
        VkDeviceSize offsets[] = {0, 0, 0, 0, 0};
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, quadPipeline_);
        vkCmdBindVertexBuffers(commandBuffer, 0, 5, vertexBuffers, offsets);
        vkCmdPushConstants(
                commandBuffer,
                quadPipelineLayout_,
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                0,
                sizeof(QuadPushConstants),
                &push);
        vkCmdDraw(commandBuffer, 6, static_cast<uint32_t>(drawCount_), 0, 0);
    }

    struct BufferResource {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize size = 0;
    };

    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
        VkPhysicalDeviceMemoryProperties memProperties{};
        vkGetPhysicalDeviceMemoryProperties(physicalDevice_, &memProperties);
        for (uint32_t i = 0; i < memProperties.memoryTypeCount; ++i) {
            if ((typeFilter & (1u << i)) != 0 &&
                (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
                return i;
            }
        }
        return UINT32_MAX;
    }

    bool uploadFloatBuffer(
            JNIEnv* env,
            jobject source,
            VkDeviceSize size,
            VkBufferUsageFlags usage,
            BufferResource& out) {
        if (source == nullptr || size == 0) return false;
        void* sourcePtr = env->GetDirectBufferAddress(source);
        if (sourcePtr == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "GetDirectBufferAddress failed for scene buffer");
            return false;
        }

        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = size;
        bufferInfo.usage = usage;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkResult result = vkCreateBuffer(device_, &bufferInfo, nullptr, &out.buffer);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateBuffer failed result=%d size=%llu", result, static_cast<unsigned long long>(size));
            return false;
        }

        VkMemoryRequirements memRequirements{};
        vkGetBufferMemoryRequirements(device_, out.buffer, &memRequirements);
        const uint32_t memoryType = findMemoryType(
                memRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memoryType == UINT32_MAX) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "no host-visible memory type for scene buffer");
            return false;
        }

        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memRequirements.size;
        allocInfo.memoryTypeIndex = memoryType;
        result = vkAllocateMemory(device_, &allocInfo, nullptr, &out.memory);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkAllocateMemory failed result=%d size=%llu", result, static_cast<unsigned long long>(memRequirements.size));
            return false;
        }
        result = vkBindBufferMemory(device_, out.buffer, out.memory, 0);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkBindBufferMemory failed result=%d", result);
            return false;
        }

        void* mapped = nullptr;
        result = vkMapMemory(device_, out.memory, 0, size, 0, &mapped);
        if (result != VK_SUCCESS || mapped == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkMapMemory failed result=%d", result);
            return false;
        }
        std::memcpy(mapped, sourcePtr, static_cast<size_t>(size));
        vkUnmapMemory(device_, out.memory);
        out.size = size;
        return true;
    }

    bool uploadHostBuffer(
            const void* sourcePtr,
            VkDeviceSize size,
            VkBufferUsageFlags usage,
            BufferResource& out) {
        if (sourcePtr == nullptr || size == 0) return false;
        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = size;
        bufferInfo.usage = usage;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkResult result = vkCreateBuffer(device_, &bufferInfo, nullptr, &out.buffer);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateBuffer failed result=%d size=%llu", result, static_cast<unsigned long long>(size));
            return false;
        }
        VkMemoryRequirements memRequirements{};
        vkGetBufferMemoryRequirements(device_, out.buffer, &memRequirements);
        const uint32_t memoryType = findMemoryType(
                memRequirements.memoryTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memoryType == UINT32_MAX) return false;

        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memRequirements.size;
        allocInfo.memoryTypeIndex = memoryType;
        result = vkAllocateMemory(device_, &allocInfo, nullptr, &out.memory);
        if (result != VK_SUCCESS) return false;
        result = vkBindBufferMemory(device_, out.buffer, out.memory, 0);
        if (result != VK_SUCCESS) return false;
        void* mapped = nullptr;
        result = vkMapMemory(device_, out.memory, 0, size, 0, &mapped);
        if (result != VK_SUCCESS || mapped == nullptr) return false;
        std::memcpy(mapped, sourcePtr, static_cast<size_t>(size));
        vkUnmapMemory(device_, out.memory);
        out.size = size;
        return true;
    }

    bool createQuadCornerBuffer() {
        static constexpr float kCorners[] = {
                -1.0f, -1.0f,
                 1.0f, -1.0f,
                -1.0f,  1.0f,
                -1.0f,  1.0f,
                 1.0f, -1.0f,
                 1.0f,  1.0f
        };
        destroyBufferLocked(quadCornerBuffer_);
        return uploadHostBuffer(
                kCorners,
                sizeof(kCorners),
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                quadCornerBuffer_);
    }

    bool recreateSwapchainLocked() {
        if (device_ == VK_NULL_HANDLE) return false;
        vkDeviceWaitIdle(device_);
        destroySwapchainLocked();
        return createSwapchain();
    }

    void destroySwapchainLocked() {
        if (device_ == VK_NULL_HANDLE) return;
        if (!commandBuffers_.empty() && commandPool_ != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(device_, commandPool_, static_cast<uint32_t>(commandBuffers_.size()), commandBuffers_.data());
            commandBuffers_.clear();
        }
        for (VkFramebuffer framebuffer : framebuffers_) {
            if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device_, framebuffer, nullptr);
        }
        framebuffers_.clear();
        for (VkImageView imageView : imageViews_) {
            if (imageView != VK_NULL_HANDLE) vkDestroyImageView(device_, imageView, nullptr);
        }
        imageViews_.clear();
        if (quadPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, quadPipeline_, nullptr);
            quadPipeline_ = VK_NULL_HANDLE;
        }
        if (quadPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, quadPipelineLayout_, nullptr);
            quadPipelineLayout_ = VK_NULL_HANDLE;
        }
        if (renderPass_ != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device_, renderPass_, nullptr);
            renderPass_ = VK_NULL_HANDLE;
        }
        if (swapchain_ != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device_, swapchain_, nullptr);
            swapchain_ = VK_NULL_HANDLE;
        }
        swapchainImages_.clear();
    }

    void destroyBufferLocked(BufferResource& resource) {
        if (device_ == VK_NULL_HANDLE) return;
        if (resource.buffer != VK_NULL_HANDLE) vkDestroyBuffer(device_, resource.buffer, nullptr);
        if (resource.memory != VK_NULL_HANDLE) vkFreeMemory(device_, resource.memory, nullptr);
        resource = BufferResource{};
    }

    void destroySceneBuffersLocked() {
        destroyBufferLocked(scenePositions_);
        destroyBufferLocked(sceneColors_);
        destroyBufferLocked(sceneScales_);
        destroyBufferLocked(sceneRotations_);
        sceneCount_ = 0;
    }

    void stopLocked() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        destroySceneBuffersLocked();
        destroyBufferLocked(quadCornerBuffer_);
        destroySwapchainLocked();
        if (imageAvailable_ != VK_NULL_HANDLE) vkDestroySemaphore(device_, imageAvailable_, nullptr);
        if (renderFinished_ != VK_NULL_HANDLE) vkDestroySemaphore(device_, renderFinished_, nullptr);
        if (inFlightFence_ != VK_NULL_HANDLE) vkDestroyFence(device_, inFlightFence_, nullptr);
        imageAvailable_ = VK_NULL_HANDLE;
        renderFinished_ = VK_NULL_HANDLE;
        inFlightFence_ = VK_NULL_HANDLE;
        if (commandPool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, commandPool_, nullptr);
        commandPool_ = VK_NULL_HANDLE;
        if (device_ != VK_NULL_HANDLE) vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
        queue_ = VK_NULL_HANDLE;
        physicalDevice_ = VK_NULL_HANDLE;
        if (surface_ != VK_NULL_HANDLE && instance_ != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance_, surface_, nullptr);
        surface_ = VK_NULL_HANDLE;
        if (instance_ != VK_NULL_HANDLE) vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
    int width_ = 1;
    int height_ = 1;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkSurfaceFormatKHR surfaceFormat_{};
    VkExtent2D swapchainExtent_{};
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkSemaphore imageAvailable_ = VK_NULL_HANDLE;
    VkSemaphore renderFinished_ = VK_NULL_HANDLE;
    VkFence inFlightFence_ = VK_NULL_HANDLE;
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> imageViews_;
    std::vector<VkFramebuffer> framebuffers_;
    std::vector<VkCommandBuffer> commandBuffers_;
    VkPipelineLayout quadPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline quadPipeline_ = VK_NULL_HANDLE;
    BufferResource quadCornerBuffer_;
    BufferResource scenePositions_;
    BufferResource sceneColors_;
    BufferResource sceneScales_;
    BufferResource sceneRotations_;
    SceneMetadata sceneMetadata_;
    RenderState renderState_;
    int sceneCount_ = 0;
    int drawCount_ = 0;
};

VulkanClearRenderer* fromHandle(jlong handle) {
    return reinterpret_cast<VulkanClearRenderer*>(handle);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_00024Companion_nativeIsVulkanAvailable(
        JNIEnv*, jobject) {
    VulkanProbeHandles handles{};
    if (!createInstance(handles, false)) return JNI_FALSE;
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
        const VkResult surfaceResult = vkCreateAndroidSurfaceKHR(handles.instance, &surfaceInfo, nullptr, &handles.surface);
        if (surfaceResult != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateAndroidSurfaceKHR failed result=%d", surfaceResult);
            ok = false;
        }
    }
    uint32_t queueFamily = 0;
    if (ok) ok = choosePhysicalDevice(handles, true, &queueFamily);
    if (ok) ok = createDevice(handles, queueFamily);

    destroyProbe(handles);
    ANativeWindow_release(window);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_nativeCreateRenderer(
        JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new VulkanClearRenderer());
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_nativeDestroyRenderer(
        JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_nativeStartRenderer(
        JNIEnv* env, jobject, jlong handle, jobject surface) {
    auto* renderer = fromHandle(handle);
    return renderer != nullptr && renderer->start(env, surface) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_nativeStopRenderer(
        JNIEnv*, jobject, jlong handle) {
    auto* renderer = fromHandle(handle);
    if (renderer != nullptr) renderer->stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_nativeResizeRenderer(
        JNIEnv*, jobject, jlong handle, jint width, jint height) {
    auto* renderer = fromHandle(handle);
    return renderer != nullptr && renderer->resize(width, height) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_nativeRenderClear(
        JNIEnv*, jobject, jlong handle, jfloat r, jfloat g, jfloat b) {
    auto* renderer = fromHandle(handle);
    return renderer != nullptr && renderer->render(r, g, b) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_nativeRenderScene(
        JNIEnv*, jobject, jlong handle, jint drawCount) {
    auto* renderer = fromHandle(handle);
    return renderer != nullptr && renderer->renderScene(drawCount) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_nativeUpdateRenderState(
        JNIEnv*,
        jobject,
        jlong handle,
        jfloat tiltX,
        jfloat tiltY,
        jfloat parallaxStrength,
        jfloat cameraZoom,
        jfloat centerOffsetX,
        jfloat centerOffsetY,
        jfloat focusDepthOffset,
        jfloat splatScale,
        jfloat opacity,
        jfloat alphaFalloff) {
    auto* renderer = fromHandle(handle);
    if (renderer == nullptr) return;
    RenderState state{};
    state.tiltX = tiltX;
    state.tiltY = tiltY;
    state.parallaxStrength = parallaxStrength;
    state.cameraZoom = cameraZoom;
    state.centerOffsetX = centerOffsetX;
    state.centerOffsetY = centerOffsetY;
    state.focusDepthOffset = focusDepthOffset;
    state.splatScale = splatScale;
    state.opacity = opacity;
    state.alphaFalloff = alphaFalloff;
    renderer->updateRenderState(state);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_nativeUploadScene(
        JNIEnv* env,
        jobject,
        jlong handle,
        jobject positions,
        jobject colors,
        jobject scales,
        jobject rotations,
        jint count,
        jint imageWidth,
        jint imageHeight,
        jfloat focusDepth,
        jfloat farDepth,
        jfloat backgroundR,
        jfloat backgroundG,
        jfloat backgroundB,
        jfloat sceneCenterX,
        jfloat sceneCenterY,
        jfloat sceneCenterZ,
        jfloat sceneRadius,
        jfloat defaultCameraDistance) {
    auto* renderer = fromHandle(handle);
    SceneMetadata metadata{};
    metadata.imageWidth = std::max(1, imageWidth);
    metadata.imageHeight = std::max(1, imageHeight);
    metadata.focusDepth = focusDepth;
    metadata.farDepth = farDepth;
    metadata.backgroundR = backgroundR;
    metadata.backgroundG = backgroundG;
    metadata.backgroundB = backgroundB;
    metadata.sceneCenterX = sceneCenterX;
    metadata.sceneCenterY = sceneCenterY;
    metadata.sceneCenterZ = sceneCenterZ;
    metadata.sceneRadius = sceneRadius;
    metadata.defaultCameraDistance = defaultCameraDistance;
    return renderer != nullptr &&
           renderer->uploadScene(env, positions, colors, scales, rotations, count, metadata) ? JNI_TRUE : JNI_FALSE;
}
