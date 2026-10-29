#!/usr/bin/env python3
"""Seam clipboard spike, Mac side.

Grants the test app its two extra permissions over adb (so the user never opens a terminal in the
real app), tunnels the phone's 127.0.0.1:47800 to this Mac with `adb reverse`, then puts every
clip the phone sends on the Mac clipboard and prints how long it took.

    python3 seam_clip_helper.py --apk ../android/app/build/outputs/apk/debug/app-debug.apk
    python3 seam_clip_helper.py --self-test
"""
import argparse
import base64
import csv
import os
import shutil
import socket
import subprocess
import sys
import time

PKG = "com.avetero.seam.spike"
PORT = 47800
RESULTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results.csv")

NO_DEVICE = """No phone found. Either:
  USB:      plug the phone in, turn on Settings > System > Developer options > USB debugging, accept the prompt
  Wireless: Developer options > Wireless debugging > Pair device with pairing code, then run
              adb pair <ip>:<pairing-port>     (enter the code)
              adb connect <ip>:<port>          (the port shown on the Wireless debugging screen)
Then run this again."""


def find_adb():
    for p in (shutil.which("adb"),
              os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
              os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb")):
        if p and os.path.isfile(p):
            return p
    sys.exit("adb not found. Install Android platform-tools (brew install android-platform-tools).")


def adb(adb_path, serial, *args, check=True):
    cmd = [adb_path] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed:\n{(r.stderr or r.stdout).strip()}")
    return r.stdout.strip()


def pick_device(adb_path, wanted):
    rows = adb(adb_path, None, "devices").splitlines()[1:]
    devices = [r.split()[0] for r in rows if r.strip().endswith("device")]
    if wanted:
        if wanted not in devices:
            sys.exit(f"{wanted} isn't connected. Connected: {', '.join(devices) or 'none'}")
        return wanted
    if not devices:
        sys.exit(NO_DEVICE)
    if len(devices) > 1:
        sys.exit(f"More than one phone connected; pass --serial with one of: {', '.join(devices)}")
    return devices[0]


def setup(adb_path, serial, apk):
    model = adb(adb_path, serial, "shell", "getprop", "ro.product.model")
    release = adb(adb_path, serial, "shell", "getprop", "ro.build.version.release")
    print(f"Phone: {model}, Android {release}")
    if apk:
        print("Installing the test app…")
        adb(adb_path, serial, "install", "-r", "-g", apk)
    print("Granting: read system logs, draw over other apps, no battery limits")
    adb(adb_path, serial, "shell", "pm", "grant", PKG, "android.permission.READ_LOGS")
    adb(adb_path, serial, "shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
    adb(adb_path, serial, "shell", "dumpsys", "deviceidle", "whitelist", f"+{PKG}", check=False)
    adb(adb_path, serial, "reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    # Force-stop so the new grants apply, then open it so Android can show any log-access prompt
    adb(adb_path, serial, "shell", "am", "force-stop", PKG)
    adb(adb_path, serial, "shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    return f"{model} / Android {release}"


def parse(line):
    """'clip\\t<method>\\t<ms>\\t<b64>' or 'log\\t<b64>' -> tuple, or None if malformed."""
    parts = line.rstrip("\r\n").split("\t")
    try:
        if parts[0] == "clip" and len(parts) == 4:
            return ("clip", parts[1], int(parts[2]), base64.b64decode(parts[3]).decode("utf-8"))
        if parts[0] == "log" and len(parts) == 2:
            return ("log", base64.b64decode(parts[1]).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        pass
    return None


def record(device, method, ms, length):
    new = not os.path.exists(RESULTS)
    with open(RESULTS, "a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["time", "device", "method", "detect_to_read_ms", "chars"])
        # Only the length is stored, never the copied text
        w.writerow([time.strftime("%Y-%m-%d %H:%M:%S"), device, method, ms, length])


def serve(server, device):
    print(f"\nReady. Copy something on the phone; it should land here. Results go to {RESULTS}\n")
    while True:
        conn, _ = server.accept()
        print("· phone connected")
        with conn, conn.makefile("r", encoding="utf-8") as lines:
            for line in lines:
                msg = parse(line)
                if not msg:
                    continue
                if msg[0] == "log":
                    print(f"  phone: {msg[1]}")
                    continue
                _, method, ms, text = msg
                subprocess.run(["pbcopy"], input=text.encode("utf-8"), check=True)
                preview = text.replace("\n", " ")[:60]
                print(f"✓ on Mac clipboard via {method}, {ms} ms after the copy: “{preview}”")
                record(device, method, ms, len(text))
        print("· phone disconnected, waiting…")


def self_test():
    b = lambda s: base64.b64encode(s.encode()).decode()
    sample = "héllo\tworld"  # tabs inside the text must survive
    assert parse("clip\toverlay\t142\t" + b(sample) + "\n") == ("clip", "overlay", 142, sample)
    assert parse(f"log\t{b('Service started')}") == ("log", "Service started")
    assert parse("clip\toverlay\tnot-a-number\tAAAA") is None
    assert parse("garbage") is None
    print("self-test ok")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", help="install this APK first")
    ap.add_argument("--serial", help="which phone, if more than one is connected")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    adb_path = find_adb()
    serial = pick_device(adb_path, args.serial)
    # Listen before the app starts so its first connection succeeds
    server = socket.create_server(("127.0.0.1", PORT))
    device = setup(adb_path, serial, args.apk)
    try:
        serve(server, device)
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
