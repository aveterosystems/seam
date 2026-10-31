#!/bin/sh
# Renders the README pictures in ../ from the pages here. Needs macOS, Google Chrome and Python with Pillow.
set -e
cd "$(dirname "$0")"
src=$PWD
work=$(mktemp -d)
cp ./*.html sym.js "$work/"
cp ../../../apps/mac/Seam/Assets.xcassets/AppIcon.appiconset/icon_128x128.png "$work/AppIcon.png"
mkdir "$work/sym"
(cd "$work" && swift "$src/assets.swift")
curl -sfL -o "$work/Roboto.ttf" "https://github.com/google/fonts/raw/main/ofl/roboto/Roboto%5Bwdth,wght%5D.ttf"

shot() { # page, frame, width, height
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --disable-gpu --hide-scrollbars \
    --allow-file-access-from-files --force-device-scale-factor=2 --window-size="$3,$4" --default-background-color=00000000 \
    --virtual-time-budget=2000 --screenshot="$work/$1-$2.png" "file://$work/$1.html#$2" 2>/dev/null
}
for f in $(seq 1 19); do shot hero "$f" 1000 920; done
shot pairing 1 840 610

python3 - "$work" <<'PY'
import sys
from PIL import Image
work = sys.argv[1]
durs = [600,100,100,100,300,150,150,150,150,150,150,100,100,100,100,100,300,600,2500]
# The hero shows at 560 wide, so 1x frames keep it small; the stills stay 2x for sharp text
fr = [Image.open(f"{work}/hero-{i}.png").convert("RGBA").resize((1000, 920), Image.LANCZOS) for i in range(1, 20)]
fr[0].save("../hero-animated.png", save_all=True, append_images=fr[1:], duration=durs, loop=0, optimize=True)
for name in ("pairing",):
    Image.open(f"{work}/{name}-1.png").save(f"../{name}.png", optimize=True)
PY
rm -rf "$work"
