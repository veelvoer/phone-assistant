#!/usr/bin/env python3
"""Virtual keyboard/mouse through /dev/uinput (Linux, Wayland and X11). Reads JSON lines on stdin, answers one line each. Standard library only."""
import os, sys, json, struct, fcntl, time

UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_RELBIT, UI_SET_ABSBIT = 0x40045564, 0x40045565, 0x40045566, 0x40045567
UI_DEV_CREATE, UI_DEV_DESTROY = 0x5501, 0x5502
EV_SYN, EV_KEY, EV_REL, EV_ABS = 0, 1, 2, 3
BTN = {"left": 0x110, "right": 0x111, "middle": 0x112}

def make_device(name, keys=(), rels=(), abss=(), absmax=65535):
    fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
    evs = [EV_SYN]
    if keys: evs.append(EV_KEY)
    if rels: evs.append(EV_REL)
    if abss: evs.append(EV_ABS)
    for e in evs: fcntl.ioctl(fd, UI_SET_EVBIT, e)
    for k in keys: fcntl.ioctl(fd, UI_SET_KEYBIT, k)
    for r in rels: fcntl.ioctl(fd, UI_SET_RELBIT, r)
    for a in abss: fcntl.ioctl(fd, UI_SET_ABSBIT, a)
    mx = [0] * 64
    for a in abss: mx[a] = absmax
    dev = struct.pack("80sHHHHI", name.encode(), 0x03, 0x1234, 0x5678, 1, 0)
    dev += struct.pack("64i", *mx) + struct.pack("64i", *([0] * 64)) * 3
    os.write(fd, dev)
    fcntl.ioctl(fd, UI_DEV_CREATE)
    time.sleep(0.7)   # let the compositor notice the new device
    return fd

def emit(fd, t, c, v):
    now = time.time()
    os.write(fd, struct.pack("llHHi", int(now), int((now % 1) * 1e6), t, c, v))

def syn(fd): emit(fd, EV_SYN, 0, 0)

main = make_device("assistant-virtual-input", keys=list(range(1, 249)) + [0x110, 0x111, 0x112], rels=[0, 1, 6, 8])
absdev = None

def handle(m):
    global absdev
    op = m["op"]
    if op == "keys":                       # [[code, 1|0], ...] with optional {"sleep": ms}
        for item in m["seq"]:
            if isinstance(item, dict): time.sleep(item["sleep"] / 1000.0); continue
            emit(main, EV_KEY, item[0], item[1]); syn(main); time.sleep(0.008)
    elif op == "btn":
        emit(main, EV_KEY, BTN[m.get("btn", "left")], 1 if m["down"] else 0); syn(main)
    elif op == "click":
        for _ in range(int(m.get("count", 1))):
            b = BTN[m.get("btn", "left")]
            emit(main, EV_KEY, b, 1); syn(main); time.sleep(0.04); emit(main, EV_KEY, b, 0); syn(main); time.sleep(0.07)
    elif op == "wheel":
        if m.get("v"): emit(main, EV_REL, 8, int(m["v"]))
        if m.get("h"): emit(main, EV_REL, 6, int(m["h"]))
        syn(main)
    elif op == "rel":
        emit(main, EV_REL, 0, int(m.get("dx", 0))); emit(main, EV_REL, 1, int(m.get("dy", 0))); syn(main)
    elif op == "abs":
        if absdev is None: absdev = make_device("assistant-virtual-pointer", keys=[0x110], abss=[0, 1])
        emit(absdev, EV_ABS, 0, int(m["x"] * 65535 / max(1, m["w"]))); emit(absdev, EV_ABS, 1, int(m["y"] * 65535 / max(1, m["h"]))); syn(absdev)
    elif op == "ping":
        pass
    else:
        raise ValueError("unknown op " + op)

print("ready", flush=True)
for line in sys.stdin:
    line = line.strip()
    if not line: continue
    try:
        handle(json.loads(line)); print("ok", flush=True)
    except Exception as e:
        print("err " + str(e), flush=True)
try:
    fcntl.ioctl(main, UI_DEV_DESTROY)
except Exception:
    pass
