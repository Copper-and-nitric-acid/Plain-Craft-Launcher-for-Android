cmake_minimum_required(VERSION 3.22.1)
project(pcla_surface C)
add_library(pcla_surface SHARED surface_bridge.c native_env.c)
# 只要 JNI 头，不链 libpojavexec：那个 so 必须由系统 dlopen 打开，
# 直接链进来会让同名符号在进程启动时就被绑定。
target_link_libraries(pcla_surface log)
