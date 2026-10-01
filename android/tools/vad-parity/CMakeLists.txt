# The app's Silero VAD (android/app/src/main/cpp/vad) and its Parakeet engine (transcribe.cpp with the app's patch
# series, as android/app/src/main/cpp/CMakeLists.txt builds it) as one macOS dylib for android/tools/vad-parity.py,
# which loads it with ctypes: the Mac runs no newly built executable. CPU only, like the app.
cmake_minimum_required(VERSION 3.22)
project(vad_parity C CXX)

set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../..)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# As the app builds (android/app/src/main/cpp/CMakeLists.txt): optimized, with asserts.
set(CMAKE_C_FLAGS_RELEASE "-O3")
set(CMAKE_CXX_FLAGS_RELEASE "-O3")
set(TRANSCRIBE_DIR ${ROOT}/third_party/transcribe.cpp)
set(TRANSCRIBE_PATCH_DIR ${ROOT}/third_party/patches)
include(${ROOT}/third_party/patches/transcribe-patches.cmake)

set(TRANSCRIBE_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_METAL OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_USE_SYSTEM_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory(${TRANSCRIBE_DIR} transcribe EXCLUDE_FROM_ALL)
target_include_directories(transcribe PUBLIC ${TRANSCRIBE_DIR}/include)

add_library(vad_parity SHARED ${ROOT}/android/app/src/main/cpp/vad/silero_vad.cpp asr.cpp)
target_link_libraries(vad_parity PRIVATE transcribe ggml)
