# Ablage auf openCode

[openCode](https://opencode.de/de) ist die Plattform der öffentlichen Verwaltung für quelloffene
Software, betrieben vom Zentrum für Digitale Souveränität (ZenDiS). Behörden suchen dort in einem
Softwareverzeichnis nach nachnutzbaren Lösungen; Markterkundungen verweisen zunehmend darauf. OPAA
soll dort als Spiegel des GitHub-Repositorys liegen, damit Behörden es finden und nachnutzen
können.

**Stand:** Die Ablage ist im Repository vorbereitet (`publiccode.yml`, Workflow
`.github/workflows/opencode-mirror.yml`), aber noch nicht eingerichtet: Es gibt noch kein Projekt
auf openCode und keinen Eintrag im Softwareverzeichnis. Was dafür noch zu tun ist, steht unter
[Einrichtung](#einrichtung).

## Bedingungen von openCode

Gelesen am 24.10.2026 auf den offiziellen Seiten von openCode: Nutzungsbedingungen, FAQ, „Aufnahme
in das Softwareverzeichnis“, „Anforderungen an Inhalte“, „Badges“ und die Liste der freigegebenen
Lizenzen. Die Seiten ändern sich; vor der Einrichtung neu lesen.

| Thema | Bedingung | Bezug zu OPAA |
|---|---|---|
| Konto | Registrierung mit amtlichem Vor- und Nachnamen und E-Mail-Adresse, Zwei-Faktor-Authentifizierung Pflicht; ein technisches Konto für automatisierte Aufgaben ist als solches auszuweisen und darf keine schutzfähigen Code-Beiträge leisten | Ein Spiegel braucht ein Konto des Maintainers und ein Token mit Schreibrecht auf das Projekt |
| Projekt anlegen | Projekte anlegen und als Maintainer oder Repository Owner wirken dürfen nur „Identifizierte Nutzende“: Beschäftigte der öffentlichen Verwaltung oder Unternehmen in ihrem Auftrag, nachgewiesen über eine betriebliche E-Mail-Adresse | **Offen:** OPAA wird von keiner Behörde und in keinem Auftrag entwickelt |
| Zuordnung | „Sämtliche Projekte auf der Plattform müssen einem Träger der öffentlichen Verwaltung oder einem beauftragten Unternehmen zugeordnet werden können“ (Nutzungsbedingungen, Abschnitt 7); Inhalte müssen im Interesse der öffentlichen Verwaltung liegen | **Offen**, wie oben |
| Spiegel | Die primäre Entwicklung darf auf einer anderen Plattform stattfinden; für das Softwareverzeichnis muss der Code auf openCode gespiegelt werden (FAQ, Verweis auf den openCode-Guide) | passt zur Entwicklung auf GitHub |
| Lizenz | jeder Inhalt unter einer Open-Source-Lizenz nach der Definition der OSI aus der Liste von openCode; REUSE wird empfohlen | AGPL-3.0 steht auf der Liste (`AGPL-3.0-only` und `AGPL-3.0-or-later`) |
| SBOM | Für bereitgestellte Kompilate (ausführbare Dateien, Installer, Images) gelten die Lizenzanforderungen für alle eingehenden Komponenten; nachzuweisen über eine SBOM im Repository mit dem Namen `SPDX_<ARTEFAKT_NAME>.<spdx_dateityp>`. Die Nutzungsbedingungen verlangen den Nachweis über eine SBOM des Projekts allgemein | OPAA erzeugt CycloneDX-SBOMs am Release und als CI-Artefakt ([sbom.md](sbom.md)), keine SPDX-Datei im Repository. Der Spiegel stellt nur Quellcode und Tags bereit, keine Kompilate; ob openCode dennoch eine SPDX-Datei verlangt, ist **offen** |
| Softwareverzeichnis | eine Datei `publiccode.yml` im Wurzelverzeichnis des Repositorys auf openCode, nach dem Standard publiccode.yml 0.4, Kategorien nur aus der Liste des openCode-Editors; prüfbar mit dem [Editor](https://editor.opencode.de/) („Validate“); das Projekt erscheint danach zeitversetzt | `publiccode.yml` liegt im Wurzelverzeichnis |
| Verhalten | Code of Conduct der Plattform | — |
| CI | GitLab-Runner von openCode stehen zur Verfügung, ihr Gebrauch ist freiwillig | Der Spiegel nutzt sie nicht; gebaut und geprüft wird auf GitHub |

### Badges

openCode vergibt im Softwareverzeichnis Badges nach Kriterien, die es selbst am Projekt auf openCode
misst. Ein Spiegel erfüllt manche davon nur teilweise, weil Issues, Merge Requests und CI auf GitHub
liegen:

| Badge | Kriterien (Auszug) | Bei einem Spiegel |
|---|---|---|
| Aktive Wartung | Commits der letzten sechs Monate, Reaktionszeit auf Issues unter sieben Tagen, Releases oder Tags, mehrere aktive Maintainer | Commits und Tags kommen mit; Issues und Reaktionszeiten nicht |
| Nachnutzung | veröffentlichtes Paket und erfolgreiche CI-Pipeline der letzten sechs Monate, nutzende Organisationen | Pakete und CI liegen auf GitHub bzw. GHCR |
| Open-Source-Lizenz | freigegebene Lizenz | erfüllt |
| Sicherheit | geschützter Haupt-Branch ohne Force-Push, `SECURITY.md`, Reviews, signierte Tags, Releases ohne bekannte hohe Schwachstellen | `SECURITY.md` vorhanden; Schutz von `main` auf openCode einzustellen; Release-Tags sind annotiert, nicht signiert |
| Lieferkette | Changelog-Datei, Contribution Guidelines, SBOM-, VEX- und Provenance-Attestierungen der Container-Images | `CONTRIBUTING.md` vorhanden, SBOM und Provenance an den Images in GHCR; keine Changelog-Datei im Wurzelverzeichnis (die Änderungen stehen in den Release-Notizen), keine VEX-Attestierung |

## Art der Ablage

**Spiegel, GitHub bleibt das Hauptrepository.** Issues, Pull Requests, CI, Releases, Images, Chart,
Update-Paket und Offline-Bündel bleiben auf GitHub und in GHCR; openCode bekommt den Quellcode mit
`main`, den Wartungsbranches `release/*` und den Release-Tags `v*`. So entstehen keine zwei Orte,
an denen Beiträge ankommen, und alle Nachweise (Attestierungen, SBOMs, Update-Paket) behalten ihre
eine Quelle.

Zwei Wege führen dorthin:

- **Push aus GitHub** über den Workflow `opencode-mirror.yml` (vorbereitet). Er läuft bei jedem
  Push auf `main` oder `release/*` und bei jedem Tag `v*`, holt das Repository mit Historie und
  überträgt genau diese Referenzen. Er überträgt nie mit Force: Liegt auf openCode ein Commit, den
  GitHub nicht hat, scheitert der Lauf, statt ihn zu überschreiben, und ein Release-Tag bleibt
  auch dort unveränderlich. Ohne Einrichtung endet jeder Lauf mit einem Hinweis.
- **Pull-Spiegel in GitLab:** openCode holt selbst von GitHub; GitHub braucht dann kein
  Geheimnis. Ob die GitLab-Instanz von openCode Pull-Spiegel anbietet, ist **offen**; dann
  entfällt der Workflow.

**Regeln des Workflows** ([AGENTS.md](../AGENTS.md), „GitHub-Actions-Workflows“): Rechte nur
`contents: read`; der Checkout behält kein Token (`persist-credentials: false`); Adresse und Token
erreichen das Skript nur über `env:`. Das Token steht nur im Kopf des einen `git push`, als
Basic-Anmeldung, deren base64-Form der Lauf eigens maskiert. Das Token ist ein Projekt-Token auf
openCode mit der Berechtigung `write_repository` und Ablaufdatum, nicht das persönliche Token eines
Kontos.

## Was das Repository bereitstellt

| Datei | Zweck | Pflege |
|---|---|---|
| `publiccode.yml` | Metadaten für das Softwareverzeichnis: Name, Beschreibung auf Deutsch und Englisch, Kategorien, Lizenz, Entwicklungsstand, Kontakt | beschreibt den gebauten Stand von `main`; neue Hauptfunktionen und ein geänderter Entwicklungsstand ziehen die Datei nach. Mit dem ersten finalen Release kommen `softwareVersion` und `releaseDate` hinzu und wandern mit jedem finalen Release |
| `.github/workflows/opencode-mirror.yml` | Spiegel von `main`, `release/*` und `v*` | aktiv erst mit Variable `OPENCODE_MIRROR_URL` und Secret `OPENCODE_MIRROR_TOKEN` |

`url` in `publiccode.yml` zeigt auf GitHub, das Hauptrepository. Die Vorlage von openCode nennt dort
die Adresse des Projekts auf openCode; ob das Softwareverzeichnis einen Spiegel mit der Adresse des
Hauptrepositorys annimmt, ist **offen**.

## Einrichtung

Schritte für einen Maintainer; ein Agent führt sie nicht aus.

1. **Zuordnung klären:** Mit openCode (`info@opencode.de`) klären, ob und unter welchem Träger der
   öffentlichen Verwaltung OPAA als Projekt angelegt werden darf, oder eine Behörde finden, die es in
   ihrer Gruppe führt. Ohne diese Klärung gibt es keine eigene Ablage des Projekts.
2. **Konto anlegen** auf openCode mit Zwei-Faktor-Authentifizierung und, sofern möglich, die
   Identifizierung beantragen.
3. **Projekt anlegen** (öffentlich, leer, ohne README), etwa `gitlab.opencode.de/<gruppe>/opaa`;
   den Haupt-Branch `main` schützen und Force-Push sperren, Tags `v*` schützen.
4. **Spiegel wählen:** Pull-Spiegel in GitLab, falls angeboten; sonst ein Projekt-Token mit
   `write_repository`, Rolle Maintainer und Ablaufdatum erzeugen.
5. **Bei Push aus GitHub:** in *Settings → Secrets and variables → Actions* die Variable
   `OPENCODE_MIRROR_URL` (`https://gitlab.opencode.de/<gruppe>/opaa.git`) und das Secret
   `OPENCODE_MIRROR_TOKEN` setzen, dann den Workflow „openCode-Spiegel“ einmal von Hand starten.
   Vor Ablauf des Tokens ein neues setzen.
6. **`publiccode.yml` prüfen** im openCode-Editor mit „Validate“, Kategorien und Kontakt
   bestätigen, gegebenenfalls `url` auf das Projekt auf openCode setzen; auf openCode
   Projektbeschreibung und einen Verweis auf GitHub als Ort für Issues und Beiträge eintragen.
7. **Nachziehen** nach der Einrichtung, per PR: in diesem Dokument den Stand, in der Vorlage
   [Projektstatus](behoerden/01-markterkundung/projektstatus.md) die Zeile „Ablage“, im Handbuch
   [Releases und Nachweise](handbuch/betrieb/releases-und-nachweise.md) den Punkt unter „Was nicht
   gebaut ist“ und in der README den Hinweis auf openCode.

## Offene Fragen

- Darf OPAA ohne Träger der öffentlichen Verwaltung als eigenes Projekt auf openCode liegen
  (Nutzungsbedingungen, Abschnitte 5 und 7)?
- Verlangt openCode für einen reinen Spiegel des Quellcodes eine SPDX-Datei im Repository, oder
  genügen die CycloneDX-SBOMs am GitHub-Release?
- Bietet openCode Pull-Spiegel an, und nimmt das Softwareverzeichnis `url` auf GitHub an?
- `legal.license` lautet `AGPL-3.0-only`; das Repository legt nicht fest, ob „only“ oder „or later“
  gilt.
- Wer steht als Kontakt in `publiccode.yml`, und reicht der Entwicklungsstand `beta`, solange es nur
  Vorab-Releases gibt?
