#include <jni.h>
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <android/log.h>
#include <android/native_window_jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <mutex>
#include <vector>

#include "vulkan_gaussian_shaders.h"

namespace {
constexpr const char* kTag = "TianyinVulkan";
constexpr float kSogSortEpsilon = 0.04f;
constexpr int kSogSortSubChunkSize = 256;
constexpr uint32_t kSogSortBinCount = 32u;
constexpr bool kSogDynamicOrderSort = false;
constexpr int kSogCompactDrawMaxSplats = 0;

float channel(uint32_t packed, uint32_t index) {
    return static_cast<float>((packed >> (index * 8u)) & 255u);
}

float symmetricUnlog(float value) {
    return std::copysign(std::exp(std::abs(value)) - 1.0f, value);
}

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
    float surfaceSize[4];
    float cameraPosition[4];
    float cameraRight[4];
    float cameraUp[4];
    float cameraForward[4];
    float tanHalfFov;
    float pointScale;
    float quadExtent;
    float opacity;
    float alphaFalloff;
    float minContribution;
    float minPixelSize;
    uint32_t sourceCount;
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
    struct BufferResource {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkDeviceSize size = 0;
    };

    struct ImageResource {
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkImageView view = VK_NULL_HANDLE;
        VkFormat format = VK_FORMAT_UNDEFINED;
        uint32_t width = 0;
        uint32_t height = 0;
    };

    struct PendingImageUpload {
        BufferResource staging;
        ImageResource* image = nullptr;
    };

    struct SogChunkResource {
        ImageResource meansL;
        ImageResource meansU;
        ImageResource scales;
        ImageResource sh0;
        ImageResource quats;
        ImageResource scaleCodebook;
        ImageResource sh0Codebook;
        ImageResource meansMinMax;
        ImageResource orderImage;
        BufferResource orderStaging;
        BufferResource compactIndices;
        BufferResource indirectArgs;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        int count = 0;
        float centerX = 0.0f;
        float centerY = 0.0f;
        float centerZ = 0.0f;
        float radius = 0.0f;
        uint32_t stableIndex = 0;
        bool selectedLastFrame = false;
        bool orderValid = false;
        float lastOrderForwardX = 0.0f;
        float lastOrderForwardY = 0.0f;
        float lastOrderForwardZ = 0.0f;
        std::vector<float> centers;
        std::vector<uint32_t> order;
        std::vector<uint32_t> sortKeys;
        std::vector<uint32_t> sortCounts;
        std::vector<float> sortChunkSpheres;
        std::array<uint32_t, kSogSortBinCount> sortBinCount{};
        std::array<uint32_t, kSogSortBinCount> sortBinBase{};
        std::array<uint32_t, kSogSortBinCount> sortBinDivider{};
    };

