# Čtecí komiks

Aplikace pro trénink čtení: Studio (tento repozitář ovládaný z Claude Code) vytváří komiksové kapitoly, Android Čtečka je offline přehrává a podle čtení nahlas spouští zvuky.

Úplná specifikace je v `docs/SPEC.md`. Před první změnou ji přečti celou.

## Jak pracovat

- Komunikuj česky.
- Vše jen česky: aplikace nemá jazykové mutace, rozpoznávání i testy (asr-bench) řeší jen češtinu.
- Začni prototypy A a B (SPEC.md, sekce Fáze vývoje). MVP nestav, dokud neprojde brána.
- Než začneš část, které se týká některá z Otevřených otázek, zeptej se.
- Jako první krok založ `.gitignore` se `studio/photos/` a `dist/`. Fotky nikdy necommituj.
- Text pro čtenářku vždy ověř skriptem `studio/tools/check_text.py`, ne odhadem (`--text "..."` pro rychlou kontrolu, nebo cesta k `chapter.json`).
- Testy Studia: `python -m unittest discover -s studio/tools/tests`.
- Vývoj Čtečky v emulátoru Android Studia, tablet se nepřipojuje.
- Formát balíčku (`formatVersion`) měň jen spolu se zápisem změny do `docs/SPEC.md`.
- Android: Kotlin + Jetpack Compose, minSdk 26, targetSdk 36. Před commitem spusť `./gradlew test`.
- Cílové zařízení: tablet JENBIZZ 10" (Android 16), čtenářka 7 let.
