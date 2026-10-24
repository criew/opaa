# UI-Lab-Harness: Quellflüsse der Wissensbibliotheken

Screenshots, Kennzahlen und Vergleichsbögen für die Varianten des Quell-Redesigns. Läuft gegen das
Frontend im MSW-Mock-Modus; die Stress-Szenarien kommen aus dem Branch `ui-lab/base-mocks`
(jede Variante zweigt davon ab und erbt sie).

## Voraussetzungen

- Playwright + Chromium global installiert (`npm root -g`), nie `playwright install` ausführen.
- `axe-core` liegt in `./node_modules` (`npm install` in diesem Ordner, falls es fehlt).
- Python 3 mit Pillow für `compare.py`.

## Dev-Server der Variante starten

```bash
cd <worktree>/frontend
pnpm run generate:api-types        # einmal je Worktree (erzeugt src/shared/api/generated/api.ts)
VITE_ENABLE_MOCKS=true pnpm exec vite --port <PORT> --strictPort
```

Nur einen Server je Agent; nach dem Lauf über die eigene PID beenden (`kill <pid>`), nie `pkill`.

## Szenarien (`?mockScenario=<name>`)

Im Browser per URL wählen, z. B. `http://localhost:5191/libraries/new?mockScenario=scale`. Die Wahl
bleibt in der sessionStorage des Tabs (Navigation und Neuladen behalten sie);
`?mockScenario=default` setzt zurück. Quelle: `frontend/src/shared/mocks/mockScenario.ts`,
Auflistungen/Tests in `frontend/src/features/library/mocks/sourceScenarioMock.ts`,
Detail-Fixtures in `…/libraryScenarioFixtures.ts`.

| Szenario | Auflistungen (`POST /source-types/:type/browse`) | Verbindungstest (`POST /libraries/source-test`) | Detailseiten / Läufe / Gruppen |
|---|---|---|---|
| `default` | bisherige Fixtures (SharePoint: Sites.Selected, Suche abgelehnt) | bisherige Antworten | bisherige Fixtures |
| `small` | 1 Site · 1 Dokumentbibliothek · 2 Ordner je Ebene, 2 Ebenen · 2 Spaces · 1 Drive-Bereich · 1 Bucket · 2 Ordner (NC/SMB/Dropbox) | Erfolg | je Bibliothek 1 Auswahl; 1 Gruppe |
| `scale` | 17 Sites × 25 Dokumentbibliotheken · 200 Ordner je Ebene, 8 Ebenen · 450 Spaces (jeder 10. persönlich) · 300 Drive-Bereiche · 600 Buckets, Präfixe 200 je Ebene · NC/SMB/Dropbox 200 Ordner je Ebene, 8 Ebenen · jeder 3. Name 80–120 Zeichen mit Umlauten, Nr. 7 mit 35-Zeichen-Kompositum „Grundstücksverkehrsgenehmigungsakte“ | Erfolg, Zählung nach Anfrage | Bibliotheksnamen lang; Confluence 120 Spaces, SharePoint 25 Bibliotheken, S3/Drive/NC/SMB/Dropbox 50 Auswahlen (Ordnerpfade bis 8 Ebenen); 10 Läufe mit bis zu 140 Ereignissen (gekappt); 302 Gruppen |
| `empty` | 0 Einträge überall | Erfolg | 0 Dokumente, keine Läufe |
| `slow` | wie `default`, Antwort nach 3 s | wie `default`, nach 3 s | – |
| `failed` | HTTP 403 (SharePoint, Drive, S3) bzw. 502 (Confluence, Nextcloud, SMB, Dropbox) mit deutscher Backend-Meldung | `reachable:false, credentialsVerified:false` mit typgerechter Ablehnung; Webverzeichnis/RSS HTTP 502; Confluence-Editionserkennung ohne Zugangsdaten funktioniert | `lastScheduledRunsFailed`, 3 fehlgeschlagene Läufe |
| `partial` | 6 Sites · 8 Bibliotheken · 30 Ordner, 4 Ebenen · 40 Spaces · 25 Bereiche · 30 Buckets · 30 Ordner; alle `complete:false` mit Meldung | Teilfehler je Bereich: jeder 2. (bzw. der einzige) Bereich scheitert — S3 `details.scopes`, Drive `details.scopes`, SharePoint `details.libraries`, NC/SMB/Dropbox `details.folders`, Confluence `details.unreadableSpaces` | Lauf mit 17 Fehlern |

