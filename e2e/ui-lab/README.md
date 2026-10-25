# UI-Lab-Werkzeuge

Screenshots, Kennzahlen, Videos und Vergleichsbögen für Entwürfe und Varianten der Oberfläche.
Die Werkzeuge laufen gegen das Frontend im MSW-Mock-Modus, nie gegen ein Backend. Den Ablauf, in
dem sie eingesetzt werden, beschreibt der Skill [`.claude/skills/ui-lab/SKILL.md`](../../.claude/skills/ui-lab/SKILL.md);
den Prüfmaßstab die [UI-Prüfliste](../../docs/design/ui-pruefliste.md).

Sie gehören nicht zur E2E-Suite: Playwright findet sie nicht (`testDir` ist `tests/`), und keine
CI-Pflicht ruft sie auf. Sie liegen unter `e2e/`, weil sie dieselben gepinnten Abhängigkeiten
nutzen (`@playwright/test`, `@axe-core/playwright`), die Renovate dort pflegt.

| Datei | Zweck |
| --- | --- |
| `capture.mjs` | Screenshots und Kennzahlen je Ablauf, Szenario, Schema und Breite |
| `calibrate.mjs` | prüft die Messung gegen eine bekannt gute und eine bekannt schlechte Seite |
| `record-flows.mjs` | Videos je Art und Szenario (`--kinds`) als MP4 mit sichtbarem Mauszeiger; im UI-Lab je Szenario ein Video über alle Reiter |
| `compare.py` | Vergleichsbögen und Kennzahlentabelle über mehrere Varianten |
| `contact_sheets.py` | Querbögen einer Variante (derselbe Bildschirm aller Bereiche nebeneinander) und Bilderlisten für das Konsistenz-Review |
| `build-prototype.mjs` | baut den klickbaren Prototyp nach `out/prototype/` und teilt ihn in Upload-Pakete |
| `build_site.py` | statische Vergleichsseite: Ist-Stand und Entwürfe mit Bewertung, Bildschirmen und Videos |
| `site-template/` | Vorlage der Vergleichsseite und Beispielkonfiguration `site.example.json` |
| `flows/` | Abläufe je Bereich: `libraries.mjs`, `pages.mjs` (beliebige Seiten), `library-videos.mjs` |
| `lib/` | Browserstart, Messung, Elementhelfer |
| `calibration/` | Kalibrierseiten `good.html` und `bad.html` |

## Voraussetzungen

```bash
cd e2e && pnpm install                     # Playwright und axe aus der E2E-Suite
pnpm exec playwright install chromium      # einmalig, falls kein Chromium vorhanden ist
python3 -m venv .venv && .venv/bin/pip install -r ui-lab/requirements.txt   # Pillow, gepinnt; für die Python-Werkzeuge
```

Für Videos zusätzlich `ffmpeg` auf dem `PATH`. `compare.py` und `build_site.py` brauchen Pillow.

**Cloud-Session mit vorinstalliertem Browser.** Ist der Chromium einer anderen Playwright-Version
schon installiert (etwa unter `/opt/pw-browsers/chromium`), nicht neu installieren, sondern
`UI_LAB_CHROMIUM=/opt/pw-browsers/chromium` setzen (oder `--chromium <pfad>`). Fehlt der
Video-Encoder der gepinnten Playwright-Version, meldet `record-flows.mjs` „no recording written“.
Dann ein eigenes Browser-Verzeichnis mit dem System-ffmpeg anlegen (die Nummer nennt die
Playwright-Fehlermeldung, z. B. `ffmpeg-1013`):

```bash
mkdir -p "$SCRATCH/pw/ffmpeg-1013" && ln -sf "$(command -v ffmpeg)" "$SCRATCH/pw/ffmpeg-1013/ffmpeg-linux"
touch "$SCRATCH/pw/ffmpeg-1013/INSTALLATION_COMPLETE"
export PLAYWRIGHT_BROWSERS_PATH="$SCRATCH/pw"
```

## Dev-Server mit Mocks

