#!/usr/bin/env python3
"""Generate synthetic JPEGs for indexing and rotation tests.

    python3 scripts/make-test-photos.py out/ 1500
    adb push out/. /sdcard/Pictures/fengine-bulk/
    adb shell 'for f in /sdcard/Pictures/fengine-bulk/*.jpg; do am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f"; done'

Needs Pillow. Images are 640x480 flat colours with a few shapes: enough for
ML Kit to label and cheap to decode.
"""
import os
import random
import sys

from PIL import Image, ImageDraw


def main(out: str, count: int, seed: int = 7) -> None:
    random.seed(seed)
    os.makedirs(out, exist_ok=True)
    w, h = 640, 480
    for i in range(count):
        im = Image.new("RGB", (w, h), tuple(random.randint(0, 255) for _ in range(3)))
        d = ImageDraw.Draw(im)
        for _ in range(random.randint(2, 8)):
            x1, y1 = random.randint(0, w), random.randint(0, h)
            x2, y2 = x1 + random.randint(20, 300), y1 + random.randint(20, 300)
            colour = tuple(random.randint(0, 255) for _ in range(3))
            (d.ellipse if random.random() < 0.5 else d.rectangle)([x1, y1, x2, y2], fill=colour)
        im.save(os.path.join(out, f"fengine-bulk-{i:04d}.jpg"), quality=70)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 1500)
