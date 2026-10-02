cmake_minimum_required(VERSION 3.22.1)
project("ptyjni")

add_library(ptyjni SHARED ptyjni.c)
target_compile_features(ptyjni PRIVATE c_std_11)
target_link_options(ptyjni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(ptyjni log)