Zusätzliche Abfrageformen der Mocks (für Varianten, die tiefer browsen):

- Nextcloud, SMB, Dropbox: `query.path` (auch `query.folder`) → direkte Unterordner; Schlüssel sind Pfade.
- S3: `query.bucket` (+ `query.prefix`) → Präfixe darunter, Schlüssel enden auf `/`.
- SharePoint (generierte Szenarien): `query.search` filtert die Sites nach Namen (leer = alle),
  `query.siteUrl` findet `…/sites/rathaus` (Site 1) und `…/sites/site-002` …,
  `query.site` → Bibliotheken, `query.drive` + `query.folder` → Ordner (IDs Graph-konform, `<drive>_F1-4-2`).

Fixture-Bibliotheken je Quellart (Detailseite direkt aufrufbar):

| Typ | ID |
|---|---|
| Dateisystem | `library-filesystem-satzungen` |
| Confluence | `library-confluence-wiki` |
| SharePoint | `library-sharepoint-rathaus` (Zugang „Microsoft 365 Rheinfurt“) |
| Nextcloud | `library-nextcloud-projekte` |
| SMB | `library-smb-ablage` |
| S3 | `library-s3-protokolle` |
| Google Drive | `library-gdrive-projekte` |
| Dropbox | `library-dropbox-projekte` (Zugang „Dropbox Rheinfurt“, verbunden) |

Weitere behobene Mock-Lücken: Zugänge für SharePoint/Dropbox (nur im Browser, die Unit-Tests sehen
weiter nur den Nextcloud-Zugang), POST/PUT `/libraries` behalten Quellkonfiguration jeder Art,
Dropbox-Auflistung und -Test, `/api/v1/notifications`, der Dropbox-OAuth-Rundweg übersteht das
Neuladen (ausstehende Zustimmungen in der sessionStorage), und der Service Worker bleibt nach einem
Neustart angemeldet (`startMockWorker` in `src/app/mocks/browser.ts`).

## Capture

```bash
node capture.mjs --base http://localhost:<PORT> --out <dir> [--quick]
     [--types filesystem,confluence,sharepoint,nextcloud,smb,s3,gdrive,dropbox]
     [--scenarios small,scale,empty,slow,failed,partial]   # auch: default
     [--schemes light,dark] [--widths 1280,320]
     [--areas wizard,review,detail,edit] [--concurrency 2] [--no-axe] [--no-full]
```

- `--quick` = hell, 1280 px, Szenarien `small,scale` (alle Typen).
- Vorgabe = alle 8 Typen × `small,scale,empty,slow,failed,partial` × hell/dunkel × 1280/320.
- Je (Typ, Szenario) ein frischer Seitenaufruf (Mock-Zustand zurückgesetzt), zwei Seiten parallel
  in einem Browser (`--concurrency`).
- Zustände je Szenario: `small`: wizard-empty, wizard-validation (Weiter auf leerem Formular),
  wizard-filled, review, detail, edit · `scale`: wizard-filled, review, detail, edit ·
  `failed`: wizard-filled, detail, edit · `partial`/`empty`/`slow`: wizard-filled, detail
  (`slow` wird mitten im Laden aufgenommen) · `default`: wie `small` ohne Validierung.