```bash
cd frontend
pnpm run generate:api-types        # einmal je Arbeitsverzeichnis
VITE_ENABLE_MOCKS=true pnpm exec vite --port 5191 --strictPort
```

Ein Server je Variante und Agent, eigener Port; nach dem Lauf über die eigene PID beenden
(`kill <pid>`), nie `pkill`.

## Klickbarer Prototyp

Das Frontend im Mock-Modus als statische Dateien, zum selbst Durchklicken statt
Screenshot-Matrizen ansehen; im UI-Lab die Entscheidungsgrundlage beider Stufen. Läuft aus jedem Unterpfad und in einem Sandbox-iframe
(`sandbox="allow-scripts allow-same-origin"`), etwa als claude.ai-Artifact.

```bash
cd e2e
node ui-lab/build-prototype.mjs            # baut und legt nach ui-lab/out/prototype/
node ui-lab/build-prototype.mjs --skip-build   # nur kopieren und auflisten
```

Der Build (`pnpm run build:prototype` in `frontend/`, Ausgabe `dist-prototype/`) unterscheidet
sich vom Dev-Server mit Mocks in drei Punkten:

- **Kein Service Worker.** `src/app/mocks/inPageMocks.ts` ersetzt `fetch` und `XMLHttpRequest`
  der Seite und beantwortet jede Anfrage mit denselben MSW-Handlern (`getResponse` aus `msw`);
  was kein Handler beantwortet, geht an das ursprüngliche `fetch`.
- **Hash-Routing.** Die Route steht hinter `#`: `index.html#/libraries/library-nextcloud-projekte`.
  Deep Links und Neuladen funktionieren ohne Server, der jede Route mit `index.html` beantwortet.
- **Relative Pfade** (`base: './'`).
- **Reines ASCII in allen Skripten** und Seitentitel „OPAA-Prototyp“ (`frontend/prototypeOutput.ts`):
  Das Hosting von claude.ai-Artifacts lehnt Dateien mit bestimmten rohen Zeichen ab, darunter
  U+FFFD. `build-prototype.mjs` bricht ab, wenn eine Ausgabedatei U+FFFD oder ein Skript ein
  Nicht-ASCII-Zeichen enthält.

Szenario, Breite und Variante stehen als Suchparameter **vor** dem Hash, z. B.
`index.html?mockScenario=scale#/libraries/library-s3-protokolle`.

**Prototyp-Leiste** (violett, unten rechts, einklappbar; nur in diesem Build):

| Feld | Wirkung |
| --- | --- |
| Szenario | lädt neu mit `?mockScenario=…` (Tabelle unten) |
| Einstieg | springt zu einer Bibliothek je Quellart (Rolle Eigentümer), zur Leser-Bibliothek „Dienstanweisungen“ (`myRole: VIEWER`) oder zu Katalog, Spaces, Chat, Administration, Einstellungen. Der Mock-Benutzer ist immer Systemadministrator; Rollen gibt es nur je Bibliothek |
| Breite | Normal oder 320 px: die App läuft dann in einem 320 px breiten iframe derselben Seite, damit ihre Media Queries greifen; die Route wird in den äußeren Hash gespiegelt |
| Variante | nur sichtbar, wenn `UI_LAB_VARIANTS` in `frontend/src/shared/uiLabVariant.ts` Einträge hat; Komponenten fragen `currentUiLabVariant()` ab (`?variant=<id>`, `none` setzt zurück). Auf `main` leer, ein Lab-Branch trägt seine Varianten dort ein |

**Hochladen.** `ui-lab/out/prototype/files.json` nennt alle Dateien mit Größe und teilt sie
(ohne `index.html`) in Pakete von höchstens 55 MB und 240 Dateien, unter der Grenze eines
Artifact-Publish von 255 Dateien. Für ein Artifact: `index.html` als Seite veröffentlichen, die
Pakete nacheinander als `files` an dieselbe URL; die Pfade in `files.json` sind die
veröffentlichten Pfade. Bricht der Build wegen U+FFFD ab, wird nichts hochgeladen.

