cmake_minimum_required(VERSION 3.22.1)
project(ula_graphics_compat LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../../../../vendor/androidx-graphics-path/main/cpp"
    "${CMAKE_CURRENT_BINARY_DIR}/graphics-path"
)
target_compile_options(androidx.graphics.path PRIVATE
    -fvisibility=hidden -fno-rtti -fno-exceptions
)
# Preserve RELRO protection while aligning its end to a 16 KB page. Upstream
# 1.1.0 only sets max-page-size; both flags are needed for the prebuilt issue.
target_link_options(androidx.graphics.path PRIVATE
    "-Wl,-z,max-page-size=16384,-z,common-page-size=16384,-z,relro,-z,now"
)
