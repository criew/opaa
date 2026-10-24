# UI-Inventar „Detailansicht einer Wissensbibliothek“ (Ist = Variante F, 24.10.2026)

**Gegenstand.** `/libraries/:id` mit Kopf, Kennzahlen, dem Bereich zwischen Kopf und Reitern und den Reitern Dokumente, Quelle (mit Läufen), Metadaten, Freigaben und Zuordnungen, für alle 11 Quellarten. Grundlage ist Variante F aus dem ersten Lab. Ihr Reiter „Quelle“ (Zeilen Verbindung · Umfang · Zeitplan mit „Ändern“) ist entschieden und bleibt; diese Inventur bewertet ihn nur dort, wo er mit dem Rest der Seite zusammenstößt.

**Belege**
- **Bilder:** `ui-lab/results/ist-detail/<bereich>__<art>__<szenario>__<schema>__<breite>[__full].jpg`.
  - Bereiche: `dokumente quelle laeufe metadaten freigaben zuordnungen upload`.
  - Arten: `upload filesystem web rss confluence sharepoint nextcloud smb s3 gdrive dropbox`, dazu `privat`, `upload-leser`, `confluence-leser` und `nextcloud-leser`.
  - Szenarien: `small scale failed empty partial slow`; `slow` steht hier für „ein Lauf läuft“.
  - Hell und dunkel, 1280 und 320 px. `__full` gibt es nur für hell, in halber Breite.
- **Kennzahlen:** `metrics.jsonl` und `metrics-extra.jsonl`.
- **Review:** `ui-lab/results/ist-detail/REVIEW-1.md`. Nummern `R1-n` beziehen sich darauf.
- **Prüfliste:** P-Nummern beziehen sich auf `docs/design/ui-pruefliste.md`, G-Nummern auf die Abschnitte von `docs/design/guidelines.md`.

**Pfadkürzel:** `C/` = `frontend/src/features/library/components/`, `P/` = `…/library/pages/`, `A/` = `frontend/src/features/asset/components/`.

---

## 1 Aufbau der Seite

```
AssetDetailHeader (A/AssetDetailHeader.tsx, geteilt mit der Prompt-Bibliothek)
 ├ Zeile 1  Chips [Wissen] [Quellart] [eigene Rolle] (+ Privat · Wird gelöscht · administrativ)   [Jetzt indizieren] ☆ ⋯
 ├ Titel (Stift = Name und Beschreibung bearbeiten), Beschreibung
 ├ note      LibraryHeaderNote: Nachfolge offen · Löschung vorgemerkt (Alert error) · Kontingent erschöpft (Alert warning)
 ├ Kennzahlen (HeaderFigure = Icon + Text, umbrechend)
 │    n Dokumente · [Speicher belegt] · [Letzter Lauf: … ] · in n Spaces · Eigentümer · Aktualisiert am …
 └ children  LibraryScopeHero (Warnkante „… nicht lesbar“) · Fortschrittsbalken + „n von m … verarbeitet“
Seitenfehler (Alert) · LibraryRoleNotices (Alert info: nur Leserechte / nur Dokumente / administrativ)
Reiterleiste (C/LibraryDetailTabBar.tsx)  Dokumente · Quelle¹ · Metadaten² · Freigaben · Zuordnungen
Panels (alle gemountet, nur `hidden`)
```
¹ nicht bei Upload · ² Feature-Flag `metadata`

„Jetzt indizieren“ ist ein Umriss-Knopf (eine Primärfläche je Fläche, die Zeilen im Reiter Quelle speichern mit ihrer eigenen). „Vollabgleich starten“ steht im ⋯-Menü, aber nur bei Arten mit Rhythmus (Confluence, SharePoint, Drive, Dropbox).

## 2 Aufbau je Reiter

### 2.1 Dokumente (`C/LibraryDocumentsSection.tsx`, `LibraryDocumentList.tsx`)

| Teil | Upload | Konnektor |
|---|---|---|
| Anfang | Drop-Fläche (gestrichelt, Icon, „Dateien oder Ordner hierher ziehen“), darunter „Dateien hochladen“ (gefüllt) · „Ordner hochladen“ · „Neuer Ordner“ | blau getöntes Info-Alert „Dieser Bestand stammt aus der Quelle; einzelne Dokumente lassen sich hier nicht löschen — …“ (R1-41) |
| Meldungen | Upload-Fehler: ein rotes Alert mit X, eine Zeile je Datei, Grund jedes Mal wiederholt (`upload__upload__failed__light__1280`, R1-10) | – |
| Metadaten-Pflege | Overline „METADATEN-PFLEGE“, Erklärsatz (2 Zeilen), je Kernfeld „n Dokumente ohne Wert (x %)“ + Knopf „Anzeigen“ | gleich |
| Werkzeugleiste | Suche über die ganze Breite + „20 je Seite“; „Alle auf dieser Seite auswählen“ · „0 ausgewählt“ · „Feld setzen“ (deaktiviert) | gleich |
| Ordner | Ordnerzeilen vor den Dateien, ungeblättert (60 in `scale`, R1-37); blasse Checkbox + ⋯ | – |
| Zeile | Name · „Größe · n Abschnitte · Datum“ · Status-**Chip** (indiziert, fehlgeschlagen, ausstehend) · ⓘ (Metadaten) · ↗ (Original) · Papierkorb | ohne Papierkorb; Web, RSS und Confluence mit dritter Zeile „Quelle: URL“ bzw. „Space: … · Seite …“ (R1-21) |
| Fuß | „n Dokumente · Seite 1 von m“ (bei Upload je Ordnerebene, ≠ Kopf, R1-37) | gleich, stimmt mit dem Kopf überein |
| leer | „Es sind noch keine Dokumente vorhanden …“ **und** „vollständig gepflegt“, Suche und Info-Alert (R1-32) | gleich |

