/* One upward swipe through /dev/uinput.
 * adb shell input is denied INJECT_EVENTS on this device; shell can open uinput.
 *
 *   aarch64-linux-android26-clang -O2 -o vfrida_swipe tools/vfrida_swipe.c
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static void die(const char *what) {
    fprintf(stderr, "%s: %s\n", what, strerror(errno));
    exit(1);
}

static void set_bit(int fd, unsigned long request, int bit) {
    if (ioctl(fd, request, bit) < 0) {
        die("ioctl");
    }
}

static void emit(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event event;
    memset(&event, 0, sizeof(event));
    event.type = type;
    event.code = code;
    event.value = value;
    if (write(fd, &event, sizeof(event)) != (ssize_t) sizeof(event)) {
        die("write event");
    }
}

int main(int argc, char **argv) {
    int width = argc > 1 ? atoi(argv[1]) : 1200;
    int height = argc > 2 ? atoi(argv[2]) : 2608;
    if (width < 2) {
        width = 2;
    }
    if (height < 2) {
        height = 2;
    }

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        die("open /dev/uinput");
    }

    set_bit(fd, UI_SET_EVBIT, EV_SYN);
    set_bit(fd, UI_SET_EVBIT, EV_KEY);
    set_bit(fd, UI_SET_EVBIT, EV_ABS);
    set_bit(fd, UI_SET_KEYBIT, BTN_TOUCH);
    set_bit(fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);
    set_bit(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    set_bit(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    set_bit(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    set_bit(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    set_bit(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    set_bit(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    strncpy(dev.name, "vfrida", sizeof(dev.name) - 1);
    dev.id.bustype = BUS_VIRTUAL;
    dev.absmax[ABS_MT_SLOT] = 9;
    dev.absmax[ABS_MT_TOUCH_MAJOR] = 255;
    dev.absmax[ABS_MT_POSITION_X] = width - 1;
    dev.absmax[ABS_MT_POSITION_Y] = height - 1;
    dev.absmax[ABS_MT_TRACKING_ID] = 65535;
    if (write(fd, &dev, sizeof(dev)) != (ssize_t) sizeof(dev)) {
        die("write device");
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        die("UI_DEV_CREATE");
    }

    usleep(200000);

    int x = width / 2;
    int y0 = height * 8 / 10;
    int y1 = height * 3 / 10;
    int steps = 16;

    emit(fd, EV_ABS, ABS_MT_SLOT, 0);
    emit(fd, EV_ABS, ABS_MT_TRACKING_ID, 7);
    emit(fd, EV_ABS, ABS_MT_POSITION_X, x);
    emit(fd, EV_ABS, ABS_MT_POSITION_Y, y0);
    emit(fd, EV_ABS, ABS_MT_TOUCH_MAJOR, 8);
    emit(fd, EV_KEY, BTN_TOUCH, 1);
    emit(fd, EV_KEY, BTN_TOOL_FINGER, 1);
    emit(fd, EV_SYN, SYN_REPORT, 0);

    int i;
    for (i = 1; i <= steps; i++) {
        int y = y0 + (y1 - y0) * i / steps;
        emit(fd, EV_ABS, ABS_MT_POSITION_X, x);
        emit(fd, EV_ABS, ABS_MT_POSITION_Y, y);
        emit(fd, EV_SYN, SYN_REPORT, 0);
        usleep(12000);
    }

    emit(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    emit(fd, EV_KEY, BTN_TOUCH, 0);
    emit(fd, EV_KEY, BTN_TOOL_FINGER, 0);
    emit(fd, EV_SYN, SYN_REPORT, 0);

    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}
