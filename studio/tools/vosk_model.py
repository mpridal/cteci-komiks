#!/usr/bin/env python3
"""Instalace českého modelu Vosk do Čtečky a výtah jeho slovníku pro Studio.

Použití:
  vosk_model.py install --source cesta/k/vosk-model-small-cs-0.4-rhasspy(.zip|/)
  vosk_model.py vocab   --source cesta/k/modelu        jen slovník

Výstup:
  app/app/src/main/assets/model-cs/      model pro Čtečku (v .gitignore, ~67 MB)
  studio/asr/vosk-cs-words.txt           slovník modelu (jedno slovo na řádek), pro asr_hints

Model: vosk-model-small-cs-0.4-rhasspy, licence MIT (https://github.com/rhasspy/cs_kaldi-rhasspy),
stažení: https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip (rodič/Claude ručně).
"""
from __future__ import annotations

import argparse
import shutil
import struct
import sys
import tempfile
import uuid
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "app" / "src" / "main" / "assets" / "model-cs"
VOCAB = ROOT / "studio" / "asr" / "vosk-cs-words.txt"


def _str(f) -> str:
    n = struct.unpack("<i", f.read(4))[0]
    return f.read(n).decode("utf-8", "replace")


def _symtab(f) -> list[str]:
    magic = struct.unpack("<i", f.read(4))[0]
    if magic != 2125658996:
        raise ValueError("neplatná tabulka symbolů")
    _str(f)
    _, size = struct.unpack("<qq", f.read(16))
    out = []
    for _ in range(size):
        s = _str(f)
        f.read(8)
        out.append(s)
    return out


def fst_words(path: Path) -> list[str]:
    """Výstupní symboly (slova) z OpenFst souboru Gr.fst."""
    with open(path, "rb") as f:
        f.read(4)
        _str(f)
        _str(f)
        _, flags = struct.unpack("<ii", f.read(8))
        f.read(32)
        tabs = []
        if flags & 1:
            tabs.append(_symtab(f))
        if flags & 2:
            tabs.append(_symtab(f))
    if not tabs:
        raise ValueError(f"{path}: bez tabulky slov")
    return [w for w in tabs[-1] if w and not w.startswith(("<", "!", "#"))]


def model_dir(source: Path, tmp: Path) -> Path:
    if source.is_file() and source.suffix == ".zip":
        with zipfile.ZipFile(source) as z:
            for name in z.namelist():
                if ".." in Path(name).parts or Path(name).is_absolute():
                    raise ValueError(f"podezřelá cesta v zipu: {name}")
            z.extractall(tmp)
        source = tmp
    for gr in source.rglob("Gr.fst"):
        return gr.parent.parent
    raise ValueError(f"{source}: model nenalezen (chybí graph/Gr.fst)")


def write_vocab(model: Path) -> int:
    words = fst_words(model / "graph" / "Gr.fst")
    VOCAB.parent.mkdir(parents=True, exist_ok=True)
    VOCAB.write_text("\n".join(words) + "\n", encoding="utf-8")
    return len(words)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("\n\n", 1)[1])
    ap.add_argument("action", choices=["install", "vocab"])
    ap.add_argument("--source", type=Path, required=True, help="zip nebo rozbalená složka modelu")
    args = ap.parse_args(argv)
    try:
        with tempfile.TemporaryDirectory() as t:
            model = model_dir(args.source, Path(t))
            n = write_vocab(model)
            print(f"Slovník: {n} slov → {VOCAB}")
            if args.action == "install":
                if ASSETS.exists():
                    backup = Path(tempfile.gettempdir()) / f"model-cs-zaloha-{uuid.uuid4().hex[:8]}"
                    shutil.move(str(ASSETS), backup)
                    print(f"Původní model přesunut do {backup}")
                shutil.copytree(model, ASSETS)
                # StorageService ve vosk-android podle uuid pozná, zda model znovu rozbalit
                (ASSETS / "uuid").write_text(uuid.uuid4().hex + "\n", encoding="ascii")
                print(f"Model nainstalován do {ASSETS}")
    except (OSError, ValueError, zipfile.BadZipFile) as e:
        print(f"Chyba: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