**Lokal prüfen:** `ui-lab/out/prototype/` mit einem beliebigen statischen Server unter einem
Unterpfad ausliefern (etwa `python3 -m http.server` im Elternordner, dann
`http://localhost:8000/prototype/index.html`). Bilder, die die App als `<img src="/api/…">`
lädt, laufen nicht über `fetch` und bleiben im Prototyp leer.

## Mock-Szenarien

`?mockScenario=<name>` an einer beliebigen URL wählt den Fixture-Satz, z. B.
`http://localhost:5191/libraries/new?mockScenario=scale`. Die Wahl bleibt in der sessionStorage
des Tabs, Navigation und Neuladen behalten sie; `?mockScenario=default` setzt zurück. Quelle:
`frontend/src/shared/mocks/mockScenario.ts`; die Fixtures liegen je Thema unter
`frontend/src/features/<thema>/mocks/`.

| Szenario | Auflistungen (`POST /source-types/:type/browse`) | Verbindungstest (`POST /libraries/source-test`) | Detailseiten, Läufe, Gruppen |
|---|---|---|---|
| `default` | bisherige Fixtures (SharePoint: Sites.Selected, Suche abgelehnt) | bisherige Antworten | bisherige Fixtures |
| `small` | 1 Site · 1 Dokumentbibliothek · 2 Ordner je Ebene, 2 Ebenen · 2 Spaces · 1 Drive-Bereich · 1 Bucket · 2 Ordner (Nextcloud, SMB, Dropbox) | Erfolg | je Bibliothek 1 Auswahl; 1 Gruppe |
| `scale` | 17 Sites × 25 Dokumentbibliotheken · 200 Ordner je Ebene, 8 Ebenen · 450 Spaces (jeder 10. persönlich) · 300 Drive-Bereiche · 600 Buckets, Präfixe 200 je Ebene · Nextcloud, SMB, Dropbox 200 Ordner je Ebene, 8 Ebenen · jeder 3. Name 80–120 Zeichen mit Umlauten, Nr. 7 mit dem Kompositum „Grundstücksverkehrsgenehmigungsakte“ | Erfolg, Zählung nach Anfrage | lange Bibliotheksnamen; Confluence 120 Spaces, SharePoint 25 Bibliotheken, übrige 50 Auswahlen (Ordnerpfade bis 8 Ebenen); 10 Läufe mit bis zu 140 Ereignissen; 302 Gruppen |
| `empty` | 0 Einträge überall | Erfolg | 0 Dokumente, keine Läufe |
| `slow` | wie `default`, Antwort nach 3 s (`sessionStorage` `opaa.mockSlowMs` überschreibt die Dauer) | wie `default`, nach 3 s | – |
| `failed` | HTTP 403 (SharePoint, Drive, S3) bzw. 502 (Confluence, Nextcloud, SMB, Dropbox) mit deutscher Meldung | `reachable:false`, `credentialsVerified:false` mit passender Ablehnung; Webverzeichnis und RSS HTTP 502 | `lastScheduledRunsFailed`, 3 fehlgeschlagene Läufe |
| `partial` | 6 Sites · 8 Bibliotheken · 30 Ordner, 4 Ebenen · 40 Spaces · 25 Bereiche · 30 Buckets · 30 Ordner; alle `complete:false` mit Meldung | Teilfehler je Bereich: jeder 2. Bereich scheitert (`details.scopes`, `details.libraries`, `details.folders`, `details.unreadableSpaces`) | Lauf mit 17 Fehlern |

Weitere Abfrageformen für Entwürfe, die tiefer browsen: Nextcloud, SMB und Dropbox nehmen
`query.path` (auch `query.folder`) und liefern die direkten Unterordner; S3 nimmt `query.bucket`
und `query.prefix`; SharePoint (generierte Szenarien) nimmt `query.search`, `query.siteUrl`,
`query.site` und `query.drive` mit `query.folder`.