### 2.2 Quelle (Variante F, entschieden)

- **Zeilen:** `Verbindung · Umfang · Zeitplan` als Titel | Inhalt | „Ändern“, Inhaltsbreite 720 px (`CONTENT_WIDTH`). Web und RSS haben **keine Zeile Umfang** (R1-12).
- **Verbindungszeile:** Sie trägt immer „● nicht geprüft“, auch nach abgelehnter Anmeldung (R1-27). Ihr Aufbau ist je Art anders (R1-14).
- **Zeitplan:** „täglich 06:00 · [Vollabgleich alle 7 Tage ·] nächster Lauf 25.10.26, 06:00“.
- **Leser:** Sie sehen nur die Zeile Umfang und den Satz „Verbindung, Zeitplan und Läufe sehen nur Verwaltende.“
- **In F gegenüber `main` nicht mehr erreichbar:** Der Zeilenpfad in `C/LibrarySourceSection.tsx` rendert `StoredView` nicht mehr. Betroffen sind der Confluence-Webhook (`ConfluenceWebhookSection`), die S3-Ereignisbenachrichtigung (`S3EventSection`) und der Hinweis „Die letzten geplanten Läufe … sind fehlgeschlagen“ (`lastScheduledRunsFailed`). Jede Richtung braucht für sie einen Platz (Kapitel 8).

### 2.3 Läufe (im Reiter Quelle, unter den Zeilen)

- **Platz und Form:** Überschrift „Läufe“, je Lauf ein Accordion (die letzten 10, Grenze des Backends).
- **Kopfzeile:** Punkt + Status („Abgeschlossen“, „Teilweise fehlgeschlagen“, „Fehlgeschlagen“, „Läuft“), Startzeit, Auslöser („per Zeitplan“, „manuell gestartet“), „n verarbeitet, n übersprungen, n fehlgeschlagen“, dazu bei Rhythmus-Arten „Inkrementell“ oder „Vollabgleich“ und optional „unvollständig, wird fortgesetzt“ sowie „n Bereiche nicht lesbar“.
  - Die Zeile bricht ohne Raster auf 2–4 Zeilen um.
  - Die ganze Zeile ist als Überschrift ausgezeichnet (R1-31).
- **Aufgeklappt:**
  1. die Meldung des Backends („Indizierung abgeschlossen“, auch bei laufenden Läufen, R1-26)
  2. die Kostenzeile („n Anfragen an die Quelle · n-mal gedrosselt · n MB geladen · Anhänge: … · Dauer …“)
  3. die Ereignisliste: fettes Kategoriewort, Meldung, Fundort in Kleinschrift. Es gibt keine Gruppierung, keinen Filter und keine Seiten. Bis zu 500 Ereignisse folgen, danach „… und n weitere“ (R1-29).
  4. Ohne Ereignisse: „Nichts übersprungen oder fehlgeschlagen.“, auch neben „3 übersprungen“ (R1-28).
- **Rückmeldung 2 („sehr länglich, seltsam aufgebaut“)** ist bestätigt. Belege: `laeufe__smb__scale__light__1280__full` (24 000 px), `laeufe__sharepoint__scale__light__1280__full` (Zeilenumbrüche) und `laeufe__web__partial__light__1280__full` (Hinweise zwischen Fehlern, R1-30).

### 2.4 Metadaten (`C/LibraryMetadataTab.tsx`)

- **Metadatenfelder** (`PageSection`): Overline „METADATENFELDER“ + Beschreibung, darunter noch einmal „Metadatenfelder“ (R1-7) mit Erklärsatz und „Feld anlegen“ (Link). Es folgen ein getöntes Info-Alert zu Wertelisten, die Schalter für das Kontextpräfix und je Feld der Titel, die getönten Chips Filter/Kontextpräfix, „Bearbeiten“ und „Löschen“ sowie die Werteliste als Fließtext (40 Werte = Textwand, `metadaten__*__scale__light__1280__full`).
- **Modellgestützte Extraktion** (ab Verwalter): Schalter, Datenschutz-Alert (beige), Extraktionsgüte (doppelt zur Metadaten-Pflege im Reiter Dokumente, R1-6), Modell-URL, Zähler.
- Mit 352–363 Wörtern in `small` liegt der Reiter mehr als doppelt über dem Budget (R1-42).

### 2.5 Freigaben (`C/LibrarySharingTab.tsx`)

