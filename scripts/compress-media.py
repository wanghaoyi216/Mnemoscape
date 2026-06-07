#!/usr/bin/env python3
"""
Compress frontend/public/media/images/*.png → .webp + -thumb.webp.

为什么不在前端运行时压？因为 public/ 目录走 Vite 原样 serve，PNG 是几 MB
级的素材会直接拖慢首屏。我们在源仓库里一次性产出 WebP（高质量 + 缩略图），
让 media-catalog.ts 把 .webp 作为主源，把 -thumb.webp 暴露为可选预览图。
PNG 仍然保留 — 一方面是兜底（不支持 WebP 的极旧浏览器虽然几乎没了，但 PNG
保住"原片"语义），另一方面方便日后再压一遍。

用法：
  python scripts/compress-media.py [--quality 78] [--thumb-width 256]
"""
import argparse
import sys
import time
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow not installed. Install via: pip install Pillow")


def compress_one(src: Path, quality: int, thumb_width: int) -> tuple[int, int, int]:
    """Return (original_bytes, full_webp_bytes, thumb_webp_bytes)."""
    img = Image.open(src)
    # WebP 不接受 P 模式（调色板）的 alpha；先转 RGBA 再编码
    if img.mode not in ("RGB", "RGBA"):
        img = img.convert("RGBA" if "A" in img.getbands() else "RGB")
    orig_size = src.stat().st_size

    # 全质量 WebP
    full = src.with_suffix(".webp")
    img.save(full, "webp", quality=quality, method=6)
    full_size = full.stat().st_size

    # 缩略图 — 等比缩到 thumb_width 宽
    w, h = img.size
    if w > thumb_width:
        ratio = thumb_width / w
        thumb = img.resize((thumb_width, int(h * ratio)), Image.LANCZOS)
    else:
        thumb = img
    thumb_path = src.with_name(src.stem + "-thumb.webp")
    thumb.save(thumb_path, "webp", quality=max(55, quality - 15), method=6)
    return orig_size, full_size, thumb_path.stat().st_size


def fmt_kb(n: int) -> str:
    return f"{n / 1024:.0f}KB"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--quality", type=int, default=78)
    parser.add_argument("--thumb-width", type=int, default=320)
    parser.add_argument("--dir", default="frontend/public/media/images")
    args = parser.parse_args()

    root = Path(args.dir)
    if not root.is_dir():
        sys.exit(f"directory not found: {root}")

    pngs = sorted(root.glob("*.png"))
    if not pngs:
        sys.exit(f"no PNGs under {root}")

    total_orig = total_full = total_thumb = 0
    started = time.time()
    print(f"Compressing {len(pngs)} images (quality={args.quality}, thumb={args.thumb_width}px)\n")
    for src in pngs:
        orig, full, thumb = compress_one(src, args.quality, args.thumb_width)
        total_orig += orig
        total_full += full
        total_thumb += thumb
        ratio = (1 - full / orig) * 100
        print(f"  {src.name:32s}  {fmt_kb(orig):>8s} → {fmt_kb(full):>7s}  "
              f"({ratio:5.1f}% saved)  + thumb {fmt_kb(thumb)}")

    elapsed = time.time() - started
    print(
        f"\nDone in {elapsed:.1f}s — "
        f"PNGs: {fmt_kb(total_orig)}  →  "
        f"WebP full: {fmt_kb(total_full)} ({(1 - total_full / total_orig) * 100:.1f}% off)  "
        f"+ thumbs: {fmt_kb(total_thumb)}"
    )
    print("\nReminder: media-catalog.ts has been wired to .webp; PNGs kept as originals.")


if __name__ == "__main__":
    main()
