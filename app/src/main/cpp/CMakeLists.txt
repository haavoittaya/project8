cmake_minimum_required(VERSION 3.22.1)

project(whiteknuckle)

add_library(
    hev_socks5_tunnel
    SHARED
    IMPORTED
)

set_target_properties(
    hev_socks5_tunnel
    PROPERTIES
    IMPORTED_LOCATION
        "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libhev-socks5-tunnel.so"
)

add_library(
    hev_socks5_tunnel_jni
    SHARED
    hev_socks5_tunnel_jni.cpp
)

target_include_directories(
    hev_socks5_tunnel_jni
    PRIVATE
    "${CMAKE_SOURCE_DIR}/../../main/hev/src"
)

target_link_libraries(
    hev_socks5_tunnel_jni
    hev_socks5_tunnel
    log
)