1. Eigentümer: Name, Erklärsatz, „Eigentum übergeben“
2. Berechtigungen: dreizeiliger Erklärsatz, der mit „— auch die.“ endet. Danach die Liste, je Eintrag 3 Textzeilen (Name · „Gruppe · Rolle vergeben von … · zuletzt geändert …“ · „Mitglieder anzeigen“) mit Rollen-Select samt Label „Rolle“, „unbefristet“ oder Datum und Papierkorb. Am Listenende steht „+ Freigeben“. Bei 45 Einträgen gibt es weder Suche noch Gruppierung (R1-34).
3. Rollen-Legende
4. Externer Zugang (Feature-Flag; im Mock aus, dann nur ein Satz)
5. Diagnosesperre
6. „Warum sehe ich diese Bibliothek?“

Privat: Statt 2–4 steht ein Info-Alert „Nur Sie sehen …“.

### 2.6 Zuordnungen (`A/AssetSpacesSection.tsx`)

Der Satz „Die Spaces, in denen dieses Objekt als Datenquelle bereitsteht.“ (P19: „Objekt“) und der Knopf „In Space verwenden“ stehen oben. Darunter je Space eine Zeile: Icon (Schmuck, P3), Name, Datum, ⋯. Auch bei 34 Spaces gibt es keine Suche (R1-35).

---

## 3 Unterschiede je Quellart (Rückmeldung 1)

Szenario `small`, Rolle Eigentümer, hell 1280, wenn nicht anders genannt. Belege: `grids` der Querbögen und `dokumente|quelle__<art>__small__light__1280`.

| Element | upload | filesystem | web | rss | confluence | sharepoint | nextcloud | smb | s3 | gdrive | dropbox |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Chip Quellart | Upload | Dateisystem | Webverzeichnis | RSS-Feed | Confluence | SharePoint | Nextcloud | **Windows-Dateifreigabe (SMB)** | S3-Objektspeicher | Google Drive | Dropbox |
| „Jetzt indizieren“ | **–** | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| ⋯ „Vollabgleich starten“ | – | – | – | – | ✓ | ✓ | – | – | – | ✓ | ✓ |
| Kennzahl Speicher | ✓ | – | – | – | – | – | privat: andere Form | – | – | – | – |
| Kennzahl „Letzter Lauf“ | **–** | Dokumente | Dokumente | **Feed-Einträge**, 2 Zeilen | Dokumente | Dokumente | Dokumente | Dokumente | Dokumente | Dokumente | Dokumente |
| Lage der Reiterleiste (y) | **≈ 258** | ≈ 288 | ≈ 288 | ≈ 288 | ≈ 288 | ≈ 288 | ≈ 288 | ≈ 288 | ≈ 288 | ≈ 288 | ≈ 288 |
| Teilfehler-Kante (`partial`) | – | **–** | **–** | **–** | Spaces · „Vollabgleich … lesen“ | Bibliotheken · „Lauf … erreichen“ | Ordner · „Abgleich … auflisten“ | Ordner · „… vollständig auflisten“ | „Geltungsbereiche … Vollabgleich“ | **„drive:…“-IDs** | Ordner · „Lauf … auflisten“ |
| Dokumente: Anfang | Drop-Fläche + 3 Knöpfe | Info-Alert | Info-Alert | Info-Alert | Info-Alert | Info-Alert | Info-Alert | Info-Alert | Info-Alert | Info-Alert | Info-Alert |
| Dokumentzeile | 2 Z. + Ordner | 2 | **3** (URL) | **3** (URL) | **3–4** (Space-Pfad) | 2 | 2 | 2 | 2 | 2 | 2 |
| Reiter Quelle | **fehlt** | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Verbindung (Inhalt) | – | Pfad | Host | Host | Host · Cloud | Zugang „…“ | Host | Host/Pfad | Host:Port | „Schlüsseldatei gesetzt“ | Zugang „…“ · eigene Ablage |
| Zeile Umfang | – | Muster | **–** | **–** | Spaces | Bibliotheken | Ordner | Ordner | Buckets/Präfixe + Muster | Ablagen/Ordner | Ordner |
| Zeitplan | – | täglich | täglich | täglich | + Vollabgleich 7 T. | + Vollabgleich tägl. | täglich | täglich | **aus** | + Vollabgleich 7 T. | täglich (**ohne** Vollabgleich) |
| Betriebsart im Lauf | – | – | – | – | ✓ | ✓ | – | – | – | ✓ | ✓ |
| Leser-Knopf | – | – | – | – | – | – | – | – | – | – | – |

**Befund:** Rahmen, Reiter und Abschnittsfolge sind gleich. Unterschiede entstehen an fünf Stellen:

1. Kennzahlzeile (Lauf, Speicher, RSS-Zählung → Kopfhöhe)
2. Zwischenbereich (Kante nur bei 7 Arten, 6 Wortlaute)
3. Anfang des Reiters Dokumente (Upload gegen Konnektor)
4. Verbindungs- und Umfangszeile
5. Betriebsart (Rhythmus nur bei 4 Arten, Dropbox halb)

Upload weicht am stärksten ab: Es fehlen „Jetzt indizieren“, der Reiter Quelle, ein Gesamtstatus und der Ort für Upload-Fehler über einen Seitenwechsel hinaus.

## 4 Zwischenbereich (Rückmeldung 4 und Hypothese des Maintainers)