Fixture-Bibliotheken je Quellart, Detailseite direkt aufrufbar: `library-filesystem-satzungen`,
`library-confluence-wiki`, `library-sharepoint-rathaus` (Zugang „Microsoft 365 Rheinfurt“),
`library-nextcloud-projekte`, `library-smb-ablage`, `library-s3-protokolle`,
`library-gdrive-projekte`, `library-dropbox-projekte` (Zugang „Dropbox Rheinfurt“).

Ein neuer Bereich bekommt seine Szenarien, indem seine Mock-Handler `currentMockScenario()` aus
`mockScenario.ts` abfragen und je Szenario andere Fixtures liefern; die Vorlage ist
`frontend/src/features/library/mocks/sourceScenarioMock.ts`.

## capture.mjs

```bash
cd e2e
node ui-lab/capture.mjs --base http://localhost:5191 --out <dir> [--quick]
     [--flow libraries|pages] [--pages /spaces,/admin]
     [--types filesystem,confluence,sharepoint,nextcloud,smb,s3,gdrive,dropbox]
     [--scenarios small,scale,empty,slow,failed,partial]   # auch: default
     [--schemes light,dark] [--widths 1280,320]
     [--areas wizard,review,detail,edit] [--concurrency 2] [--no-axe] [--no-full] [--variant <id>]
```

- `--variant <id>` hängt `?variant=<id>` an jede URL, die der Ablauf öffnet (vor den Hash), und
  zeigt so eine Variante aus `UI_LAB_VARIANTS`; gleich in `record-flows.mjs`.
- `--flow libraries` (Vorgabe) fährt den Quellassistenten, den Prüfschritt sowie Detail und
  Bearbeiten der Fixture-Bibliothek je Quellart. `--flow pages` lädt jeden Pfad aus `--pages` je
  Szenario einmal und nimmt ihn auf; das reicht für eine erste Inventur jedes Bereichs.
- `--quick`: hell, 1280 px, Szenarien `small,scale`. Vorgabe: alle Szenarien des Ablaufs × hell
  und dunkel × 1280 und 320 px.
