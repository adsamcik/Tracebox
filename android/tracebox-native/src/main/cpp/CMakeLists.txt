cmake_minimum_required(VERSION 4.1.2)
project(tracebox_native LANGUAGES C CXX)

add_library(tracebox_native SHARED
    tracebox_native.cpp
    ../../../../../native/emergency/tracebox_emergency.c
)

target_include_directories(tracebox_native PRIVATE
    ../../../../../native/include
)
target_compile_features(tracebox_native PRIVATE c_std_17 cxx_std_20)
target_link_libraries(tracebox_native PRIVATE log)

target_link_options(tracebox_native PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=4096"
)
