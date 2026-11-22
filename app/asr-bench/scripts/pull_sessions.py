#!/usr/bin/env python3
"""Stáhne záznamy relací prototypu A z emulátoru (nebo zařízení) do app/asr-bench/sessions/.

Použití (ze složky app/):
  python -I asr-bench/scripts/pull_sessions.py [--serial emulator-5556]

Záznamy obsahují přepis čtení dítěte a hlasitost, proto jsou v .gitignore (repo je veřejné).
Přehrání: gradlew :asr-bench:replay --args="asr-bench/sessions"
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

PKG = "io.github.mpridal.ctecikomiks"
OUT = Path(__file__).resolve().parents[1] / "sessions"


def adb_path() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    exe = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    return str(exe) if exe.exists() else "adb"


def run(adb: str, serial: str, *args: str) -> bytes:
    return subprocess.run([adb, "-s", serial, "exec-out", "run-as", PKG, *args],
                          check=True, capture_output=True).stdout


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default="emulator-5556")
    args = ap.parse_args(argv)
    adb = adb_path()
    try:
        names = [n for n in run(adb, args.serial, "ls", "files/sessions").decode().split() if n.endswith(".json")]
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        print(f"Nelze číst záznamy ze zařízení {args.serial}: {e}", file=sys.stderr)
        return 2
    OUT.mkdir(exist_ok=True)
    new = 0
    for n in names:
        dst = OUT / n
        if dst.exists():
            continue
        dst.write_bytes(run(adb, args.serial, "cat", f"files/sessions/{n}"))
        new += 1
    print(f"{len(names)} záznamů na zařízení, {new} nových → {OUT}")
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
