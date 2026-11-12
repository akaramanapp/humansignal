#!/usr/bin/env python3
"""
HumanSignal araştırma uygulaması için adb bot'u (SPEC §9.1, bot_tool = adb_input).

Uygulamayı Oturum Kurulumu ekranından başlatır (etiket: Bot / adb_input), T1–T7 görevlerini yürütür,
Özet ekranında yüklemenin bitmesini bekler. Hedef konumlarını logcat'teki marker'lardan okur (`adb logcat -s HS-MARK`),
diğer öğeleri resource-id / contentDescription ile bulur (§4.3).

Kullanım:
  python3 tools/bots/adb_bot.py [--mode naive] [--host emulator_android_studio] [-s SERIAL]

Modlar: `naive` (sabit süreler), `jitter` (rastgele gecikme ve konum sapması),
`humanlike` (eğri yollar, minimum-jerk hız profili; `injector/build.sh` ile derlenen enjektörü kullanır).
Not: ayarlar/rıza ekranları bu script'in kapsamı dışında; uygulama Oturum Kurulumu'na gelmiş olmalı
(ilk kurulumda --first-run bunları da geçer).
"""
import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "io.humansignal.research"


class Adb:
    def __init__(self, serial=None):
        self.base = ["adb"] + (["-s", serial] if serial else [])

    def sh(self, *args, check=True):
        r = subprocess.run(self.base + ["shell", *args], capture_output=True, text=True)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb shell {' '.join(args)}: {r.stderr.strip()}")
        return r.stdout

    def tap(self, x, y):
        self.sh("input", "tap", str(int(x)), str(int(y)))

    def swipe(self, x1, y1, x2, y2, ms=250):
        self.sh("input", "swipe", *map(lambda v: str(int(v)), (x1, y1, x2, y2, ms)))

    def text(self, s):
        # `input text` boşluk ve bazı karakterleri özel yorumlar.
        esc = s.replace(" ", "%s").replace("'", "").replace("&", "").replace("(", "").replace(")", "")
        self.sh("input", "text", esc)

    def drag_hold(self, x1, y1, x2, y2, steps=4):
        cmds = [f"input motionevent DOWN {int(x1)} {int(y1)}"]
        for i in range(1, steps + 1):
            cmds.append(f"input motionevent MOVE {int(x1 + (x2 - x1) * i / steps)} {int(y1 + (y2 - y1) * i / steps)}")
        cmds += [f"input motionevent MOVE {int(x2)} {int(y2)}"] * 2   # dur: hız sıfırlansın
        cmds.append(f"input motionevent UP {int(x2)} {int(y2)}")
        self.sh("; ".join(cmds))

    def key(self, code):
        self.sh("input", "keyevent", code)

    def dump(self, retries=5):
        for _ in range(retries):
            out = self.sh("uiautomator", "dump", "/sdcard/hs_ui.xml", check=False)
            if "dumped" in out:
                xml = subprocess.run(self.base + ["exec-out", "cat", "/sdcard/hs_ui.xml"], capture_output=True, text=True).stdout
                try:
                    return ET.fromstring(xml)
                except ET.ParseError:
                    pass
            time.sleep(0.5)
        raise RuntimeError("uiautomator dump failed")

    def start_marker_stream(self):
        """`HS-MARK` satırlarını arka planda sürekli okur. logcat halka tamponu (2 MiB) eski satırları atar;
        her seferinde `logcat -d` okumak indeksleri kaydırır. Bu yüzden yalnızca eklenen bir liste tutulur."""
        import threading
        self._marks = []
        self._lock = threading.Lock()
        self._lc = subprocess.Popen(self.base + ["logcat", "-v", "raw", "-s", "HS-MARK"], stdout=subprocess.PIPE, text=True)

        def pump():
            for line in self._lc.stdout:
                i = line.find("{")
                if i < 0:
                    continue
                try:
                    m = json.loads(line[i:])
                except json.JSONDecodeError:
                    continue
                with self._lock:
                    self._marks.append(m)
        threading.Thread(target=pump, daemon=True).start()

    def markers(self):
        with self._lock:
            return list(self._marks)

    def stop_marker_stream(self):
        if getattr(self, "_lc", None):
            self._lc.kill()

# ============================================================================ giriş modları (§9.1)
import math
import os
import random as _random