**Hypothese (Koordinator):** „Was im oberen Teil über den Reitern steht (Umfangszeile, quellartspezifische Hinweise), gehört eigentlich in den Reiter ‚Quelle‘.“

**Bestand**, zwischen der Kennzahlzeile und der Reiterleiste; dazu die Kennzahlzeile selbst und der Anfang jedes Reiters:

| # | Element | Arten | Szenario | Einordnung | Beleg |
|---|---|---|---|---|---|
| Z1 | Umfangszeile („Alle Ordner · Details“) | – | – | **in F entfernt** (Review F, R1-14 dort); auf `main` noch vorhanden | `ui-lab/results/F/detail__nextcloud__small__light__1280.jpg` (vor der Runde) |
| Z2 | Kennzahl „Letzter Lauf: Zustand · Zahlen · Zeit“ | 10 Konnektoren | alle außer `slow`, `empty` | **Doppelung** (erste Laufzeile, R1-1), zugleich Status mit **falschem Wort** (R1-26) | `quelle__nextcloud__scale__light__1280__full` |
| Z3 | Teilfehler-Kante „… konnte … nicht auflisten …“ | 7 Konnektoren | `partial` | **Status** (Prüfen nötig), **Doppelung** zur Laufzeile „n Bereiche nicht lesbar“, 6 Wortlaute | `dokumente__confluence__partial__light__1280` |
| Z4 | Fortschrittsbalken „831 von 1340 Dokumenten verarbeitet“ | 10 Konnektoren | `slow` | **Status**, dreifach mit Knopf und Laufzeile (R1-2) | `dokumente__s3__slow__light__1280` |
| Z5 | Rollenhinweis „Sie haben … nur Leserechte“ | alle (Leser) | alle | **Doppelung** zum Chip „Leser“ (R1-8) | `quelle__confluence-leser__small__light__1280` |
| Z6 | Nachfolge offen / Löschung vorgemerkt / Kontingent erschöpft | Upload, privat, alle | selten | **Status** mit Handlung (nicht im Mock-Szenario, Code `C/LibraryHeaderNote.tsx`) | – |
| Z7 | Hinweis auf abgelehnte Anmeldung | – | `failed` | **fehlt**: der einzige Zustand mit dringender Handlung hat keinen Platz (R1-27) | `dokumente__nextcloud__failed__light__1280` |
| Z8 | Kennzahlen Spaces, Eigentümer | alle | alle | **Doppelung** zu den Reitern Zuordnungen und Freigaben; Information | `freigaben__gdrive__small__light__1280__full` |
| Z9 | Kennzahl Speicher | Upload, privat | alle | Information; bei vollem Kontingent fehlt der Status (R1-37) | `upload__upload__failed__light__1280` |
| Z10 | Dokumente: Info-Alert „Bestand stammt aus der Quelle“ | 10 Konnektoren | alle, auch leer und Leser | **Information ohne Handlung** (R1-41) | `dokumente__sharepoint__empty__light__1280` |
| Z11 | Dokumente: Metadaten-Pflege | alle | alle | **Doppelung** zur Extraktionsgüte (R1-6) | `dokumente__smb__small__light__1280` |

**Prüfung der Hypothese**

- **Bestätigt** für das Quellartspezifische:
  - Die Umfangszeile Z1 ist schon weg.
  - Die Teilfehler-Kante Z3 ist ein Umfangsbefund und gehört an die Zeile Umfang. Dort ist der Name der Bereiche bekannt, die Roh-IDs entfallen, und die Doppelung zur Laufzeile auch.
  - Die Zahlen des letzten Laufs (Z2) gehören in die Laufliste.
  - Das Info-Alert Z10 entfällt ersatzlos.
- **Nicht haltbar** ist „alles in den Reiter Quelle“ für drei Fälle:
  - (a) **Abgelehnte Anmeldung, Zustimmung abgelaufen, letzte Läufe fehlgeschlagen** (Z7): Wer im Reiter Dokumente arbeitet, muss es sehen (P41, G 5.9: „bleibt an seinem Ort sichtbar“; der Ort ist die Bibliothek, nicht nur die Zeile).
  - (b) **Ein laufender Lauf** (Z4): G 5.7 verlangt, dass Hintergrundvorgänge über Seitenwechsel sichtbar bleiben.
  - (c) **Upload:** Ohne Reiter Quelle gibt es keinen Ort für Kontingent und Upload-Fehler.
- **Folgerung:** Der Kopf trägt genau **ein Zustandsfeld** (ein Wort plus Datum oder Fortschritt), dasselbe für alle Arten:

  | Zustand | Anzeige |
  |---|---|
  | Aktuell | Zeitpunkt |
  | Läuft | 62 % |
  | Teilweise | Zeitpunkt |
  | Fehlgeschlagen | Zeitpunkt |
  | Anmeldung abgelehnt | – |
  | Kontingent voll | – |
  | Noch nie gelaufen | – |

  Es verlinkt auf den Ort der Ursache: Quelle › Verbindung, Quelle › Läufe oder bei Upload Dokumente. Bei Upload heißt es „Hochgeladen“ oder „Kontingent voll“. Richtung A setzt die Hypothese so um, Richtung B stellt einen kompakten Statusbereich für alle dagegen (siehe `richtungen-detail.md`).

