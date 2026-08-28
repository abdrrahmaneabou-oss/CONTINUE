cmake_minimum_required(VERSION 3.22.1)
project(pixeltrigger_shoulder LANGUAGES CXX)

add_library(pixeltrigger_shoulder SHARED shoulder_uinput.cpp)
target_compile_features(pixeltrigger_shoulder PRIVATE cxx_std_17)
target_link_libraries(pixeltrigger_shoulder log)