    struct SogDrawCommand {
        SogChunkResource* chunk = nullptr;
        int drawCount = 0;
        float sortDepth = 0.0f;
        float importance = 0.0f;
    };

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
            !createCommandPool() || !createSyncObjects() || !createSogSampler() ||
            !createQuadCornerBuffer() || !createSwapchain()) {
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
        if (sceneCount_ <= 0 || quadCornerBuffer_.buffer == VK_NULL_HANDLE) return false;
        if (sogSceneActive_) {
            if (sogPipeline_ == VK_NULL_HANDLE || sogCompactPipeline_ == VK_NULL_HANDLE || sogChunks_.empty()) return false;
            for (const auto& chunk : sogChunks_) {
                if (chunk.descriptorSet == VK_NULL_HANDLE || chunk.count <= 0 ||
                    chunk.meansL.image == VK_NULL_HANDLE || chunk.meansU.image == VK_NULL_HANDLE ||
                    chunk.scales.image == VK_NULL_HANDLE || chunk.sh0.image == VK_NULL_HANDLE ||
                    chunk.quats.image == VK_NULL_HANDLE || chunk.scaleCodebook.image == VK_NULL_HANDLE ||
                    chunk.sh0Codebook.image == VK_NULL_HANDLE || chunk.meansMinMax.image == VK_NULL_HANDLE ||
                    chunk.orderImage.image == VK_NULL_HANDLE || chunk.orderStaging.buffer == VK_NULL_HANDLE ||
                    chunk.compactIndices.buffer == VK_NULL_HANDLE || chunk.indirectArgs.buffer == VK_NULL_HANDLE ||
                    chunk.centers.size() < static_cast<size_t>(chunk.count) * 3u ||
                    chunk.order.size() < static_cast<size_t>(chunk.count)) {
                    return false;
                }
            }
        } else {
            if (quadPipeline_ == VK_NULL_HANDLE || scenePositions_.buffer == VK_NULL_HANDLE ||
                sceneColors_.buffer == VK_NULL_HANDLE || sceneCovariance_.buffer == VK_NULL_HANDLE) {
                return false;
            }
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
        if (device_ == VK_NULL_HANDLE || swapchain_ == VK_NULL_HANDLE || framebuffers_.empty() ||
            inFlightFence_ == VK_NULL_HANDLE) return false;

        vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, UINT64_MAX);

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

        if (drawScene && sogSceneActive_) {
            QuadPushConstants orderPush{};
            orderPush.surfaceSize[0] = static_cast<float>(std::max(1u, swapchainExtent_.width));
            orderPush.surfaceSize[1] = static_cast<float>(std::max(1u, swapchainExtent_.height));
            buildCameraFrame(orderPush);
            orderPush.tanHalfFov = 0.57735026f;
            orderPush.pointScale = std::clamp(renderState_.splatScale, 0.25f, 3.0f);
            orderPush.quadExtent = 1.0f;
            orderPush.opacity = renderState_.opacity;
            orderPush.alphaFalloff = renderState_.alphaFalloff;
            orderPush.minContribution = 1.0f;
            orderPush.minPixelSize = 0.30f;
            selectSogDrawCommands(orderPush);
            updateSogOrderImages(commandBuffer, orderPush);
            if (sogUseCompactDraw_) {
                compactSogVisibleIndices(commandBuffer, orderPush);
            }
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
        result = vkResetFences(device_, 1, &inFlightFence_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkResetFences failed result=%d", result);
            return false;
        }
        result = vkQueueSubmit(queue_, 1, &submitInfo, inFlightFence_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkQueueSubmit failed result=%d", result);
            vkDestroyFence(device_, inFlightFence_, nullptr);
            VkFenceCreateInfo fenceInfo{};
            fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
            fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
            inFlightFence_ = VK_NULL_HANDLE;
            const VkResult fenceResult = vkCreateFence(device_, &fenceInfo, nullptr, &inFlightFence_);
            if (fenceResult != VK_SUCCESS) {
                __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateFence recovery failed result=%d", fenceResult);
            }
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
        const auto* scaleData = static_cast<const float*>(env->GetDirectBufferAddress(scales));
        const auto* rotationData = static_cast<const float*>(env->GetDirectBufferAddress(rotations));
        if (scaleData == nullptr || rotationData == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "GetDirectBufferAddress failed for covariance inputs");
            return false;
        }
        std::vector<float> covariance(static_cast<size_t>(count) * 6u);
        buildCovarianceData(scaleData, rotationData, count, covariance.data());
        destroySceneBuffersLocked();
        const VkBufferUsageFlags usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        const bool ok =
                uploadFloatBuffer(env, positions, static_cast<VkDeviceSize>(count) * 3u * sizeof(float), usage, scenePositions_) &&
                uploadFloatBuffer(env, colors, static_cast<VkDeviceSize>(count) * 4u * sizeof(float), usage, sceneColors_) &&
                uploadFloatData(covariance.data(), covariance.size() * sizeof(float), usage, sceneCovariance_);
        if (!ok) {
            destroySceneBuffersLocked();
            return false;
        }
        sceneCount_ = count;
        sceneMetadata_ = metadata;
        __android_log_print(ANDROID_LOG_INFO, kTag, "Vulkan scene uploaded count=%d", sceneCount_);
        return true;
    }

    bool uploadSogScene(
            JNIEnv* env,
            jobjectArray meansL,
            jobjectArray meansU,
            jobjectArray scales,
            jobjectArray sh0,
            jobjectArray quats,
            jobjectArray scaleCodebook,
            jobjectArray sh0Codebook,
            jobjectArray meansMinMax,
            jintArray counts,
            jfloatArray chunkBounds,
            SceneMetadata metadata) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (device_ == VK_NULL_HANDLE || physicalDevice_ == VK_NULL_HANDLE ||
            meansL == nullptr || meansU == nullptr || scales == nullptr || sh0 == nullptr ||
            quats == nullptr || scaleCodebook == nullptr || sh0Codebook == nullptr ||
            meansMinMax == nullptr || counts == nullptr || chunkBounds == nullptr) {
            return false;
        }
        const jsize chunkCount = env->GetArrayLength(counts);
        if (chunkCount <= 0 ||
            env->GetArrayLength(meansL) != chunkCount || env->GetArrayLength(meansU) != chunkCount ||
            env->GetArrayLength(scales) != chunkCount || env->GetArrayLength(sh0) != chunkCount ||
            env->GetArrayLength(quats) != chunkCount || env->GetArrayLength(scaleCodebook) != chunkCount ||
            env->GetArrayLength(sh0Codebook) != chunkCount || env->GetArrayLength(meansMinMax) != chunkCount ||
            env->GetArrayLength(chunkBounds) != chunkCount * 4) {
            return false;
        }
        std::vector<jint> chunkCounts(static_cast<size_t>(chunkCount));
        std::vector<jfloat> bounds(static_cast<size_t>(chunkCount) * 4u);
        env->GetIntArrayRegion(counts, 0, chunkCount, chunkCounts.data());
        env->GetFloatArrayRegion(chunkBounds, 0, chunkCount * 4, bounds.data());
        if (env->ExceptionCheck()) return false;

        std::vector<SogChunkResource> newChunks(static_cast<size_t>(chunkCount));
        VkDescriptorPool newDescriptorPool = VK_NULL_HANDLE;
        int totalCount = 0;
        bool ok = true;
        for (jsize i = 0; i < chunkCount && ok; ++i) {
            const int count = chunkCounts[static_cast<size_t>(i)];
            if (count <= 0 || totalCount > std::numeric_limits<int>::max() - count) {
                ok = false;
                break;
            }
            auto& chunk = newChunks[static_cast<size_t>(i)];
            chunk.count = count;
            chunk.stableIndex = static_cast<uint32_t>(i);
            chunk.centerX = bounds[static_cast<size_t>(i) * 4u];
            chunk.centerY = bounds[static_cast<size_t>(i) * 4u + 1u];
            chunk.centerZ = bounds[static_cast<size_t>(i) * 4u + 2u];
            chunk.radius = bounds[static_cast<size_t>(i) * 4u + 3u];
            if (!std::isfinite(chunk.centerX) || !std::isfinite(chunk.centerY) ||
                !std::isfinite(chunk.centerZ) || !std::isfinite(chunk.radius) || chunk.radius <= 0.0f) {
                ok = false;
                break;
            }
            const uint32_t imageWidth = std::min<uint32_t>(2048u, static_cast<uint32_t>(count));
            const uint32_t imageHeight = (static_cast<uint32_t>(count) + imageWidth - 1u) / imageWidth;
            jobject meansLBuffer = env->GetObjectArrayElement(meansL, i);
            jobject meansUBuffer = env->GetObjectArrayElement(meansU, i);
            jobject scalesBuffer = env->GetObjectArrayElement(scales, i);
            jobject sh0Buffer = env->GetObjectArrayElement(sh0, i);
            jobject quatsBuffer = env->GetObjectArrayElement(quats, i);
            jobject scaleCodebookBuffer = env->GetObjectArrayElement(scaleCodebook, i);
            jobject sh0CodebookBuffer = env->GetObjectArrayElement(sh0Codebook, i);
            jobject meansMinMaxBuffer = env->GetObjectArrayElement(meansMinMax, i);
            std::vector<PendingImageUpload> imageUploads;
            imageUploads.reserve(9);
            chunk.centers.resize(static_cast<size_t>(count) * 3u);
            chunk.order.resize(static_cast<size_t>(count));
            for (int orderIndex = 0; orderIndex < count; ++orderIndex) {
                chunk.order[static_cast<size_t>(orderIndex)] = static_cast<uint32_t>(orderIndex);
            }
            const VkDeviceSize orderImageSize = static_cast<VkDeviceSize>(imageWidth) * imageHeight * sizeof(uint32_t);
            ok = !env->ExceptionCheck() &&
                    buildSogCenters(env, meansLBuffer, meansUBuffer, meansMinMaxBuffer, count, chunk.centers) &&
                    buildSogSortChunkSpheres(count, chunk.centers, chunk.sortChunkSpheres) &&
                    prepareDirectImageUpload(env, meansLBuffer, count * sizeof(uint32_t), imageWidth, imageHeight, VK_FORMAT_R32_UINT, chunk.meansL, imageUploads) &&
                    prepareDirectImageUpload(env, meansUBuffer, count * sizeof(uint32_t), imageWidth, imageHeight, VK_FORMAT_R32_UINT, chunk.meansU, imageUploads) &&
                    prepareDirectImageUpload(env, scalesBuffer, count * sizeof(uint32_t), imageWidth, imageHeight, VK_FORMAT_R32_UINT, chunk.scales, imageUploads) &&
                    prepareDirectImageUpload(env, sh0Buffer, count * sizeof(uint32_t), imageWidth, imageHeight, VK_FORMAT_R32_UINT, chunk.sh0, imageUploads) &&
                    prepareDirectImageUpload(env, quatsBuffer, count * sizeof(uint32_t), imageWidth, imageHeight, VK_FORMAT_R32_UINT, chunk.quats, imageUploads) &&
                    prepareDirectImageUpload(env, scaleCodebookBuffer, 256u * sizeof(float), 256u, 1u, VK_FORMAT_R32_SFLOAT, chunk.scaleCodebook, imageUploads) &&
                    prepareDirectImageUpload(env, sh0CodebookBuffer, 256u * sizeof(float), 256u, 1u, VK_FORMAT_R32_SFLOAT, chunk.sh0Codebook, imageUploads) &&
                    prepareDirectImageUpload(env, meansMinMaxBuffer, 8u * sizeof(float), 2u, 1u, VK_FORMAT_R32G32B32A32_SFLOAT, chunk.meansMinMax, imageUploads) &&
                    prepareRawImageUpload(chunk.order.data(), count * sizeof(uint32_t), imageWidth, imageHeight, VK_FORMAT_R32_UINT, chunk.orderImage, imageUploads) &&
                    createBufferResource(
                            orderImageSize,
                            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                            chunk.orderStaging,
                            "SOG order staging") &&
                    createBufferResource(
                            static_cast<VkDeviceSize>(count) * sizeof(uint32_t),
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                            chunk.compactIndices,
                            "SOG compact indices") &&
                    createBufferResource(
                            static_cast<VkDeviceSize>(4u) * sizeof(uint32_t),
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                            chunk.indirectArgs,
                            "SOG indirect args") &&
                    submitImageUploadsLocked(imageUploads);
            if (ok) {
                chunk.orderValid = true;
            }
            for (auto& upload : imageUploads) {
                destroyBufferLocked(upload.staging);
            }
            env->DeleteLocalRef(meansLBuffer);
            env->DeleteLocalRef(meansUBuffer);
            env->DeleteLocalRef(scalesBuffer);
            env->DeleteLocalRef(sh0Buffer);
            env->DeleteLocalRef(quatsBuffer);
            env->DeleteLocalRef(scaleCodebookBuffer);
            env->DeleteLocalRef(sh0CodebookBuffer);
            env->DeleteLocalRef(meansMinMaxBuffer);
            totalCount += count;
        }
        if (ok) ok = createSogDescriptorSetsLocked(newChunks, newDescriptorPool);
        if (!ok) {
            destroySogChunksLocked(newChunks, newDescriptorPool);
            return false;
        }
        float aggregateRadius = 0.0f;
        for (const auto& chunk : newChunks) {
            const float dx = chunk.centerX - metadata.sceneCenterX;
            const float dy = chunk.centerY - metadata.sceneCenterY;
            const float dz = chunk.centerZ - metadata.sceneCenterZ;
            aggregateRadius = std::max(
                    aggregateRadius,
                    std::sqrt(dx * dx + dy * dy + dz * dz) + chunk.radius);
        }
        metadata.sceneRadius = std::max(metadata.sceneRadius, aggregateRadius);
        metadata.defaultCameraDistance = std::max(
                metadata.defaultCameraDistance,
                1.8f + metadata.sceneRadius * 0.25f);
        vkDeviceWaitIdle(device_);
        destroySceneBuffersLocked();
        sogChunks_ = std::move(newChunks);
        sogDescriptorPool_ = newDescriptorPool;
        sogSceneActive_ = true;
        sceneCount_ = totalCount;
        sceneMetadata_ = metadata;
        __android_log_print(
                ANDROID_LOG_INFO,
                kTag,
                "Vulkan SOG image/codebook scene uploaded chunks=%d count=%d",
                static_cast<int>(sogChunks_.size()),
                sceneCount_);
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

    bool createSogSampler() {
        if (sogSampler_ != VK_NULL_HANDLE) return true;
        VkSamplerCreateInfo samplerInfo{};
        samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
        samplerInfo.magFilter = VK_FILTER_NEAREST;
        samplerInfo.minFilter = VK_FILTER_NEAREST;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.maxLod = 0.0f;
        const VkResult result = vkCreateSampler(device_, &samplerInfo, nullptr, &sogSampler_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateSampler SOG failed result=%d", result);
            return false;
        }
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
        return createRenderPass() && createQuadPipeline() && createSogPipeline() &&
               createImageViews() && createFramebuffers() && createCommandBuffers();
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
        bindings[3].stride = sizeof(float) * 6;
        bindings[3].inputRate = VK_VERTEX_INPUT_RATE_INSTANCE;
        bindings[4].binding = 4;
        bindings[4].stride = sizeof(float) * 6;
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
        attributes[4].format = VK_FORMAT_R32G32B32_SFLOAT;
        attributes[4].offset = sizeof(float) * 3;

        VkPipelineVertexInputStateCreateInfo vertexInput{};
        vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        vertexInput.vertexBindingDescriptionCount = static_cast<uint32_t>(bindings.size());
        vertexInput.pVertexBindingDescriptions = bindings.data();
        vertexInput.vertexAttributeDescriptionCount = static_cast<uint32_t>(attributes.size());
        vertexInput.pVertexAttributeDescriptions = attributes.data();

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
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

    bool createSogDescriptorSetLayout() {
        if (sogDescriptorSetLayout_ != VK_NULL_HANDLE) return true;
        std::array<VkDescriptorSetLayoutBinding, 11> bindings{};
        for (uint32_t i = 0; i < 9u; ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_COMPUTE_BIT;
        }
        for (uint32_t i = 9u; i < bindings.size(); ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = i == 9u
                    ? (VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_COMPUTE_BIT)
                    : VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        layoutInfo.pBindings = bindings.data();
        VkResult result = vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &sogDescriptorSetLayout_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateDescriptorSetLayout failed result=%d", result);
            return false;
        }
        return true;
    }

    bool createSogPipeline() {
        if (!createSogDescriptorSetLayout()) return false;
        VkShaderModule vertModule = createShaderModule(
                tianyin_vulkan_shaders::kGaussianSogQuadVert,
                tianyin_vulkan_shaders::kGaussianSogQuadVertSize);
        VkShaderModule fragModule = createShaderModule(
                tianyin_vulkan_shaders::kGaussianQuadFrag,
                tianyin_vulkan_shaders::kGaussianQuadFragSize);
        VkShaderModule compModule = createShaderModule(
                tianyin_vulkan_shaders::kGaussianSogCompactComp,
                tianyin_vulkan_shaders::kGaussianSogCompactCompSize);
        if (vertModule == VK_NULL_HANDLE || fragModule == VK_NULL_HANDLE || compModule == VK_NULL_HANDLE) {
            if (vertModule != VK_NULL_HANDLE) vkDestroyShaderModule(device_, vertModule, nullptr);
            if (fragModule != VK_NULL_HANDLE) vkDestroyShaderModule(device_, fragModule, nullptr);
            if (compModule != VK_NULL_HANDLE) vkDestroyShaderModule(device_, compModule, nullptr);
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

        VkVertexInputBindingDescription binding{};
        binding.binding = 0;
        binding.stride = sizeof(float) * 2;
        binding.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;
        VkVertexInputAttributeDescription attribute{};
        attribute.binding = 0;
        attribute.location = 0;
        attribute.format = VK_FORMAT_R32G32_SFLOAT;
        attribute.offset = 0;
        VkPipelineVertexInputStateCreateInfo vertexInput{};
        vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        vertexInput.vertexBindingDescriptionCount = 1;
        vertexInput.pVertexBindingDescriptions = &binding;
        vertexInput.vertexAttributeDescriptionCount = 1;
        vertexInput.pVertexAttributeDescriptions = &attribute;

        VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
        inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
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
        pushRange.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.offset = 0;
        pushRange.size = sizeof(QuadPushConstants);
        VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
        pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        pipelineLayoutInfo.setLayoutCount = 1;
        pipelineLayoutInfo.pSetLayouts = &sogDescriptorSetLayout_;
        pipelineLayoutInfo.pushConstantRangeCount = 1;
        pipelineLayoutInfo.pPushConstantRanges = &pushRange;
        VkResult result = vkCreatePipelineLayout(device_, &pipelineLayoutInfo, nullptr, &sogPipelineLayout_);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreatePipelineLayout SOG failed result=%d", result);
            vkDestroyShaderModule(device_, vertModule, nullptr);
            vkDestroyShaderModule(device_, fragModule, nullptr);
            vkDestroyShaderModule(device_, compModule, nullptr);
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
        pipelineInfo.layout = sogPipelineLayout_;
        pipelineInfo.renderPass = renderPass_;
        pipelineInfo.subpass = 0;
        result = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &sogPipeline_);
        vkDestroyShaderModule(device_, vertModule, nullptr);
        vkDestroyShaderModule(device_, fragModule, nullptr);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateGraphicsPipelines SOG failed result=%d", result);
            vkDestroyShaderModule(device_, compModule, nullptr);
            return false;
        }
        VkComputePipelineCreateInfo computeInfo{};
        computeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        computeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        computeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        computeInfo.stage.module = compModule;
        computeInfo.stage.pName = "main";
        computeInfo.layout = sogPipelineLayout_;
        result = vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &computeInfo, nullptr, &sogCompactPipeline_);
        vkDestroyShaderModule(device_, compModule, nullptr);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateComputePipelines SOG compact failed result=%d", result);
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

    void selectSogDrawCommands(const QuadPushConstants& push) {
        sogDrawCommands_.clear();
        sogUseCompactDraw_ = false;
        sogSelectedSplatCount_ = 0;
        if (!sogSceneActive_ || sogChunks_.empty()) return;

        std::vector<SogDrawCommand> candidates;
        candidates.reserve(sogChunks_.size());
        const float nearPlane = 0.02f;
        const float aspect = push.surfaceSize[0] / std::max(1.0f, push.surfaceSize[1]);
        const float tanHalfVertical = std::max(push.tanHalfFov, 0.0001f);
        const float tanHalfHorizontal = tanHalfVertical * aspect;
        const float verticalPixelsPerWorld = push.surfaceSize[1] / (2.0f * tanHalfVertical);
        int visibleSplatCount = 0;
        for (auto& chunk : sogChunks_) {
            const float relX = chunk.centerX - push.cameraPosition[0];
            const float relY = chunk.centerY - push.cameraPosition[1];
            const float relZ = chunk.centerZ - push.cameraPosition[2];
            const float cameraX =
                    relX * push.cameraRight[0] +
                    relY * push.cameraRight[1] +
                    relZ * push.cameraRight[2];
            const float cameraY =
                    relX * push.cameraUp[0] +
                    relY * push.cameraUp[1] +
                    relZ * push.cameraUp[2];
            const float centerDepth =
                    relX * push.cameraForward[0] +
                    relY * push.cameraForward[1] +
                    relZ * push.cameraForward[2];
            const float radius = std::max(chunk.radius * 1.05f + 0.02f, 0.0001f);
            if (centerDepth + radius <= nearPlane) {
                continue;
            }
            const float sideDepth = std::max(centerDepth, nearPlane);
            const float horizontalRadius = radius * std::sqrt(1.0f + tanHalfHorizontal * tanHalfHorizontal);
            const float verticalRadius = radius * std::sqrt(1.0f + tanHalfVertical * tanHalfVertical);
            if (std::abs(cameraX) > sideDepth * tanHalfHorizontal + horizontalRadius ||
                std::abs(cameraY) > sideDepth * tanHalfVertical + verticalRadius) {
                continue;
            }

            const float nearestDepth = std::max(nearPlane, centerDepth - radius);
            const float projectedRadiusPixels = radius * verticalPixelsPerWorld / nearestDepth;
            const float centerDepthForNdc = std::max(centerDepth, nearPlane);
            const float ndcX = cameraX / (centerDepthForNdc * tanHalfHorizontal);
            const float ndcY = cameraY / (centerDepthForNdc * tanHalfVertical);
            const float centerWeight = 1.0f / (1.0f + 0.25f * (ndcX * ndcX + ndcY * ndcY));
            float importance = std::max(1.0f, projectedRadiusPixels * projectedRadiusPixels) * centerWeight;
            importance /= std::pow(static_cast<float>(std::max(1, chunk.count)), 0.35f);
            if (chunk.selectedLastFrame) {
                importance *= 1.08f;
            }
            candidates.push_back({&chunk, chunk.count, centerDepth + radius, importance});
            visibleSplatCount += chunk.count;
        }

        for (auto& chunk : sogChunks_) {
            chunk.selectedLastFrame = false;
        }
        if (candidates.empty()) {
            if (lastVisibleChunkCount_ != 0 || lastSelectedChunkCount_ != 0) {
                __android_log_print(ANDROID_LOG_DEBUG, kTag, "SOG chunk selection visible=0 selected=0 splats=0 budget=%d", drawCount_);
                lastVisibleChunkCount_ = 0;
                lastSelectedChunkCount_ = 0;
                lastSelectedSplatCount_ = 0;
            }
            return;
        }

        sogDrawCommands_.reserve(candidates.size());
        if (visibleSplatCount <= drawCount_) {
            sogDrawCommands_ = candidates;
        } else {
            std::stable_sort(
                    candidates.begin(),
                    candidates.end(),
                    [](const SogDrawCommand& a, const SogDrawCommand& b) {
                        if (a.importance != b.importance) return a.importance > b.importance;
                        return a.chunk->stableIndex < b.chunk->stableIndex;
                    });
            int remainingBudget = drawCount_;
            for (const auto& candidate : candidates) {
                if (candidate.drawCount <= remainingBudget) {
                    sogDrawCommands_.push_back(candidate);
                    remainingBudget -= candidate.drawCount;
                }
            }
            if (sogDrawCommands_.empty()) {
                sogDrawCommands_.push_back(candidates.front());
            }
        }

        for (auto& command : sogDrawCommands_) {
            command.chunk->selectedLastFrame = true;
        }
        int selectedSplatCount = 0;
        for (const auto& command : sogDrawCommands_) {
            selectedSplatCount += command.drawCount;
        }
        sogSelectedSplatCount_ = selectedSplatCount;
        sogUseCompactDraw_ = selectedSplatCount > 0 && selectedSplatCount <= kSogCompactDrawMaxSplats;
        const int visibleChunkCount = static_cast<int>(candidates.size());
        const int selectedChunkCount = static_cast<int>(sogDrawCommands_.size());
        if (visibleChunkCount != lastVisibleChunkCount_ ||
            selectedChunkCount != lastSelectedChunkCount_ ||
            selectedSplatCount != lastSelectedSplatCount_) {
            __android_log_print(
                    ANDROID_LOG_DEBUG,
                    kTag,
                    "SOG chunk selection visible=%d selected=%d splats=%d budget=%d",
                    visibleChunkCount,
                    selectedChunkCount,
                    selectedSplatCount,
                    drawCount_);
            lastVisibleChunkCount_ = visibleChunkCount;
            lastSelectedChunkCount_ = selectedChunkCount;
            lastSelectedSplatCount_ = selectedSplatCount;
        }
        std::stable_sort(
                sogDrawCommands_.begin(),
                sogDrawCommands_.end(),
                [](const SogDrawCommand& a, const SogDrawCommand& b) {
                    if (a.sortDepth != b.sortDepth) return a.sortDepth > b.sortDepth;
                    return a.chunk->stableIndex < b.chunk->stableIndex;
                });
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

        QuadPushConstants push{};
        push.surfaceSize[0] = static_cast<float>(std::max(1u, swapchainExtent_.width));
        push.surfaceSize[1] = static_cast<float>(std::max(1u, swapchainExtent_.height));
        buildCameraFrame(push);
        push.tanHalfFov = 0.57735026f;
        push.pointScale = std::clamp(renderState_.splatScale, 0.25f, 3.0f);
        push.quadExtent = 1.0f;
        push.opacity = renderState_.opacity;
        push.alphaFalloff = renderState_.alphaFalloff;
        push.minContribution = 1.0f;
        push.minPixelSize = 0.30f;
        push.sourceCount = sogUseCompactDraw_ ? 1u : 0u;

        if (sogSceneActive_) {
            VkBuffer vertexBuffers[] = { quadCornerBuffer_.buffer };
            VkDeviceSize offsets[] = {0};
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, sogPipeline_);
            vkCmdBindVertexBuffers(commandBuffer, 0, 1, vertexBuffers, offsets);
            vkCmdPushConstants(
                    commandBuffer,
                    sogPipelineLayout_,
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                    0,
                    sizeof(QuadPushConstants),
                    &push);
            for (const auto& command : sogDrawCommands_) {
                vkCmdBindDescriptorSets(
                        commandBuffer,
                        VK_PIPELINE_BIND_POINT_GRAPHICS,
                        sogPipelineLayout_,
                        0,
                        1,
                        &command.chunk->descriptorSet,
                        0,
                        nullptr);
                if (sogUseCompactDraw_) {
                    vkCmdDrawIndirect(commandBuffer, command.chunk->indirectArgs.buffer, 0, 1, sizeof(uint32_t) * 4u);
                } else {
                    vkCmdDraw(commandBuffer, 4, static_cast<uint32_t>(command.drawCount), 0, 0);
                }
            }
            return;
        }

        VkBuffer vertexBuffers[] = {
                quadCornerBuffer_.buffer,
                scenePositions_.buffer,
                sceneColors_.buffer,
                sceneCovariance_.buffer,
                sceneCovariance_.buffer
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
        vkCmdDraw(commandBuffer, 4, static_cast<uint32_t>(drawCount_), 0, 0);
    }

    bool createSogDescriptorSetsLocked(
            std::vector<SogChunkResource>& chunks,
            VkDescriptorPool& descriptorPool) {
        if (device_ == VK_NULL_HANDLE || chunks.empty() || !createSogDescriptorSetLayout()) return false;
        std::array<VkDescriptorPoolSize, 2> poolSizes{};
        poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        poolSizes[0].descriptorCount = static_cast<uint32_t>(chunks.size() * 9u);
        poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        poolSizes[1].descriptorCount = static_cast<uint32_t>(chunks.size() * 2u);

        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.maxSets = static_cast<uint32_t>(chunks.size());
        poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
        poolInfo.pPoolSizes = poolSizes.data();
        VkResult result = vkCreateDescriptorPool(device_, &poolInfo, nullptr, &descriptorPool);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateDescriptorPool SOG failed result=%d", result);
            return false;
        }

        std::vector<VkDescriptorSetLayout> layouts(chunks.size(), sogDescriptorSetLayout_);
        std::vector<VkDescriptorSet> descriptorSets(chunks.size(), VK_NULL_HANDLE);
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = descriptorPool;
        allocInfo.descriptorSetCount = static_cast<uint32_t>(layouts.size());
        allocInfo.pSetLayouts = layouts.data();
        result = vkAllocateDescriptorSets(device_, &allocInfo, descriptorSets.data());
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkAllocateDescriptorSets SOG failed result=%d", result);
            vkDestroyDescriptorPool(device_, descriptorPool, nullptr);
            descriptorPool = VK_NULL_HANDLE;
            return false;
        }

        for (size_t chunkIndex = 0; chunkIndex < chunks.size(); ++chunkIndex) {
            auto& chunk = chunks[chunkIndex];
            chunk.descriptorSet = descriptorSets[chunkIndex];
            std::array<VkDescriptorImageInfo, 9> images{{
                    {sogSampler_, chunk.meansL.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
                    {sogSampler_, chunk.meansU.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
                    {sogSampler_, chunk.scales.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
                    {sogSampler_, chunk.sh0.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
                    {sogSampler_, chunk.quats.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
                    {sogSampler_, chunk.scaleCodebook.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
                    {sogSampler_, chunk.sh0Codebook.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
                    {sogSampler_, chunk.meansMinMax.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
                    {sogSampler_, chunk.orderImage.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL},
            }};
            std::array<VkDescriptorBufferInfo, 2> buffers{{
                    {chunk.compactIndices.buffer, 0, chunk.compactIndices.size},
                    {chunk.indirectArgs.buffer, 0, chunk.indirectArgs.size},
            }};
            std::array<VkWriteDescriptorSet, 11> writes{};
            for (uint32_t i = 0; i < 9u; ++i) {
                writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                writes[i].dstSet = chunk.descriptorSet;
                writes[i].dstBinding = i;
                writes[i].descriptorCount = 1;
                writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                writes[i].pImageInfo = &images[i];
            }
            for (uint32_t i = 9u; i < writes.size(); ++i) {
                writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                writes[i].dstSet = chunk.descriptorSet;
                writes[i].dstBinding = i;
                writes[i].descriptorCount = 1;
                writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                writes[i].pBufferInfo = &buffers[i - 9u];
            }
            vkUpdateDescriptorSets(device_, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
        }
        return true;
    }

    bool buildSogCenters(
            JNIEnv* env,
            jobject meansLBuffer,
            jobject meansUBuffer,
            jobject meansMinMaxBuffer,
            int count,
            std::vector<float>& centers) {
        const auto* meansL = static_cast<const uint32_t*>(env->GetDirectBufferAddress(meansLBuffer));
        const auto* meansU = static_cast<const uint32_t*>(env->GetDirectBufferAddress(meansUBuffer));
        const auto* meansMinMax = static_cast<const float*>(env->GetDirectBufferAddress(meansMinMaxBuffer));
        if (meansL == nullptr || meansU == nullptr || meansMinMax == nullptr || count <= 0) return false;
        if (centers.size() < static_cast<size_t>(count) * 3u) {
            centers.resize(static_cast<size_t>(count) * 3u);
        }
        const bool flipZ = meansMinMax[3] > 0.5f;
        for (int i = 0; i < count; ++i) {
            const uint32_t l = meansL[i];
            const uint32_t u = meansU[i];
            for (uint32_t axis = 0; axis < 3u; ++axis) {
                const float q = (channel(l, axis) + channel(u, axis) * 256.0f) / 65535.0f;
                const float encoded = meansMinMax[axis] + (meansMinMax[4u + axis] - meansMinMax[axis]) * q;
                centers[static_cast<size_t>(i) * 3u + axis] = symmetricUnlog(encoded);
            }
            if (flipZ) {
                centers[static_cast<size_t>(i) * 3u + 2u] = -centers[static_cast<size_t>(i) * 3u + 2u];
            }
        }
        return true;
    }

    bool buildSogSortChunkSpheres(
            int count,
            const std::vector<float>& centers,
            std::vector<float>& chunkSpheres) {
        if (count <= 0 || centers.size() < static_cast<size_t>(count) * 3u) return false;
        const int chunkCount = (count + kSogSortSubChunkSize - 1) / kSogSortSubChunkSize;
        chunkSpheres.resize(static_cast<size_t>(chunkCount) * 4u);
        for (int chunkIndex = 0; chunkIndex < chunkCount; ++chunkIndex) {
            const int start = chunkIndex * kSogSortSubChunkSize;
            const int end = std::min(count, start + kSogSortSubChunkSize);
            float minX = std::numeric_limits<float>::infinity();
            float minY = std::numeric_limits<float>::infinity();
            float minZ = std::numeric_limits<float>::infinity();
            float maxX = -std::numeric_limits<float>::infinity();
            float maxY = -std::numeric_limits<float>::infinity();
            float maxZ = -std::numeric_limits<float>::infinity();
            for (int i = start; i < end; ++i) {
                const size_t base = static_cast<size_t>(i) * 3u;
                const float x = centers[base];
                const float y = centers[base + 1u];
                const float z = centers[base + 2u];
                if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(z)) continue;
                minX = std::min(minX, x);
                minY = std::min(minY, y);
                minZ = std::min(minZ, z);
                maxX = std::max(maxX, x);
                maxY = std::max(maxY, y);
                maxZ = std::max(maxZ, z);
            }
            if (!std::isfinite(minX) || !std::isfinite(maxX)) {
                minX = minY = minZ = maxX = maxY = maxZ = 0.0f;
            }
            const float centerX = (minX + maxX) * 0.5f;
            const float centerY = (minY + maxY) * 0.5f;
            const float centerZ = (minZ + maxZ) * 0.5f;
            const float dx = maxX - minX;
            const float dy = maxY - minY;
            const float dz = maxZ - minZ;
            const float radius = std::sqrt(dx * dx + dy * dy + dz * dz) * 0.5f;
            const size_t out = static_cast<size_t>(chunkIndex) * 4u;
            chunkSpheres[out] = centerX;
            chunkSpheres[out + 1u] = centerY;
            chunkSpheres[out + 2u] = centerZ;
            chunkSpheres[out + 3u] = radius;
        }
        return true;
    }

    bool sogOrderNeedsUpdate(const SogChunkResource& chunk, const QuadPushConstants& push) const {
        if (!chunk.orderValid) return true;
        if (!kSogDynamicOrderSort) return false;
        return std::abs(chunk.lastOrderForwardX - push.cameraForward[0]) > kSogSortEpsilon ||
                std::abs(chunk.lastOrderForwardY - push.cameraForward[1]) > kSogSortEpsilon ||
                std::abs(chunk.lastOrderForwardZ - push.cameraForward[2]) > kSogSortEpsilon;
    }

    void sortSogChunkOrder(SogChunkResource& chunk, const QuadPushConstants& push) {
        const int count = chunk.count;
        if (count <= 0 || chunk.centers.size() < static_cast<size_t>(count) * 3u) return;
        chunk.order.resize(static_cast<size_t>(count));
        chunk.sortKeys.resize(static_cast<size_t>(count));

        const float fx = push.cameraForward[0];
        const float fy = push.cameraForward[1];
        const float fz = push.cameraForward[2];
        float minDepth = std::numeric_limits<float>::infinity();
        float maxDepth = -std::numeric_limits<float>::infinity();
        const size_t sortChunkCount = chunk.sortChunkSpheres.size() / 4u;
        for (size_t i = 0; i < sortChunkCount; ++i) {
            const size_t base = i * 4u;
            const float depth = chunk.sortChunkSpheres[base] * fx +
                    chunk.sortChunkSpheres[base + 1u] * fy +
                    chunk.sortChunkSpheres[base + 2u] * fz;
            const float radius = chunk.sortChunkSpheres[base + 3u];
            minDepth = std::min(minDepth, depth - radius);
            maxDepth = std::max(maxDepth, depth + radius);
        }
        if (!std::isfinite(minDepth) || !std::isfinite(maxDepth)) {
            for (int i = 0; i < count; ++i) {
                const size_t base = static_cast<size_t>(i) * 3u;
                const float depth = chunk.centers[base] * fx + chunk.centers[base + 1u] * fy + chunk.centers[base + 2u] * fz;
                if (i == 0) {
                    minDepth = maxDepth = depth;
                } else {
                    minDepth = std::min(minDepth, depth);
                    maxDepth = std::max(maxDepth, depth);
                }
            }
        }

        const float range = maxDepth - minDepth;
        if (range < 1.0e-6f) {
            for (int i = 0; i < count; ++i) {
                chunk.order[static_cast<size_t>(i)] = static_cast<uint32_t>(i);
            }
        } else {
            const float countScale = std::max(1.0f, static_cast<float>(count) * 0.25f);
            const int compareBits = std::max(10, std::min(20, static_cast<int>(std::round(std::log2(countScale)))));
            const uint32_t maxKey = (1u << compareBits) - 1u;
            const uint32_t bucketCount = maxKey + 1u;
            chunk.sortBinCount.fill(0u);
            chunk.sortBinBase.fill(0u);
            chunk.sortBinDivider.fill(0u);
            if (chunk.sortCounts.size() != bucketCount) {
                chunk.sortCounts.assign(bucketCount, 0u);
            } else {
                std::fill(chunk.sortCounts.begin(), chunk.sortCounts.end(), 0u);
            }

            for (size_t i = 0; i < sortChunkCount; ++i) {
                const size_t base = i * 4u;
                const float depth = chunk.sortChunkSpheres[base] * fx +
                        chunk.sortChunkSpheres[base + 1u] * fy +
                        chunk.sortChunkSpheres[base + 2u] * fz;
                const float radius = chunk.sortChunkSpheres[base + 3u];
                const float binStart = (depth - radius - minDepth) * static_cast<float>(kSogSortBinCount) / range;
                const float binEnd = (depth + radius - minDepth) * static_cast<float>(kSogSortBinCount) / range;
                const uint32_t binMin = static_cast<uint32_t>(std::clamp(static_cast<int>(std::floor(binStart)), 0, static_cast<int>(kSogSortBinCount - 1u)));
                const uint32_t binMaxExclusive = static_cast<uint32_t>(std::clamp(static_cast<int>(std::ceil(binEnd)), 1, static_cast<int>(kSogSortBinCount)));
                for (uint32_t bin = binMin; bin < binMaxExclusive; ++bin) {
                    chunk.sortBinCount[bin]++;
                }
            }
            uint32_t binTotal = 0;
            for (uint32_t bin = 0; bin < kSogSortBinCount; ++bin) {
                binTotal += chunk.sortBinCount[bin];
            }
            if (binTotal == 0) {
                binTotal = 1;
                chunk.sortBinCount[0] = 1;
            }
            uint32_t binBase = 0;
            for (uint32_t bin = 0; bin < kSogSortBinCount; ++bin) {
                chunk.sortBinBase[bin] = binBase;
                chunk.sortBinDivider[bin] = static_cast<uint32_t>(
                        static_cast<uint64_t>(chunk.sortBinCount[bin]) * bucketCount / binTotal);
                binBase += chunk.sortBinDivider[bin];
            }
            chunk.sortBinDivider[kSogSortBinCount - 1u] =
                    std::max(chunk.sortBinDivider[kSogSortBinCount - 1u], bucketCount - chunk.sortBinBase[kSogSortBinCount - 1u]);

            const float binRange = range / static_cast<float>(kSogSortBinCount);
            for (int i = 0; i < count; ++i) {
                const size_t base = static_cast<size_t>(i) * 3u;
                const float depth = chunk.centers[base] * fx + chunk.centers[base + 1u] * fy + chunk.centers[base + 2u] * fz;
                const float d = std::clamp((maxDepth - depth) / std::max(binRange, 1.0e-6f), 0.0f, static_cast<float>(kSogSortBinCount) - 0.0001f);
                const uint32_t bin = std::min(kSogSortBinCount - 1u, static_cast<uint32_t>(d));
                const float frac = d - static_cast<float>(bin);
                const uint32_t key = std::min(
                        maxKey,
                        chunk.sortBinBase[bin] + static_cast<uint32_t>(static_cast<float>(chunk.sortBinDivider[bin]) * frac));
                chunk.sortKeys[static_cast<size_t>(i)] = key;
                chunk.sortCounts[key]++;
            }

            uint32_t offset = 0;
            for (uint32_t key = 0; key < bucketCount; ++key) {
                const uint32_t countForKey = chunk.sortCounts[key];
                chunk.sortCounts[key] = offset;
                offset += countForKey;
            }
            for (int i = 0; i < count; ++i) {
                const uint32_t key = chunk.sortKeys[static_cast<size_t>(i)];
                const uint32_t dst = chunk.sortCounts[key]++;
                chunk.order[dst] = static_cast<uint32_t>(i);
            }
        }

        chunk.lastOrderForwardX = fx;
        chunk.lastOrderForwardY = fy;
        chunk.lastOrderForwardZ = fz;
        chunk.orderValid = true;
    }

    void updateSogOrderImages(VkCommandBuffer commandBuffer, const QuadPushConstants& push) {
        std::vector<SogChunkResource*> changed;
        changed.reserve(sogChunks_.size());
        for (auto& chunk : sogChunks_) {
            if (chunk.orderImage.image == VK_NULL_HANDLE || chunk.orderStaging.buffer == VK_NULL_HANDLE ||
                chunk.count <= 0 || !chunk.selectedLastFrame || !sogOrderNeedsUpdate(chunk, push)) {
                continue;
            }
            sortSogChunkOrder(chunk, push);
            const VkDeviceSize imageSize = static_cast<VkDeviceSize>(chunk.orderImage.width) *
                    chunk.orderImage.height * sizeof(uint32_t);
            if (imageSize == 0 || chunk.orderStaging.size < imageSize) {
                continue;
            }
            void* mapped = nullptr;
            VkResult result = vkMapMemory(device_, chunk.orderStaging.memory, 0, imageSize, 0, &mapped);
            if (result != VK_SUCCESS || mapped == nullptr) {
                chunk.orderValid = false;
                continue;
            }
            std::memset(mapped, 0, static_cast<size_t>(imageSize));
            std::memcpy(mapped, chunk.order.data(), static_cast<size_t>(chunk.count) * sizeof(uint32_t));
            vkUnmapMemory(device_, chunk.orderStaging.memory);
            changed.push_back(&chunk);
        }
        if (changed.empty()) return;

        std::vector<VkImageMemoryBarrier> toTransfer(changed.size());
        for (size_t i = 0; i < changed.size(); ++i) {
            auto& barrier = toTransfer[i];
            barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.image = changed[i]->orderImage.image;
            barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            barrier.subresourceRange.levelCount = 1;
            barrier.subresourceRange.layerCount = 1;
        }
        vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0,
                0, nullptr,
                0, nullptr,
                static_cast<uint32_t>(toTransfer.size()), toTransfer.data());

        for (const auto* chunk : changed) {
            VkBufferImageCopy copy{};
            copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            copy.imageSubresource.layerCount = 1;
            copy.imageExtent = {chunk->orderImage.width, chunk->orderImage.height, 1u};
            vkCmdCopyBufferToImage(
                    commandBuffer,
                    chunk->orderStaging.buffer,
                    chunk->orderImage.image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    1,
                    &copy);
        }

        std::vector<VkImageMemoryBarrier> toShaderRead = toTransfer;
        for (auto& barrier : toShaderRead) {
            barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        }
        vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0,
                0, nullptr,
                0, nullptr,
                static_cast<uint32_t>(toShaderRead.size()), toShaderRead.data());
    }

    void compactSogVisibleIndices(VkCommandBuffer commandBuffer, const QuadPushConstants& push) {
        if (sogCompactPipeline_ == VK_NULL_HANDLE || sogDrawCommands_.empty()) return;

        std::vector<VkBufferMemoryBarrier> clearBarriers;
        clearBarriers.reserve(sogDrawCommands_.size());
        for (const auto& command : sogDrawCommands_) {
            if (command.chunk == nullptr) continue;
            const auto& chunk = *command.chunk;
            if (chunk.indirectArgs.buffer == VK_NULL_HANDLE || chunk.indirectArgs.size < sizeof(uint32_t) * 4u) continue;
            vkCmdFillBuffer(commandBuffer, chunk.indirectArgs.buffer, 0, chunk.indirectArgs.size, 0);
            VkBufferMemoryBarrier barrier{};
            barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.buffer = chunk.indirectArgs.buffer;
            barrier.offset = 0;
            barrier.size = chunk.indirectArgs.size;
            clearBarriers.push_back(barrier);
        }
        if (!clearBarriers.empty()) {
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    0, nullptr,
                    static_cast<uint32_t>(clearBarriers.size()), clearBarriers.data(),
                    0, nullptr);
        }

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, sogCompactPipeline_);
        for (const auto& command : sogDrawCommands_) {
            if (command.chunk == nullptr) continue;
            const auto& chunk = *command.chunk;
            if (chunk.descriptorSet == VK_NULL_HANDLE || chunk.count <= 0 || command.drawCount <= 0) continue;
            QuadPushConstants chunkPush = push;
            chunkPush.sourceCount = static_cast<uint32_t>(std::min(command.drawCount, chunk.count));
            vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    sogPipelineLayout_,
                    0,
                    1,
                    &chunk.descriptorSet,
                    0,
                    nullptr);
            vkCmdPushConstants(
                    commandBuffer,
                    sogPipelineLayout_,
                    VK_SHADER_STAGE_COMPUTE_BIT,
                    0,
                    sizeof(QuadPushConstants),
                    &chunkPush);
            vkCmdDispatch(commandBuffer, 1, 1, 1);
        }

        std::vector<VkBufferMemoryBarrier> drawBarriers;
        drawBarriers.reserve(sogDrawCommands_.size() * 2u);
        for (const auto& command : sogDrawCommands_) {
            if (command.chunk == nullptr) continue;
            const auto& chunk = *command.chunk;
            if (chunk.compactIndices.buffer != VK_NULL_HANDLE) {
                VkBufferMemoryBarrier barrier{};
                barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
                barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
                barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barrier.buffer = chunk.compactIndices.buffer;
                barrier.offset = 0;
                barrier.size = chunk.compactIndices.size;
                drawBarriers.push_back(barrier);
            }
            if (chunk.indirectArgs.buffer != VK_NULL_HANDLE) {
                VkBufferMemoryBarrier barrier{};
                barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
                barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
                barrier.dstAccessMask = VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
                barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                barrier.buffer = chunk.indirectArgs.buffer;
                barrier.offset = 0;
                barrier.size = chunk.indirectArgs.size;
                drawBarriers.push_back(barrier);
            }
        }
        if (!drawBarriers.empty()) {
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                    0,
                    0, nullptr,
                    static_cast<uint32_t>(drawBarriers.size()), drawBarriers.data(),
                    0, nullptr);
        }
    }

    void buildCameraFrame(QuadPushConstants& push) const {
        const float radius = std::max(sceneMetadata_.sceneRadius, 0.001f);
        const float targetX = sceneMetadata_.sceneCenterX + renderState_.centerOffsetX * radius;
        const float targetY = sceneMetadata_.sceneCenterY + renderState_.centerOffsetY * radius;
        const float targetZ = sceneMetadata_.sceneCenterZ + radius * renderState_.focusDepthOffset;
        const float frameDistance = std::max(sceneMetadata_.defaultCameraDistance, radius * 0.02f);
        const float distance = std::max(frameDistance / std::max(renderState_.cameraZoom, 0.6f), radius * 0.02f);
        float tangentX = renderState_.tiltX * frameDistance * std::max(renderState_.parallaxStrength, 0.02f) * 2.4f;
        float tangentY = -renderState_.tiltY * frameDistance * std::max(renderState_.parallaxStrength, 0.02f) * 2.4f;
        const float maxTangent = distance * 0.75f;
        float tangentLength = std::sqrt(tangentX * tangentX + tangentY * tangentY);
        if (tangentLength > maxTangent && tangentLength > 0.0001f) {
            const float scale = maxTangent / tangentLength;
            tangentX *= scale;
            tangentY *= scale;
            tangentLength = maxTangent;
        }
        const float frontDepth = std::sqrt(std::max(
                distance * distance - tangentLength * tangentLength,
                distance * distance * 0.25f));
        const float positionX = targetX + tangentX;
        const float positionY = targetY + tangentY;
        const float positionZ = targetZ - frontDepth;

        float forwardX = targetX - positionX;
        float forwardY = targetY - positionY;
        float forwardZ = targetZ - positionZ;
        const float forwardLength = std::max(
                std::sqrt(forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ),
                0.0001f);
        forwardX /= forwardLength;
        forwardY /= forwardLength;
        forwardZ /= forwardLength;

        float rightX = forwardZ;
        float rightZ = -forwardX;
        const float rightLength = std::max(std::sqrt(rightX * rightX + rightZ * rightZ), 0.0001f);
        rightX /= rightLength;
        rightZ /= rightLength;

        float upX = forwardY * rightZ;
        float upY = forwardZ * rightX - forwardX * rightZ;
        float upZ = -forwardY * rightX;
        const float upLength = std::max(std::sqrt(upX * upX + upY * upY + upZ * upZ), 0.0001f);
        upX /= upLength;
        upY /= upLength;
        upZ /= upLength;

        push.cameraPosition[0] = positionX;
        push.cameraPosition[1] = positionY;
        push.cameraPosition[2] = positionZ;
        push.cameraRight[0] = rightX;
        push.cameraRight[2] = rightZ;
        push.cameraUp[0] = upX;
        push.cameraUp[1] = upY;
        push.cameraUp[2] = upZ;
        push.cameraForward[0] = forwardX;
        push.cameraForward[1] = forwardY;
        push.cameraForward[2] = forwardZ;
    }

    static void buildCovarianceData(
            const float* scales,
            const float* rotations,
            int count,
            float* covariance) {
        for (int index = 0; index < count; ++index) {
            const float sx = std::max(scales[index * 3], 0.0001f);
            const float sy = std::max(scales[index * 3 + 1], 0.0001f);
            const float sz = std::max(scales[index * 3 + 2], 0.0001f);
            float qx = rotations[index * 4];
            float qy = rotations[index * 4 + 1];
            float qz = rotations[index * 4 + 2];
            float qw = rotations[index * 4 + 3];
            const float quatLength = std::sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (quatLength > 0.000001f) {
                qx /= quatLength;
                qy /= quatLength;
                qz /= quatLength;
                qw /= quatLength;
            } else {
                qx = 0.0f;
                qy = 0.0f;
                qz = 0.0f;
                qw = 1.0f;
            }

            const float x2 = qx + qx;
            const float y2 = qy + qy;
            const float z2 = qz + qz;
            const float xx = qx * x2;
            const float xy = qx * y2;
            const float xz = qx * z2;
            const float yy = qy * y2;
            const float yz = qy * z2;
            const float zz = qz * z2;
            const float wx = qw * x2;
            const float wy = qw * y2;
            const float wz = qw * z2;
            const float m0x = 1.0f - yy - zz;
            const float m0y = xy + wz;
            const float m0z = xz - wy;
            const float m1x = xy - wz;
            const float m1y = 1.0f - xx - zz;
            const float m1z = yz + wx;
            const float m2x = xz + wy;
            const float m2y = yz - wx;
            const float m2z = 1.0f - xx - yy;
            const float sx2 = sx * sx;
            const float sy2 = sy * sy;
            const float sz2 = sz * sz;
            float* out = covariance + index * 6;
            out[0] = m0x * m0x * sx2 + m1x * m1x * sy2 + m2x * m2x * sz2;
            out[1] = m0x * m0y * sx2 + m1x * m1y * sy2 + m2x * m2y * sz2;
            out[2] = m0x * m0z * sx2 + m1x * m1z * sy2 + m2x * m2z * sz2;
            out[3] = m0y * m0y * sx2 + m1y * m1y * sy2 + m2y * m2y * sz2;
            out[4] = m0y * m0z * sx2 + m1y * m1z * sy2 + m2y * m2z * sz2;
            out[5] = m0z * m0z * sx2 + m1z * m1z * sy2 + m2z * m2z * sz2;
        }
    }

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

    bool createBufferResource(
            VkDeviceSize size,
            VkBufferUsageFlags usage,
            VkMemoryPropertyFlags properties,
            BufferResource& out,
            const char* label) {
        if (size == 0) return false;
        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = size;
        bufferInfo.usage = usage;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkResult result = vkCreateBuffer(device_, &bufferInfo, nullptr, &out.buffer);
        if (result != VK_SUCCESS) {
            __android_log_print(
                    ANDROID_LOG_WARN,
                    kTag,
                    "vkCreateBuffer failed label=%s result=%d size=%llu",
                    label,
                    result,
                    static_cast<unsigned long long>(size));
            return false;
        }

        VkMemoryRequirements memRequirements{};
        vkGetBufferMemoryRequirements(device_, out.buffer, &memRequirements);
        const uint32_t memoryType = findMemoryType(memRequirements.memoryTypeBits, properties);
        if (memoryType == UINT32_MAX) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "no memory type for %s", label);
            destroyBufferLocked(out);
            return false;
        }

        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memRequirements.size;
        allocInfo.memoryTypeIndex = memoryType;
        result = vkAllocateMemory(device_, &allocInfo, nullptr, &out.memory);
        if (result != VK_SUCCESS) {
            __android_log_print(
                    ANDROID_LOG_WARN,
                    kTag,
                    "vkAllocateMemory failed label=%s result=%d size=%llu",
                    label,
                    result,
                    static_cast<unsigned long long>(memRequirements.size));
            destroyBufferLocked(out);
            return false;
        }
        result = vkBindBufferMemory(device_, out.buffer, out.memory, 0);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkBindBufferMemory failed label=%s result=%d", label, result);
            destroyBufferLocked(out);
            return false;
        }
        out.size = size;
        return true;
    }

    bool createImageResource(
            uint32_t width,
            uint32_t height,
            VkFormat format,
            ImageResource& out,
            const char* label) {
        if (width == 0 || height == 0) return false;
        VkImageCreateInfo imageInfo{};
        imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = format;
        imageInfo.extent = {width, height, 1u};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        VkResult result = vkCreateImage(device_, &imageInfo, nullptr, &out.image);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateImage failed label=%s result=%d", label, result);
            return false;
        }

        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(device_, out.image, &requirements);
        const uint32_t memoryType = findMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (memoryType == UINT32_MAX) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "no image memory type for %s", label);
            destroyImageLocked(out);
            return false;
        }
        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = requirements.size;
        allocInfo.memoryTypeIndex = memoryType;
        result = vkAllocateMemory(device_, &allocInfo, nullptr, &out.memory);
        if (result == VK_SUCCESS) {
            result = vkBindImageMemory(device_, out.image, out.memory, 0);
        }
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "image memory allocation failed label=%s result=%d", label, result);
            destroyImageLocked(out);
            return false;
        }

        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = out.image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = format;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.layerCount = 1;
        result = vkCreateImageView(device_, &viewInfo, nullptr, &out.view);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkCreateImageView failed label=%s result=%d", label, result);
            destroyImageLocked(out);
            return false;
        }
        out.format = format;
        out.width = width;
        out.height = height;
        return true;
    }

    bool submitImageUploadsLocked(const std::vector<PendingImageUpload>& uploads) {
        if (uploads.empty()) return false;
        VkCommandBufferAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocInfo.commandPool = commandPool_;
        allocInfo.commandBufferCount = 1;
        VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
        VkResult result = vkAllocateCommandBuffers(device_, &allocInfo, &commandBuffer);
        if (result != VK_SUCCESS) return false;

        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
        if (result != VK_SUCCESS) {
            vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
            return false;
        }

        std::vector<VkImageMemoryBarrier> toTransfer(uploads.size());
        for (size_t i = 0; i < uploads.size(); ++i) {
            auto& barrier = toTransfer[i];
            barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            barrier.srcAccessMask = 0;
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.image = uploads[i].image->image;
            barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            barrier.subresourceRange.levelCount = 1;
            barrier.subresourceRange.layerCount = 1;
        }
        vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0,
                0, nullptr,
                0, nullptr,
                static_cast<uint32_t>(toTransfer.size()), toTransfer.data());

        for (const auto& upload : uploads) {
            VkBufferImageCopy copy{};
            copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            copy.imageSubresource.layerCount = 1;
            copy.imageExtent = {upload.image->width, upload.image->height, 1u};
            vkCmdCopyBufferToImage(
                    commandBuffer,
                    upload.staging.buffer,
                    upload.image->image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    1,
                    &copy);
        }

        std::vector<VkImageMemoryBarrier> toShaderRead = toTransfer;
        for (auto& barrier : toShaderRead) {
            barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        }
        vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0,
                0, nullptr,
                0, nullptr,
                static_cast<uint32_t>(toShaderRead.size()), toShaderRead.data());

        result = vkEndCommandBuffer(commandBuffer);
        if (result == VK_SUCCESS) {
            VkSubmitInfo submitInfo{};
            submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            submitInfo.commandBufferCount = 1;
            submitInfo.pCommandBuffers = &commandBuffer;
            result = vkQueueSubmit(queue_, 1, &submitInfo, VK_NULL_HANDLE);
            if (result == VK_SUCCESS) result = vkQueueWaitIdle(queue_);
        }
        vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
        return result == VK_SUCCESS;
    }

    bool prepareDirectImageUpload(
            JNIEnv* env,
            jobject source,
            VkDeviceSize sourceSize,
            uint32_t width,
            uint32_t height,
            VkFormat format,
            ImageResource& out,
            std::vector<PendingImageUpload>& uploads) {
        if (source == nullptr || sourceSize == 0) return false;
        const void* sourcePtr = env->GetDirectBufferAddress(source);
        if (sourcePtr == nullptr) return false;
        return prepareRawImageUpload(sourcePtr, sourceSize, width, height, format, out, uploads);
    }

    bool prepareRawImageUpload(
            const void* sourcePtr,
            VkDeviceSize sourceSize,
            uint32_t width,
            uint32_t height,
            VkFormat format,
            ImageResource& out,
            std::vector<PendingImageUpload>& uploads) {
        if (sourcePtr == nullptr || sourceSize == 0) return false;
        VkFormatProperties formatProperties{};
        vkGetPhysicalDeviceFormatProperties(physicalDevice_, format, &formatProperties);
        const VkFormatFeatureFlags requiredFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        if ((formatProperties.optimalTilingFeatures & requiredFeatures) != requiredFeatures) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "sampled image format unsupported format=%d", format);
            return false;
        }
        const VkDeviceSize texelSize = format == VK_FORMAT_R32G32B32A32_SFLOAT ? 16u : 4u;
        const VkDeviceSize imageSize = static_cast<VkDeviceSize>(width) * height * texelSize;
        BufferResource staging{};
        if (!createBufferResource(
                    imageSize,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    staging,
                    "SOG image staging")) return false;
        void* mapped = nullptr;
        VkResult result = vkMapMemory(device_, staging.memory, 0, imageSize, 0, &mapped);
        if (result != VK_SUCCESS || mapped == nullptr) {
            destroyBufferLocked(staging);
            return false;
        }
        std::memset(mapped, 0, static_cast<size_t>(imageSize));
        std::memcpy(mapped, sourcePtr, static_cast<size_t>(std::min(sourceSize, imageSize)));
        vkUnmapMemory(device_, staging.memory);
        if (!createImageResource(width, height, format, out, "SOG image")) {
            destroyBufferLocked(staging);
            return false;
        }
        uploads.push_back({staging, &out});
        return true;
    }

    bool copyBufferLocked(VkBuffer source, VkBuffer destination, VkDeviceSize size) {
        if (commandPool_ == VK_NULL_HANDLE || queue_ == VK_NULL_HANDLE || size == 0) return false;

        VkCommandBufferAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocInfo.commandPool = commandPool_;
        allocInfo.commandBufferCount = 1;

        VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
        VkResult result = vkAllocateCommandBuffers(device_, &allocInfo, &commandBuffer);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkAllocateCommandBuffers copy failed result=%d", result);
            return false;
        }

        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        result = vkBeginCommandBuffer(commandBuffer, &beginInfo);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkBeginCommandBuffer copy failed result=%d", result);
            vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
            return false;
        }

        VkBufferCopy copyRegion{};
        copyRegion.size = size;
        vkCmdCopyBuffer(commandBuffer, source, destination, 1, &copyRegion);

        result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkEndCommandBuffer copy failed result=%d", result);
            vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
            return false;
        }

        VkSubmitInfo submitInfo{};
        submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commandBuffer;
        result = vkQueueSubmit(queue_, 1, &submitInfo, VK_NULL_HANDLE);
        if (result == VK_SUCCESS) {
            result = vkQueueWaitIdle(queue_);
        }
        vkFreeCommandBuffers(device_, commandPool_, 1, &commandBuffer);
        if (result != VK_SUCCESS) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "Vulkan buffer copy failed result=%d", result);
            return false;
        }
        return true;
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
        return uploadFloatData(sourcePtr, size, usage, out);
    }

    bool uploadFloatData(
            const void* sourcePtr,
            VkDeviceSize size,
            VkBufferUsageFlags usage,
            BufferResource& out) {
        if (sourcePtr == nullptr || size == 0) return false;
        BufferResource staging{};
        if (!createBufferResource(
                    size,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    staging,
                    "scene staging")) {
            return false;
        }

        void* mapped = nullptr;
        VkResult result = vkMapMemory(device_, staging.memory, 0, size, 0, &mapped);
        if (result != VK_SUCCESS || mapped == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "vkMapMemory failed result=%d", result);
            destroyBufferLocked(staging);
            return false;
        }
        std::memcpy(mapped, sourcePtr, static_cast<size_t>(size));
        vkUnmapMemory(device_, staging.memory);

        BufferResource deviceLocal{};
        if (!createBufferResource(
                    size,
                    usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    deviceLocal,
                    "scene device-local")) {
            destroyBufferLocked(staging);
            return false;
        }
        if (!copyBufferLocked(staging.buffer, deviceLocal.buffer, size)) {
            destroyBufferLocked(staging);
            destroyBufferLocked(deviceLocal);
            return false;
        }
        destroyBufferLocked(staging);
        out = deviceLocal;
        return true;
    }

    bool uploadHostBuffer(
            const void* sourcePtr,
            VkDeviceSize size,
            VkBufferUsageFlags usage,
            BufferResource& out) {
        if (sourcePtr == nullptr || size == 0) return false;
        if (!createBufferResource(
                    size,
                    usage,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    out,
                    "host buffer")) {
            return false;
        }
        void* mapped = nullptr;
        VkResult result = vkMapMemory(device_, out.memory, 0, size, 0, &mapped);
        if (result != VK_SUCCESS || mapped == nullptr) {
            destroyBufferLocked(out);
            return false;
        }
        std::memcpy(mapped, sourcePtr, static_cast<size_t>(size));
        vkUnmapMemory(device_, out.memory);
        return true;
    }

    bool createQuadCornerBuffer() {
        static constexpr float kCorners[] = {
                -1.0f, -1.0f,
                 1.0f, -1.0f,
                -1.0f,  1.0f,
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
        if (sogPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, sogPipeline_, nullptr);
            sogPipeline_ = VK_NULL_HANDLE;
        }
        if (sogCompactPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, sogCompactPipeline_, nullptr);
            sogCompactPipeline_ = VK_NULL_HANDLE;
        }
        if (sogPipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, sogPipelineLayout_, nullptr);
            sogPipelineLayout_ = VK_NULL_HANDLE;
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

    void destroyImageLocked(ImageResource& resource) {
        if (device_ == VK_NULL_HANDLE) return;
        if (resource.view != VK_NULL_HANDLE) vkDestroyImageView(device_, resource.view, nullptr);
        if (resource.image != VK_NULL_HANDLE) vkDestroyImage(device_, resource.image, nullptr);
        if (resource.memory != VK_NULL_HANDLE) vkFreeMemory(device_, resource.memory, nullptr);
        resource = ImageResource{};
    }

    void destroySogChunksLocked(
            std::vector<SogChunkResource>& chunks,
            VkDescriptorPool& descriptorPool) {
        if (&chunks == &sogChunks_) {
            sogDrawCommands_.clear();
            sogSelectedSplatCount_ = 0;
            sogUseCompactDraw_ = false;
        }
        if (device_ != VK_NULL_HANDLE && descriptorPool != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device_, descriptorPool, nullptr);
            descriptorPool = VK_NULL_HANDLE;
        }
        for (auto& chunk : chunks) {
            destroyImageLocked(chunk.meansL);
            destroyImageLocked(chunk.meansU);
            destroyImageLocked(chunk.scales);
            destroyImageLocked(chunk.sh0);
            destroyImageLocked(chunk.quats);
            destroyImageLocked(chunk.scaleCodebook);
            destroyImageLocked(chunk.sh0Codebook);
            destroyImageLocked(chunk.meansMinMax);
            destroyImageLocked(chunk.orderImage);
            destroyBufferLocked(chunk.orderStaging);
            destroyBufferLocked(chunk.compactIndices);
            destroyBufferLocked(chunk.indirectArgs);
            chunk.descriptorSet = VK_NULL_HANDLE;
            chunk.count = 0;
            chunk.centers.clear();
            chunk.order.clear();
            chunk.sortKeys.clear();
            chunk.sortCounts.clear();
            chunk.sortChunkSpheres.clear();
            chunk.orderValid = false;
        }
        chunks.clear();
    }

    void destroySceneBuffersLocked() {
        destroySogChunksLocked(sogChunks_, sogDescriptorPool_);
        sogSceneActive_ = false;
        lastVisibleChunkCount_ = -1;
        lastSelectedChunkCount_ = -1;
        lastSelectedSplatCount_ = -1;
        destroyBufferLocked(scenePositions_);
        destroyBufferLocked(sceneColors_);
        destroyBufferLocked(sceneCovariance_);
        sceneCount_ = 0;
    }

    void stopLocked() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        destroySceneBuffersLocked();
        destroyBufferLocked(quadCornerBuffer_);
        destroySwapchainLocked();
        if (sogDescriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device_, sogDescriptorSetLayout_, nullptr);
            sogDescriptorSetLayout_ = VK_NULL_HANDLE;
        }
        if (sogSampler_ != VK_NULL_HANDLE) {
            vkDestroySampler(device_, sogSampler_, nullptr);
            sogSampler_ = VK_NULL_HANDLE;
        }
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
    VkDescriptorSetLayout sogDescriptorSetLayout_ = VK_NULL_HANDLE;
    VkSampler sogSampler_ = VK_NULL_HANDLE;
    VkPipelineLayout sogPipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline sogPipeline_ = VK_NULL_HANDLE;
    VkPipeline sogCompactPipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool sogDescriptorPool_ = VK_NULL_HANDLE;
    BufferResource quadCornerBuffer_;
    BufferResource scenePositions_;
    BufferResource sceneColors_;
    BufferResource sceneCovariance_;
    std::vector<SogChunkResource> sogChunks_;
    std::vector<SogDrawCommand> sogDrawCommands_;
    SceneMetadata sceneMetadata_;
    RenderState renderState_;
    int sceneCount_ = 0;
    int drawCount_ = 0;
    int lastVisibleChunkCount_ = -1;
    int lastSelectedChunkCount_ = -1;
    int lastSelectedSplatCount_ = -1;
    int sogSelectedSplatCount_ = 0;
    bool sogSceneActive_ = false;
    bool sogUseCompactDraw_ = false;
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zeaze_tianyinwallpaper_renderer_VulkanGaussianRenderer_nativeUploadSogScene(
        JNIEnv* env,
        jobject,
        jlong handle,
        jobjectArray meansL,
        jobjectArray meansU,
        jobjectArray scales,
        jobjectArray sh0,
        jobjectArray quats,
        jobjectArray scaleCodebook,
        jobjectArray sh0Codebook,
        jobjectArray meansMinMax,
        jintArray counts,
        jfloatArray chunkBounds,
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
           renderer->uploadSogScene(
                   env,
                   meansL,
                   meansU,
                   scales,
                   sh0,
                   quats,
                   scaleCodebook,
                   sh0Codebook,
                   meansMinMax,
                   counts,
                   chunkBounds,
                   metadata) ? JNI_TRUE : JNI_FALSE;
}
