#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OPUS_VERSION="1.5.2"
OPUS_ARCHIVE_URL="https://github.com/xiph/opus/archive/refs/tags/v${OPUS_VERSION}.tar.gz"
OPUS_ARCHIVE_SHA256="9480e329e989f70d69886ded470c7f8cfe6c0667cc4196d4837ac9e668fb7404"
GRADLE_USER_HOME_DIR="${GRADLE_USER_HOME:-$(cd ~ && pwd)/.gradle}"
ANDROID_SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ "$(uname -s)" == "Darwin" ]]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    fi
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
    echo "Set JAVA_HOME to a JDK 17 installation." >&2
    exit 1
fi
if [[ -z "${ANDROID_SDK_DIR}" ]]; then
    echo "Set ANDROID_HOME or ANDROID_SDK_ROOT for the Gradle wrapper compile step." >&2
    exit 1
fi
if ! command -v cmake >/dev/null 2>&1; then
    echo "cmake is required to build the host JNI library." >&2
    exit 1
fi
if ! command -v curl >/dev/null 2>&1 || ! command -v shasum >/dev/null 2>&1; then
    echo "curl and shasum are required to fetch and verify Opus." >&2
    exit 1
fi

TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/ridechat-opus-host.XXXXXX")"
cleanup() {
    rm -rf "${TEMP_ROOT}"
}
trap cleanup EXIT

OPUS_ARCHIVE="${TEMP_ROOT}/opus-${OPUS_VERSION}.tar.gz"
echo "Downloading and verifying Opus ${OPUS_VERSION}..."
curl --fail --silent --show-error --location --retry 3 "${OPUS_ARCHIVE_URL}" --output "${OPUS_ARCHIVE}"
printf '%s  %s\n' "${OPUS_ARCHIVE_SHA256}" "${OPUS_ARCHIVE}" | shasum -a 256 --check --status
tar -xzf "${OPUS_ARCHIVE}" -C "${TEMP_ROOT}"
OPUS_SOURCE_DIR="${TEMP_ROOT}/opus-${OPUS_VERSION}"

case "$(uname -s)" in
    Darwin) JNI_OS_INCLUDE="darwin" ;;
    Linux) JNI_OS_INCLUDE="linux" ;;
    *) echo "Unsupported host OS: $(uname -s)" >&2; exit 1 ;;
esac

cat > "${TEMP_ROOT}/CMakeLists.txt" <<EOF
cmake_minimum_required(VERSION 3.22.1)
project(ridechat_opus_host LANGUAGES C CXX)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_FRAMEWORK OFF CACHE BOOL "" FORCE)
set(OPUS_FIXED_POINT OFF CACHE BOOL "" FORCE)
set(OPUS_ENABLE_FLOAT_API ON CACHE BOOL "" FORCE)

add_subdirectory("${OPUS_SOURCE_DIR}" "\${CMAKE_CURRENT_BINARY_DIR}/opus-build" EXCLUDE_FROM_ALL)

add_library(opus_jni SHARED "${PROJECT_ROOT}/codec-opus/src/main/cpp/opus_jni.cpp")
target_compile_features(opus_jni PRIVATE cxx_std_17)
target_compile_options(opus_jni PRIVATE -fno-exceptions -fno-rtti)
target_include_directories(opus_jni PRIVATE
    "${JAVA_HOME}/include"
    "${JAVA_HOME}/include/${JNI_OS_INCLUDE}"
    "${OPUS_SOURCE_DIR}/include"
)
target_link_libraries(opus_jni PRIVATE opus)
EOF

cmake -S "${TEMP_ROOT}" -B "${TEMP_ROOT}/build" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "${TEMP_ROOT}/build" --target opus_jni --parallel >/dev/null

echo "Compiling the Kotlin wrappers and host smoke test..."
JAVA_HOME="${JAVA_HOME}" ANDROID_HOME="${ANDROID_SDK_DIR}" \
    "${PROJECT_ROOT}/gradlew" :codec-opus:compileDebugKotlin :codec-opus:compileDebugUnitTestKotlin --no-daemon >/dev/null

KOTLIN_STDLIB_JAR="$(find "${GRADLE_USER_HOME_DIR}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" \
    -type f -name '*.jar' 2>/dev/null | sort | tail -n 1)"
if [[ -z "${KOTLIN_STDLIB_JAR}" ]]; then
    echo "Could not find the Kotlin standard library in ${GRADLE_USER_HOME_DIR}." >&2
    exit 1
fi

TEST_CLASSES="${PROJECT_ROOT}/codec-opus/build/tmp/kotlin-classes/debugUnitTest"
WRAPPER_CLASSES="${PROJECT_ROOT}/codec-opus/build/tmp/kotlin-classes/debug"
if [[ ! -d "${TEST_CLASSES}" || ! -d "${WRAPPER_CLASSES}" ]]; then
    echo "Gradle did not produce the expected Kotlin class directories." >&2
    exit 1
fi

echo "Running the JNI codec smoke test..."
JAVA_HOME="${JAVA_HOME}" "${JAVA_HOME}/bin/java" \
    -Djava.library.path="${TEMP_ROOT}/build" \
    -cp "${TEST_CLASSES}:${WRAPPER_CLASSES}:${KOTLIN_STDLIB_JAR}" \
    com.ridechat.codec.OpusHostSmokeTest
