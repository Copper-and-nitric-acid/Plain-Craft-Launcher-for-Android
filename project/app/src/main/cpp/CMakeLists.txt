cmake_minimum_required(VERSION 3.22.1)
project(pcla_surface C)
add_library(pcla_surface SHARED surface_bridge.c native_env.c)
# JNI headers only; do not link libpojavexec — that .so must be opened by the system dlopen,
# linking it directly would bind same-named symbols at process start.
target_link_libraries(pcla_surface log)