class NaiveInput:
    """`--mode naive`: sabit süreler, tam merkez, `input tap/swipe`."""
    def __init__(self, adb: Adb, rng):
        self.a, self.r = adb, rng

    def tap(self, x, y, radius=None):
        self.a.tap(x, y)

    def swipe(self, x1, y1, x2, y2, ms=250):
        self.a.swipe(x1, y1, x2, y2, ms)

    def drag(self, x1, y1, x2, y2, ms=900):
        """
        Fırlatmasız sürükleme. `input swipe` sabit hızla biter ve parmak kalkınca liste kaymaya devam eder (fling);
        bu yüzden `input motionevent` ile hedefte durulur, sonra kaldırılır → bırakma hızı ~0.
        """
        self.a.drag_hold(x1, y1, x2, y2)

    def text(self, s):
        self.a.text(s)

    def pause(self, base):
        time.sleep(base)

    def close(self):
        pass


class JitterInput(NaiveInput):
    """`--mode jitter`: rastgele gecikme (log-normal) ve konum sapması (Gauss)."""
    def _j(self, radius):
        sigma = (radius or 40) * 0.3
        return self.r.gauss(0, sigma), self.r.gauss(0, sigma)

    def tap(self, x, y, radius=None):
        dx, dy = self._j(radius)
        self.a.tap(x + dx, y + dy)

    def swipe(self, x1, y1, x2, y2, ms=250):
        dx, dy = self._j(30)
        self.a.swipe(x1 + dx, y1 + dy, x2 + self.r.gauss(0, 25), y2 + self.r.gauss(0, 25), int(ms * self.r.uniform(0.6, 1.8)))

    def drag(self, x1, y1, x2, y2, ms=900):
        self.a.drag_hold(x1 + self.r.gauss(0, 8), y1 + self.r.gauss(0, 10), x2 + self.r.gauss(0, 8), y2 + self.r.gauss(0, 10),
                         steps=self.r.randint(3, 6))

    def text(self, s):
        # Kelime kelime, aralarda bekleyerek.
        words = s.split(" ")
        for i, w in enumerate(words):
            self.a.text(w + (" " if i < len(words) - 1 else ""))
            time.sleep(self.r.lognormvariate(-1.2, 0.5))

    def pause(self, base):
        time.sleep(base * self.r.lognormvariate(0, 0.45) + 0.05)


