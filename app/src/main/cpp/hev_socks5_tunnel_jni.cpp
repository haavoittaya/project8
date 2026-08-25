#include <jni.h>

extern "C" {
#include "hev-main.h"
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_whiteknuckle_HevSocks5TunnelBridge_nativeRunTunnel(
        JNIEnv *env,
        jobject /* thiz */,
        jint tun_fd,
        jstring config_path
) {
    if (config_path == nullptr || tun_fd < 0) {
        return -2;
    }

    const char *config_path_chars =
            env->GetStringUTFChars(config_path, nullptr);

    if (config_path_chars == nullptr) {
        return -3;
    }

    const int rc = hev_socks5_tunnel_main_from_file(
            config_path_chars,
            static_cast<int>(tun_fd)
    );

    env->ReleaseStringUTFChars(config_path, config_path_chars);

    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_whiteknuckle_HevSocks5TunnelBridge_nativeStopTunnel(
        JNIEnv * /* env */,
jobject /* thiz */
) {
hev_socks5_tunnel_quit();
}