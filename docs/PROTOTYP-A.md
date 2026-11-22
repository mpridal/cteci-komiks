# Prototyp A: čtení nahlas

Ověřuje, jestli Čtečka spolehlivě pozná přečtená slova. Brána: aspoň 85 % slov bez ručního posunu a odezva pod 1 s. Zároveň se srovnává Vosk se systémovým rozpoznávačem a hlídá, aby hvězdička za těžké slovo nepřišla omylem. Vše jen česky.

## Jak spustit

1. Model (jednou): `python studio/tools/vosk_model.py install --source <vosk-model-small-cs-0.4-rhasspy.zip>`
2. Obsah (po změně kapitol): `studio/.venv/Scripts/python studio/tools/export_proto_a.py`
3. Emulátor tabletu s mikrofonem počítače:
   `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd CteciKomiks_Tablet -allow-host-audio`
   (bez `-allow-host-audio` dostane emulátor místo zvuku nuly)
4. Build a instalace ze složky `app/`: `gradlew test :app:installDebug`
   (buildy jdou do `%LOCALAPPDATA%\cteci-komiks-build`, protože OneDrive zamyká `build/`)

## Jak testovat

- Čtěte nahlas do mikrofonu počítače 5 stránek. Aktuální slovo je žluté, přečtená šednou.
- **Dál ▶** posune o jedno slovo ručně, to se počítá proti bráně.
- Nahoře se přepíná **Vosk / Systém** a u Vosku režim **Volně / Stránka + 1000 / Jen stránka**. Po 5 stránkách se ukáže souhrn s výsledkem brány.
- Zkuste i **ticho a hovor v místnosti**: text se nesmí posouvat sám.
- Zkuste těžká slova schválně **přečíst špatně** (KOČKU jako KOČKA, STŘÍKÁ jako SŘÍKÁ) a zapište si, kolikrát přišla hvězdička omylem.

## Záznamy relací a přehrání

Po každém průchodu uloží Čtečka záznam do `files/sessions/` (formát `proto-a-session/2`). Záznam obsahuje:
- slova stránky se slabikami,
- surový i filtrovaný výstup rozpoznávače s časy,
- jistotu slov,
- hlasitost po 100 ms,
- ruční posuny.

Zvuk se neukládá.

1. Stažení z emulátoru: `python -I asr-bench/scripts/pull_sessions.py` (ze složky `app/`). Záznamy jdou do `app/asr-bench/sessions/`, která je v `.gitignore`, protože repo je veřejné a záznam je přepis čtení dítěte.
2. Přehrání s jiným nastavením filtru: `gradlew :asr-bench:replay --args="asr-bench/sessions [snrDb] [stableMs]"`. Ukáže, jak by stejné čtení dopadlo s jiným nastavením, bez dalšího čtení nahlas.
3. Syntetické fixtures z nahrávek (bez dítěte) jsou v `asr-bench/src/test/resources/sessions/`. Test ověřuje, že přehrání dá stejný výsledek jako živý běh.

## Zjištění (22. 11. 2026)

- **Slovník modelu:** model má 63 020 slov. Z 39 slov kapitol nezná 5, včetně jména VIKY (dál MUŠLE, MUŠLI, STŘÍKÁ, SUŠENKU). Nezná ani 21 z 38 slabik (VI, KY, HU, RÁ…).
  - Řešení: pole `asr` s podobně znějícími náhradami (VIKY → vika, víko, vísky) a `[unk]` u neznámých slov.
- **Falešná shoda:** s gramatikou jen ze slov stránky Vosk napasuje každou řeč na slova stránky. Šum to nedělá, cizí hovor ano. Řeší to volné rozpoznávání nebo rušivá slova v gramatice (viz měření).
- **Odměna:** posun kurzoru je tolerantní (0,34), ale hvězdička přijde jen za přesnou shodu. Chybné čtení typu SŘÍKÁ místo STŘÍKÁ ji nedá.
- **Slabiky z volného rozpoznávání** se skládají („koč“ „ku“ → KOČKU).

