#!/usr/bin/env python3
"""
Kabul kriteri A5 (SPEC §10): uygulama arka plana atılıp geri getirildiğinde toplama devam eder, `lifecycle` olayları yazılır.

Akış: bot etiketiyle oturum aç → 10 sn ön planda → HOME (arka plan) 20 sn → uygulamayı geri aç → 10 sn → STOP.
Sonra oturum Supabase'ten okunur (`npm run inspect`) ve kontrol edilir:
  - lifecycle: foreground … background … foreground
  - ivme akışında arka plan süresine (~20 sn) denk gelen tek bir boşluk; öncesinde ve sonrasında veri var
Gereken: emülatör/cihaz, çalışan API (research-app'in ayarlı olduğu), api/.env (inspect için).
  python3 tools/acceptance/a5_background.py
"""
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools", "bots"))
from adb_bot import Adb, Bot  # noqa: E402

PKG = "io.humansignal.research"
BG_SECONDS = 20


def main():
    adb = Adb()
    subprocess.run(adb.base + ["logcat", "-c"])
    adb.start_marker_stream()
    bot = Bot(adb, "naive")
    adb.sh("am", "start", "-n", f"{PKG}/.LauncherActivity")
    time.sleep(2)
    bot.setup("emulator_android_studio")
    print("session open; foreground 10 s")
    time.sleep(10)
    adb.key("KEYCODE_HOME")
    print(f"background {BG_SECONDS} s")
    time.sleep(BG_SECONDS)
    adb.sh("am", "start", "-n", f"{PKG}/.LauncherActivity")  # açık oturum varsa Görevler'e döner
    time.sleep(3)
    top = adb.sh("dumpsys", "activity", "activities")
    resumed = re.search(r"mResumedActivity: .*?/(\.\w+)", top)
    print("resumed:", resumed.group(1) if resumed else "?")
    time.sleep(10)
    adb.sh("am", "broadcast", "-a", f"{PKG}.STOP", "-p", PKG)
    adb.stop_marker_stream()
    print("stopped; waiting for upload")
    time.sleep(15)

    out = subprocess.run(["npm", "run", "--silent", "inspect"], cwd=os.path.join(ROOT, "api"), capture_output=True, text=True).stdout
    print(out)
    life = re.search(r"lifecycle: (.*)", out).group(1)
    gaps = re.search(r"acc gaps >1s: (.*)", out).group(1)
    events = re.findall(r"(\w+)@", life)
    ok_life = "background" in events and events.index("background") < len(events) - 1 and "foreground" in events[events.index("background"):]
    m = re.findall(r"([\d.]+)s→([\d.]+)s", gaps)
    gap_len = [float(b) - float(a) for a, b in m]
    ok_gap = len(gap_len) == 1 and abs(gap_len[0] - BG_SECONDS) < 6
    ok_resume = resumed is not None and resumed.group(1) == ".TaskActivity"
    print(f"{'OK  ' if ok_resume else 'FAIL'} app returned to tasks")
    print(f"{'OK  ' if ok_life else 'FAIL'} lifecycle background → foreground recorded ({' '.join(events)})")
    print(f"{'OK  ' if ok_gap else 'FAIL'} sensors paused only while in background (gaps: {gap_len})")
    ok = ok_resume and ok_life and ok_gap
    print("\nA5 PASSED" if ok else "\nA5 FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
