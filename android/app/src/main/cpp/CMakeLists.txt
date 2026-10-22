cmake_minimum_required(VERSION 3.22.1)

# llama.cpp is much too slow unoptimized, so always build this library in Release,
# even for debug APKs.
set(CMAKE_BUILD_TYPE "Release" CACHE STRING "" FORCE)

project("lilyai_llama" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Build only the llama library, as static code that gets linked into our one .so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# No -march=native (it breaks cross compiling). Target ARMv8.2 with dot-product
# instructions, which nearly all phones from 2018 onward support, for much faster
# 4-bit matrix math than plain ARMv8.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b10795
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama)

add_library(lilyai_llama SHARED llama_bridge.cpp)
target_link_libraries(lilyai_llama PRIVATE llama log android)