## 5 Doppelungen

### 5.1 Auf der Seite
R1-1 bis R1-10, gebündelt:

| Information | Stellen |
|---|---|
| Ergebnis des letzten Laufs | Kopf · Laufzeile · Laufdetail (3) |
| laufender Lauf | Knopf · Balken · Laufzeile (3) |
| Teilfehler | Kante · Laufzeile (2) |
| eigene Rolle | Chip · Rollenhinweis · Satz im Reiter Quelle (3) |
| Eigentümer | Kennzahl · Freigaben/Eigentümer · Eintrag „Eigentümer“ in den Berechtigungen (3) |
| Spaces | Kennzahl · Reiter Zuordnungen (2) |
| Füllgrad Metadaten | Dokumente · Metadaten (2) |
| Überschrift Metadatenfelder | Overline · Titel (2) |
| Upload-Fehlergrund | je Datei wiederholt |
| Ereignisgrund | Label · Meldung (2) |

### 5.2 Zum Reiter „Quelle“ von F
- **Kopf-Kennzahl „Letzter Lauf“ ↔ Laufliste im Reiter Quelle.** Die Zahlen gehören nur in die Laufliste (R1-1).
- **Teilfehler-Kante ↔ Zeile Umfang.** Die Zeile Umfang kennt die Bereiche und kann sie markieren; die Kante wird überflüssig.
- **„nicht geprüft“ in der Zeile Verbindung ↔ Ergebnis des letzten Laufs.** Beide sagen etwas über die Verbindung und widersprechen sich (R1-27). Im Detail sollte die Zeile den letzten bekannten Zustand tragen: „Anmeldung ok · zuletzt 23.10.“ oder „Anmeldung abgelehnt“.
- **Zeile Zeitplan „nächster Lauf …“ ↔ Kennzahl „Aktualisiert am …“ im Kopf:** Information, keine Doppelung.
- **Rollenhinweis „nur Verwaltende“** im Reiter Quelle ↔ Rollenhinweis über den Reitern.

## 6 Verstöße gegen Guidelines und Prüfliste

| Regel | Befund | Beleg |
|---|---|---|
| G 1.2, P5 | Fortschrittsbalken über die volle Breite in Akzentblau, Akzent ohne Handlung | `dokumente__confluence__slow__light__1280` |
| G 1, P4 | Getönte Info-Alerts (Bestand, Rolle, Wertelisten), beige Warnbox, getönte Chips | `dokumente__s3__small__light__1280`, `metadaten__s3__small__light__1280__full` |
| P3 | Schmuck-Icons vor jeder Kennzahl und vor jedem Space | `zuordnungen__web__scale__light__1280` |
| P6 | Dokumentzeile 3–4 Zeilen; Freigabezeile 3 Zeilen; Laufzeile 2–4 Zeilen | R1-21, R1-34, R1-31 |
| P11 | Zwei Überschriftenstile (Mono-Versalien gegen fette Satzschrift) | R1-18 |
| P13 + Budget | Wörter: Metadaten 352–363, Freigaben 237–261, Dokumente 151–218 (Grenze 150) | `metrics.jsonl`, R1-42 |
| P15 | Formatfehler zählt alle Endungen auf; Teilfehler mit Roh-ID (Drive) | R1-43, R1-39 |
| P16 | Status als Satz in der Kennzahl („Letzter Lauf: Abgeschlossen · Dokumente: …“) | R1-1 |
| P18 | „Nichts übersprungen oder fehlgeschlagen“ neben Zahlen | R1-28 |
| P19 | „Windows-Dateifreigabe (SMB)“ (Doppel-Label), „Objekt“ (Zuordnungen), „Space“-Kollision bei Confluence | R1-36 |
| P20 | Freigaben (45), Zuordnungen (34), Ereignisse (500) ohne Suche, Gruppierung oder Seiten | R1-29, R1-34, R1-35 |
| P22, P43 | Titel bei 320 px abgeschnitten, Kompositum bricht nicht | R1-33 |
| P23, P38 | Leerzustand nicht unterscheidbar (leer und „vollständig gepflegt“, Suche bei 0) | R1-32 |
| P25 | Kopf belegt bei 320 px 430–600 px, Reiter ohne Scrollhinweis | R1-33 |
| P39 | Laufender Lauf: Balken und Knopf und Zeile mit verschiedenen Zahlen | R1-2 |
| P40 | Indizierungsstatus in drei Formen und mit Widerspruch (grün „Abgeschlossen“ gegen „Teilweise fehlgeschlagen“) | R1-19, R1-26 |
| P41, G 5.9 | Abgelehnte Anmeldung nur im zugeklappten Lauf | R1-27 |
| P42 | Nur-Lesen dreifach und je Reiter anders gesagt | R1-8 |
| P44 | axe: `heading-order`, `landmark-unique`, `aria-progressbar-name`, `label-content-name-mismatch` | R1-40 |
| P46 | Inhaltsbreite 720 px (Quelle) gegen 1136 px (übrige), Kopfhöhe je Art | R1-16, R1-17 |
| P50 | siehe Kapitel 5 | |
| G 5.5 | Dokumentstatus als umrandeter Chip, Laufstatus als Punkt + Wort | R1-19 |
| G 5.12 | Kein Verstoß: keine Überschrift wiederholt den Reiternamen (doppelt ist „Metadatenfelder“ in sich, nicht zum Reiter) | |