class HumanlikeInput(NaiveInput):
    """
    `--mode humanlike`: `injector/Injector.java` (app_process) ile ~120 Hz enjeksiyon; eğri yollar (Bezier),
    minimum-jerk hız profili, el titremesi, basınç/boyut eğrisi, log-normal tepki süreleri, karakter karakter yazma.
    """
    DEX = "/data/local/tmp/hs_injector.dex"

    def __init__(self, adb: Adb, rng):
        super().__init__(adb, rng)
        local = os.path.join(os.path.dirname(os.path.abspath(__file__)), "injector", "injector.dex")
        if not os.path.exists(local):
            raise SystemExit("injector.dex yok: önce tools/bots/injector/build.sh")
        subprocess.run(adb.base + ["push", local, self.DEX], check=True, capture_output=True)
        self.p = subprocess.Popen(adb.base + ["shell", f"CLASSPATH={self.DEX}", "app_process", "/", "Injector"],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
        ready = self.p.stdout.readline().strip()
        if not ready.startswith("READY"):
            raise RuntimeError(f"injector failed: {ready}")
        self.size = self.r.uniform(0.07, 0.14)  # bu "parmağın" tipik temas boyutu

    def _run(self, pts):
        """pts: [(kind, t_ms, x, y, pressure, size)]"""
        lines = ["G"] + [f"{k} {t:.1f} {x:.1f} {y:.1f} {p:.3f} {sz:.3f}" for k, t, x, y, p, sz in pts] + ["E"]
        self.p.stdin.write("\n".join(lines) + "\n")
        self.p.stdin.flush()
        if self.p.stdout.readline().strip() != "OK":
            raise RuntimeError("injector did not ack")

    def _pressure(self, u):
        # Basma-bırakma eğrisi: hızlı yükselir, ortada plato, sonda düşer.
        return max(0.05, min(1.0, 0.25 + 0.4 * math.sin(math.pi * min(1, u)) + self.r.gauss(0, 0.02)))

    def tap(self, x, y, radius=None):
        sigma = (radius or 40) * 0.18
        x, y = x + self.r.gauss(0, sigma), y + self.r.gauss(0, sigma)
        dur = self.r.lognormvariate(math.log(85), 0.3)  # basılı kalma süresi
        n = max(2, int(dur / 8))
        sz = self.size * self.r.uniform(0.85, 1.15)
        pts = [("D", 0, x, y, self._pressure(0.1), sz)]
        for i in range(1, n):  # parmak hafifçe kayar
            u = i / n
            pts.append(("M", u * dur, x + self.r.gauss(0, 0.6) + u * 1.5, y + self.r.gauss(0, 0.6) + u * 2, self._pressure(u), sz * (1 + 0.15 * math.sin(math.pi * u))))
        pts.append(("U", dur, pts[-1][2], pts[-1][3], self._pressure(0.95), sz))
        self._run(pts)

    def _path(self, x1, y1, x2, y2, dur):
        # Kuadratik Bezier, kontrol noktası yolun normali boyunca rastgele kaydırılır; zaman minimum-jerk ile.
        L = math.hypot(x2 - x1, y2 - y1) or 1
        nx, ny = -(y2 - y1) / L, (x2 - x1) / L
        off = self.r.gauss(0, 0.12) * L
        cx, cy = (x1 + x2) / 2 + nx * off, (y1 + y2) / 2 + ny * off
        sz = self.size * self.r.uniform(0.9, 1.1)
        n = max(3, int(dur / 8))
        pts = []
        for i in range(n + 1):
            tau = i / n
            u = 10 * tau ** 3 - 15 * tau ** 4 + 6 * tau ** 5
            bx = (1 - u) ** 2 * x1 + 2 * (1 - u) * u * cx + u ** 2 * x2
            by = (1 - u) ** 2 * y1 + 2 * (1 - u) * u * cy + u ** 2 * y2
            k = "D" if i == 0 else "U" if i == n else "M"
            pts.append((k, tau * dur, bx + self.r.gauss(0, 0.8), by + self.r.gauss(0, 0.8), self._pressure(tau), sz))
        return pts

    def swipe(self, x1, y1, x2, y2, ms=250):
        self._run(self._path(x1, y1, x2, y2, ms * self.r.uniform(0.8, 1.6)))

    def drag(self, x1, y1, x2, y2, ms=900):
        # Minimum-jerk sonda hızı sıfıra indirir → fırlatma olmaz.
        self._run(self._path(x1, y1, x2, y2, ms * self.r.uniform(0.9, 1.3)))

    def text(self, s):
        for ch in s:
            self.a.text(ch)  # her çağrı ~150 ms: insan yazma hızına yakın
            time.sleep(max(0, self.r.lognormvariate(math.log(0.12), 0.6) - 0.1))

    def pause(self, base):
        # Tepki süresi: log-normal, ortalama ~base + 300 ms.
        time.sleep(base * 0.5 + self.r.lognormvariate(math.log(0.35), 0.4))

    def close(self):
        try:
            self.p.stdin.write("Q\n"); self.p.stdin.flush()
            self.p.wait(timeout=3)
        except Exception:
            self.p.kill()


MODES = {"naive": NaiveInput, "jitter": JitterInput, "humanlike": HumanlikeInput}


def bounds(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return x1, y1, x2, y2


def center(node):
    x1, y1, x2, y2 = bounds(node)
    return (x1 + x2) / 2, (y1 + y2) / 2


class Bot:
    def __init__(self, adb: Adb, mode: str, seed=None):
        self.a = adb
        self.mode = mode
        self.inp = MODES[mode](adb, _random.Random(seed))
        self.cursor = 0  # okunan son marker'ın indeksi (logcat -c sonrası)

    # ---------------------------------------------------------------- bulma
    def find(self, rid=None, desc=None, text=None, desc_prefix=None, root=None):
        root = root if root is not None else self.a.dump()
        for n in root.iter("node"):
            if rid and n.get("resource-id") == f"{PKG}:id/{rid}":
                return n
            if desc and n.get("content-desc") == desc:
                return n
            if desc_prefix and n.get("content-desc", "").startswith(desc_prefix):
                return n
            if text and n.get("text") == text:
                return n
        return None

    def wait(self, timeout=15, **kw):
        end = time.time() + timeout
        while time.time() < end:
            n = self.find(**kw)
            if n is not None:
                return n
            time.sleep(0.5)
        raise RuntimeError(f"not found: {kw}")

    def tap_el(self, scroll=False, **kw):
        for _ in range(8 if scroll else 1):
            n = self.find(**kw)
            if n is not None:
                x1, y1, x2, y2 = bounds(n)
                self.inp.tap((x1 + x2) / 2, (y1 + y2) / 2, radius=min(x2 - x1, y2 - y1) / 2)
                return n
            if scroll:
                self.inp.drag(540, 1700, 540, 700, 600)
                time.sleep(0.6)
        raise RuntimeError(f"not found: {kw}")

    def pause(self, s=0.35):
        self.inp.pause(s)

    def next_start(self, task, timeout=20):
        """İmleçten sonraki ilk `task_start` (görev adına göre); imleci ilerletir."""
        end = time.time() + timeout
        while time.time() < end:
            ms = self.a.markers()
            for i in range(self.cursor, len(ms)):
                m = ms[i]
                if m.get("name") == "task_start" and m.get("attrs", {}).get("task") == task:
                    self.cursor = i + 1
                    return m["attrs"]
            time.sleep(0.25)
        raise RuntimeError(f"no task_start for {task}")

    # ---------------------------------------------------------------- akışlar
    def first_run(self):
        """Ayarlar (varsayılanlarla) + Rıza. Uygulama verisi temizken bir kez."""
        self.tap_el(rid="settings_save")
        time.sleep(3)  # süreç yeniden başlar
        self.a.sh("am", "start", "-n", f"{PKG}/.LauncherActivity")
        time.sleep(2)
        self.tap_el(rid="consent_general", scroll=True)
        self.tap_el(rid="consent_abroad", scroll=True)
        self.tap_el(rid="consent_accept", scroll=True)
        time.sleep(1.5)

    def setup(self, host):
        if self.find(rid="summary_new_session") is not None:
            self.tap_el(rid="summary_new_session"); time.sleep(1.5)
        self.wait(rid="setup_label_kind", timeout=10)
        self.tap_el(desc="setup_label_kind_bot")
        self.pause()
        # Varsayılan bot aracı adb_input (listede ilk); host seçimi:
        spinner = self.find(rid="setup_bot_host")
        if spinner is not None and host != "emulator_android_studio":
            self.a.tap(*center(spinner)); self.pause(0.8)
            self.tap_el(text=host)
        self.tap_el(rid="setup_start", scroll=True)
        self.wait(rid="task_progress", timeout=15)

    def trial_ended(self, task, trial, timeout=2.5):
        end = time.time() + timeout
        while time.time() < end:
            for m in self.a.markers()[self.cursor - 1:]:
                at = m.get("attrs", {})
                if m.get("name") == "task_end" and at.get("task") == task and at.get("trial") == trial:
                    return True
            time.sleep(0.2)
        return False

    def t1(self):
        for _ in range(30):
            attrs = self.next_start("tap_targets")
            for _attempt in range(4):  # sapmalı modlarda hedef kaçabilir: tekrar dene
                self.inp.tap(int(attrs["x"]), int(attrs["y"]), radius=int(attrs["d_px"]) / 2)
                if self.trial_ended("tap_targets", attrs["trial"]):
                    break
            self.pause(0.25)

    def t2(self):
        for _ in range(5):
            attrs = self.next_start("list_scroll")
            target = attrs["target"]
            lst = self.find(rid="task_t2_list")
            x1, y1, x2, y2 = bounds(lst)
            for _ in range(40):
                root = self.a.dump()
                n = self.find(desc=f"task_t2_item_{target}", root=root)
                rows = [x for x in root.iter("node") if re.match(r"task_t2_item_\d+$", x.get("content-desc", ""))]
                # Tam görünür mü? (kısmen görünen satıra dokunma listeyi kaydırabilir)
                if n is not None and bounds(n)[1] >= y1 and bounds(n)[3] <= y2:
                    bx1, by1, bx2, by2 = bounds(n)
                    self.inp.tap((bx1 + bx2) / 2, (by1 + by2) / 2, radius=(by2 - by1) / 2); break
                nums = [int(x.get("content-desc").rsplit("_", 1)[1]) for x in rows]
                row_h = (bounds(rows[0])[3] - bounds(rows[0])[1]) if rows else 100
                gap = (int(target) - max(nums)) if int(target) > max(nums) else (int(target) - min(nums)) if int(target) < min(nums) else 1
                # Fırlatma (fling) olmadan, gerektiği kadar yavaş sürükle.
                dist = max(row_h, min(abs(gap) * row_h + row_h, int((y2 - y1) * 0.8)))
                cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
                sign = 1 if gap > 0 else -1
                self.inp.drag(cx, cy + sign * dist / 2, cx, cy - sign * dist / 2, 900)
                time.sleep(0.4)
            self.pause(0.6)

    def t3(self):
        for _ in range(20):
            attrs = self.next_start("card_swipe")
            card = self.wait(rid="task_t3_card")
            cx, cy = center(card)
            dx = 450 if attrs["expected"] == "right" else -450
            self.inp.swipe(cx, cy, cx + dx, cy, 200)
            self.pause(0.6)

    def enter_pin(self, display_rid):
        pin = self.wait(rid=display_rid).get("text")
        root = self.a.dump()
        def key(desc):
            x1, y1, x2, y2 = bounds(self.find(desc=desc, root=root))
            self.inp.tap((x1 + x2) / 2, (y1 + y2) / 2, radius=min(x2 - x1, y2 - y1) / 2)
        for d in pin:
            key(f"keypad_key_{d}")
            self.pause(0.15)
        key("keypad_key_submit")

    def t4(self):
        for _ in range(10):
            self.next_start("pin_entry")
            self.enter_pin("task_t4_pin_display")
            self.pause(0.6)

    def t5(self):
        for _ in range(3):
            self.next_start("text_entry")
            root = self.a.dump()
            iban = self.find(rid="task_t5_prompt_iban", root=root).get("text").split(": ", 1)[1]
            amount = self.find(rid="task_t5_prompt_amount", root=root).get("text").split(": ", 1)[1].replace(" ₺", "")
            desc = self.find(rid="task_t5_prompt_desc", root=root).get("text").split(": ", 1)[1]
            for rid, val in (("task_t5_iban", iban.replace(" ", "")), ("task_t5_amount", amount), ("task_t5_desc", desc)):
                self.tap_el(rid=rid); self.pause(0.4)
                self.inp.text(ascii_only(val)); self.pause(0.4)
            self.a.key("KEYCODE_BACK")  # klavyeyi kapat
            self.tap_el(rid="task_t5_submit", scroll=True)
            self.pause(0.8)

    def t6(self):
        for _ in range(3):
            self.next_start("fake_transfer")
            instr = self.wait(rid="task_instruction").get("text")
            m = re.match(r"(.+?) kişisine (.+?) ₺ gönderin\. Açıklama: \"(.+)\"", instr)
            name, amount, desc = m.group(1), m.group(2), m.group(3)
            self.tap_el(text=name); self.pause(0.6)
            self.tap_el(rid="task_t6_amount"); self.inp.text(amount); self.pause(0.3)
            self.a.key("KEYCODE_BACK"); self.tap_el(rid="task_t6_next"); self.pause(0.6)
            self.tap_el(rid="task_t6_desc"); self.inp.text(ascii_only(desc)); self.pause(0.3)
            self.a.key("KEYCODE_BACK"); self.tap_el(rid="task_t6_next"); self.pause(0.6)
            self.tap_el(rid="task_t6_confirm"); self.pause(0.6)
            self.enter_pin("task_t6_pin_display")
            self.pause(0.8)

    def t7(self):
        self.next_start("free_use")
        end = time.time() + 62
        while time.time() < end:
            self.inp.swipe(540, 1600, 540, 900, 300)
            self.pause(1.5)

    def summary(self, timeout=120):
        n = self.wait(rid="summary_upload", timeout=30)
        end = time.time() + timeout
        while time.time() < end:
            n = self.find(rid="summary_upload")
            txt = n.get("text") if n is not None else ""
            if "yüklendi" in txt or "durdu" in txt:
                return txt
            time.sleep(2)
        return txt


def ascii_only(s):
    tr = str.maketrans("çğıöşüÇĞİÖŞÜ", "cgiosuCGIOSU")
    return s.translate(tr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", default="naive", choices=list(MODES))
    ap.add_argument("--seed", type=int, help="tekrarlanabilir rastgelelik")
    ap.add_argument("--host", default="emulator_android_studio")
    ap.add_argument("--first-run", action="store_true", help="Ayarlar + Rıza ekranlarını da geç")
    ap.add_argument("--tasks", default="t1,t2,t3,t4,t5,t6,t7", help="çalıştırılacak görevler; diğerleri 'Atla' ile geçilir")
    ap.add_argument("-s", "--serial")
    args = ap.parse_args()

    adb = Adb(args.serial)
    bot = Bot(adb, args.mode, args.seed)
    subprocess.run(adb.base + ["logcat", "-c"])
    adb.start_marker_stream()
    if args.first_run:
        bot.first_run()
    bot.setup(args.host)
    wanted = set(args.tasks.split(","))
    for t in ["t1", "t2", "t3", "t4", "t5", "t6", "t7"]:
        t0 = time.time()
        if t in wanted:
            getattr(bot, t)()
        else:
            bot.tap_el(rid="task_skip")
            bot.pause(1)
        print(f"{t}: {'done' if t in wanted else 'skipped'} in {time.time() - t0:.0f}s", flush=True)
    print("summary:", bot.summary())
    bot.inp.close()
    adb.stop_marker_stream()


if __name__ == "__main__":
    sys.exit(main())
