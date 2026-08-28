#include <jni.h>
#include <android/log.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <string>

#define LOG_TAG "PixelTriggerShoulder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::mutex gMutex;
int gFdF7 = -1;
int gFdF8 = -1;
std::string gStatus = "native not initialized";

void setStatus(const std::string &value) {
    gStatus = value;
    LOGI("%s", value.c_str());
}

void closeDevice(int &fd) {
    if (fd >= 0) {
        ioctl(fd, UI_DEV_DESTROY);
        close(fd);
        fd = -1;
    }
}

int emitEvent(int fd, unsigned short type, unsigned short code, int value) {
    input_event ev{};
    ev.type = type;
    ev.code = code;
    ev.value = value;
    const ssize_t written = write(fd, &ev, sizeof(ev));
    if (written != static_cast<ssize_t>(sizeof(ev))) return -errno;
    return 0;
}

int createTgkDevice(const char *name, int keyCode, int &outFd) {
    const int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;

    auto fail = [&](int error) {
        close(fd);
        return error;
    };

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0) return fail(-errno);
    if (ioctl(fd, UI_SET_KEYBIT, keyCode) < 0) return fail(-errno);
    if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) return fail(-errno);
    if (ioctl(fd, UI_SET_ABSBIT, ABS_DISTANCE) < 0) return fail(-errno);

    uinput_user_dev device{};
    std::strncpy(device.name, name, UINPUT_MAX_NAME_SIZE - 1);
    device.id.bustype = BUS_VIRTUAL;
    device.id.vendor = 0x19d2;
    device.id.product = keyCode == KEY_F7 ? 0x0f07 : 0x0f08;
    device.id.version = 1;
    device.absmin[ABS_DISTANCE] = -1;
    device.absmax[ABS_DISTANCE] = 100;

    const ssize_t written = write(fd, &device, sizeof(device));
    if (written != static_cast<ssize_t>(sizeof(device))) return fail(-errno);
    if (ioctl(fd, UI_DEV_CREATE) < 0) return fail(-errno);

    outFd = fd;
    return 0;
}

int initLocked() {
    if (gFdF7 >= 0 && gFdF8 >= 0) {
        setStatus("uinput ready: R=KEY_F7, L=KEY_F8");
        return 0;
    }

    closeDevice(gFdF7);
    closeDevice(gFdF8);

    int rc = createTgkDevice("nubia_tgk_aw_sar0_ch0", KEY_F7, gFdF7);
    if (rc != 0) {
        setStatus("create R/F7 failed rc=" + std::to_string(rc) + " " + std::strerror(-rc));
        return rc;
    }

    rc = createTgkDevice("nubia_tgk_aw_sar1_ch0", KEY_F8, gFdF8);
    if (rc != 0) {
        closeDevice(gFdF7);
        setStatus("create L/F8 failed rc=" + std::to_string(rc) + " " + std::strerror(-rc));
        return rc;
    }

    usleep(300000);
    setStatus("uinput ready: R=KEY_F7, L=KEY_F8");
    return 0;
}

int fdForKey(int keyCode) {
    if (keyCode == KEY_F7) return gFdF7;
    if (keyCode == KEY_F8) return gFdF8;
    return -1;
}

int keyDownLocked(int keyCode) {
    int rc = initLocked();
    if (rc != 0) return rc;
    const int fd = fdForKey(keyCode);
    if (fd < 0) return -EINVAL;

    if ((rc = emitEvent(fd, EV_ABS, ABS_DISTANCE, 1)) != 0) return rc;
    if ((rc = emitEvent(fd, EV_KEY, keyCode, 1)) != 0) return rc;
    if ((rc = emitEvent(fd, EV_SYN, SYN_REPORT, 0)) != 0) return rc;
    return 0;
}

int keyUpLocked(int keyCode) {
    const int fd = fdForKey(keyCode);
    if (fd < 0) return -EINVAL;

    int rc;
    if ((rc = emitEvent(fd, EV_ABS, ABS_DISTANCE, 0)) != 0) return rc;
    if ((rc = emitEvent(fd, EV_KEY, keyCode, 0)) != 0) return rc;
    if ((rc = emitEvent(fd, EV_SYN, SYN_REPORT, 0)) != 0) return rc;
    return 0;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_pixeltrigger_app_input_ShoulderInputUserService_nativeInit(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    return initLocked();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pixeltrigger_app_input_ShoulderInputUserService_nativeKeyDown(JNIEnv *, jclass, jint keyCode) {
    std::lock_guard<std::mutex> lock(gMutex);
    const int rc = keyDownLocked(keyCode);
    if (rc != 0) setStatus("key down failed rc=" + std::to_string(rc) + " " + std::strerror(-rc));
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pixeltrigger_app_input_ShoulderInputUserService_nativeKeyUp(JNIEnv *, jclass, jint keyCode) {
    std::lock_guard<std::mutex> lock(gMutex);
    const int rc = keyUpLocked(keyCode);
    if (rc != 0) setStatus("key up failed rc=" + std::to_string(rc) + " " + std::strerror(-rc));
    return rc;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pixeltrigger_app_input_ShoulderInputUserService_nativeStatus(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    return env->NewStringUTF(gStatus.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_pixeltrigger_app_input_ShoulderInputUserService_nativeDestroy(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(gMutex);
    closeDevice(gFdF7);
    closeDevice(gFdF8);
    setStatus("uinput devices destroyed");
}
