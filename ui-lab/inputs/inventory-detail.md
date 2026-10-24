# UI-Inventar „Detailansicht einer Wissensbibliothek“ (Ist = Variante F, 24.10.2026)

> **Entwurf 1.** Dieser Stand kommt aus Code und den hellen 1280-px-Bildern. Die Befunde des Konsistenz-Reviews (6 Prüfer) werden noch eingearbeitet.

Grundlage ist der Branch `feature/3084_ui-lab-detail-base`, abgezweigt von `feature/3084_ui-lab-var-f`. Der Reiter „Quelle“ von F ist entschieden und bleibt. Ist-Matrix: `ui-lab/results/ist-detail/` mit 11 Quellarten × 6 Szenarien (`small scale failed empty partial slow`) × hell/dunkel × 1280/320 px, Bereiche `dokumente quelle laeufe metadaten freigaben zuordnungen upload`. Ganze Seiten (`__full`, halbe Breite) liegen nur für hell vor. Kennzahlen stehen in `metrics.jsonl`. Sonderfälle (`privat`, `*-leser`) liegen in `metrics-extra.jsonl`.

Pfadkürzel: `C/` = `frontend/src/features/library/components/`, `P/` = `…/library/pages/`, `A/` = `frontend/src/features/asset/components/`.

## 1 Aufbau der Seite

```
AssetDetailHeader (A/AssetDetailHeader.tsx)
 ├ Zeile 1: Chips [Wissen] [Quellart] [Rolle] (+ privat, wird gelöscht, administrativ) ······ [Jetzt indizieren] ☆ ⋯
 ├ Titel (inline bearbeitbar, Stift) + Beschreibung
 ├ note: LibraryHeaderNote – Nachfolge offen · Löschung vorgemerkt (Alert error) · Kontingent erschöpft (Alert warning)
 ├ Kennzahlen (HeaderFigure, Icon + Text, umbrechend):
 │    n Dokumente · [Speicher belegt] · [Letzter Lauf: … ] · in n Spaces · Eigentümer · Aktualisiert am …
 └ children: LibraryScopeHero (Warnkante: Bereiche nicht lesbar) · Fortschrittsbalken + „n von m verarbeitet“ (läuft)
Seitenfehler (Alert) · Rollenhinweise (LibraryRoleNotices, Alert info)
Reiterleiste (C/LibraryDetailTabBar.tsx): Dokumente · Quelle* · Metadaten** · Freigaben · Zuordnungen
Panels (alle gemountet, `hidden`)
```
\* nicht bei Upload, \*\* Feature-Flag `metadata`.

## 2 Je Reiter

### 2.1 Dokumente (`C/LibraryDocumentsSection.tsx`, `LibraryDocumentList.tsx`)
| Teil | Upload | Konnektor |
|---|---|---|
| Kopf des Reiters | Drop-Fläche (gestrichelt, Icon) + „Dateien hochladen“ (contained) · „Ordner hochladen“ · „Neuer Ordner“ | getöntes Info-Alert „Dieser Bestand stammt aus der Quelle; einzelne Dokumente lassen sich hier nicht löschen …“ |
| Fehler | Upload-Fehler als ein rotes Alert, eine Zeile je Datei, mit X (`upload__upload__failed__light__1280`) | – |
| Metadaten-Pflege | Overline „METADATEN-PFLEGE“, zweizeiliger Erklärsatz, je Kernfeld „n Dokumente ohne Wert (100 %)“ + Knopf „Anzeigen“ | gleich |
| Liste | Suche (volle Breite) + „20 je Seite“, „Alle auf dieser Seite auswählen“, „0 ausgewählt“, „Feld setzen“ (deaktiviert); Ordnerzeilen; Zeile: Name, „Größe · Abschnitte · Datum“, Status-**Chip** (indiziert/fehlgeschlagen/ausstehend), ⓘ (Metadaten), ↗ (Original) | dazu dritte Zeile „Space: … · Seite …“ bzw. Pfad (bis 3 Zeilen) |

### 2.2 Quelle (Variante F, entschieden)
Zeilen `Verbindung · Umfang · Zeitplan` (Titel | Inhalt | „Ändern“), danach die Überschrift „Läufe“ mit den Accordions. Webverzeichnis und RSS haben **keine Zeile Umfang**. Die Verbindungszeile zeigt immer „● nicht geprüft“ (`quelle__*__failed__light__1280`). Leser sehen nur die Zeile Umfang und „Verbindung, Zeitplan und Läufe sehen nur Verwaltende.“

