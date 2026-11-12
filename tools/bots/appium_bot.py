#!/usr/bin/env python3
"""
Appium / UI Automator2 bot'u (SPEC §9.2, bot_tool = appium). Görevleri yalnızca resource-id'ler üzerinden yürütür (§4.3).
adb_bot.py ile aynı görev mantığı; dokunmalar Appium W3C Actions (UiAutomator2 → sistem enjeksiyonu) ile yapılır.

Kurulum (bir kez):
  npm i -g appium && appium driver install uiautomator2
  python3 -m pip install Appium-Python-Client
Çalıştırma:
  appium &                                   # varsayılan http://127.0.0.1:4723
  python3 tools/bots/appium_bot.py [--host emulator_android_studio] [--tasks t1,t2,...]
Uygulama Oturum Kurulumu ya da Özet ekranında olmalı (ayarlar ve rıza önceden yapılmış).
"""
import argparse
import json
import re
import time

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy
from selenium.webdriver.common.actions import interaction
from selenium.webdriver.common.actions.action_builder import ActionBuilder
from selenium.webdriver.common.actions.pointer_input import PointerInput

PKG = "io.humansignal.research"


class AppiumBot:
    def __init__(self, server: str):
        opts = UiAutomator2Options()
        opts.app_package = PKG
        opts.app_activity = ".LauncherActivity"
        opts.no_reset = True                 # ayarlar/rıza korunur
        opts.auto_grant_permissions = True
        opts.new_command_timeout = 600
        self.d = webdriver.Remote(server, options=opts)
        self.d.implicitly_wait(0)
        self.marks = []

    # ---------------------------------------------------------------- yardımcılar
    def el(self, rid, timeout=15):
        end = time.time() + timeout
        while time.time() < end:
            found = self.d.find_elements(AppiumBy.ID, f"{PKG}:id/{rid}")
            if found:
                return found[0]
            time.sleep(0.3)
        raise RuntimeError(f"not found: {rid}")

    def by_desc(self, desc):
        found = self.d.find_elements(AppiumBy.ACCESSIBILITY_ID, desc)
        return found[0] if found else None

    def scroll_to_id(self, rid):
        return self.d.find_element(
            AppiumBy.ANDROID_UIAUTOMATOR,
            f'new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().resourceId("{PKG}:id/{rid}"))',
        )

    def tap_xy(self, x, y):
        a = ActionBuilder(self.d, mouse=PointerInput(interaction.POINTER_TOUCH, "finger"))
        a.pointer_action.move_to_location(int(x), int(y)).pointer_down().pause(0.08).pointer_up()
        a.perform()

    def swipe(self, x1, y1, x2, y2, ms):
        # `duration`: pointer hareketlerinin süresi (ms)
        a = ActionBuilder(self.d, mouse=PointerInput(interaction.POINTER_TOUCH, "finger"), duration=int(ms))
        a.pointer_action.move_to_location(int(x1), int(y1)).pointer_down()
        a.pointer_action.move_to_location(int(x2), int(y2)).pointer_up()
        a.perform()

    def center(self, e):
        r = e.rect
        return r["x"] + r["width"] / 2, r["y"] + r["height"] / 2

    def next_start(self, task, timeout=20):
        """Logcat'ten (HS-MARK) sıradaki task_start marker'ı."""
        end = time.time() + timeout
        while time.time() < end:
            for entry in self.d.get_log("logcat"):
                msg = entry.get("message", "")
                if "HS-MARK" in msg and "{" in msg:
                    try:
                        self.marks.append(json.loads(msg[msg.index("{"):]))
                    except json.JSONDecodeError:
                        pass
            for i, m in enumerate(self.marks):
                if m.get("name") == "task_start" and m.get("attrs", {}).get("task") == task:
                    self.marks = self.marks[i + 1:]
                    return m["attrs"]
            time.sleep(0.3)
        raise RuntimeError(f"no task_start for {task}")

    # ---------------------------------------------------------------- akış
    def setup(self, host):
        nxt = self.d.find_elements(AppiumBy.ID, f"{PKG}:id/summary_new_session")
        if nxt:
            nxt[0].click()
        self.by_desc("setup_label_kind_bot").click()
        self.el("setup_bot_tool").click()
        self.d.find_element(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("appium")').click()
        if host != "emulator_android_studio":
            self.el("setup_bot_host").click()
            self.d.find_element(AppiumBy.ANDROID_UIAUTOMATOR, f'new UiSelector().text("{host}")').click()
        self.get_log_flush()
        self.scroll_to_id("setup_start").click()
        self.el("task_progress")

    def get_log_flush(self):
        self.d.get_log("logcat")
        self.marks = []

    def t1(self):
        for _ in range(30):
            self.next_start("tap_targets")
            self.el("task_t1_target").click()  # merkezine tıklar
            time.sleep(0.2)

    def t2(self):
        for _ in range(5):
            target = self.next_start("list_scroll")["target"]
            self.d.find_element(
                AppiumBy.ANDROID_UIAUTOMATOR,
                f'new UiScrollable(new UiSelector().resourceId("{PKG}:id/task_t2_list")).scrollIntoView(new UiSelector().description("task_t2_item_{target}"))',
            ).click()
            time.sleep(0.4)

    def t3(self):
        for _ in range(20):
            exp = self.next_start("card_swipe")["expected"]
            x, y = self.center(self.el("task_t3_card"))
            self.swipe(x, y, x + (450 if exp == "right" else -450), y, 200)
            time.sleep(0.5)

    def pin(self, display):
        p = self.el(display).text
        for ch in p:
            self.d.find_element(AppiumBy.ID, f"{PKG}:id/keypad_key_{ch}").click()
        self.d.find_element(AppiumBy.ID, f"{PKG}:id/keypad_key_submit").click()

    def t4(self):
        for _ in range(10):
            self.next_start("pin_entry")
            self.pin("task_t4_pin_display")
            time.sleep(0.4)

    def t5(self):
        for _ in range(3):
            self.next_start("text_entry")
            vals = {k: self.el(f"task_t5_prompt_{k}").text.split(": ", 1)[1].replace(" ₺", "") for k in ("iban", "amount", "desc")}
            for k, v in vals.items():
                self.el(f"task_t5_{k}").send_keys(v)  # Appium metni tek seferde yazar (paste benzeri)
            self.d.hide_keyboard()
            self.scroll_to_id("task_t5_submit").click()
            time.sleep(0.5)

    def t6(self):
        for _ in range(3):
            self.next_start("fake_transfer")
            m = re.match(r"(.+?) kişisine (.+?) ₺ gönderin\. Açıklama: \"(.+)\"", self.el("task_instruction").text)
            self.d.find_element(AppiumBy.ANDROID_UIAUTOMATOR, f'new UiSelector().text("{m.group(1)}")').click()
            self.el("task_t6_amount").send_keys(m.group(2)); self.el("task_t6_next").click()
            self.el("task_t6_desc").send_keys(m.group(3)); self.el("task_t6_next").click()
            self.el("task_t6_confirm").click()
            self.pin("task_t6_pin_display")
            time.sleep(0.5)

    def t7(self):
        self.next_start("free_use")
        end = time.time() + 62
        while time.time() < end:
            self.swipe(540, 1600, 540, 900, 300)
            time.sleep(1.5)

    def summary(self, timeout=120):
        end = time.time() + timeout
        txt = ""
        while time.time() < end:
            txt = self.el("summary_upload", timeout=30).text
            if "yüklendi" in txt or "durdu" in txt:
                break
            time.sleep(2)
        return txt


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://127.0.0.1:4723")
    ap.add_argument("--host", default="emulator_android_studio")
    ap.add_argument("--tasks", default="t1,t2,t3,t4,t5,t6,t7")
    args = ap.parse_args()
    bot = AppiumBot(args.server)
    try:
        bot.setup(args.host)
        wanted = set(args.tasks.split(","))
        for t in ["t1", "t2", "t3", "t4", "t5", "t6", "t7"]:
            t0 = time.time()
            if t in wanted:
                getattr(bot, t)()
            else:
                bot.el("task_skip").click(); time.sleep(1)
            print(f"{t}: {'done' if t in wanted else 'skipped'} in {time.time() - t0:.0f}s", flush=True)
        print("summary:", bot.summary())
    finally:
        bot.d.quit()


if __name__ == "__main__":
    main()
