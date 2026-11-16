# lppi/sandhook native code is built via ndk-build (core/src/main/cpp/lppi/Android.mk)
# and statically linked into libb. Do not build this directory with CMake.
cmake_minimum_required(VERSION 3.22.1)
message(FATAL_ERROR "lppi is statically linked into libb; use core/src/main/cpp/Android.mk")
