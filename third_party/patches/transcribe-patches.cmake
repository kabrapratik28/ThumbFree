# Applies the patches in TRANSCRIBE_PATCH_DIR (third_party/patches), in name order, to the transcribe.cpp checkout in
# TRANSCRIBE_DIR, then checks that the checkout is exactly its pinned commit plus the whole series. The pinned commit is
# the superproject's gitlink for the checkout, or TRANSCRIBE_PINNED when given. Patches already applied are skipped, so
# every configure can include this. It lives beside the series so that every app's native build can include it (the
# Android app's does, and so do the Mac tools). Also runs on its own:
#   cmake -DTRANSCRIBE_DIR=<checkout> -DTRANSCRIBE_PATCH_DIR=<patches> [-DTRANSCRIBE_PINNED=<commit>] -P transcribe-patches.cmake
if(NOT DEFINED TRANSCRIBE_PATCHES)
    file(GLOB TRANSCRIBE_PATCHES ${TRANSCRIBE_PATCH_DIR}/*.patch)
endif()
if(NOT TRANSCRIBE_PATCHES)
    message(FATAL_ERROR "No patches in ${TRANSCRIBE_PATCH_DIR}: the engine would build unpatched.")
endif()
list(SORT TRANSCRIBE_PATCHES)
find_package(Git REQUIRED)
# Each fix in the messages below is a line of its own, since CMake rewraps the rest of a message. Its paths are
# absolute, so it works from any folder: Gradle runs in android/, the Mac tools anywhere.
# An uninitialized submodule is an empty directory inside this repository, where git would answer for the wrong tree.
execute_process(COMMAND ${GIT_EXECUTABLE} -C ${TRANSCRIBE_DIR} rev-parse --show-toplevel OUTPUT_VARIABLE top
                OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE top_rc ERROR_QUIET)
get_filename_component(checkout ${TRANSCRIBE_DIR} REALPATH)
if(NOT top_rc EQUAL 0 OR NOT top STREQUAL checkout)
    message(FATAL_ERROR "${TRANSCRIBE_DIR} is not a transcribe.cpp checkout. Fetch the submodule (from any folder of "
                        "the repository):\n  git submodule update --init")
endif()
string(CONCAT reset "Reset the submodule and build again (this discards local edits in the submodule):\n"
       "  git -C ${checkout} checkout -- .")
# The checkout must be the commit the superproject pins, not just any commit the series applies to.
if(NOT DEFINED TRANSCRIBE_PINNED)
    get_filename_component(parent ${checkout} DIRECTORY)
    execute_process(COMMAND ${GIT_EXECUTABLE} -C ${parent} rev-parse --show-toplevel OUTPUT_VARIABLE super
                    OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE super_rc ERROR_QUIET)
    set(link "")
    if(super_rc EQUAL 0)
        get_filename_component(super ${super} REALPATH)
        file(RELATIVE_PATH link_path ${super} ${checkout})
        execute_process(COMMAND ${GIT_EXECUTABLE} -C ${super} ls-files --stage -- ${link_path} OUTPUT_VARIABLE link
                        ERROR_QUIET)
    endif()
    if(NOT link MATCHES "^160000 ([0-9a-f]+) ")
        message(FATAL_ERROR "No superproject pins ${TRANSCRIBE_DIR}: pass -DTRANSCRIBE_PINNED=<commit>.")
    endif()
    set(TRANSCRIBE_PINNED ${CMAKE_MATCH_1})
    set(update "git -C ${super} submodule update -- ${link_path}")
else()
    set(update "git -C ${checkout} checkout ${TRANSCRIBE_PINNED}")
endif()
execute_process(COMMAND ${GIT_EXECUTABLE} -C ${TRANSCRIBE_DIR} rev-parse HEAD OUTPUT_VARIABLE head
                OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
if(NOT head STREQUAL TRANSCRIBE_PINNED)
    message(FATAL_ERROR "third_party/transcribe.cpp is at ${head}, not the pinned commit ${TRANSCRIBE_PINNED}. Check "
                        "out the pinned commit and build again:\n  ${update}")
endif()
# Debug and release configures may run at once; only one may patch. The lock lives in the submodule's git directory.
execute_process(COMMAND ${GIT_EXECUTABLE} -C ${TRANSCRIBE_DIR} rev-parse --absolute-git-dir OUTPUT_VARIABLE git_dir
                OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
file(LOCK ${git_dir}/thumbfree-patches.lock TIMEOUT 600)
# git apply checks each patch file against the untouched tree, so a series that edits one file twice goes to it as one
# input. The applied patches are the longest prefix of the series that reverse-applies.
set(series ${CMAKE_CURRENT_BINARY_DIR}/transcribe-patches.diff)
file(WRITE ${series} "")
set(applied 0)
set(i 0)
foreach(patch IN LISTS TRANSCRIBE_PATCHES)
    math(EXPR i "${i} + 1")
    file(READ ${patch} text)
    file(APPEND ${series} "${text}")
    execute_process(COMMAND ${GIT_EXECUTABLE} -C ${TRANSCRIBE_DIR} apply --reverse --check INPUT_FILE ${series}
                    RESULT_VARIABLE reverse_rc OUTPUT_QUIET ERROR_QUIET)
    if(reverse_rc EQUAL 0)
        set(applied ${i})
    endif()
endforeach()
list(LENGTH TRANSCRIBE_PATCHES count)
if(applied LESS count)
    list(SUBLIST TRANSCRIBE_PATCHES ${applied} -1 pending)
    file(WRITE ${series} "")
    foreach(patch IN LISTS pending)
        file(READ ${patch} text)
        file(APPEND ${series} "${text}")
    endforeach()
    execute_process(COMMAND ${GIT_EXECUTABLE} -C ${TRANSCRIBE_DIR} apply --check INPUT_FILE ${series}
                    RESULT_VARIABLE check_rc ERROR_VARIABLE check_err)
    if(NOT check_rc EQUAL 0)
        message(FATAL_ERROR "third_party/patches do not apply to third_party/transcribe.cpp:\n${check_err}${reset}")
    endif()
    execute_process(COMMAND ${GIT_EXECUTABLE} -C ${TRANSCRIBE_DIR} apply INPUT_FILE ${series} COMMAND_ERROR_IS_FATAL ANY)
    message(STATUS "Applied to transcribe.cpp: ${pending}")
endif()
# The files must now be the pinned commit plus exactly this series: a patch removed from the series while applied, or a
# local edit, would otherwise build a different engine without a word. Compared through a scratch index.
file(WRITE ${series} "")
foreach(patch IN LISTS TRANSCRIBE_PATCHES)
    file(READ ${patch} text)
    file(APPEND ${series} "${text}")
endforeach()
# A configure killed during read-tree leaves git's lock beside the scratch index; safe to drop under the patch lock.
file(REMOVE ${CMAKE_CURRENT_BINARY_DIR}/transcribe-patches.index.lock)
set(ENV{GIT_INDEX_FILE} ${CMAKE_CURRENT_BINARY_DIR}/transcribe-patches.index)
execute_process(COMMAND ${GIT_EXECUTABLE} -C ${TRANSCRIBE_DIR} read-tree ${TRANSCRIBE_PINNED} COMMAND_ERROR_IS_FATAL ANY)
execute_process(COMMAND ${GIT_EXECUTABLE} -C ${TRANSCRIBE_DIR} apply --cached INPUT_FILE ${series}
                RESULT_VARIABLE cached_rc ERROR_QUIET)
execute_process(COMMAND ${GIT_EXECUTABLE} -C ${TRANSCRIBE_DIR} diff --quiet RESULT_VARIABLE differs)
unset(ENV{GIT_INDEX_FILE})
if(NOT cached_rc EQUAL 0 OR NOT differs EQUAL 0)
    message(FATAL_ERROR "third_party/transcribe.cpp is not its pinned commit plus third_party/patches (a patch removed "
                        "from the series, or a local edit). ${reset}")
endif()
file(LOCK ${git_dir}/thumbfree-patches.lock RELEASE)