**In F gegenüber `main` verloren** (Code `C/LibrarySourceSection.tsx`: der Zeilenpfad rendert `StoredView` nicht mehr):
- Confluence-Webhook (`ConfluenceWebhookSection`)
- S3-Ereignisbenachrichtigung (`S3EventSection`)
- Hinweis „Die letzten geplanten Läufe … sind fehlgeschlagen“ (`lastScheduledRunsFailed`)

Diese Teile brauchen einen Platz in jeder Richtung (Kapitel 8).

### 2.3 Läufe (im Reiter Quelle)
Je Lauf ein Accordion. Kopfzeile: Punkt + Status („Abgeschlossen“, „Teilweise fehlgeschlagen“, „Fehlgeschlagen“, „Läuft“), Startzeit, Auslöser, „n verarbeitet, n übersprungen, n fehlgeschlagen“, bei Rhythmus-Quellen die Betriebsart, dazu optional „unvollständig, wird fortgesetzt“ und „n Bereiche nicht lesbar“. Die Zeile bricht auf 2 Zeilen um (`laeufe__confluence__scale__light__1280`). Aufgeklappt folgen die Meldung (oft nur „Indizierung abgeschlossen“), die Kostenzeile („1843 Anfragen … · Anhänge … · Dauer …“) und die Ereignisliste: fettes Kategoriewort, Meldung, darunter der Pfad. Bis 500 Ereignisse ohne Gruppierung, Filter oder Seiten, danach „… und n weitere“.

### 2.4 Metadaten (`C/LibraryMetadataTab.tsx`)
`PageSection` „Metadatenfelder“ mit Beschreibung, danach Info-Alert, Kontextpräfix-Schalter und je Feld eine Überschrift, Chips Filter/Kontextpräfix, „Bearbeiten“, „Löschen“ und eine Werteliste als Fließtext-Chips (40 Werte = Textwand, `metadaten__*__scale`). Dann „Modellgestützte Extraktion“ (nur ab Verwalter).

### 2.5 Freigaben (`C/LibrarySharingTab.tsx`)
Reihenfolge: Eigentümer (+ „Eigentum übergeben“), Berechtigungen (Satz, Liste, „+ Freigeben“ am Listenende), Rollen-Legende, Externer Zugang, Diagnosesperre, „Warum sehe ich diese Bibliothek?“. Bei 45 Freigaben gibt es keine Seiten und keine Suche. Jede Zeile hat 3 Textzeilen (Name, „Gruppe · Rolle vergeben von … · zuletzt geändert …“, „Mitglieder anzeigen“) + Rollen-Select + „unbefristet“ + Papierkorb (`freigaben__*__scale__light__1280__full`).

### 2.6 Zuordnungen (`A/AssetSpacesSection.tsx`)
Satz „Die Spaces, in denen dieses Objekt als Datenquelle bereitsteht.“ + „In Space verwenden“, danach je Space eine Zeile mit Datum und „⋯“. Keine Suche, auch nicht bei 34 Spaces. „Objekt“ statt „Bibliothek“ (P19).

## 3 Unterschiede je Quellart

| Element | Upload | Dateisystem | Web | RSS | Confluence | SharePoint | Nextcloud | SMB | S3 | Drive | Dropbox |
|---|---|---|---|---|---|---|---|---|---|---|---|
| „Jetzt indizieren“ | – | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| „Vollabgleich“ im ⋯-Menü | – | – | – | – | ✓ | ✓ | – | – | – | ✓ | ✓ |
| Kennzahl Speicher | ✓ | – | – | – | – | – | – | – | – | – | – |
| Kennzahl „Letzter Lauf …“ | – | Dokumente | Dokumente | **Feed-Einträge**, längerer Satz | Dokumente | … | … | … | … | … | … |
| Kopfhöhe 1280 px (small) | 1 Zeile Kennzahlen | 2 Zeilen | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 | 2 |
| Teilfehler-Kante über den Reitern (`partial`) | – | – | – | – | Spaces (Name + Key) | **Drive-ID** | Pfade | Pfade | Bucket/Präfix | **Drive-/Ordner-ID** | Pfade |
| Info-Alert im Reiter Dokumente | – | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Zeile Umfang (Quelle) | – | ✓ | – | – | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Betriebsart je Lauf | – | – | – | – | ✓ | ✓ | – | – | – | ✓ | ✓ |
| Chip Quellart | „Upload“ | „Dateisystem“ | „Webverzeichnis“ | „RSS-Feed“ | … | … | … | „Windows-Dateifreigabe (SMB)“ | „S3-Objektspeicher“ | „Google Drive“ | … |

Belege: `dokumente__*__small__light__1280`, `dokumente__*__partial__light__1280`, `quelle__*__small__light__1280`.

## 4 Zwischenbereich (zwischen Titel und Reiterleiste)

