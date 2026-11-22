---
description: Nová postava z 1–3 fotek – popis rysů, sada SVG dílů, karta postavy ke schválení
argument-hint: <id> <jméno> <role>
---

Vytvoř novou postavu pro Čtecí komiks podle `docs/SPEC.md` (sekce Kresba: SVG loutkový systém).

Argumenty: $ARGUMENTS (id postavy malými písmeny bez diakritiky, jméno, role).

1. Najdi fotky v `studio/photos/` (jsou v `.gitignore`, nikdy je necommituj ani nekopíruj jinam do repa).
   Když chybí, požádej rodiče o 1–3 fotky.
2. Popiš z fotek rysy: vlasy (barva, délka, ofina), oči, pleť, tvář, typický úsměv, oblečení,
   oblíbené doplňky. Navrhni 2–4 poznávací znaky (musí být vidět v každé póze) a ukaž je rodiči.
   Loga a postavičky z oblečení (značky, kreslené postavy) nepřenášej, nahraď je neutrálním motivem.
3. Vytvoř `studio/kit/characters/<id>/build.py` podle vzoru `studio/kit/characters/viky/build.py`:
   paleta (jen ploché barvy), díly hlavy, vlasů, očí, obočí, úst, trupu, rukou a nohou,
   `character.json` s rysy, poznávacími znaky, výrazy, pózami, oblečením a doplňky.
   Styl série: obrys 2,5 px #2B2B2B, oblé tvary, hlava zhruba třetina výšky, žádný text v SVG.
4. Spusť:
   - `studio/.venv/Scripts/python studio/kit/characters/<id>/build.py`
   - `studio/.venv/Scripts/python studio/tools/character_card.py <id>`
5. Prohlédni `card.png` sám (poznávací znaky vidět ve všech pózách?), oprav zjevné chyby
   a pak kartu ukaž rodiči. Připomínky zapracuj úpravou `build.py`, nikdy ručně v SVG.
6. Po schválení zapiš postavu do `studio/bible.json` (`characters[]`: id, jméno, role, vzhled,
   povaha, způsob řeči, `kit`: cesta ke složce).
