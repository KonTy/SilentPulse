cmake_minimum_required(VERSION 3.22.1)

project("silentpulse_whisper")

set(CMAKE_CXX_STANDARD 17)

# ── Paths ─────────────────────────────────────────────────────────────────────
# This CMakeLists.txt lives at:
#   presentation/src/main/cpp/whisper/CMakeLists.txt
# The whisper_cpp submodule lives at the project root:
#   <project_root>/whisper_cpp/
# Count: cpp/whisper -> cpp -> main -> src -> presentation -> root
set(WHISPER_ROOT "${CMAKE_SOURCE_DIR}/../../../../../whisper_cpp")

# ── Source files ──────────────────────────────────────────────────────────────
set(SOURCE_FILES
    "${WHISPER_ROOT}/src/whisper.cpp"
    "${CMAKE_SOURCE_DIR}/jni.c"
)

# ── Include ggml via FetchContent (bundled copy in the submodule) ─────────────
include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR "${WHISPER_ROOT}/ggml")
FetchContent_MakeAvailable(ggml)

# ── Shared library ────────────────────────────────────────────────────────────
add_library(whisper_silentpulse SHARED ${SOURCE_FILES})

find_library(LOG_LIB log)

target_compile_definitions(whisper_silentpulse PUBLIC
    GGML_USE_CPU
    # Mirrors how whisper.cpp's own CMakeLists.txt exposes the project version
    WHISPER_VERSION="1.8.4"
)

if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisper_silentpulse PRIVATE -O3)
    target_compile_options(whisper_silentpulse PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -ffunction-sections
        -fdata-sections
    )
    target_link_options(whisper_silentpulse PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -flto
    )
endif()

# ARM NEON optimizations
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(whisper_silentpulse PRIVATE -march=armv8.2-a+fp16)
    target_compile_options(ggml PRIVATE -march=armv8.2-a+fp16)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(whisper_silentpulse PRIVATE -mfpu=neon-vfpv4)
    target_compile_options(ggml PRIVATE -mfpu=neon-vfpv4)
endif()

target_link_libraries(whisper_silentpulse ${LOG_LIB} android ggml)

# ── Include directories ───────────────────────────────────────────────────────
target_include_directories(whisper_silentpulse PRIVATE
    "${WHISPER_ROOT}"
    "${WHISPER_ROOT}/src"
    "${WHISPER_ROOT}/include"
    "${WHISPER_ROOT}/ggml/include"
    "${WHISPER_ROOT}/ggml/src"
    "${WHISPER_ROOT}/ggml/src/ggml-cpu"
)