Ohne Befund: Kontrast im dunklen Schema (0), `overflowX` (0) und der blaue Verlauf (weg, R1-44).

## 7 Rückmeldungen des Maintainers

| # | Rückmeldung | Ergebnis |
|---|---|---|
| 1 | Detailseite je Quellart anders | **Bestätigt**, aber auf fünf Stellen begrenzt (Kapitel 3). Upload weicht am stärksten ab. |
| 2 | Läufe länglich und seltsam aufgebaut | **Bestätigt**: bis 24 000 px, kein Raster, Widersprüche, Hinweise zwischen Fehlern (Kapitel 2.3). |
| 3 | Blauer Verlauf oben links | **In F behoben** (9e13e10, `radial-gradient` aus `A/AssetDetailHeader.tsx`); keiner der 6 Prüfer fand ihn. **Auf `main` noch vorhanden**, dort auch in der Prompt-Bibliothek, weil der Kopf geteilt ist. Das Umsetzungs-Issue muss ihn für beide entfernen. |
| 4 | Zwischenbereich je Quellart anders | **Bestätigt**: Kante bei 7 von 10 Arten, 6 Wortlaute, Fortschritt, Rollenhinweis; bei `failed` fehlt der wichtigste Hinweis (Kapitel 4). |

## 8 Einstellungen nur in der Detailansicht, nicht im Assistenten

Der Assistent (`P/LibraryCreatePage.tsx`, F: Quellart → Name → Quelle einrichten → Freigaben → Prüfen) kennt keine der folgenden Einstellungen. `grep` über `LibraryCreatePage.tsx`, `CreateSharingStep.tsx`, `LibraryReviewStep.tsx` und `CreateSourceSetupStep.tsx` findet weder Diagnose, Extern, Metadaten noch Webhook.

| Einstellung | Quellart(en) | Reiter in F | Wer sieht sie | Warum nicht im Assistenten | Vorschlag |
|---|---|---|---|---|---|
| Confluence-Webhook (Geheimnis erzeugen, URL) | Confluence | **in F nicht erreichbar** (nur auf `main` im Abschnitt Anbindung) | Verwalter+ | **technisch nötig**: Die Webhook-URL enthält die Bibliotheks-ID, die erst nach dem Anlegen existiert | Abschluss-Schritt „Live-Aktualisierung einrichten“; im Reiter Quelle als vierte Zeile „Live-Aktualisierung · aus · Einrichten“ |
| S3-Ereignisbenachrichtigung | S3 | **in F nicht erreichbar** | Verwalter+ | technisch nötig (wie oben) | wie oben |
| Hinweis „letzte geplante Läufe fehlgeschlagen“ | alle Konnektoren | **in F nicht sichtbar** | Verwalter+ | entsteht erst im Betrieb | Zustandsfeld im Kopf (Kapitel 4) |
| Vollabgleich starten | Confluence, SharePoint, Drive, Dropbox | Kopf, ⋯-Menü | Bearbeiter+ | bewusst: einmalige Handlung im Betrieb | bleibt; einheitlich an der Laufliste anbieten |
| Neu verbinden / Quelle verbinden (OAuth) | Dropbox (Zustimmung) | Quelle › Verbindung | Verwalter+ | Assistent verbindet selbst; Detail nur bei Ablauf | bleibt; bei Ablauf als Zustand im Kopf |
| Zugang zuordnen / wechseln / lösen | Arten mit Zugängen | Quelle › Verbindung | Verwalter+ | im Assistenten als Wahl, danach nur hier | bleibt (Reiter Quelle) |
| Obergrenze „Freigabe an Alle erlaubt“ | Konnektoren | Freigaben (Abschnitt Berechtigungen) | nur Systemverwaltung | bewusst: Sache der Systemverwaltung, nicht der Anlegenden | bleibt |
| Diagnosesperre | alle (privat fest gesperrt) | Freigaben | alle sehen; umschalten nur Eigentümer | **Lücke**: Die Vorgabe „gesperrt“ ist sicher, wird aber nie bewusst entschieden | im Prüfschritt als Zeile „Diagnose · gesperrt · Ändern“ |
| Externer Zugang (externe KI-Werkzeuge) | alle außer privat | Freigaben | Verwalter+ (Feature-Flag) | **Lücke**: Freigabe-Frage wie die Berechtigungen | im Schritt Freigaben als eigene Zeile, Vorgabe aus |
| Warum sehe ich diese Bibliothek? | alle | Freigaben | alle | bewusst: Auskunft, keine Einstellung | bleibt |
| Eigene Metadatenfelder | alle | Metadaten | Verwalter+ ändern, alle lesen | bewusst: braucht meist erst Dokumente | Abschluss-Schritt verweist; kein Assistentenschritt |
| Modellgestützte Extraktion | alle | Metadaten | Verwalter+ | **Lücke**: Datenschutzrelevant (Sprachmodell liest Inhalte), Vorgabe aus | im Prüfschritt als Zeile „Extraktion · aus · Ändern“ |
| Eigentum übergeben | alle außer privat | Freigaben | Eigentümer | bewusst (Eigentümer wird im Assistenten gewählt) | bleibt |
| In Space verwenden (Zuordnung) | alle | Zuordnungen, ⋯-Menü | wer zuordnen darf | **Lücke**: häufigster nächster Schritt | Abschluss-Schritt „In Space verwenden“ |
| Ordner anlegen/umbenennen, Upload | Upload | Dokumente | Bearbeiter+ | technisch nötig (braucht die Bibliothek) | Abschluss-Schritt für Upload: „Dateien hochladen“ |
| Metadaten je Dokument, „Feld setzen“ | alle | Dokumente | Bearbeiter+ | technisch nötig | bleibt |
| Name und Beschreibung ändern | alle | Kopf (Stift) | Verwalter+ | auch im Assistenten | bleibt |

