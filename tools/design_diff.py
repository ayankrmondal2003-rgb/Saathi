"""Design-difference scores for screens 01-14 in Light and Dark (28 pairs).

    python tools/design_diff.py capture [--serial emulator-5554]   # run the capture test at the design's 390dp width
    python tools/design_diff.py score <captures-dir> [--baseline]   # score against design-reference/crops

`capture` needs a running emulator (never a phone: it installs the debug build) and pulls the PNGs into
build/design-diff/. It sets the emulator to 390dp wide for the run and restores the density afterwards.

`score` compares the board area of each screen (below the status bar, above the tab bar: board units 28-757).
Both images are reduced to "ink" (pixels that stand out from the page background: text, icons, card edges), the
capture is shifted vertically to line up with the design (phones have taller status bars than the design's 28 units),
and the score is the share of ink in each image that has ink within 2 units in the other, 0-100.

What the numbers mean: a wrong screen scores about 15-25. Real screens land well above that, but not at 100: the
design uses Inter and the app the system font, the design shows sample data ("Maya", "Rosewood Park") and screen 03
is intentionally different (Google sign-in). So the useful signal is the change against the saved baseline in
design-reference/scores.json: a screen that drops by more than TOLERANCE points fails the run. `--baseline` saves
the current scores. A design | app | overlap sheet per screen is written next to the captures.
"""
import json
import os
import subprocess
import sys
from collections import Counter
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parents[1]
CROPS = ROOT / "design-reference" / "crops"
BASELINE = ROOT / "design-reference" / "scores.json"
OUT = ROOT / "build" / "design-diff"
TOLERANCE = 3.0
TOP, BOTTOM = 28, 757   # board units: below the status bar, above the tab bar
U = 2                   # pixels per board unit while scoring
NEAR = 2                # units of shift allowed between matching ink
SEARCH = 44             # units of vertical alignment searched
SCREENS = [f"{t}-{n:02d}" for t in ("light", "dark") for n in range(1, 15)]


def adb_path():
    """adb from PATH, ANDROID_HOME / ANDROID_SDK_ROOT, or sdk.dir in local.properties (as Gradle finds the SDK)."""
    import shutil
    found = shutil.which("adb")
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    props = ROOT / "local.properties"
    if not sdk and props.exists():
        for line in props.read_text(encoding="utf8").splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
    exe = Path(sdk or ".") / "platform-tools" / ("adb.exe" if sys.platform == "win32" else "adb")
    if not exe.exists():
        sys.exit("adb not found: put it on PATH or set ANDROID_HOME")
    return str(exe)


def adb(serial, *args, check=True):
    return subprocess.run([adb_path(), "-s", serial, *args], capture_output=True, text=True, check=check).stdout


def capture(serial):
    if not serial.startswith("emulator-"):
        sys.exit("capture installs the debug build; run it on an emulator only")
    width_px = int(adb(serial, "shell", "wm", "size").split()[-1].split("x")[0])
    adb(serial, "shell", "wm", "density", str(round(width_px * 160 / 390)))
    try:
        gradle = str(ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))
        subprocess.run([gradle, "-q", ":app:installDebug", ":app:installDebugAndroidTest"], cwd=ROOT, check=True,
                       env={**os.environ, "ANDROID_SERIAL": serial})
        remote = "/sdcard/Android/data/in.maitri.app/files/checkpoint-d"
        adb(serial, "shell", "rm", "-rf", remote, check=False)
        result = adb(serial, "shell", "am", "instrument", "-w", "-e", "class",
                     "in.maitri.app.MaitriAllScreensCaptureTest#captureAllScreensInBothThemes",
                     "in.maitri.app.test/androidx.test.runner.AndroidJUnitRunner")
        if "OK (" not in result:
            sys.exit("capture test failed:\n" + result[-2000:])
        OUT.mkdir(parents=True, exist_ok=True)
        adb(serial, "pull", remote + "/.", str(OUT))
    finally:
        adb(serial, "shell", "wm", "density", "reset", check=False)
    print("captures in", OUT)


