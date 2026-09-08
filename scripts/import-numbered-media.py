#!/usr/bin/env python3
"""Import numbered AI artwork into the frontend's optimized static media pool.

The source PNG number is the contract with ``AI生成图片素材提示词.md``. Missing
numbers are intentionally skipped rather than substituted with unrelated artwork.
"""
from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageOps

CATEGORY_RANGES: tuple[tuple[range, str], ...] = (
    (range(1, 13), "login"),
    (range(13, 45), "memory-covers"),
    (range(45, 59), "scene-nature"),
    (range(59, 71), "scene-architecture"),
    (range(71, 86), "concepts"),
    (range(86, 96), "features"),
    (range(96, 101), "video-posters"),
)


def category_for(number: int) -> str | None:
    return next((category for numbers, category in CATEGORY_RANGES if number in numbers), None)


def save_webp(image: Image.Image, target: Path, *, quality: int) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target, "WEBP", quality=quality, method=6)


def import_media(source: Path, output: Path, quality: int, thumb_width: int) -> None:
    imported = 0
    for source_file in sorted(source.rglob("*.png"), key=lambda path: int(path.stem)):
        try:
            number = int(source_file.stem)
        except ValueError:
            print(f"skip non-numbered file: {source_file}")
            continue

        category = category_for(number)
        if category is None:
            print(f"skip out-of-plan number: {number}")
            continue

        with Image.open(source_file) as raw:
            image = ImageOps.exif_transpose(raw)
            if image.mode not in ("RGB", "RGBA"):
                image = image.convert("RGBA" if "A" in image.getbands() else "RGB")

            stem = f"{number:03d}"
            target_dir = output / category
            save_webp(image, target_dir / f"{stem}.webp", quality=quality)

            thumb = image.copy()
            if thumb.width > thumb_width:
                height = round(thumb.height * thumb_width / thumb.width)
                thumb = thumb.resize((thumb_width, height), Image.Resampling.LANCZOS)
            save_webp(thumb, target_dir / f"{stem}-thumb.webp", quality=max(65, quality - 12))

        imported += 1
        print(f"{number:03d} -> {category}/{number:03d}.webp")

    print(f"Imported {imported} generated assets into {output}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path("图"))
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("frontend/public/media/generated"),
    )
    parser.add_argument("--quality", type=int, default=86)
    parser.add_argument("--thumb-width", type=int, default=420)
    args = parser.parse_args()
    import_media(args.source, args.output, args.quality, args.thumb_width)


if __name__ == "__main__":
    main()
