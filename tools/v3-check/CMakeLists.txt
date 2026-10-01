# The app's Parakeet engine as a macOS dylib for tools/v3-check.py, which loads it with ctypes: the Mac runs no newly
# built executable. TRANSCRIBE_DIR picks the transcribe.cpp checkout (default the submodule). With PATCHED (the default)
# the app's patch series goes on it first, as android/app/src/main/cpp/CMakeLists.txt does; without, it builds the
# checkout as it is, for the unpatched side of the comparison. CPU only, like the app. Same build as
# android/tools/vad-parity.
cmake_minimum_required(VERSION 3.22)
project(v3_check C CXX)

set(ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../..)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_FLAGS_RELEASE "-O3")
set(CMAKE_CXX_FLAGS_RELEASE "-O3")
option(PATCHED "apply third_party/patches to TRANSCRIBE_DIR" ON)
if(NOT DEFINED TRANSCRIBE_DIR)
    set(TRANSCRIBE_DIR ${ROOT}/third_party/transcribe.cpp)
endif()
if(PATCHED)
    set(TRANSCRIBE_PATCH_DIR ${ROOT}/third_party/patches)
    include(${ROOT}/third_party/patches/transcribe-patches.cmake)
endif()

set(TRANSCRIBE_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_METAL OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_USE_SYSTEM_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory(${TRANSCRIBE_DIR} transcribe EXCLUDE_FROM_ALL)
target_include_directories(transcribe PUBLIC ${TRANSCRIBE_DIR}/include)

add_library(v3_check SHARED asr.cpp)
target_compile_definitions(v3_check PRIVATE $<$<BOOL:${PATCHED}>:V3_PATCHED>)
target_link_libraries(v3_check PRIVATE transcribe ggml)
