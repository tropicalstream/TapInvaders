#!/usr/bin/env python3
"""
Pre-generate the space sweeper voice lines (sequel shift) with fish.audio S2.1 Pro
(free developer tier: https://fish.audio/blog/s2-1-pro-free-api/).

Player character voice model:
  https://fish.audio/app/m/1864d40339ae4dbabf832f844c8d1d6f/

Usage:
  export FISH_API_KEY=...          # from https://fish.audio developer console
  python3 tools/generate_tts.py    # writes app/src/main/assets/tts/<id>.mp3
  ./gradlew assembleDebug          # clips ship inside the APK

A phrase may be a single string ("id": "...") or a list of variants
("id": ["...", "...", ...]) that the app picks between at random — those
generate one file per variant, named <id>_<index>.mp3.

Already-generated phrases are skipped; delete a file to force regeneration.
Requires: pip install requests
"""

import json
import os
import sys
import time
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("pip install requests")

ROOT = Path(__file__).resolve().parent.parent
PHRASES = ROOT / "app/src/main/assets/phrases.json"
OUT_DIR = ROOT / "app/src/main/assets/tts"
VOICE_MODEL_ID = "1864d40339ae4dbabf832f844c8d1d6f"  # the space sweeper
API_URL = "https://api.fish.audio/v1/tts"


def main() -> None:
    api_key = os.environ.get("FISH_API_KEY")
    if not api_key:
        sys.exit("Set FISH_API_KEY first (free tier: fish.audio developer console)")

    phrases = json.loads(PHRASES.read_text())
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # Flatten to (output_stem, text) pairs: single string -> "id", list -> "id_0", "id_1", ...
    jobs: list[tuple[str, str]] = []
    for pid, value in phrases.items():
        if isinstance(value, list):
            for i, text in enumerate(value):
                jobs.append((f"{pid}_{i}", text))
        else:
            jobs.append((pid, value))

    done = skipped = failed = 0
    for stem, text in jobs:
        out = OUT_DIR / f"{stem}.mp3"
        if out.exists() and out.stat().st_size > 0:
            skipped += 1
            continue
        resp = requests.post(
            API_URL,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
                "Model": "s1",  # server maps to the current S2.1 Pro engine
            },
            json={
                "text": text,
                "reference_id": VOICE_MODEL_ID,
                "format": "mp3",
                "mp3_bitrate": 64,
                "normalize": True,
                "latency": "normal",
            },
            timeout=60,
        )
        if resp.status_code == 200 and resp.content:
            out.write_bytes(resp.content)
            print(f"  ok  {stem}: {text[:60]}")
            done += 1
        else:
            print(f" FAIL {stem}: HTTP {resp.status_code} {resp.text[:120]}")
            failed += 1
        time.sleep(0.4)  # be polite to the free tier

    print(f"\ngenerated={done} skipped={skipped} failed={failed}")
    print(f"clips in {OUT_DIR}")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