- Ablauf: `/libraries/new` → Quellart-Kachel (Name per Rolle radio/button/option/tab) → Weiter →
  Zugang wählen (SharePoint/Dropbox Profil, Nextcloud „Eigene Adresse“) → Dropbox: Häkchen
  „Dienstkonto“ + „Quelle verbinden“ (Mock-OAuth) → Felder per Label füllen → Auflistungsknöpfe
  („… laden“, „Sites suchen“, „Site öffnen“, …) → Auswahl (offene Listbox, sonst treeitem/checkbox)
  → „Verbindung testen“ → Weiter bis zu einer Überschrift „Prüfen/Überprüfen/Zusammenfassung“.
  Detail/Bearbeiten: `/libraries/<fixture-id>` → Reiter „Quelle“ → Knopf „Quellkonfiguration
  bearbeiten | Quelle bearbeiten | Bearbeiten | Ändern“ → Auflistungsknopf im Bearbeiten-Fluss.
  Was nicht gefunden wird, steht als `status: "not-found"` mit `reason` in `metrics.jsonl`.
- Dateien: `<area>__<type>__<scenario>__<scheme>__<width>.png` (Viewport 900 px hoch) und
  `…__full.png` (Viewport auf Inhaltshöhe vergrößert, bei offenem Dialog nur dessen Inhalt,
  höchstens 16 000 px).
- `metrics.jsonl`, eine Zeile je Screenshot: `title`, `url`, `overflowX` (+ `overflowCulprit`),
  `docHeight` (Inhaltshöhe inkl. innerer Scrollbereiche), `clippedText` (abgeschnittener Text ohne
  Ellipse), `disabledSubmits`, `alertCount`/`alertSeverities`, `invalidFields` (`aria-invalid`),
  `focusable`, `fontSizes`/`colors` (verschiedene Werte im Hauptbereich bzw. offenen Dialog),
  `words`, `headings`, `axe` (`violations[{id,impact,nodes,sample}]`, `contrastViolations`;
  hell: WCAG 2.0–2.2 A/AA + best-practice inkl. `target-size`; dunkel: nur `color-contrast`),
  `tabStops` (40 Tab-Stopps: role, name, hidden, obscured; nur hell) mit `focusHidden`/`focusObscured`.
- `run.json`: Argumente, Anzahl, Laufzeit.

## Vergleich

```bash
python3 compare.py --variants A=<dirA>,B=<dirB>,C=<dirC> --out <dir> [--full] [--filter scale]
```

Erzeugt `<dir>/sheets/<screen>.jpg` (Varianten nebeneinander, beschriftet, auf 560 px Zellbreite
verkleinert; 320-px-Aufnahmen bleiben in Originalgröße), `<dir>/index.html` (alle Bögen nach
Bereich) und `<dir>/metrics-summary.md` (Kennzahlen je Variante, Höhe/Wörter/Alerts je Bereich,
axe-Regeln).

## Laufzeiten (Basis `ui-lab/base-mocks`, 4 CPUs)

- `--quick`: 240 s, 63 Screenshots (`/home/user/ui-lab/runs/base-quick/`).
- Vorgabe-Matrix: 810 s (13,5 min), 532 Screenshots + ebenso viele `__full`
  (`/home/user/ui-lab/runs/base-full/`).
- Mit einer Variante, die mehr Zustände erreicht (z. B. Prüfschritt), wächst die Zeit um etwa 10 s je
  zusätzlichem Zustand und Szenario/Typ.

## Grenzen

- Die Ablauferkennung folgt deutschen Beschriftungen (Weiter, Verbindung testen, … laden,
  Bearbeiten, Prüfen). Eine Variante mit abweichenden Wörtern erscheint als `not-found`; dann die
  Regexe am Anfang von `capture.mjs` (`LISTING_BUTTONS`, `TEST_BUTTON`, `next()`) erweitern.
- Die Basis hat keinen Prüfschritt: `review` ist dort `not-found` (Wizard endet bei „Freigaben“).
- Detail und Bearbeiten nutzen immer die Fixture-Bibliothek der Art, nicht die im Assistenten
  angelegte (stabil über Varianten hinweg).
- `__full` ist auf 16 000 px begrenzt (300 Drive-Bereiche als Checkbox-Liste werden abgeschnitten).
- Der Nextcloud-Zugang zeigt weiter die undeklarierte Vorgabe `edition: INTERN` (Unit-Tests hängen
  daran).
