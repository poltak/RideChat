cmake_minimum_required(VERSION 3.22.1)

project(ridechat_opus_jni LANGUAGES C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Keep the upstream source in the native build cache. The archive is verified
# before extraction so the repository does not contain an opaque prebuilt
# library or an unpinned copy of Opus.
set(OPUS_VERSION "1.5.2")
set(OPUS_ARCHIVE_URL "https://github.com/xiph/opus/archive/refs/tags/v${OPUS_VERSION}.tar.gz")
set(OPUS_ARCHIVE_SHA256 "9480e329e989f70d69886ded470c7f8cfe6c0667cc4196d4837ac9e668fb7404")
set(OPUS_DOWNLOAD_DIR "${CMAKE_BINARY_DIR}/opus-download")
set(OPUS_ARCHIVE "${OPUS_DOWNLOAD_DIR}/opus-${OPUS_VERSION}.tar.gz")
set(OPUS_SOURCE_DIR "${OPUS_DOWNLOAD_DIR}/opus-${OPUS_VERSION}")

file(MAKE_DIRECTORY "${OPUS_DOWNLOAD_DIR}")
if(NOT EXISTS "${OPUS_SOURCE_DIR}/CMakeLists.txt")
    file(DOWNLOAD
        "${OPUS_ARCHIVE_URL}"
        "${OPUS_ARCHIVE}"
        EXPECTED_HASH "SHA256=${OPUS_ARCHIVE_SHA256}"
        TLS_VERIFY ON
        STATUS OPUS_DOWNLOAD_STATUS
    )
    list(GET OPUS_DOWNLOAD_STATUS 0 OPUS_DOWNLOAD_CODE)
    if(NOT OPUS_DOWNLOAD_CODE EQUAL 0)
        message(FATAL_ERROR "Unable to download verified Opus ${OPUS_VERSION}: ${OPUS_DOWNLOAD_STATUS}")
    endif()
    file(ARCHIVE_EXTRACT
        INPUT "${OPUS_ARCHIVE}"
        DESTINATION "${OPUS_DOWNLOAD_DIR}"
    )
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_FRAMEWORK OFF CACHE BOOL "" FORCE)
set(OPUS_FIXED_POINT OFF CACHE BOOL "" FORCE)
set(OPUS_ENABLE_FLOAT_API ON CACHE BOOL "" FORCE)

add_subdirectory(
    "${OPUS_SOURCE_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/opus-build"
    EXCLUDE_FROM_ALL
)

add_library(opus_jni SHARED opus_jni.cpp)
target_compile_features(opus_jni PRIVATE cxx_std_17)
target_compile_options(opus_jni PRIVATE -fno-exceptions -fno-rtti)
target_link_options(
    opus_jni
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
target_include_directories(opus_jni PRIVATE "${OPUS_SOURCE_DIR}/include")
target_link_libraries(opus_jni PRIVATE opus log)