**Vorschlag:** Ein **Abschluss nach dem Anlegen** auf der Detailseite (Richtung C) zeigt einmalig 2–4 nächste Schritte je nach Art:
- „In Space verwenden“
- „Dateien hochladen“ (Upload)
- „Live-Aktualisierung einrichten“ (Confluence, S3)
- „Metadatenfelder anlegen“

Er verschwindet, sobald jeder Schritt erledigt oder verworfen ist. Diagnosesperre, externer Zugang und Extraktion kommen als Zeilen in den Prüfschritt des Assistenten, damit sie bewusst entschieden werden.

## 9 Die zehn größten Probleme (nach Wirkung)

1. **Der Zustand der Bibliothek widerspricht sich und versteckt den Ernstfall.** Kopf grün „Abgeschlossen“, Lauf „Teilweise fehlgeschlagen“, Detail „Indizierung abgeschlossen“. Eine abgelehnte Anmeldung steht nur im zugeklappten Lauf, die Verbindung bleibt „nicht geprüft“. (R1-26, R1-27, R1-28; P40, P41)
2. **Läufe sind unlesbar lang und ohne Raster.** 500 Ereignisse flach (bis 24 000 px), Hinweise zwischen Fehlern, Grund doppelt, Laufzeilen brechen beliebig um. (R1-29, R1-30, R1-31, R1-5; Rückmeldung 2)
3. **Ein Ereignis, drei Anzeigen.** Laufender Lauf: Knopf, Balken und Zeile mit verschiedenen Zahlen. Letzter Lauf: Kopf, Zeile und Detail. Teilfehler: Kante und Zeile. (R1-1 bis R1-4; P50)
4. **Der Zwischenbereich ist je Art und Szenario verschieden und zeigt das Falsche.** Teilfehler-Kante bei 7 von 10 Arten in 6 Wortlauten, keine Meldung bei `failed`, Roh-IDs bei Drive. Die Reiterleiste springt um bis zu 70 px. (R1-11, R1-16, R1-39; Rückmeldungen 1, 4)
5. **Freigaben und Zuordnungen skalieren nicht.** 45 Freigaben mit je 3 Zeilen, ohne Suche, „Freigeben“ am Ende; 34 Spaces ohne Suche; bei 320 px rund 9 000 px. (R1-34, R1-35; P20)
6. **Textlast.** Metadaten mehr als doppelt, Freigaben und Dokumente über dem Wortbudget. Dazu das Dauerbanner auf jedem Konnektor-Reiter Dokumente und die doppelte Überschrift. (R1-41, R1-42, R1-7; P13)
7. **Upload ist eine andere Seite.** Kein Gesamtstatus, keine Hauptaktion im Kopf, Fehler nur im schließbaren Banner, volles Kontingent unsichtbar, Listenfuß ≠ Kopf, 60 Ordner ungeblättert. (R1-10, R1-19, R1-37)
8. **F hat Funktionen verloren.** Confluence-Webhook, S3-Ereignisse und der Hinweis „letzte geplante Läufe fehlgeschlagen“ sind von der Detailseite nicht mehr erreichbar (Kapitel 2.2, 8).
9. **Zwei Layoutsysteme auf einer Seite.** Quelle 720 px mit Zeilenraster, die übrigen Reiter 1136 px mit `PageSection`. Dazu zwei Überschriftenstile, drei Statusformen und vier Formen der Hinzufügen-Aktion. (R1-17, R1-18, R1-19, R1-20; P46, P11)
10. **320 px:** Der Titel wird abgeschnitten (unbemerkt von der Messung), der Kopf ist 430–600 px hoch, und die Reiterleiste hat keinen Scrollhinweis und keinen sichtbaren aktiven Reiter. (R1-33; P25, P43)

## 10 Randfälle je Bereich (aus Datenmodell und API)