## Měření (asr-bench)

Lavice `app/asr-bench` prožene Vosk na PC stejným filtrem a sledováním (`:reader-core`) přes nahrávky z `asr-bench/scripts/make_corpus.py`. Všechny nahrávky jsou česky, hlas Windows Jakub:
- **dospělé čtení** slovo po slově, celými slovy i po slabikách,
- **dětsky znějící čtení:** hlas o 60 % výš, tempo 0,7, nepravidelné pauzy, zaváhání („MO… MOŘI“), „ehm“,
- **chybné čtení** těžkých slov (vynechaná souhláska ze shluku),
- **dětské čtení s tichším hovorem dospělého v pozadí,**
- **ticho, bílý šum, brum,**
- **cizí český hovor:** zprávy, řeč doma, sourozenec (dětský hlas).

Filtr: hlas aspoň 12 dB nad šumem, slovo stabilní 200 ms (`HypothesisFilter`).

### Výběr gramatiky (dospělý hlas)

| Gramatika | Čtení | Po slabikách | Cizí český hovor (posunutých slov z 46) | Hvězdičky omylem |
|---|---|---|---|---|
| jen stránka | 93 % | 91 % | 38 | 4 |
| stránka + 300 rušivých | 91 % | 89 % | 7 | 2 |
| stránka + 1000 rušivých | 87 % | 76 % | 3 | 2 |
| stránka + 3000 rušivých | 85 % | 76 % | 2 | 2 |
| volně (celý slovník) | 91 % | 89 % | 5 | 0 |

Ticho, šum a brum dávají ve všech režimech 0 falešných posunů. Proti cizí řeči rozhoduje gramatika, ne hlasitost.

### Dětský hlas, cizí český hovor

| Režim | Dětské čtení | Dětské slabikování | Cizí hovor: zprávy / doma / sourozenec | Čtení s hovorem v pozadí | Hvězdičky omylem (dět.) | Odezva medián / 90 % |
|---|---|---|---|---|---|---|
| **volně, stabilita 200 ms (výchozí)** | **93 %** | 72 % | 8 / 7 / 9 | 67 % | 1 | 0,35 / 0,63 s |
| volně, stabilita 100 ms | 93 % | 83 % | 8 / 7 / 10 | 67 % | 1 | 0,25 / 1,5 s |
| stránka + 1000, 200 ms | 89 % | 76 % | **3 / 4 / 6** | 61 % | 2 | 0,30 / 0,49 s |

Poznámky:
- Stabilita 150 ms dává totéž co 200 ms, protože zvuk chodí po 100 ms.
- **Čtení celými slovy** by bránu prošlo: dětský hlas má 93 % a odezvu pod 1 s i u 90 % slov.
- **Slabá místa:**
  - dětské slabikování (72 %),
  - čtení, když v místnosti někdo mluví (61–67 %),
  - cizí český hovor posune 7–9 slov ze 46 (sourozenec nejvíc).
- „Stránka + 1000“ cizí řeč tlumí lépe, ale hůř čte. Rozhodne čtení Viky.

Spuštění lavice (ze složky `app/`, cesty bez mezer):
```
python -I asr-bench/scripts/make_corpus.py --out %LOCALAPPDATA%/cteci-komiks-build/asr-corpus-cs
set VOSK_VOCAB=<repo>/studio/asr/vosk-cs-words.txt
set DISTRACTORS=1000
gradlew :asr-bench:run --args="<korpus> <model> <výstup.json> [konfigurace,...]"
```
(konfigurace bez diakritiky, mezera jako `_`, např. `volne_/_hlas+stabilita`; `DUMP_SESSIONS=<složka>` uloží záznamy relací)

- **APK** má kvůli modelu 117 MB, v MVP stačí jen ABI tabletu (arm64).
