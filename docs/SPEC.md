# Čtecí komiks – specifikace pro Claude Code (varianta A)

Verze: 21. 11. 2026 · autor: Miloš

## Cíl a rozsah

Aplikace pomáhá sedmileté čtenářce trénovat čtení nahlas: čte komiks vytvořený podle jejích fotek a zvolených témat a přečtená slova oživují zvuky, ruchy, hudba a pohyb postav.

Systém má dvě části:

- **Studio** – Git repozitář ovládaný z Claude Code v interaktivní relaci (běžné předplatné, žádný API klíč). Rodič dodá fotky a klíčová slova, Claude napíše kapitolu, složí obrázky z SVG loutek, vybere zvuky a publikuje balíček.
- **Čtečka** – Android aplikace primárně pro tablet (JENBIZZ 10", Android 16) s omezeným režimem pro telefon. Funguje offline, stahuje nové kapitoly z GitHubu, poslouchá čtení a spouští zvuky.

Mimo rozsah první verze:

- bitmapové obrázky z AI (Gemini apod.) – formát balíčku je na ně jen připravený (varianta B),
- zvuky generované AI,
- jakékoli volání AI služby z Android aplikace,
- více dětí a profilů, iOS.

## Architektura

Rodič vytváří kapitoly v Claude Code na PC, GitHub je přenáší a Čtečka je na tabletu přehrává bez internetu a bez AI.

```
Rodič: fotky a klíčová slova
   │
   ▼
┌─ Studio: Claude Code a repozitář ───────────────────────────────┐
│  Příběhová bible     Sada SVG loutek        Knihovna zvuků      │
│  (postavy, styl,     (díly postav,          (efekty a hudba     │
│   shrnutí kapitol)    pozadí, rekvizity)     s licencí)         │
└─────────────────────────────────────────────────────────────────┘
   │  /publikuj: validace a zip balíček
   ▼
┌─ GitHub: veřejné repo, Releases ────────────────────────────────┐
│  Balíčky kapitol     Katalog                APK Čtečky          │
│  (chapter.json,      (seznam kapitol        (aktualizace        │
│   SVG vrstvy, OGG)    a SHA-256)             aplikace)          │
└─────────────────────────────────────────────────────────────────┘
   │  stažení nových kapitol a aktualizací
   ▼
┌─ Čtečka: Android tablet, offline ───────────────────────────────┐
│  Zobrazení           Sledování čtení        Zvuk                │
│  (panely, bubliny,   (Vosk, slova stránky)  (efekty, hudba,     │
│   písmo)                                     animace)           │
└─────────────────────────────────────────────────────────────────┘
              ▲ mikrofon tabletu              │ do sluchátek
              │                               ▼
                       Dcera čte nahlas
```

Sledování čtení je nejrizikovější část, proto ho jako první ověří prototyp A. Cokoli Čtečka potřebuje, je v balíčku kapitoly.

## Repozitář a datový model

Jedno repo obsahuje Studio i Čtečku. Claude Code v něm vidí bibli, sadu postav i všechny dřívější kapitoly, a proto drží kontinuitu bez dalšího nástroje.

```
cteci-komiks/
  CLAUDE.md                  pravidla pro Claude Code, odkaz na tuto specifikaci
  docs/SPEC.md               tato specifikace
  .claude/commands/          příkazy Studia (nova-postava, nova-serie, nova-kapitola, publikuj)
  studio/
    bible.json               příběhová bible série
    reader-profile.json      úroveň čtení: známá písmena, typ písma, délky
    schema/                  JSON Schema pro bible, character, chapter, catalog
    kit/
      characters/<id>/       díly postavy (SVG) + character.json
      backgrounds/           pozadí (SVG)
      props/                 rekvizity (SVG)
    sounds/
      library/               efekty a hudba (OGG)
      catalog.json           popisky, tagy, licence, zdroj
    chapters/<serie>/<NN>/   zdrojová kapitola: scene.json + chapter.json
    practice/                v .gitignore: tréninkové záznamy z tabletu (inbox/ = nové exporty, history.json = souhrn)
    photos/                  v .gitignore, nikdy necommitovat
    tools/                   skripty: validace, kontrola textu, skládání panelů, náhledy PNG, balíčky
  app/                       Android Čtečka
  dist/                      výstup: zip balíčky kapitol (ne v gitu, jdou do Releases)
```

**Příběhová bible** (`bible.json`) platí pro celou sérii a posílá se do každého generování:

- `series`: id, název, tón, zákazy (nic strašidelného, žádné násilí),
- `style`: paleta série, tloušťka obrysu, proporce postav, poměr stran panelu,
- `characters[]`: id, jméno, role, vzhled (vlasy, oči, oblečení, poznávací znaky), povaha, způsob řeči, odkaz na kit,
- `places[]`: id, popis, odkaz na pozadí,
- `chapters[]`: číslo, název, shrnutí ve 2–3 větách, otevřené nitky.

**Profil čtenářky** (`reader-profile.json`): `knownLetters` (písmena, která už zná ze slabikáře), `fontStage` (`UPPER` | `lower` | `script`), `maxWordsPerSentence` (výchozí 6), `maxSyllablesPerWord` (3), `maxWordsPerPanel` (12), `maxBubblesPerPanel` (2), `syllableHints` (ano/ne) a blok `practice` pro trénink těžkých slov:

- `easySyllableMaxLetters` (2; CH = jedno písmeno): delší slabika dělá slovo tréninkovým,
- `minPerChapter` (8): nejméně tréninkových slov v kapitole,
- `maxPerSentence` (2): nejvýš tréninkových slov ve větě, aby nebyla zahlcená,
- `focus`: slova a slabiky, které jí podle tabletu dělají potíže (plní `tools/update_practice.py`).

**Těžká (tréninková) slova** se nezakazují, ale cíleně trénují. Slovník má být široký. Kategorie: `long-syllable` (slabika delší než 2 písmena: KO-ČKA, DŮM, PES), `no-vowel` (slabika bez samohlásky: VLK, KRK, předložky V, S, K, Z), `focus` (obsahuje položku z `practice.focus`).

**Balíček kapitoly** je zip s `chapter.json`, složkou `panels/` a složkou `audio/` (jen použité zvuky). Panel může být typu `svg-layers` (varianta A, s animací), `svg` (jeden soubor) nebo `webp` (varianta B). Čtečka musí všechny tři typy umět od první verze.

```json
{
  "formatVersion": 1,
  "seriesId": "mour",
  "chapter": 3,
  "title": "MOUR A VÁZA",
  "readerLevel": { "fontStage": "UPPER" },
  "cover": "panels/cover.webp",
  "audio": [
    { "id": "calm_home", "file": "audio/calm_home.ogg", "kind": "music", "loop": true },
    { "id": "cat_meow", "file": "audio/cat_meow.ogg", "kind": "sfx" },
    { "id": "glass_break", "file": "audio/glass_break.ogg", "kind": "sfx" }
  ],
  "pages": [
    {
      "music": "calm_home",
      "panels": [
        {
          "id": "p1",
          "image": {
            "type": "svg-layers",
            "aspect": "4:3",
            "layers": [
              { "id": "bg", "file": "panels/p1/bg.svg" },
              { "id": "vika", "file": "panels/p1/vika.svg", "box": [0.10, 0.30, 0.38, 0.95] },
              { "id": "mour", "file": "panels/p1/mour.svg", "box": [0.66, 0.62, 0.92, 0.95] }
            ]
          },
          "texts": [
            {
              "id": "t1",
              "kind": "bubble",
              "speaker": "vika",
              "anchor": [0.30, 0.12],
              "words": [
                { "w": "TO", "syl": ["TO"] },
                { "w": "JE", "syl": ["JE"] },
                { "w": "MOUR.", "syl": ["MOUR"], "practice": ["long-syllable"], "cues": ["c1", "c2"] }
              ]
            }
          ],
          "cues": [
            { "id": "c1", "type": "sfx", "audio": "cat_meow" },
            { "id": "c2", "type": "anim", "layer": "mour", "action": "bounce" }
          ]
        }
      ]
    }
  ]
}
```

Pravidla formátu:

- Text nikdy není v obrázku, vždy v `texts` jako pole slov se slabikami. Interpunkce zůstává u slova, pro porovnání se odstraňuje.
- `box` a `anchor` jsou relativní souřadnice panelu (0–1): `box` = [x1, y1, x2, y2] vrstvy, `anchor` = střed bubliny.
- `practice` (volitelné) u slova = seznam kategorií těžkého slova. Doplňuje ho `check_text.py --annotate`, ručně se nepíše. Čtečka podle něj dává odměnu a zapisuje tréninkový záznam. Pole přibylo 22. 11. 2026 ve `formatVersion` 1 (volitelné, starší čtečka ho ignoruje, proto verze zůstává).
- `asr` (volitelné) u slova, které nezná slovník rozpoznávače: `{"oov": true, "alt": ["vika", "víko"]}`. `alt` jsou podobně znějící slova ze slovníku modelu. Čtečka je přidá do gramatiky a bere je jako přečtení slova. U `oov` přijme i token `[unk]`, ale bez odměny. Doplňuje ho `compose.py` (přes `tools/asr_hints.py`), když existuje `studio/asr/vosk-cs-words.txt`. Pole přibylo 22. 11. 2026 ve `formatVersion` 1 (volitelné).
- Značka (`cues`) se spustí, když čtenářka slovo přečte. Typy: `sfx`, `anim` (`bounce`, `jump`, `shake`, `wave`, `blink`), `music` (změna nálady).
- `formatVersion` se zvyšuje při nekompatibilní změně. Čtečka balíček s vyšší verzí nenahraje a nabídne aktualizaci aplikace.
- **Katalog** (`catalog.json` v posledním Release) obsahuje seznam kapitol: série, číslo, název, obálka, URL zipu, SHA-256, `formatVersion`, minimální verzi aplikace.

## Studio: příkazy v Claude Code

Studio nemá vlastní rozhraní: tvoří ho čtyři vlastní příkazy v `.claude/commands/` a skripty v `studio/tools/`. Vše běží v interaktivní relaci Claude Code, kde rodič výsledky průběžně schvaluje.

| Příkaz | Vstup | Co udělá | Výstup |
| --- | --- | --- | --- |
| `/nova-postava` | 1–3 fotky v `studio/photos/`, jméno, role | Popíše rysy z fotek, navrhne sadu dílů, vyrenderuje kartu postavy (PNG) ke schválení | `kit/characters/<id>/`, záznam v bibli |
| `/nova-serie` | název, postavy, prostředí, témata | Založí bibli, paletu a první pozadí | `bible.json`, `kit/backgrounds/` |
| `/nova-kapitola` | 2–5 klíčových slov, volitelně nová fotka nebo nápad | Napíše kapitolu, složí panely, přiřadí zvuky, ukáže náhledy | `chapters/<serie>/<NN>/` |
| `/publikuj` | číslo kapitoly | Validace, sestavení zipu, GitHub Release, aktualizace katalogu | balíček a `catalog.json` v Releases |

Postup `/nova-kapitola`:

1. Načti `bible.json`, `reader-profile.json` (včetně `practice.focus`), shrnutí všech kapitol a celé poslední dvě kapitoly. Pokud jsou v `studio/practice/inbox/` nové tréninkové záznamy z tabletu, nejdřív spusť `tools/update_practice.py`.
2. Navrhni osnovu (3–5 stránek, 4–8 panelů celkem) a nech ji rodiče schválit.
3. Napiš text podle pravidel níže a ověř ho skriptem `tools/check_text.py`. Kontrolu písmen a délek dělá skript, ne model.
4. Pro každý panel zapiš scénu do `scene.json` (pozadí, postavy s pózou a výrazem, rekvizity). Chybějící rekvizitu nakresli jako nové SVG do `kit/props/`.
5. Přiřaď 1–3 zvukové značky na stránku a hudbu nálady z knihovny.
6. Spusť `tools/compose.py` (složí vrstvy panelů) a `tools/render_previews.py` (PNG náhledy celých stránek i s bublinami). Ukaž náhledy a zapracuj připomínky.
7. Dopiš shrnutí kapitoly a otevřené nitky do bible.

Pravidla textu pro začínající čtenářku:

- Jen písmena z `knownLetters`. Nové písmeno se přidá až ve chvíli, kdy ho probírají ve škole.
- Věty nejvýš `maxWordsPerSentence` slov, slova nejvýš `maxSyllablesPerWord` slabik, na panel nejvýš `maxBubblesPerPanel` bublin a `maxWordsPerPanel` slov.
- Slovník je široký, těžká slova (viz Profil čtenářky) se nevyhýbají, ale dávkují: v kapitole aspoň `practice.minPerChapter`, ve větě nejvýš `practice.maxPerSentence`. Problémová slova a slabiky z `practice.focus` se v kapitole opakují, ideálně v situaci, kde je spojí se zvukem nebo pohybem (VLK → vytí).
- Po napsání textu se spustí `check_text.py --annotate`, který u slov doplní `practice`.
- Klíčová slova kapitoly se v textu objeví aspoň 3×.
- Slabiky v `syl` dělit podle pravidel čtení po slabikách (MA-MI-NKA, ne podle typografického dělení). Sporná slova nahradit jednoduššími.
- Přímá řeč odpovídá způsobu řeči postavy v bibli. Hlavní hrdinkou je postava z fotek.
- Obsah je laskavý, konflikty malé a vyřešitelné do konce kapitoly. Kapitola končí malým háčkem na další díl.

## Kresba: SVG loutkový systém

Konzistence postav se nehlídá až po vygenerování: každá postava se vždy skládá ze stejných dílů se stejnou paletou, takže vypadá stejně ve všech kapitolách.

**Sada postavy** (`kit/characters/<id>/character.json` + SVG díly):

- kostra s otočnými body: hlava, trup, ruce L/P, nohy,
- díly: vlasy vzadu, hlava, obličej, vlasy vpředu, oči (otevřené, zavřené, vykulené, ve smíchu), obočí, ústa (úsměv, otevřená, „O“, smutná, mluví), oblečení (výchozí + až 3 další), ruce (dole, mává, nahoru, ukazuje), nohy (stojí, jde, sedí, skočí),
- pevné barvy kůže, vlasů a oblečení,
- poznávací znaky z fotek (copy, brýle, pihy, oblíbená čelenka). Jsou povinné v každé póze.

Výrazy jsou kombinace očí, obočí a úst: radost, překvapení, smutek, přemýšlení, smích, lehký strach. Pózy jsou kombinace rukou a nohou: stát, jít, sedět, skákat, ukazovat, objet. Pohled zepředu a ze tří čtvrtin (druhá strana zrcadlením), profil až ve fázi 2.

**Scéna panelu** (`scene.json`): pozadí, záběr (celek, polocelek, detail = měřítko), postavy (`id`, pozice, měřítko, póza, výraz, směr), rekvizity (pozice, měřítko, natočení) a efekty (čáry pohybu, jiskry). Skript `tools/compose.py` z ní vytvoří vrstvy panelu: pozadí včetně statických rekvizit a jednu SVG vrstvu na každou postavu nebo animovanou rekvizitu.

Implementace (prototyp B, 22. 11. 2026):

- Díly postavy generuje `kit/characters/<id>/build.py`, pozadí a rekvizity `kit/build_scenery.py`. SVG se needitují ručně, aby zůstaly konzistentní.
- Přesný formát `scene.json` je popsaný v nápovědě `tools/compose.py`. Rekvizitu lze posadit na kotvu postavy (`"at": "viky.hands"`), statickou rekvizitu dát před postavu (`"layer": "front"` → vrstva `fg`).
- `compose.py` po složení rovnou zkontroluje text jako `check_text.py`. `render_previews.py` vytvoří PNG stránek a obálku `panels/cover.webp`.
- Zatím jen pohled zepředu (zrcadlení přes `flip`), pohled ze tří čtvrtin přijde po bráně.

Styl série:

- ploché barvy bez přechodů, obrys 2,5 px tmavě šedý (#2B2B2B), oblé tvary,
- dětské proporce postav (hlava zhruba třetina výšky těla),
- panel 4:3, horních ~25 % plochy bez důležité kresby (místo pro bubliny),
- barvy jen z palety série.

Kontinuita: doplňky postavy (kšiltovka a další) se během jedné kapitoly nemění. Kontroluje to `compose.py` jako chybu. Oblečení se měnit smí, když to vyžaduje děj (převlékne se do plavek).

Kontroly (`tools/check_kit.py`): panely používají jen díly ze sady a rekvizity z `kit/props/`, barvy jsou jen z palety, poznávací znaky jsou přítomné a SVG neobsahuje text, skripty ani externí odkazy.

## Zvuky a hudba

Zvuky se negenerují ani nevybírají za běhu. Studio je vybere z místní knihovny a do balíčku kapitoly přibalí jen ty použité.

- **Knihovna:** `studio/sounds/library/` + `catalog.json` s položkami id, soubor, popis (česky), tagy (zvíře, domov, příroda, legrace, kouzlo, počasí…), délka, licence, autor, zdrojová URL.
- **Licence:** přednostně CC0. U CC-BY uložit atribuci a zobrazit ji v Čtečce v sekci O aplikaci. Nic bez jasné licence.
- **Startovní sada:** zhruba 80 efektů a 8 hudebních smyček nálad (klid doma, dobrodružství, lehké napětí, legrace, kouzlo, noc, venku, oslava). Seznam s odkazy připraví Claude Code, stažení udělá rodič ručně, pokud web nejde stáhnout skriptem.
- **Syntetické zvuky:** kreslené efekty (skok, pop, cink, kouzlo, bublinka) vyrobí skript `tools/sfx_synth.py` ve stylu sfxr a uloží je do knihovny jako běžné položky.
- **Úprava:** vše do OGG Vorbis, efekty mono, hudba stereo, sjednocená hlasitost (loudness normalizace skriptem), smyčky bez klikání na přechodu.
- **Výběr:** Claude ke každé značce vybere nejbližší zvuk podle tagů a popisu. Když žádný nesedí, zapíše požadavek do `sounds/wishlist.md` a použije neutrální náhradu.
- **Hudba:** jedna smyčka na stránku, přechod mezi náladami crossfadem 1,5 s.

## Čtečka: zobrazení a čtení

Tablet ukazuje celou stránku komiksu, telefon jeden panel po druhém. Text vždy kreslí aplikace sama, aby ho mohla zvětšovat, dělit na slabiky a zvýrazňovat.

- **Tablet** (šířka ≥ 600 dp): stránka se 2–3 panely na výšku i na šířku. Panel, který se právě čte, je zvýrazněný, ostatní ztlumené.
- **Telefon:** režim panel po panelu na výšku, posun swipem nebo automaticky po dočtení panelu.
- **Bubliny:** bílé, kreslené aplikací nad obrázkem podle `anchor`, ocas míří k hlavě mluvčího. Vyprávění v obdélníku u horního okraje.
- **Bubliny nikdy nezasahují do hlavy:** ani bublina, ani její ocas nesmí překrýt hlavu, obličej nebo oči postavy. Ocas končí kousek před hlavou: mezera 14 px panelu od kruhu `headRadius` kolem kotvy `head` z `character.json`. Bublina mluvčího má ocas vždy (nikdy se nevynechá), dlouhý aspoň 32 px panelu. Když bublina hlavu překrývá nebo na ocas nezbývá místo, posune se: nejdřív stranou od hlavy, pak nahoru, pak na druhou stranu. Stejné pravidlo platí v `render_previews.py` i v Čtečce.
- **Písmo podle `fontStage`:** velká tiskací, malá tiskací, nebo psací. Pro tiskací písmo Andika (SIL, licence OFL, navrženo pro učení čtení), psací viz otevřené otázky. Velikost na tabletu 36 sp, na telefonu 28 sp, rodič ji může změnit.
- **Slabiky:** volitelnou nápovědou je střídavé podbarvení slabik dvěma jemnými barvami.
- **Čtecí okénko:** aktuální slovo má žluté podbarvení, přečtená slova jsou ztlumená, následující slovo nic nemá.
- **Nápověda:** dlouhý stisk slova ho přečte hlasem (Android TextToSpeech, cs-CZ) a slovo se označí jako „s nápovědou“. Rodič může nápovědu vypnout.
- **Ovládání pro dítě:** velká tlačítka s ikonami, knihovna jako obálky kapitol, tlačítko „dál“ je vždy po ruce. Žádné menu s textem.
- **Rodičovská sekce** za jednoduchým zámkem (příklad násobení): úroveň písma, slabiky, nápověda, hlasitosti, stažení kapitol, statistiky.
- **Statistiky** (fáze 2): čas čtení, počet přečtených slov, slova s nápovědou. Jen v tabletu.

## Čtečka: trénink těžkých slov a odměny

Těžká slova (pole `practice`) se cíleně trénují a za správné přečtení přijde odměna.

- **Správně přečteno** = slovo rozpoznané přímo (ne přeskočené posunem kurzoru), bez nápovědy a bez tlačítka „dál“. Při čtení po slabikách musí zaznít všechny slabiky v pořadí. Pro tréninková slova platí přísnější práh shody než pro ostatní (výchozí 0,2, ladí se v prototypu A), aby fanfáry nezazněly za chybné čtení.
- **Hvězdička:** po správně přečteném těžkém slově krátký „cink“ a hvězdička, která z místa slova odletí do počítadla v rohu stránky. Čtení se nepřerušuje, hudba se na okamžik ztlumí.
- **Fanfáry:** na konci stránky podle počtu hvězdiček (malé / střední / velké) s krátkou animací, při všech hvězdičkách stránky navíc konfety. Na konci kapitoly souhrn hvězdiček, ty se ukazují i na obálce kapitoly v knihovně.
- **Zvuky odměn** jsou součástí aplikace (ne balíčku kapitoly), CC0 nebo ze `sfx_synth.py`. Rodič může odměny ztlumit.
- **Nepřečtené těžké slovo** se netrestá: žádný zvuk ani ztráta hvězdičky, jen se zapíše do záznamu.

**Tréninkový záznam (adaptace):** Čtečka u každého výskytu tréninkového slova zapíše výsledek: kapitola, slovo, slabiky, kategorie, výsledek (`ok` | `hint` | `skipped` | `next`), doba od zvýraznění po přečtení a datum. Záznam neobsahuje zvuk ani přepis řeči.

- V rodičovské sekci „Poslat do Studia“ vytvoří soubor `practice-<datum>.json` a otevře systémové sdílení (OneDrive, e-mail…). Rodič ho uloží do `studio/practice/inbox/`.
- `tools/update_practice.py` záznamy sloučí do `studio/practice/history.json` a podle něj přepíše `practice.focus` v profilu: slova a slabiky s nejnižší úspěšností nebo nejdelší dobou čtení (nejvýš 10 položek). Co Viky opakovaně čte správně, z `focus` vypadne.

## Čtečka: rozpoznávání řeči

Aplikace řeč nepřepisuje, jen sleduje, kde v známém textu čtenářka je. Vše běží offline v tabletu a zvuk se nikam neukládá ani neodesílá.

**Engine:**

- Primárně Vosk (`com.alphacephei:vosk-android`) s malým českým modelem `vosk-model-small-cs-0.4-rhasspy` (asi 46 MB, licence MIT), přibaleným v aplikaci.
- Pro každou stránku nový recognizer s gramatikou: slova stránky, jejich slabiky a `[unk]`. Jestli model přijme slabiky jako samostatná slova, ověří prototyp A.
- **Zjištění prototypu A (22. 11. 2026, `docs/PROTOTYP-A.md`):**
  - Gramatika jen ze slov stránky napasuje i cizí řeč na slova stránky (37–43 z 46 slov falešně). Výchozí je proto **volné rozpoznávání celým slovníkem modelu** + filtr šumu (`HypothesisFilter`: hlas aspoň 12 dB nad šumem, slovo stabilní 200 ms). Alternativa je gramatika stránky + 1000 rušivých slov.
  - Slabiky model zná jen zčásti (17 z 38).
  - Slova mimo slovník řeší pole `asr`.
- Rozhraní `SpeechTracker` se dvěma implementacemi: `VoskTracker` a `AndroidSpeechTracker` (systémový `SpeechRecognizer`, cs-CZ, offline, pokud ho tablet podporuje). Volba v rodičovské sekci, výchozí podle výsledku prototypu.

**Algoritmus sledování** (čistý Kotlin v `:reader-core`, pokrytý unit testy):

1. Text stránky se převede na posloupnost normalizovaných slov: malá písmena, bez interpunkce, pro porovnání i bez diakritiky.
2. Průběžné výsledky rozpoznávání se rozdělí na tokeny. Každý token se porovná se slovy v okně od kurzoru po kurzor + 3.
3. Shoda: slova do 3 písmen jen přesně, delší s normalizovanou Levenshteinovou vzdáleností nejvýš 0,34. Prahy jsou v konfiguraci a ladí se v prototypu.
4. Čtení po slabikách: tokeny, které odpovídají slabikám aktuálního slova, se sbírají. Když zazní všechny slabiky v pořadí, slovo je přečtené.
5. Po shodě se kurzor posune za slovo. Přeskočená slova v okně se označí jako přečtená, dítě se netrestá.
6. Přečtení slova se značkou spustí jeho `cues`, každou jednou za průchod stránkou.
7. Bez posunu 6 s: aktuální slovo jemně pulzuje. Po dalších 6 s se nabídne nápověda a tlačítko „dál“.
8. Konec panelu nebo stránky: krátká animace a přechod dál.

**Zvukové cesty:**

- Mikrofon vždy vestavěný (`AudioRecord`, zdroj `VOICE_RECOGNITION`, preferované zařízení nastavené na vestavěný mikrofon). Nikdy nezapínat SCO: mikrofon Bluetooth sluchátek by přepnul zvuk do nízké kvality.
- Výstup do Bluetooth sluchátek (A2DP), jinak do reproduktoru.
- Když hraje reproduktor, tracker ignoruje výsledky po dobu efektu + 300 ms, aby se nespouštěl vlastním zvukem.
- Hudba během čtení ztlumená na 30 % (ducking), efekty na plnou hlasitost.
- Povolení mikrofonu se vyžádá v rodičovské sekci při prvním spuštění. Bez něj Čtečka funguje s tlačítkem „dál“ a zvuky se spouštějí klepnutím na slovo.

## Technologie a struktura projektu

Čtečka je nativní Kotlin s Jetpack Compose, minSdk 26, targetSdk 36 (Android 16). Studio jsou Python skripty, které spouští Claude Code.

| Modul | Obsah | Hlavní knihovny |
| --- | --- | --- |
| `:app` | UI, navigace, knihovna kapitol, rodičovská sekce | Jetpack Compose, Navigation, DataStore |
| `:reader-core` | model kapitoly, načítání JSON, stav čtení, algoritmus sledování | kotlinx.serialization, čistý Kotlin |
| `:speech` | `SpeechTracker`, Vosk a systémová implementace, směrování mikrofonu | vosk-android, AudioRecord |
| `:audio` | efekty, hudební smyčky, crossfade, ducking | SoundPool, Media3 ExoPlayer |
| `:render` | vrstvy panelu, bubliny, animace vrstev | AndroidSVG (vrstva → Picture), Compose graphicsLayer |
| `:sync` | katalog a balíčky z GitHub Releases, kontrola SHA-256, aktualizace APK | OkHttp, WorkManager |

Zásady:

- Vrstvy panelu se vykreslí z SVG jednou při otevření stránky a animují se jen transformace (posun, rotace, měřítko), ne SVG uvnitř.
- Balíčky se rozbalí do interního úložiště aplikace. Jedna kapitola má mít do 10 MB.
- Aplikace obsahuje ukázkovou kapitolu pro první spuštění bez internetu a pro testy.
- Aktualizace aplikace i kapitol jako u ostatních projektů: GitHub Releases. Pokud již existuje sdílený modul pro aktualizace z GitHubu (Deskovky, ThermoCamera), převzít ho.
- Testy: unit testy sledování na nahraných přepisech (fixtures z prototypu A), validace JSON Schema ve Studiu i v Čtečce, instrumentační test vykreslení ukázkové kapitoly.
- Studio: Python 3.12 ve `studio/.venv`, `resvg-py` pro PNG náhledy (cairosvg na Windows potřebuje systémovou knihovnu cairo), `jsonschema`, `pydub` + ffmpeg pro zvuky. Každý skript má `--help` a vrací nenulový kód při chybě.

## Soukromí a bezpečnost

Fotky ani hlas dítěte neopouštějí rodinná zařízení, kromě jednorázového použití fotek v relaci Claude Code při vytváření postavy.

- **Fotky:** jen v `studio/photos/` (v `.gitignore`). Do repa se dostanou jen kreslené díly postavy.
- **Hlas:** zpracování jen v tabletu, nic se neukládá. Statistiky obsahují jen počty, ne zvuk ani přepis.
- **Tréninkový záznam:** jen slova z textu kapitoly, výsledek a doby čtení. Opouští tablet jen ručním sdílením rodiče. Repo je veřejné, proto je celá složka `studio/practice/` v `.gitignore` (zůstává jen v PC a v OneDrive). Do repa se dostane jen `practice.focus` v profilu, tedy seznam slov k procvičení bez výsledků.
- **Repo:** veřejné (`mpridal/cteci-komiks`, rozhodnuto 22. 11. 2026). Čtečka stahuje katalog a balíčky z veřejných Releases bez tokenu. Veřejné jsou proto i kapitoly, jméno Viky a kreslená postava. Fotky, tréninkové záznamy a cokoli dalšího o dítěti zůstávají jen lokálně (v `.gitignore`).
- **Síť:** Čtečka komunikuje jen s GitHubem. Žádné reklamy, analytika ani AI služby.
- **AI:** generuje jen rodič v interaktivní relaci Claude Code. Aplikace se k předplatnému nepřihlašuje.
- **Balíčky:** Čtečka před rozbalením ověří SHA-256 z katalogu a odmítne cesty mimo cílovou složku (ochrana proti zip slip). SVG s odkazy, skripty nebo externími zdroji nevykreslí.
- **Rodičovský zámek:** nastavení, stažení a export záznamu jsou za jednoduchým zámkem.

## Fáze vývoje

Stavba začíná dvěma malými prototypy, které ověří nejrizikovější předpoklady. MVP se staví, až obě projdou branou.

1. **Prototyp A: čtení nahlas** (souběžně s B)
    - Vosk s gramatikou stránky, nejdřív v emulátoru Android Studia (mikrofon PC přes „Virtual microphone uses host audio input“), na tabletu až na závěr
    - 5 stránek, zvýraznění slov, 1 zvuk na stránku
    - srovnání se systémovým rozpoznávačem
    - těžká slova (KOČKA, VLK, V LESE): pozná se správné přečtení od chybného, aby fanfáry nezněly omylem?
2. **Prototyp B: loutky** (souběžně s A)
    - `/nova-postava` z 2–3 fotek
    - 6 panelů ve 2 kapitolách, PNG náhledy
    - kontrola: poznáme ji v každém panelu?
    - **Splněno 22. 11. 2026:** rodič potvrdil, že Viky pozná ve všech 6 panelech (kapitoly `viky/01` a `viky/02`). Připomínky: bubliny nesmí zasahovat do hlavy, kšiltovka se v kapitole nemění (obojí zapracováno).
3. **Brána: pokračovat, až když platí obojí**
    - čtení: aspoň 85 % slov rozpoznáno bez ručního posunu, odezva pod 1 s
    - loutky: dcera i rodič postavu poznají ve všech 6 panelech
4. **MVP: tablet, velká tiskací písmena**
    - Studio: `/nova-postava`, `/nova-serie`, `/nova-kapitola`, `/publikuj`, validátor balíčku
    - Čtečka: knihovna, stránka na tabletu, sledování čtení, efekty, hudba, animace vrstev, hvězdičky a fanfáry, tréninkový záznam a export do Studia
    - Studio: `tools/update_practice.py`
    - stahování kapitol a aktualizace z GitHubu, rodičovská sekce, ukázková kapitola
    - hotovo, až dcera přečte 3 kapitoly bez zásahu rodiče
5. **Fáze 2: rozšíření** – telefon panel po panelu, slabiky, malá tiskací a psací písmo, profil postav, nápověda hlasem, statistiky
6. **Fáze 3: volitelně** – bitmapové panely z Gemini (varianta B), další děti a profily, vlastní zvuky nahrané doma

Když prototyp A neprojde ani se systémovým rozpoznávačem, Čtečka v MVP pojede na tlačítko „dál“ a klepání na slova a rozpoznávání se vrátí ve fázi 2.

Akceptační kritéria MVP:

- [ ] `/nova-kapitola` vytvoří kapitolu, která projde validací bez ručních oprav JSON.
- [ ] Všechna slova kapitoly obsahují jen písmena z `reader-profile.json` a kapitola má hlídanou dávku těžkých slov (kontroluje skript).
- [ ] Správně přečtené těžké slovo dá hvězdičku do 1 s, chybně přečtené ani jednou za kapitolu.
- [ ] Export z tabletu po `update_practice.py` změní `practice.focus` a další kapitola problémová slova obsahuje.
- [ ] Postava je ve všech panelech složená jen z dílů své sady a má všechny poznávací znaky.
- [ ] Čtečka otevře staženou kapitolu offline do 2 s.
- [ ] Zvuk ke slovu se spustí do 1 s od jeho přečtení a falešně nejvýš jednou za kapitolu.
- [ ] Bez mikrofonu jde kapitola projít tlačítkem „dál“ a klepáním na slova.
- [ ] Kapitola vydaná přes `/publikuj` se v tabletu objeví po otevření knihovny (s internetem).
- [ ] Mikrofon Bluetooth sluchátek se nikdy neaktivuje, zvuk jde do sluchátek, když jsou připojená.

## Otevřené otázky

Tyto body rozhodne rodič. Claude Code se na ně zeptá, než dojde k části, které se týkají.

- [x] Která písmena dcera aktuálně zná? Všechna. Potíže dělají slabiky delší než 2 písmena a slabiky bez samohlásky: ty se cíleně trénují s odměnou (hvězdička + fanfáry na konci stránky), dávkování hlídá skript, adaptace automaticky z tabletu.
- [ ] Jak se tréninkový záznam dostane z tabletu do Studia? Návrh: systémové sdílení do OneDrive a rodič soubor přesune do `studio/practice/inbox/`.
- [ ] Jakým písmem čtou ve škole? Zatím předpokládám velká tiskací (`UPPER`).
- [ ] Psací písmo: který font odpovídá školnímu (Comenia Script, nebo klasické psací) a má licenci použitelnou v aplikaci?
- [x] Jméno hlavní postavy v příběhu: Viky (VI-KY).
- [x] Repo soukromé s tokenem, nebo veřejné? Veřejné, bez tokenu: `github.com/mpridal/cteci-komiks`.
- [ ] Jaký stack mají Deskovky a ThermoCamera a dá se z nich převzít modul pro aktualizace z GitHubu?
- [ ] Kolik kapitol týdně? Podle toho velikost startovní knihovny zvuků.
- [ ] Má Studio běžet jen na PC, nebo i z Claude Code na webu nebo v mobilu nad stejným repem? (fotky by pak musely do relace jinou cestou)