- **Je Schema ein eigener Browserkontext**, der jede Seite von Anfang an in diesem Schema lädt
  (gespeicherte Theme-Wahl und `prefers-color-scheme` vor dem ersten Laden). Ein Schema wird nie
  in einer laufenden Sitzung umgeschaltet, siehe [Kalibrierung](#kalibrierung).
- Je Ablauf und Szenario ein frischer Seitenaufruf; ein nicht erreichbarer Zustand steht als
  `status: "not-found"` mit `reason` in `metrics.jsonl`, statt den Lauf abzubrechen.
- Dateien: `<bereich>__<typ>__<szenario>__<schema>__<breite>.png` (900 px hoch) und `…__full.png`
  (auf Inhaltshöhe vergrößert, höchstens 16 000 px; bei offenem Dialog nur der Dialog selbst).
  Als offen gilt ein Dialog nur, wenn er sichtbar ist; die geschlossene Navigationsschublade bei
  320 px zählt nicht.
- Im Szenario `slow` antworten Auflistungen und Verbindungstests während der Aufnahme nie
  (`opaa.mockSlowMs` = 10 min); so zeigen alle Schemata und Breiten denselben Ladezustand.
  `record-flows.mjs` behält die 3 s.
- `metrics.jsonl`, eine Zeile je Screenshot: `appliedScheme` (tatsächlich dargestelltes Schema),
  `overflowX`, `docHeight`, `clippedText`, `disabledSubmits`, `alertCount`/`alertSeverities`,
  `invalidFields`, `focusable`, `fontSizes`, `colors`, `words`, `uiWords`, `helpTexts`,
  `helpTextsPerForm`, `helpTextMaxLines`, `budgetViolations`, `headings`, `axe` (hell: WCAG 2.0–2.2
  A/AA und best-practice mit `target-size`; dunkel: nur `color-contrast`; je Kontrastbefund Farben
  und Verhältnis in `contrastSamples`), `tabStops` mit `focusHidden`/`focusObscured` (nur hell).
- Laufzeit auf 4 CPUs: `--quick` rund 4 min, volle Matrix des Bibliotheksablaufs rund 14 min.

Die Ablauferkennung folgt deutschen Beschriftungen (Weiter, Verbindung testen, … laden,
Bearbeiten, Prüfen). Nennt eine Variante andere Wörter, erscheint der Zustand als `not-found`;
dann die regulären Ausdrücke in `flows/libraries.mjs` erweitern.

## Kalibrierung

```bash
cd e2e && node ui-lab/calibrate.mjs
```

Misst `calibration/good.html` und `calibration/bad.html` genau so wie `capture.mjs` und bricht
mit Exit-Code 1 ab, wenn ein Ergebnis von der Erwartung abweicht: die gute Seite ohne
Budgetüberschreitung in beiden Schemata, hell ohne axe-Befund, dunkel ohne Kontrastbefund, die schlechte mit Kontrastbefund in beiden
Schemata, mit `label`, `button-name` und `target-size` und über allen drei Zählbudgets. Eine
Gegenprobe misst die gute Seite auf dem verworfenen Weg, mit Umschalten des Schemas in der
Sitzung: Die Theme-Übergänge sind dann noch nicht abgeschlossen, und axe meldet Kontrastfehler,
die es im direkt geladenen Schema nicht gibt.

Nach einer Änderung an `lib/measure.mjs` oder `lib/browser.mjs` die Kalibrierung laufen lassen.

## record-flows.mjs

```bash
cd e2e
node ui-lab/record-flows.mjs --base http://localhost:5191 --out <dir>
     [--flows ui-lab/flows/library-videos.mjs] [--types nextcloud,s3] [--kinds ok,viele,fehler]
     [--scheme light|dark] [--width 1280] [--height 800] [--pause 700] [--keep-webm] [--variant <id>]
```

Nimmt je Art und Fall ein Video auf, vorgegeben drei Fälle **ok** (`small`), **viele** (`scale`)
und **fehler** (`failed`), als `<art>__<fall>__<schema>.mp4` (H.264) mit sichtbarem Mauszeiger,
Klickring, optionaler Bildunterschrift und Pause nach jedem Schritt. Ein Ablaufmodul exportiert
`TYPES` (die Arten), optional `KINDS` (Fall → Szenario, Vorgabe wie oben) und **eine**
Ablaufdefinition `run(h, { type, kind, scenario })`, die den Weg einer Art in jedem Fall spielt;
`h` bietet `goto`, `click`, `type`, `visible`, `pause` und `caption`. Ein fehlendes Element wird
geloggt und übersprungen, der Ablauf läuft weiter. `manifest.json` im Ausgabeordner listet alle
Videos mit Art, Fall, Szenario und Schema; `build_site.py` ordnet sie darüber zu.

`flows/library-videos.mjs` spielt die Quellen auf `main`: ok richtet die Quelle ein, listet, wählt,
testet und geht weiter bis „Freigaben“; viele dasselbe mit großer Liste, mehr Auswahl und
Scrollen; fehler zuerst „Weiter“ auf leerem Formular, dann die abgelehnte Auflistung bzw. den
abgelehnten Test. Eine Variante mit anderer Struktur bringt ihr eigenes Modul derselben Form mit
(`--flows`). Im UI-Lab entstehen Videos nur für die Gewinnerin: je Szenario ein Video, das alle
Reiter mit echten Aktionen durchläuft und keinen Weg wiederholt. Dafür spielt `run` je Fall den
ganzen Weg über alle Reiter; `--kinds` wählt die Szenarien, `--types` die Hauptart.

## compare.py

```bash
e2e/.venv/bin/python -I e2e/ui-lab/compare.py --variants A=<dirA>,B=<dirB>,C=<dirC> --out <dir> [--full] [--filter scale] [--per-sheet 3]
```

Erzeugt `<dir>/sheets/<screen>.png` (Varianten nebeneinander, beschriftet, jedes Bild in voller
Auflösung; mehr als `--per-sheet` Varianten verteilen sich auf `<screen>__p<n>.png`, die Variante
`Ist` steht auf jedem Teilbogen vorn), `<dir>/index.html` (alle Bögen nach Bereich) und
`<dir>/metrics-summary.md` (Kennzahlen je Variante, darunter Überschreitungen der Zählbudgets,
Kontrastbefunde und Schema-Abweichungen; Höhe, Wörter und Alerts je Bereich; axe-Regeln).

## contact_sheets.py

```bash
.venv/bin/python -I ui-lab/contact_sheets.py --dir <matrix> --out <dir> [--full] [--schemes light]
     [--widths 1280,320] [--filter <text>] [--per-reviewer 2] [--per-sheet 3]
```

Für das Konsistenz-Review einer Variante (Skill, Abschnitt 4):
- `<dir>/sheets/<bereich>__<szenario>__<schema>__<breite>.png` stellt denselben Bildschirm aller
  Typen in voller Auflösung nebeneinander, je Bogen höchstens `--per-sheet` Typen (weitere in
  `…__p<n>.png`); `<dir>/index.html` listet sie. So fallen Unterschiede zwischen Konnektoren
  oder Objekten auf, die je Einzelbild unsichtbar bleiben.
- `<dir>/reviewers/<n>-<typen>.txt` teilt die Typen in Gruppen von `--per-reviewer` und nennt je
  Prüfer alle hellen Bilder, alle 320er und je Bereich mindestens ein dunkles. Ein `__full`, das
  byte-gleich zu seinem Viewport-Bild ist, fällt weg.

## build_site.py

```bash
e2e/.venv/bin/python -I e2e/ui-lab/build_site.py --config site.json --out <dir> [--scheme light] [--cap 2400] [--video-urls urls.json]
```

Baut aus den Ergebnisordnern des Ist-Stands und der Entwürfe eine statische Vergleichsseite:
oben je Variante Idee, Stärken, Schwächen und Bewertung sowie „Gelernt und umgesetzt“, darunter
die Auswahl Variante → Bereich → Fall → Breite mit den Videos und allen Bildschirmen dieser
Auswahl, wahlweise mit dem Ist-Stand daneben. `site.json` nennt je Variante den Ergebnisordner
(`capture.mjs`-Ausgabe, Videos von `record-flows.mjs` im Unterordner `videos/`) und alle Texte;
Vorlage: `site-template/site.example.json`. Die Variante mit der ID `ist` ist der Ist-Stand.
Bereiche, Szenarien und Bildschirme ergeben sich aus den Dateinamen; `types`, `scenarios` und
`areas` in `site.json` geben ihnen Namen und Reihenfolge, für jeden Bereich des Produkts.

Je Auswahl entsteht ein senkrechtes PNG-Sprite (`shots/<variante>__<bereich>__<fall>__<breite>.png`)
mit allen Bildschirmen in voller Auflösung, jeder höchstens `--cap` px hoch, und den Offsets in
`data.json`; so bleibt die Seite unter der Dateigrenze einer Artifact-Version
(511 Dateien). Videos werden nach `videos/` kopiert. Für ein Artifact die Videos stattdessen als
Assets hochladen, die Zuordnung Dateiname (`<variante>__<datei>.mp4`) → Asset-URL als JSON
ablegen und mit `--video-urls` erneut bauen; dann veröffentlicht man `index.html` mit `data.json`
und `shots/` als Dateien.

## Grenzen

- Detail und Bearbeiten nutzen immer die Fixture-Bibliothek der Art, nicht die im Assistenten
  angelegte, damit der Vergleich über Varianten stabil bleibt.
- `__full` endet bei 16 000 px (300 Drive-Bereiche als Liste werden abgeschnitten).
- Hilfetexte erkennt die Messung an `MuiFormHelperText-root`; ein Entwurf mit eigenem
  Hilfetext-Baustein muss diese Klasse tragen, sonst zählt das Budget ihn nicht.
- Die Kennzahl `uiWords` schließt Daten nur über ihre Struktur aus (Listen, Bäume, Tabellen,
  Beschreibungslisten, Auswahlkästchen). Deshalb gilt das Wortbudget nur in `small` und `default`.
