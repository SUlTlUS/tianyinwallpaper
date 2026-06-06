cmake_minimum_required(VERSION 3.22.1)

project(tianyin_gaussian_vulkan LANGUAGES CXX)

add_library(tianyin_gaussian_vulkan SHARED
    vulkan_gaussian_jni.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(vulkan-lib vulkan)

target_link_libraries(tianyin_gaussian_vulkan
    ${log-lib}
    ${android-lib}
    ${vulkan-lib}
)