| Element | Wann | Arten | Art | Beleg |
|---|---|---|---|---|
| Kennzahl „Letzter Lauf: Abgeschlossen · Dokumente: 1337 verarbeitet (3 übersprungen) · Datum“ | immer, sobald gelaufen | alle außer Upload | **Doppelung** (Laufliste im Reiter Quelle) + Status | `dokumente__confluence__small__light__1280` |
| Teilfehler-Kante „Der letzte Abgleich konnte … nicht auflisten …“ | `partial` | Arten mit Auswahl | **Status** (verlangt Prüfen), Text mit Roh-IDs | `dokumente__sharepoint__partial__light__1280` |
| Fortschrittsbalken + „831 von 1340 Dokumenten verarbeitet“ | `slow` | alle außer Upload | **Status** | `dokumente__sharepoint__slow__light__1280` |
| Rollenhinweis „Sie haben … nur Leserechte“ (Info-Alert) | Leser | alle | **Information**, doppelt zum Chip „Leser“ | `quelle__nextcloud-leser__*` |
| Nachfolge, Löschung, Kontingent | selten | Upload, privat | **Status** | Code `C/LibraryHeaderNote.tsx` |

Hypothese des Maintainers (Kapitel 8): Was über den Reitern quellartspezifisch ist, gehört in den Reiter „Quelle“.

## 5 Verstöße (vorläufig)

| Nr. | Befund | Beleg |
|---|---|---|
| P4 | getöntes Info-Alert im Reiter Dokumente (alle Konnektoren), Rollenhinweis getönt | `dokumente__*__small__light__1280` |
| P13 | Erklärsätze: Metadaten-Pflege (2 Zeilen), Berechtigungen (3 Zeilen), Bestand-Hinweis | dito, `freigaben__*__small__light__1280` |
| P16 | Status als Satz in den Kennzahlen („Letzter Lauf: Abgeschlossen · Dokumente: …“) | `dokumente__*__small__light__1280` |
| P19 | „Windows-Dateifreigabe (SMB)“ Doppel-Label; „Objekt“ in Zuordnungen | `quelle__smb__small`, `zuordnungen__*` |
| P40 | Indizierungsstatus in 3 Formen (Kennzahl, Laufzeile, Dokument-Chip) | |
| P50 | Laufergebnis in Kopf und Läufen; Rolle als Chip und als Alert | |
| C | „Verbindung · nicht geprüft“ trotz abgelehnter Anmeldung im letzten Lauf | `quelle__*__failed__light__1280` |
| C | Aufgeklappter Lauf: „Nichts übersprungen oder fehlgeschlagen“ neben „3 übersprungen“ | `laeufe__confluence__small__light__1280` |
| C | Teilfehler nennt Roh-IDs (`b!rathausDokumente`, `0AMockDrive`) | `dokumente__sharepoint__partial`, `__gdrive__partial` |

## 6 Rückmeldungen des Maintainers – Prüfung

1. **Je Quellart anders:** bestätigt, siehe Matrix in Kapitel 3.
2. **Läufe länglich:** bestätigt. Laufzeilen brechen um, und ein aufgeklappter Lauf zeigt bis 500 Ereignisse ohne Gruppierung.
3. **Blauer Verlauf:** in F entfernt (Commit 9e13e10, `radial-gradient` in `A/AssetDetailHeader.tsx`). Auf `main` ist er noch da und trifft dort auch die Prompt-Bibliothek. Die Umsetzung muss ihn mitnehmen.
4. **Zwischenbereich:** Die Umfangszeile ist in F weg. Geblieben sind die Teilfehler-Kante (nur Arten mit Auswahl), der Fortschritt und die Rollenhinweise. Die Kennzahl „Letzter Lauf“ ist je Art unterschiedlich lang.

## 7 Mock-Lücken

Neu geschlossen in `frontend/src/features/library/mocks/detailScenarioFixtures.ts`:
- Dokumente je Szenario, Ordner, Upload-Fehler
- Freigaben, Zuordnungen, Metadatenfelder
- Läufe bis 500 Ereignisse
- laufender Lauf (`slow`), Status aus dem neuesten Lauf
- `?mockRole=`
- Laborbibliotheken Upload, Web und RSS

Offen:
- Nachfolge, Löschung und Kontingent-Erschöpfung haben kein Szenario.
- Kein Fehler beim Laden der Detailseite.
- Externer Zugang ist im Mock aus.

## 8 Offene Kapitel (folgen)

Zehn größte Probleme · Doppelungen zum Reiter „Quelle“ von F · vollständige Prüflisten-Tabelle · 320 px · dunkel · Leser und privat · Randfalltabelle · kleiner Fall.
