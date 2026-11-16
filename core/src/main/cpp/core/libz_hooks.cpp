#include "libz_hooks.h"

#include "gum_engine.h"
#include "log.h"
#include "b_native.h"
#include "xdl.h"

#include <zlib.h>

#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>

static const char *kTargetPackage = "com.xingin.xhs";
static const char *kMarker = "x98";

static int (*orig_deflate)(z_streamp strm, int flush) = nullptr;
static char g_dump_dir[1024] = {0};
static bool g_is_xhs = false;

static char *find_marker(const char *data, const char *marker, size_t len) {
    size_t marker_len = strlen(marker);
    if (marker_len == 0) {
        return const_cast<char *>(data);
    }
    while (len >= marker_len) {
        if (memcmp(data, marker, marker_len) == 0) {
            return const_cast<char *>(data);
        }
        data++;
        len--;
    }
    return nullptr;
}

static void save_data_to_file(const Bytef *data, uInt len) {
    if (g_dump_dir[0] == '\0') {
        ALOGE("libz_hooks: dump dir is empty, skip dump");
        return;
    }
    struct timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    char filename[1280];
    snprintf(filename, sizeof(filename), "%s/xhs_ddd%ld-%ld", g_dump_dir,
             static_cast<long>(ts.tv_sec), static_cast<long>(ts.tv_nsec));
    FILE *fp = fopen(filename, "wb");
    if (fp == nullptr) {
        ALOGE("libz_hooks: Failed to open %s", filename);
        return;
    }
    fwrite(data, 1, len, fp);
    fclose(fp);
    ALOGD("libz_hooks: Saved %u bytes to %s", len, filename);
}

static int new_deflate(z_streamp strm, int flush) {
    if (g_is_xhs && strm != nullptr && strm->next_in != nullptr && strm->avail_in > 0) {
        if (find_marker(reinterpret_cast<const char *>(strm->next_in), kMarker, strm->avail_in)
                != nullptr) {
            save_data_to_file(strm->next_in, strm->avail_in);
        }
    }
    if (orig_deflate == nullptr) {
        return Z_STREAM_ERROR;
    }
    return orig_deflate(strm, flush);
}

void libz_hooks::init() {
    std::string package_name = BNative::appPackageName();
    bool dump_enabled = BNative::isXhsFingerprintDumpEnabled();
    g_is_xhs = dump_enabled && package_name == kTargetPackage;
    std::string dump_dir = BNative::fingerprintDumpDir();
    if (!dump_dir.empty()) {
        strncpy(g_dump_dir, dump_dir.c_str(), sizeof(g_dump_dir) - 1);
        g_dump_dir[sizeof(g_dump_dir) - 1] = '\0';
    } else {
        g_dump_dir[0] = '\0';
    }

    if (!g_is_xhs) {
        ALOGD("libz_hooks: skip libz hook, package=%s dump=%d",
              package_name.empty() ? "(empty)" : package_name.c_str(), dump_enabled);
        return;
    }

    void *handle = xdl_open("libz.so", XDL_DEFAULT);
    if (handle == nullptr) {
        ALOGE("libz_hooks: Failed to open libz.so");
        return;
    }
    void *deflate_addr = xdl_sym(handle, "deflate", nullptr);
    if (deflate_addr == nullptr) {
        ALOGE("libz_hooks: Failed to find deflate");
        xdl_close(handle);
        return;
    }
    if (GumEngine::replace(deflate_addr, reinterpret_cast<void *>(new_deflate),
                           reinterpret_cast<void **>(&orig_deflate)) != 0) {
        ALOGE("libz_hooks: Failed to hook deflate");
        xdl_close(handle);
        return;
    }
    xdl_close(handle);
    ALOGD("libz_hooks: deflate hooked, dump dir=%s", g_dump_dir);
}
