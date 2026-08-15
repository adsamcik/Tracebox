cmake_minimum_required(VERSION 4.1.2)
project(tracebox_phase0 LANGUAGES C CXX)

include(CTest)
find_package(Threads REQUIRED)

add_library(tracebox_emergency STATIC
    emergency/tracebox_emergency.c
    client/tracebox_abi.c
    generated/tracebox_generated_events.c
)
target_include_directories(tracebox_emergency PUBLIC include)
target_compile_features(tracebox_emergency PUBLIC c_std_17)

add_executable(tracebox_emergency_test
    tests/tracebox_emergency_test.c
)
target_link_libraries(tracebox_emergency_test PRIVATE tracebox_emergency)

add_test(NAME tracebox_emergency_test COMMAND tracebox_emergency_test)

add_executable(tracebox_abi_test
    tests/tracebox_abi_test.c
)
target_link_libraries(tracebox_abi_test PRIVATE tracebox_emergency)
add_test(NAME tracebox_abi_test COMMAND tracebox_abi_test)

add_executable(tracebox_generated_events_link_test
    tests/tracebox_generated_events_link_test.c
)
target_link_libraries(tracebox_generated_events_link_test PRIVATE tracebox_emergency)
add_test(NAME tracebox_generated_events_link_test COMMAND tracebox_generated_events_link_test)

add_executable(tracebox_emergency_initialization_test
    tests/tracebox_emergency_initialization_test.cc
)
target_link_libraries(tracebox_emergency_initialization_test PRIVATE
    tracebox_emergency
    Threads::Threads
)
target_compile_features(tracebox_emergency_initialization_test PRIVATE cxx_std_17)

add_test(
    NAME tracebox_emergency_initialization_test
    COMMAND tracebox_emergency_initialization_test
        "${CMAKE_CURRENT_BINARY_DIR}/tracebox-emergency-initialization-slot.bin"
)

add_executable(tracebox_policy_transition_test
    tests/tracebox_policy_transition_test.cc
)
target_include_directories(tracebox_policy_transition_test PRIVATE include)
target_compile_features(tracebox_policy_transition_test PRIVATE cxx_std_17)
add_test(
    NAME tracebox_policy_transition_test
    COMMAND tracebox_policy_transition_test
)

add_executable(tracebox_handler_socket_cleanup_test
    tests/tracebox_handler_socket_cleanup_test.cc
)
target_include_directories(tracebox_handler_socket_cleanup_test PRIVATE include)
target_compile_features(tracebox_handler_socket_cleanup_test PRIVATE cxx_std_17)
add_test(
    NAME tracebox_handler_socket_cleanup_test
    COMMAND tracebox_handler_socket_cleanup_test
)

add_executable(tracebox_client_registration_test
    tests/tracebox_client_registration_test.cc
)
target_include_directories(tracebox_client_registration_test PRIVATE include)
target_compile_features(tracebox_client_registration_test PRIVATE cxx_std_17)
add_test(
    NAME tracebox_client_registration_test
    COMMAND tracebox_client_registration_test
)

add_executable(tracebox_handler_lifecycle_drain_test
    tests/tracebox_handler_lifecycle_drain_test.cc
)
target_include_directories(tracebox_handler_lifecycle_drain_test PRIVATE include)
target_link_libraries(tracebox_handler_lifecycle_drain_test PRIVATE Threads::Threads)
target_compile_features(tracebox_handler_lifecycle_drain_test PRIVATE cxx_std_17)
add_test(
    NAME tracebox_handler_lifecycle_drain_test
    COMMAND tracebox_handler_lifecycle_drain_test
)

add_executable(tracebox_client_lifecycle_journal_test
    tests/tracebox_client_lifecycle_journal_test.cc
)
target_include_directories(tracebox_client_lifecycle_journal_test PRIVATE include)
target_compile_features(tracebox_client_lifecycle_journal_test PRIVATE cxx_std_17)
add_test(
    NAME tracebox_client_lifecycle_journal_test
    COMMAND tracebox_client_lifecycle_journal_test
)

add_executable(tracebox_signal_stack_thread_test
    tests/tracebox_signal_stack_thread_test.cc
)
target_include_directories(tracebox_signal_stack_thread_test PRIVATE include)
target_link_libraries(tracebox_signal_stack_thread_test PRIVATE Threads::Threads)
target_compile_features(tracebox_signal_stack_thread_test PRIVATE cxx_std_17)
add_test(
    NAME tracebox_signal_stack_thread_test
    COMMAND tracebox_signal_stack_thread_test
)

add_executable(tracebox_signal_handler_installation_test
    tests/tracebox_signal_handler_installation_test.cc
)
target_include_directories(tracebox_signal_handler_installation_test PRIVATE include)
target_link_libraries(tracebox_signal_handler_installation_test PRIVATE Threads::Threads)
target_compile_features(tracebox_signal_handler_installation_test PRIVATE cxx_std_17)
add_test(
    NAME tracebox_signal_handler_installation_test
    COMMAND tracebox_signal_handler_installation_test
)
