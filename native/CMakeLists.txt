cmake_minimum_required(VERSION 4.1.2)
project(tracebox_phase0 LANGUAGES C CXX)

include(CTest)

add_library(tracebox_emergency STATIC
    emergency/tracebox_emergency.c
)
target_include_directories(tracebox_emergency PUBLIC include)
target_compile_features(tracebox_emergency PUBLIC c_std_17)

add_executable(tracebox_emergency_test
    tests/tracebox_emergency_test.c
)
target_link_libraries(tracebox_emergency_test PRIVATE tracebox_emergency)

add_test(NAME tracebox_emergency_test COMMAND tracebox_emergency_test)