| Randfall | Größenordnung | Ist-Verhalten | bricht? |
|---|---|---|---|
| Dokumente | 18 000 in einer Bibliothek, 20 je Seite | Seiten, Suche nur nach Dateiname; Fuß „Seite 1 von 63“ | nein |
| Ordner (Upload) | 60 auf einer Ebene, 8 tief | Ordner ungeblättert vor den Dateien (6 000 px) | ja |
| Ereignisse je Lauf | 500 (Backend-Grenze) + „… und n weitere“ | flach, 24 000 px | ja |
| Läufe | 10 (Backend-Grenze) | Accordions, Zeilen umbrechend | teilweise |
| Freigaben | 45 (28 Gruppen, 16 Personen, Alle) | Liste ohne Suche, 3 Zeilen je Eintrag | ja |
| Zuordnungen | 34 Spaces, lange Namen | Liste ohne Suche, umbrechende Namen | teilweise |
| Metadatenfelder | 5 (Backend-Grenze) × 40 Werte | Werte als Fließtext-Chips, Textwand | ja |
| Bibliotheksname | 120 Zeichen mit 35-Zeichen-Kompositum | 1280: 2 Zeilen; 320: abgeschnitten | ja |
| Umfang | 120 Spaces, 25 Bibliotheken mit 100 Ordnern, 50 Pfade bis 8 Ebenen | „5 + und n weitere“ (F) | nein |
| Teilfehler | 2+ nicht lesbare Bereiche | Kante mit Namen bzw. Roh-ID (Drive) | ja (Drive) |
| Rolle | Leser · Bearbeiter · Verwalter · Eigentümer · Systemverwaltung ohne Grant | Leser: Chip + Alert + Satz; administrativ: Chip + Alert | Doppelung |
| privat | eigene Nextcloud über verbundenes Konto | Freigaben durch Alert ersetzt, Diagnosesperre fest | nein |
| Lauf läuft | Fortschritt 0–100 %, unbekannter Status | Balken + Knopf + Zeile; Status unbekannt als Alert | Doppelung |
| Anmeldung abgelehnt | jeder Konnektor | nur im aufgeklappten Lauf | ja |
| Kontingent erschöpft | Upload und privat | Upload-Banner, Kopf unverändert | ja |

## 11 Der kleine Fall

Upload mit 3 Dokumenten und einer Freigabe; Konnektor mit einem Space bzw. einem Ordner, 2 Läufen ohne Ereignisse, einer Gruppe und einem Space (`*__small__light__1280`). Heute braucht er:
- im Kopf 2 Zeilen Kennzahlen
- im Reiter Dokumente: Info-Alert, Metadaten-Pflege (Erklärsatz), Werkzeugleiste und 3 Zeilen
- im Reiter Quelle: 3 Zeilen und 2 einzeilige Läufe

Jede Richtung muss ihn mindestens so knapp lassen: eine Kennzahlzeile, die Liste direkt unter den Reitern, im Reiter Quelle ohne Scrollen bis zum letzten Lauf.

## 12 Lücken der Mocks

**Geschlossen in diesem Lab:**
- `frontend/src/features/library/mocks/detailScenarioFixtures.ts`:
  - Dokumente je Szenario (3 / 1 250 / 0 / 12 mit 5 Fehlern / 214 mit 17 Fehlern und 4 ausstehenden / 26 mit 9 ausstehenden)
  - Upload-Ordner (2 bzw. 60, eine 7-stufige Kette)
  - Upload-Fehler (`failed`: Kontingent)
  - Freigaben (1 / 45 / Eigentümer allein / 4)
  - Zuordnungen (1 / 34 / 0 / 3)
  - Metadatenfelder (1 / 5 × 40 Werte / 0 / 2)
  - laufender Lauf (`slow`, 62 %)
  - Teilfehler-Schlüssel wie im Backend (`drive:`, `folder:`)
  - Laborbibliotheken Upload, Webverzeichnis und RSS (nur unter einem Szenario)
- Läufe (`libraryScenarioFixtures.ts`):
  - 10 Läufe mit gemischten Ergebnissen
  - einer mit 500 + 1 240 Ereignissen
  - Zeit nach dem Zeitplan, Auslöser nach Zeitplan oder Hand
  - Fundorte je Quellart, Kennzahlen nach Bestand
  - abgelehnt ohne Kennzahlen
- Status aus dem neuesten Lauf (`indexingHandlers.ts`).
- `?mockRole=viewer|editor|manager|owner` (`shared/mocks/mockScenario.ts`), mit normalem Konto statt Systemverwaltung (`authHandlers.ts`).
- Eigentümername, Spaces-Zahl im Katalog, Diagnosesperre nur für Eigentümer umschaltbar.

**Offen:**
- Kein Szenario für Nachfolge offen, Löschung vorgemerkt, Kontingent erschöpft (privat), Status unbekannt und Fehler beim Laden der Detailseite.
- Externer Zugang ist im Mock aus (Feature-Flag).
- Webhook und S3-Ereignisse haben keine Fixtures (in F ohnehin nicht erreichbar).
- „Extraktion aus“ zählt trotzdem Modellaufrufe.

## 13 Nebenbefunde außerhalb der UI

Keiner, der ein eigenes Issue rechtfertigt. Der Drive-Befund (R1-39: `unlistedScopeKeys` mit `drive:`/`folder:` ungelöst angezeigt) ist ein Frontend-Fehler, der auf `main` ebenso besteht, weil die Warnung in `googleDriveSource.tsx` dort gleich lautet (Stand 01a4243). Er gehört in das Umsetzungs-Issue der Detailseite, nicht in ein Backend-Issue.
