# Prototyp A: čtení nahlas

Ověřuje, jestli Čtečka spolehlivě pozná přečtená slova. Brána: aspoň 85 % slov bez ručního posunu a odezva pod 1 s. Zároveň se srovnává Vosk se systémovým rozpoznávačem a hlídá, aby hvězdička za těžké slovo nepřišla omylem.

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
- Nahoře se přepíná **Vosk / Systém**. Po 5 stránkách se ukáže souhrn s výsledkem brány.
- Zkuste i **ticho a hovor v místnosti**: text se nesmí posouvat sám.
- Zkuste těžká slova schválně **přečíst špatně** (KOČKU jako KOČKA, STŘÍKÁ jako STRAKA) a zapište si, kolikrát přišla hvězdička omylem.
- Záznam relace se uloží do `files/sessions/` v aplikaci a slouží jako fixtures pro unit testy:
  `adb -s emulator-5556 exec-out run-as io.github.mpridal.ctecikomiks ls files/sessions`

## Zjištění zatím (22. 11. 2026)

- **Slovník modelu:** model má 63 020 slov. Z 39 slov kapitol nezná 5, včetně jména VIKY (dál MUŠLE, MUŠLI, STŘÍKÁ, SUŠENKU). Nezná ani 21 z 38 slabik (VI, KY, HU, RÁ…). Čtení po slabikách proto půjde jen u slabik, které ve slovníku jsou (MA, MI, TO, VO…).
  - Řešení: pole `asr` s podobně znějícími náhradami (VIKY → vika, víko, vísky) a `[unk]` u neznámých slov.
- **Falešná shoda (vyřešeno, viz měření):** s gramatikou jen ze slov stránky Vosk napasuje každou řeč na slova stránky. Šum to nedělá, cizí hovor ano.

## Měření filtru šumu (asr-bench, 22. 11. 2026)

Lavice `app/asr-bench` prožene Vosk na PC stejným sledováním (`:reader-core`) přes nahrávky z `asr-bench/scripts/make_corpus.py`:
- 5 stránek čte český hlas Windows Jakub slovo po slově, jednou celými slovy a jednou po slabikách,
- chybné čtení těžkých slov (vynechaná souhláska ze shluku),
- čtení s anglickým hovorem v pozadí,
- ticho, bílý šum, brum, anglický a cizí český hovor.

Filtr = hlasitost aspoň 12 dB nad šumem + slovo stabilní 200 ms (`HypothesisFilter`).

| Gramatika | Čtení | Po slabikách | Cizí hovor EN / CZ (posunutých slov z 46) | Hvězdičky omylem | Odezva medián / 90 % |
|---|---|---|---|---|---|
| jen stránka | 93 % | 91 % | 43 / 38 | 4 | 0,2 / 0,3 s |
| stránka + 300 rušivých | 91 % | 89 % | 13 / 7 | 2 | 0,35 / 0,54 s |
| stránka + 1000 rušivých | 87 % | 76 % | 6 / 3 | 2 | 0,38 / 0,55 s |
| stránka + 3000 rušivých | 85 % | 76 % | 0 / 2 | 2 | 0,38 / 0,55 s |
| **volně (celý slovník)** | **91 %** | **89 %** | **5 / 5** | **0** | 0,5 / 1,1 s |

Ticho, šum a brum dávají ve všech režimech 0 falešných posunů. Filtr hlasitosti a jistoty proti cizí řeči nepomáhá, rozhoduje gramatika. Stabilita 200 ms pomáhá hlavně ve volném režimu (bez ní jen 70 % čtení), protože průběžné výsledky tam kolísají. Čtení přes hlasitý hovor v pozadí selhává ve všech režimech, které cizí řeč odfiltrují (13–39 %).

**Výchozí režim Čtečky: Vosk volně + filtr.** Pro srovnání naživo jde v aplikaci přepnout na „Stránka + 1000“ a „Jen stránka“. Pozor: syntetický dospělý hlas je snazší než dětský. Bránu rozhodne až čtení dcery.

Spuštění lavice (ze složky `app/`, cesty bez mezer):
```
python -I asr-bench/scripts/make_corpus.py --out %LOCALAPPDATA%/cteci-komiks-build/asr-corpus
set VOSK_VOCAB=<repo>/studio/asr/vosk-cs-words.txt
set DISTRACTORS=300,1000,3000
gradlew :asr-bench:run --args="<korpus> <model> <výstup.json>"
```
- **APK** má kvůli modelu 117 MB, v MVP stačí jen ABI tabletu (arm64).
