# Čtečka na tabletu (test prototypu A)

## 1. Instalace (jednou)

1. V tabletu otevři v prohlížeči **github.com/mpridal/cteci-komiks/releases/latest**.
2. Stáhni `cteci-komiks-X.Y.Z.apk` (asi 85 MB).
3. Otevři ho. Android se zeptá, jestli prohlížeči povolit instalaci neznámých aplikací → **povolit** → **Instalovat**.
4. Při prvním spuštění povol **mikrofon**.

## 2. Token pro odesílání záznamů (jednou)

Záznamy čtení jdou do soukromého repa `mpridal/cteci-komiks-logs`, které vidíš jen ty. Nejjednodušší je token vytvořit přímo v tabletu, aby šel zkopírovat a vložit:

1. V prohlížeči tabletu se přihlas na github.com → **Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**.
2. Nastav:
   - Name: `cteci-komiks-tablet`
   - Expiration: 90 dní
   - Repository access: **Only select repositories → `cteci-komiks-logs`**
   - Permissions → Repository permissions → **Contents: Read and write** (nic dalšího)
3. **Generate token**, token zkopíruj (`github_pat_…`).
4. V aplikaci klepni na **⚙**, vlož token → **Uložit a odeslat**.

Token se uloží šifrovaně (Android Keystore). Umí jen zapisovat do repa logů.

## 3. Test

- Viky čte 5 stránek. Po dočtení se ukáže souhrn a záznam se sám odešle (s internetem hned, jinak při dalším spuštění).
- Režim rozpoznávání jde přepnout nahoře (výchozí „Stránka + 1000“). Každý průchod se zaznamená zvlášť.
- Záznam obsahuje přepis toho, co rozpoznávač slyšel, a hlasitost. **Zvuk se neukládá.**
- Do repa jde i deník aplikace (verze, chyby, pády, aktualizace).

## 4. Aktualizace

- Když vydám opravu, aplikace ji při spuštění stáhne a ověří (SHA-256 a podpis).
- Nahoře se pak objeví **„Nová verze X je stažená · Nainstalovat“**.
- Poprvé Android požádá o povolení instalace aplikacím („Povolit instalaci“ v liště) a pak o potvrzení.
- Kontrolu jde vyvolat i ručně v ⚙ → **Zkontrolovat teď**.

## Pro vývoj

- Vydání: tag `vX.Y.Z` → workflow `.github/workflows/release.yml` (testy, podepsané APK, SHA-256, poznámky z `CHANGELOG.md`).
- Podpisový klíč: `%USERPROFILE%\.cteci-komiks\release.jks` a hesla v `release-secrets.env`.
  - **Klíč si zálohuj** (mimo OneDrive repa). Bez něj nejde vydat aktualizaci, kterou tablet přijme.
  - V GitHubu je v secrets `CTECI_KEYSTORE_BASE64`, `CTECI_KEYSTORE_PASSWORD`, `CTECI_KEY_ALIAS` a `CTECI_KEY_PASSWORD`.
- Záznamy čtu přes `gh` z `mpridal/cteci-komiks-logs` (`sessions/<model>-<id>/…`, `logs/<model>-<id>/…`) a přehrávám je v `asr-bench` (`docs/PROTOTYP-A.md`).
