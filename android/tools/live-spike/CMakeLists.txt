# Live preview prototype: the app's Parakeet engine (transcribe.cpp with the app's patch series, as
# android/app/src/main/cpp/CMakeLists.txt builds it) as a macOS dylib for android/tools/live-spike.py, which loads it
# with ctypes: the Mac runs no newly built executable. CPU only, like the app. Same build as android/tools/vad-parity.
cmake_minimum_required(VERSION 3.22)
project(live_spike C CXX)

set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../..)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_FLAGS_RELEASE "-O3")
set(CMAKE_CXX_FLAGS_RELEASE "-O3")
# -DTRANSCRIBE_DIR, -DTRANSCRIBE_PATCH_DIR (and -DTRANSCRIBE_PINNED) build a scratch checkout and series instead.
if(NOT DEFINED TRANSCRIBE_DIR)
    set(TRANSCRIBE_DIR ${ROOT}/third_party/transcribe.cpp)
endif()
if(NOT DEFINED TRANSCRIBE_PATCH_DIR)
    set(TRANSCRIBE_PATCH_DIR ${ROOT}/third_party/patches)
endif()
include(${ROOT}/third_party/patches/transcribe-patches.cmake)

set(TRANSCRIBE_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_METAL OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_USE_SYSTEM_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory(${TRANSCRIBE_DIR} transcribe EXCLUDE_FROM_ALL)
target_include_directories(transcribe PUBLIC ${TRANSCRIBE_DIR}/include)

# The app's Silero VAD too (android/app/src/main/cpp/vad, as android/tools/vad-parity builds it), for the gated-stream
# runs.
add_library(live_spike SHARED live.cpp ${ROOT}/android/app/src/main/cpp/vad/silero_vad.cpp)
target_link_libraries(live_spike PRIVATE transcribe ggml)
# The engine's own headers too, for live_pcm (the stream's retained audio, read from its session), in its C++.
target_include_directories(live_spike PRIVATE ${TRANSCRIBE_DIR}/src)
target_compile_features(live_spike PRIVATE cxx_std_17)