def ink(im):
    g = ImageOps.grayscale(im).filter(ImageFilter.GaussianBlur(1.2))
    page = Counter(g.crop((0, TOP * U, g.width, BOTTOM * U)).get_flattened_data()
                   if hasattr(g, "get_flattened_data") else g.crop((0, TOP * U, g.width, BOTTOM * U)).getdata())
    bg = page.most_common(1)[0][0]
    return g.point(lambda v: 255 if abs(v - bg) > 40 else 0)


def overlap(a, b):
    grow = ImageFilter.MaxFilter(2 * NEAR * U + 1)
    na, nb = a.histogram()[255], b.histogram()[255]
    hits = ImageChops.multiply(a, b.filter(grow)).histogram()[255] + ImageChops.multiply(b, a.filter(grow)).histogram()[255]
    return hits / max(1, na + nb)


def score_one(crop_path, shot_path):
    crop = Image.open(crop_path).convert("RGB").resize((390 * U, 844 * U))
    shot = Image.open(shot_path).convert("RGB")
    shot = shot.resize((390 * U, round(shot.height * 390 * U / shot.width)))
    height = (BOTTOM - TOP) * U
    design = ink(crop).crop((0, TOP * U, 390 * U, BOTTOM * U))
    app_ink = ink(shot)

    def at(dy):
        y0 = TOP * U + dy * U
        return app_ink.crop((0, y0, 390 * U, y0 + height))

    coarse = max(range(-SEARCH, SEARCH + 1, 4), key=lambda dy: overlap(design, at(dy)))
    dy = max(range(coarse - 3, coarse + 4), key=lambda d: overlap(design, at(d)))
    app = at(dy)
    y0 = TOP * U + dy * U
    return round(100 * overlap(design, app), 1), dy, crop.crop((0, TOP * U, 390 * U, BOTTOM * U)), \
        shot.crop((0, y0, 390 * U, y0 + height)), design, app


def sheet(name, s, crop, shot, design, app):
    both = Image.merge("RGB", (ImageOps.invert(design), ImageOps.invert(app), ImageOps.invert(app)))  # cyan: design only
    w = crop.width
    out = Image.new("RGB", (w * 3 + 16, crop.height + 24), "white")
    for i, (im, label) in enumerate(((crop, "design"), (shot, "app"), (both, "cyan: design only, red: app only, black: both"))):
        out.paste(im, (i * (w + 8), 24))
        ImageDraw.Draw(out).text((i * (w + 8) + 4, 6), f"{name} {label}  {s if i == 0 else ''}", fill="black")
    return out


def score(directory, write_baseline):
    directory = Path(directory)
    baseline = json.loads(BASELINE.read_text()) if BASELINE.exists() else {}
    scores, worse = {}, []
    for name in SCREENS:
        s, dy, crop, shot, design, app = score_one(CROPS / f"{name}.png", directory / f"{name}.png")
        scores[name] = s
        sheet(name, s, crop, shot, design, app).save(directory / f"compare-{name}.png")
        before = baseline.get(name)
        note = ""
        if before is not None:
            note = f"  (baseline {before})"
            if s < before - TOLERANCE:
                worse.append(name)
                note += "  WORSE"
        print(f"{name}: {s:5.1f}  shift {dy:+d}{note}")
    if write_baseline:
        BASELINE.write_text(json.dumps(scores, indent=1) + "\n")
        print("baseline written:", BASELINE)
    elif worse:
        sys.exit(f"{len(worse)} screen(s) drifted from the design: {', '.join(worse)}")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args and args[0] == "capture":
        capture(args[args.index("--serial") + 1] if "--serial" in args else "emulator-5554")
    elif len(args) >= 2 and args[0] == "score":
        score(args[1], "--baseline" in args)
    else:
        sys.exit(__doc__)
