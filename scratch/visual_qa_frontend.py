from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlparse
from urllib.request import urlopen

from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
from playwright.sync_api import sync_playwright


ROOT = Path(r"M:\Study\ProjectTest\Mnemoscape")
FRONTEND = ROOT / "frontend"
SCREENSHOT_DIR = ROOT / "scratch" / "visual-qa" / datetime.now().strftime("%Y%m%d-%H%M%S")
TRACE_PATH = SCREENSHOT_DIR / "trace.log"
CHROME_CANDIDATES = [
    Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
    Path(r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"),
    Path(r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"),
    Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
]


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def wait_for_http(url: str, timeout: float = 20.0) -> None:
    deadline = time.time() + timeout
    last_error: Exception | None = None
    while time.time() < deadline:
        try:
            with urlopen(url, timeout=1.5) as resp:
                if resp.status < 500:
                    return
        except Exception as exc:  # noqa: BLE001 - diagnostic helper
            last_error = exc
        time.sleep(0.35)
    raise RuntimeError(f"server did not become ready: {url}; last_error={last_error!r}")


def api_response(data: Any) -> dict[str, Any]:
    return {"code": 200, "message": "ok", "data": data, "requestId": "visual-qa"}


MEMORIES = [
    {
        "id": "m-aurora",
        "userId": "qa-user",
        "title": "极光下的旧车站",
        "description": "凌晨的站台像一座安静的展厅，绿色极光从玻璃穹顶上流过，我把一张皱掉的票根夹进笔记本。",
        "memoryYear": 2024,
        "memoryDate": "2024-02-12",
        "memorySeason": "WINTER",
        "memoryTimeOfDay": "NIGHT",
        "memoryLocation": "Harbin",
        "privacyLevel": "PRIVATE",
        "isLocked": False,
        "fadeLevel": 0.18,
        "sceneDataUrl": "/media/images/memory-corona.webp",
        "createdAt": "2024-02-12T22:10:00Z",
        "updatedAt": "2024-02-12T22:10:00Z",
    },
    {
        "id": "m-rain",
        "userId": "qa-user",
        "title": "雨夜里回声墙",
        "description": "雨点把路灯揉成一圈一圈的涟漪，旧录音机里的声音像从很远的房间传回来。",
        "memoryYear": 2021,
        "memoryDate": "2021-07-08",
        "memorySeason": "SUMMER",
        "memoryTimeOfDay": "NIGHT",
        "memoryLocation": "Suzhou",
        "privacyLevel": "FRIENDS",
        "isLocked": True,
        "fadeLevel": 0.62,
        "sceneDataUrl": "/media/images/echo-wall.webp",
        "createdAt": "2021-07-08T20:00:00Z",
        "updatedAt": "2021-07-08T20:00:00Z",
    },
    {
        "id": "m-field",
        "userId": "qa-user",
        "title": "霓虹原野的午后",
        "description": "风吹过空旷的草地，远处屏幕一样的天空亮着，像把童年的暑假重新调成了未来色。",
        "memoryYear": 2018,
        "memoryDate": "2018-08-19",
        "memorySeason": "SUMMER",
        "memoryTimeOfDay": "AFTERNOON",
        "memoryLocation": "Chengdu",
        "privacyLevel": "PUBLIC",
        "isLocked": False,
        "fadeLevel": 0.36,
        "sceneDataUrl": "/media/images/neon-fields.webp",
        "createdAt": "2018-08-19T10:00:00Z",
        "updatedAt": "2018-08-19T10:00:00Z",
    },
]

MEMORY_PAGE = {
    "items": MEMORIES,
    "total": len(MEMORIES),
    "page": 0,
    "size": 12,
    "totalPages": 1,
}

MATCHES = [
    {
        "memoryId": "m-rain",
        "title": "雨夜里回声墙",
        "similarityScore": 0.91,
        "emotionSimilarity": 0.88,
        "sceneSimilarity": 0.84,
        "ownerUsername": "Echo-17",
    },
    {
        "memoryId": "m-field",
        "title": "霓虹原野的午后",
        "similarityScore": 0.82,
        "emotionSimilarity": 0.76,
        "sceneSimilarity": 0.8,
        "ownerUsername": "Field-09",
    },
]


def fulfill_json(route, data: Any) -> None:
    route.fulfill(
        status=200,
        content_type="application/json; charset=utf-8",
        body=json.dumps(api_response(data), ensure_ascii=False),
    )


def trace(message: str) -> None:
    line = f"{datetime.now().isoformat(timespec='seconds')} {message}"
    print(line, flush=True)
    TRACE_PATH.parent.mkdir(parents=True, exist_ok=True)
    with TRACE_PATH.open("a", encoding="utf-8") as handle:
        handle.write(line + "\n")


def handle_api_route(route) -> None:
    path = urlparse(route.request.url).path
    if path == "/api/v1/auth/profile":
        fulfill_json(route, {
            "id": "qa-user",
            "username": "VisualQA",
            "email": "visualqa@example.test",
            "verified": True,
            "role": "USER",
        })
        return
    if path == "/api/v1/assets/static/resources":
        fulfill_json(route, [])
        return
    if path == "/api/v1/memories":
        fulfill_json(route, MEMORY_PAGE)
        return
    if path == "/api/v1/resonances/search":
        fulfill_json(route, MATCHES)
        return
    if path == "/api/v1/resonances/stats":
        fulfill_json(route, {
            "avgScore": 0.82,
            "totalMatches": 7,
            "algorithmName": "Visual QA Multi-Signal",
        })
        return
    fulfill_json(route, None)


def install_routes(page) -> None:
    page.route("**/api/v1/**", handle_api_route)


def run_case(
    page,
    base_url: str,
    name: str,
    path: str,
    viewport: dict[str, int],
    expected_h1: str,
    after_load=None,
) -> dict[str, Any]:
    trace(f"[visual-qa] case start: {name}")
    page.set_viewport_size(viewport)
    trace(f"[visual-qa] goto: {name} {path}")
    page.goto(base_url + path, wait_until="domcontentloaded", timeout=20_000)
    trace(f"[visual-qa] shell wait: {name}")
    page.locator(".app-shell").wait_for(state="visible", timeout=12_000)
    trace(f"[visual-qa] h1 wait: {name} {expected_h1}")
    page.locator("h1").filter(has_text=expected_h1).first.wait_for(state="visible", timeout=12_000)
    page.wait_for_timeout(1_200)
    if after_load:
        trace(f"[visual-qa] after_load start: {name}")
        after_load(page)
        trace(f"[visual-qa] after_load done: {name}")
        page.wait_for_timeout(1_200)
    trace(f"[visual-qa] metrics: {name}")
    dom_metrics = page.evaluate(
        """() => {
          const bodyText = document.body?.innerText || '';
          const h1 = document.querySelector('h1')?.innerText || '';
          const clientWidth = document.documentElement.clientWidth;
          const scrollWidth = document.documentElement.scrollWidth;
          const videos = Array.from(document.querySelectorAll('video')).map((video, index) => {
            const rect = video.getBoundingClientRect();
            return {
              index,
              width: Math.round(rect.width),
              height: Math.round(rect.height),
              src: video.currentSrc || video.getAttribute('src') || ''
            };
          });
          const overflowElements = Array.from(document.querySelectorAll('body *'))
            .map((element) => {
              const rect = element.getBoundingClientRect();
              return {
                tag: element.tagName.toLowerCase(),
                className: typeof element.className === 'string' ? element.className : '',
                text: (element.innerText || element.getAttribute('aria-label') || '').trim().slice(0, 80),
                left: Math.round(rect.left),
                right: Math.round(rect.right),
                width: Math.round(rect.width)
              };
            })
            .filter((item) => item.right > clientWidth + 2 || item.left < -2)
            .slice(0, 12);
          return {
            bodyTextLength: bodyText.length,
            h1,
            videos,
            clientWidth,
            scrollWidth,
            overflowElements,
            horizontalOverflow: scrollWidth > clientWidth + 2
          };
        }"""
    )
    screenshot = SCREENSHOT_DIR / f"{name}.png"
    trace(f"[visual-qa] screenshot: {name}")
    page.screenshot(path=str(screenshot), full_page=False, timeout=10_000)
    videos = dom_metrics["videos"]
    h1_text = dom_metrics["h1"]
    trace(f"[visual-qa] case done: {name} -> {screenshot}")
    return {
        "name": name,
        "path": path,
        "url": page.url,
        "viewport": viewport,
        "screenshot": str(screenshot),
        "metrics": {
            "bodyTextLength": dom_metrics["bodyTextLength"],
            "h1": h1_text,
        },
        "videos": videos,
        "horizontalOverflow": bool(dom_metrics["horizontalOverflow"]),
        "overflow": {
            "clientWidth": dom_metrics["clientWidth"],
            "scrollWidth": dom_metrics["scrollWidth"],
            "elements": dom_metrics["overflowElements"],
        },
        "blankLike": dom_metrics["bodyTextLength"] < 80,
        "badVideos": [v for v in videos if v["width"] <= 0 or v["height"] <= 0],
        "wrongPage": expected_h1 not in h1_text,
    }


def main() -> int:
    SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
    port = free_port()
    base_url = f"http://127.0.0.1:{port}"
    vite = FRONTEND / "node_modules" / ".bin" / "vite.cmd"
    vite_js = FRONTEND / "node_modules" / "vite" / "bin" / "vite.js"
    node = shutil.which("node")
    if node and vite_js.exists():
        preview_command = [node, str(vite_js), "preview", "--host", "127.0.0.1", "--port", str(port), "--strictPort"]
    elif vite.exists():
        preview_command = [str(vite), "preview", "--host", "127.0.0.1", "--port", str(port), "--strictPort"]
    else:
        raise RuntimeError(f"vite not found: {vite_js} or {vite}")

    vite_log_path = SCREENSHOT_DIR / "vite-preview.log"
    vite_log = vite_log_path.open("w", encoding="utf-8", errors="replace")
    server = subprocess.Popen(
        preview_command,
        cwd=str(FRONTEND),
        stdout=vite_log,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    try:
        wait_for_http(base_url)
        chrome = next((p for p in CHROME_CANDIDATES if p.exists()), None)
        if chrome is None:
            raise RuntimeError("no Chrome or Edge executable found")

        with sync_playwright() as p:
            browser = p.chromium.launch(
                headless=True,
                executable_path=str(chrome),
                args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"],
            )
            context = browser.new_context(
                locale="zh-CN",
                device_scale_factor=1,
                ignore_https_errors=True,
            )
            context.set_default_timeout(10_000)
            context.add_init_script(
                """(() => {
                  localStorage.setItem('token', 'visual-qa-token');
                  localStorage.setItem('refreshToken', 'visual-qa-refresh');
                })();"""
            )
            console_entries: list[dict[str, str]] = []
            page_errors: list[str] = []

            def search_resonance(current_page) -> None:
                trace("[visual-qa] resonance wait options")
                current_page.wait_for_function(
                    "() => document.querySelectorAll('.search-panel select option').length > 1",
                    timeout=8_000,
                )
                option_count = current_page.evaluate(
                    "() => document.querySelectorAll('.search-panel select option').length"
                )
                trace(f"[visual-qa] resonance option count: {option_count}")
                current_page.evaluate(
                    """() => {
                      const select = document.querySelector('.search-panel select');
                      if (!select) throw new Error('resonance select not found');
                      select.value = 'm-aurora';
                      select.dispatchEvent(new Event('change', { bubbles: true }));
                    }"""
                )
                current_page.wait_for_function(
                    """() => {
                      const button = document.querySelector('.search-panel button');
                      return Boolean(button && !button.disabled);
                    }""",
                    timeout=8_000,
                )
                trace("[visual-qa] resonance click search")
                current_page.locator(".search-panel button").click(timeout=8_000)
                trace("[visual-qa] resonance wait results")
                current_page.locator(".resonance-result").first.wait_for(state="visible", timeout=8_000)

            cases = [
                ("memories-desktop", "/memories", {"width": 1440, "height": 1000}, "我的记忆库", None),
                ("timeline-desktop", "/memories/timeline", {"width": 1440, "height": 1000}, "把记忆放回时间的几何里。", None),
                ("resonance-desktop", "/resonance", {"width": 1440, "height": 1000}, "寻找与你情感共振的记忆。", search_resonance),
                ("memories-mobile", "/memories", {"width": 390, "height": 844}, "我的记忆库", None),
                ("timeline-mobile", "/memories/timeline", {"width": 390, "height": 844}, "把记忆放回时间的几何里。", None),
                ("resonance-mobile", "/resonance", {"width": 390, "height": 844}, "寻找与你情感共振的记忆。", search_resonance),
            ]
            results = []
            for name, path, viewport, expected_h1, after in cases:
                page = context.new_page()
                page.set_default_timeout(10_000)
                install_routes(page)
                page.on("console", lambda msg: console_entries.append({"type": msg.type, "text": msg.text}))
                page.on("pageerror", lambda err: page_errors.append(str(err)))
                try:
                    results.append(run_case(page, base_url, name, path, viewport, expected_h1, after))
                finally:
                    page.close()
            browser.close()

        report = {
            "baseUrl": base_url,
            "screenshotDir": str(SCREENSHOT_DIR),
            "results": results,
            "console": console_entries,
            "pageErrors": page_errors,
        }
        report_path = SCREENSHOT_DIR / "report.json"
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

        failures = []
        for result in results:
            if result["horizontalOverflow"]:
                failures.append(f"{result['name']}: horizontal overflow")
            if result["blankLike"]:
                failures.append(f"{result['name']}: page looks blank")
            if result["badVideos"]:
                failures.append(f"{result['name']}: video has zero layout size")
            if result["wrongPage"]:
                failures.append(f"{result['name']}: unexpected h1 {result['metrics']['h1']!r}")
        if page_errors:
            failures.extend([f"pageerror: {err}" for err in page_errors])

        print(json.dumps({
            "ok": not failures,
            "failures": failures,
            "screenshotDir": str(SCREENSHOT_DIR),
            "report": str(report_path),
            "screenshots": [r["screenshot"] for r in results],
        }, ensure_ascii=False, indent=2))
        return 1 if failures else 0
    finally:
        server.terminate()
        try:
            server.wait(timeout=8)
        except subprocess.TimeoutExpired:
            server.kill()
        vite_log.close()


if __name__ == "__main__":
    sys.exit(main())
