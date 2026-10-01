cmake_minimum_required(VERSION 3.22.1)
project(engine_jni LANGUAGES CXX)

set(TRANSCRIBE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/transcribe.cpp)

# AGP builds the debug variant as CMAKE_BUILD_TYPE=Debug with no -O flag, and unoptimized ggml
# is many times slower. Keep -g and asserts, but optimize as a Release build does.
set(CMAKE_C_FLAGS_DEBUG "-O3")
set(CMAKE_CXX_FLAGS_DEBUG "-O3")
# Release builds (RelWithDebInfo) take the same flags, so they ship the engine the tests and benchmarks ran. Their
# default -O2 crashes NDK 30's clang on ggml's SVE2 and SME repack kernels.
set(CMAKE_C_FLAGS_RELWITHDEBINFO "-O3")
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3")

set(TRANSCRIBE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_USE_SYSTEM_BLAS OFF CACHE BOOL "" FORCE)
# Never tune for the build host: host-tuned flags crash on other CPUs.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# OpenMP gains nothing over ggml's own threadpool on the emulator and would ship libomp.so.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# ggml's RPC backend is the one socket client in the engine's tree: never built (INTERNET is for model downloads only).
set(GGML_RPC OFF CACHE BOOL "" FORCE)
# Runtime CPU dispatch: ggml builds one CPU backend module per ARM feature level, and
# transcribe_init_backends() loads the best one this CPU supports (ggml cpu-feats.cpp scores).
set(TRANSCRIBE_BUILD_SHARED ON CACHE BOOL "" FORCE)
set(TRANSCRIBE_GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

# Our engine patches (docs/decisions/native-build.md), applied to the pinned submodule's files before it is added. A new
# or changed patch reruns CMake.
set(TRANSCRIBE_PATCH_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/patches)
file(GLOB TRANSCRIBE_PATCHES CONFIGURE_DEPENDS ${TRANSCRIBE_PATCH_DIR}/*.patch)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${TRANSCRIBE_PATCHES})
include(${TRANSCRIBE_PATCH_DIR}/transcribe-patches.cmake)
# The files the patches touch rerun CMake too, so a reset submodule is patched again rather than built unpatched.
foreach(patch IN LISTS TRANSCRIBE_PATCHES)
    file(STRINGS ${patch} targets REGEX "^\\+\\+\\+ b/")
    foreach(target IN LISTS targets)
        string(SUBSTRING ${target} 6 -1 path)
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${TRANSCRIBE_DIR}/${path})
    endforeach()
endforeach()

add_subdirectory(${TRANSCRIBE_DIR} transcribe EXCLUDE_FROM_ALL)
# transcribe.cpp sets its public include dir from CMAKE_SOURCE_DIR, which is this directory when it is a subproject.
target_include_directories(transcribe PUBLIC ${TRANSCRIBE_DIR}/include)

# Silero VAD (vad/: vendored from whisper.cpp, MIT) runs on transcribe.cpp's ggml: one libggml in the APK.
add_library(engine_jni SHARED engine_jni.cpp vad/silero_vad.cpp)
target_link_libraries(engine_jni PRIVATE transcribe ggml log android)
