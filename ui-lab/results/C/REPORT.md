# Variante C · „Steckbrief mit Abschluss“ – Bericht (Runde 2)

Branch `feature/3084_ui-lab-detail-var-c` · Matrix: 11 Arten × 6 Szenarien + privat/3 Leser × 3 Szenarien, hell/dunkel, 1280/320, Bereich `abschluss` = `?justCreated=1`. Belege ohne `.jpg`.

## Konzept

- **Kopf schmal und gleich:** Chips · Titel mit Beschreibung in einer Zeile · Hauptaktion (Konnektor „Jetzt indizieren“, Upload „Hochladen“) · ☆ ⋯. Keine Kennzahlen im Kopf.
- **Steckbrief rechts (280 px, ab 1100 px), auf allen Reitern:** feste Reihenfolge Zustand → Quelle (Upload: Hochladen) → Bestand → Nächste Schritte.
  - Zustand: ein Wort aus D2, eine Zusatzangabe, das Wort führt mit „›“ zur Ursache.
  - Quelle: Kurzform Adresse · Umfang · Zeitplan · nächster Lauf; im Reiter Quelle ausgeblendet (keine Doppelung).
  - Unter 1100 px: **immer zugeklappt**, eine Zeile „Steckbrief ● Zustand [· n Schritte offen]“.
- **Abschluss nach dem Anlegen:** nur mit `justCreated` (Tab-Sitzung), ✓/○ ohne Balken und ohne Icons, „Ausblenden“ verwirft dauerhaft; der Schritt des offenen Reiters fehlt dort.
- **Reiter:** ein Zeilenraster Titel | Inhalt | Textaktion (Quelle, Metadaten, Freigaben), eine Überschriftenform (15/600), Zeilentitel 14/600, Textaktionen oben rechts, Suche ab 8.
- **Läufe** im Reiter Quelle als Tabelle (Zustand · Beginn · Dauer · Auslöser · Ergebnis), aufgeklappt nach Grund gruppiert.

## Status je D-Punkt

| Nr | Status | Beleg |
|---|---|---|
| D1 | erfüllt bei 1280 (Kopf überall gleich, `tabsTop` je Szenario in `metrics.jsonl`); bei 320 gleich außer privat (Chip „Privat“ bricht um) und langen Titeln in `scale` (Titel ungekürzt, P43 vor D1) | `dokumente__*__small__light__1280`, `dokumente__smb__small__light__320` |
| D2 | erfüllt in der Steckbrief-Spalte statt im Kopf (Richtung C); Wort aus der Liste + Datum/Prozent, Link zur Ursache mit „›“ | `dokumente__confluence__failed__light__1280`, `dokumente__s3__slow__light__1280` |
| D3 | erfüllt: Zustand, Laufzeile und Gruppen aus einer Zählung (Fixtures angeglichen) | `laeufe__smb__partial__light__1280__full` |
| D4 | erfüllt: Zustand auf jedem Reiter (Spalte bzw. 320-Zeile) | `freigaben__nextcloud__failed__light__1280`, `…__320` |
| D5 | erfüllt: Kennzahlen im Bestand als Links, Listenfuß nur bei Filter/Seiten | `dokumente__dropbox__small__light__1280` |
| D6 | erfüllt: nur Chip „Leser“, kein Rollenhinweis | `quelle__confluence-leser__small__light__1280` |
| D7 | erfüllt: „Läuft · 62 %“ einmal im Steckbrief, gleiche Zahl in der Laufzeile, keine Leiste | `quelle__nextcloud__slow__light__1280` |
| D8 | erfüllt: nicht lesbare Bereiche mit Namen in der Liste der Zeile Umfang markiert | `quelle__confluence__partial__light__1280`, `quelle__gdrive__partial__light__1280` |
| D9 | erfüllt: Web/RSS mit Zeile Umfang („Ganzes Verzeichnis“, „Alle Einträge des Feeds“), Confluence/S3 mit Zeile Live-Aktualisierung | `quelle__web__small__light__1280`, `quelle__s3__small__light__1280` |
| D10 | erfüllt: „Anmeldung ok · zuletzt …“ / „Anmeldung abgelehnt“; ohne Anmeldung „erreichbar“/„nicht erreichbar“ | `quelle__smb__failed__light__1280`, `quelle__filesystem__failed__light__1280` |
| D11 | erfüllt: Vollabgleich im Zeitplan und am Lauf („Zeitplan · Vollabgleich“), Dropbox jetzt auch im Zeitplan | `quelle__dropbox__small__light__1280`, `laeufe__confluence__scale__light__1280__full` |
| D12 | erfüllt: feste Spalten, eine Zeile bei 1280, Zeile als Listeneintrag ohne Überschrift; bei 320 Ergebnis in Zeile 2 | `laeufe__sharepoint__scale__light__1280` |
| D13 | erfüllt: Gruppen nach Grund mit Anzahl, 5 Fundorte, „alle n anzeigen“; „Fehler“ nach Meldung gruppiert; Hinweise getrennt und zugeklappt | `laeufe__smb__scale__light__1280__full` |
| D14 | erfüllt: kein Status-/Grundsatz im Detail, Technische Angaben zugeklappt, Läufe ohne Detail ohne Chevron | `laeufe__confluence__failed__light__1280` |
| D15 | erfüllt: 10 Läufe, Upload ohne Läufe | `laeufe__*__scale__light__1280__full` |
| D16 | erfüllt: eine Inhaltsbreite links (alle Reiter), Textaktionen an derselben Kante | Querbogen je Reiter, `quelle__s3__small__light__1280` gegen `freigaben__s3__small__light__1280` |
| D17 | erfüllt: Abschnitte 15/600 Satzschrift, keine Mono-Versalien | `metadaten__smb__small__light__1280` |
| D18 | erfüllt: „Freigeben“, „In Space verwenden“, „Feld anlegen“ als Textaktion oben rechts | `zuordnungen__rss__small__light__1280` |
| D19 | teilweise: Suche ab 8 in Freigaben, Zuordnungen, Wertelisten; ab 50 nicht gruppiert (45 Freigaben im Mock erreichen die Schwelle nicht) | `freigaben__sharepoint__scale__light__1280` |
| D20 | erfüllt: kein Dauerhinweis | `dokumente__sharepoint__small__light__1280` |
| D21 | erfüllt: leer nur ein Satz (Konnektor) bzw. Ablagefläche (Upload); Aktion im Kopf | `dokumente__rss__empty__light__1280`, `dokumente__upload__empty__light__1280` |
| D22 | erfüllt: Füllgrad nur im Reiter Metadaten, im Reiter Dokumente Filter „ohne …“ | `dokumente__smb__small__light__1280`, `metadaten__smb__small__light__1280` |
| D23 | teilweise: gleicher Kopf und Steckbrief, Zustand „Kontingent voll“/„Teilweise fehlgeschlagen“/„Läuft“; ohne Fehlerzahl (API liefert keine) | `dokumente__upload__failed__light__1280` |
| D24 | erfüllt, siehe Liste unten | – |

**D24 (Inventur Kap. 8):** Webhook und S3-Ereignisse → Quelle › Live-Aktualisierung · „letzte geplante Läufe fehlgeschlagen“ → Zustand und Zeile Zeitplan · Vollabgleich → an der Laufliste · Neu verbinden/Zugang → Quelle › Verbindung (unverändert) · Obergrenze „an Alle“ → Freigaben · Diagnosesperre → Zeile Suchdiagnose · Externer Zugang → Zeile Extern · Herleitung → Zeile · Felder, Extraktion → Metadaten · Eigentum → Zeile · In Space verwenden → ⋯, Zuordnungen, Nächste Schritte · Upload/Ordner → Kopf „Hochladen“ (Dateien, Ordner, Neuer Ordner) · Feld setzen → Sammelaktion · Name/Beschreibung → Stift · Status unbekannt → Steckbrief „Erneut abrufen“.

**E (Entscheidungen) und F (Fragen):** E1–E6 eingehalten (Läufe im Reiter Quelle, kein Balken, Upload ohne Reiter Quelle). F2 als B umgesetzt, aber nur „Vollabgleich starten“ an der Laufliste (ein zweites „Jetzt indizieren“ wäre P50). F3 als B, soweit das Frontend kann (Fehler am Dokument, Abgelehnte im Steckbrief).

## Review 1

| R1 | Ergebnis |
|---|---|
| 1 | erledigt: Block Quelle im Reiter Quelle ausgeblendet |
| 2 | erledigt: Laufdetail ohne Grund, Zeitplan-Hinweis nur ohne Anmeldefehler und mit Plan |
| 3 | erledigt: aufgeklappt bei 320 ohne Block Zustand |
| 4 | erledigt: kein Knopf im Leerzustand, Bestand ohne Nullzeilen, Schritt des offenen Reiters ausgeblendet |
| 5 | erledigt |
| 6 | erledigt: Listenfuß nur bei Suche/Filter/Seiten |
| 7 | erledigt: kein Popup mehr |
| 8 | erledigt: Markierung in der Liste, Restnamen darunter |
| 9 | erledigt |
| 10 | erledigt: Befristung nur, wenn gesetzt |
| 11 | erledigt bei 320 für alle außer privat (Art-Chip ausgeblendet, Hauptaktion als Icon in der Chipzeile, kurze Quellart-Chips) |
| 12 | erledigt: „Hochladen“ im Kopf |
| 13 | erledigt: feste Blockfolge |
| 14 | erledigt: Linkfarbe, „›“ am Zustand |
| 15 | erledigt: Signalfarbe nur im Punkt |
| 16 | erledigt: Adresse · Umfang · Zeitplan · nächster Lauf für alle |
| 17 | erledigt: Titel „Spaces“, Suchfelder voll breit |
| 18 | erledigt: Textaktionen „Entziehen“, „Lösen“, „Löschen“ |
| 19 | erledigt: Felder im Raster, Zeilentitel 14 px, Füllgrad mit festen Spalten |
| 20 | erledigt |
| 21 | erledigt: „nicht lesbar“ nur für Arten mit gewähltem Umfang, Zahl = Schlüssel |
| 22 | teilweise: „Confluence-Spaces“ im Steckbrief, „Feld anlegen“; Zählnomen der Umfangszusammenfassung („1 Bucket oder Präfix“) bewusst nicht: kommen aus dem geteilten Assistenten-Raster |
| 23 | erledigt: Fortschritt im Knopf, nicht ausgegraut |
| 24 | bewusst nicht: „Noch kein Inhalt“ (G 5.5) für leeren Upload; abgelehnte Dateien bleiben sitzungsgebunden, weil das Backend sie nicht speichert |
| 25 | bewusst nicht: Ordnerkante folgt der Checkbox-Spalte, Leser ohne Spalte (gleich wie Dokumente) |
| 26 | teilweise: Dauer „3 h 12 min“, RSS-Umfang ohne „Ändern“ korrekt (keine Wahl), Bestand „· zuständig“; Beschreibung neben langem Titel bewusst gekürzt (D1 feste Höhe); „Live-Aktualisierung“ darf zweizeilig sein |
| 27 | erledigt: unter 1100 px immer zugeklappt |
| 28 | erledigt: Abschluss nur mit `justCreated` (sessionStorage), Mock ohne Space/Feld bei `justCreated` |
| 29 | erledigt: Fixture-Ereignisse = fehlgeschlagene Dokumente, Ergebnis mit Einheit, eigene Gruppe „Bereiche nicht lesbar“ |
| 30 | erledigt: Zielhöhe 24 px |
| 31 | erledigt: sichtbarer Text am Anfang des Namens |
| 32 | erledigt: Fixtures in Ortszeit |
| 33 | teilweise: Zustand aus den geladenen Dokumenten (Teilweise fehlgeschlagen/Läuft), ohne Zahl |
| 34 | teilweise: Menü „Hochladen“ nennt „Speicherkontingent voll“, Einträge gesperrt; ohne Fehlerzahl |
| 35 | erledigt: Leser ohne Link am Zustand |
| 36 | bewusst nicht: Lesende dürfen nach ADR-0039 in eigene Spaces zuordnen |
| 37 | erledigt: Zeile „Extern“, ohne Daten nicht angezeigt |
| 38 | erledigt: OWNER-Freigabe nicht in der Liste |
| 39 | erledigt: Zeile Kontextpräfix nennt eigene Felder |
| 40 | teilweise: bei 0 Dokumenten weggelassen; eigene Felder fehlen, weil die Qualitäts-API nur Kernfelder liefert |
| 41 | erledigt: aktiver Reiter in Sicht, Scrollpfeile |
| 42 | erledigt: Kurzzeile ohne Datum, mit Ellipse |
| 43 | erledigt: Ergebnis zweite Zeile bei 320, Abstand zum Chevron |
| 44 | erledigt: Steckbrief-Zeilen einzeilig mit Ellipse und Tooltip |
| 45 | erledigt: „seit …“, Name einzeilig |
| 46 | erledigt: Statuspunkt bei 320, Freigabe-Aktionen als Textlink |
| 47 | erledigt: Zeilenaktionen bei 320 im ⋯, Titel ungekürzt |
| 48 | erledigt (kein Popup) |
| 49 | erledigt: „erreichbar/nicht erreichbar“, Zustand „Fehlgeschlagen“ ohne Anmeldung |
| 50 | erledigt |
| 51 | erledigt |
| 52 | erledigt: unter Technische Angaben |
| 53 | erledigt |
| 54 | erledigt: „Ihre Rolle: …“ |
| 55 | erledigt: Zeitplan „aus“, Privat-Satz entfernt, Upload-Leerzustand ein Satz |
| 56 | erledigt: kein Regex, kein Code im Chip |
| 57 | erledigt: Leiste entfernt |
| 58 | erledigt: Chip „Wissen“ ohne Icon |
| 59–62 | erledigt im Werkzeug: Warten auf Details, `prepare` nach jedem Resize (Lauf in Sicht, Fokus/Zeiger in Ruhe) |
| 63 | erledigt mit R1-28 |
| 64–66 | erledigt: Ergebnis = Bestand, Anmeldefehler 4 s, Pfade je Art, Feed-URLs |
| 67 | offen: Speicher- und Space-Zahlen der Fixtures nicht plausibilisiert |
| 68–69 | Werkzeug des Orchestrators |
| Fehlt | REPORT.md (hier), `tabsTop` in `measure.mjs` ergänzt |

## Schwächen

- **Zwei Spalten:** Unter 1100 px bleibt eine zusätzliche Zeile über den Reitern (Steckbrief-Zeile, 40 px); aufgeklappt wird sie wieder zum Zwischenbereich.
- **Zustand nicht im Kopf:** D2 verlangt den Kopf; C setzt ihn in die Spalte. Bei 320 steht nur das Wort, Ursache erst nach Aufklappen bzw. im Reiter.
- **Upload ohne Zählungen:** Ohne Fehler-/Wartezahl im Backend leitet C den Zustand aus der geladenen Seite ab („Teilweise fehlgeschlagen“ ohne Zahl).
- **Abgelehnte Uploads** nur in der Tab-Sitzung (Backend speichert sie nicht).
- **Geteilte Komponenten geändert:** `AssetGrantsSection`, `AssetOwnerSection`, `AssetSpacesList`, `AssetDetailHeader` (Prompt-Bibliothek betroffen; Zeilenform nur unter `PlainPageSections`).
- **Freigaben ab 50** nicht gruppiert.

## Gebrochene Unit-Tests

Basis (`feature/3084_ui-lab-detail-base`) hat bereits 108 rote Tests in 13 Dateien (Assistent, Quellformulare, Detailseite). C bricht zusätzlich (Stand Runde 1, gleiche Dateien in Runde 2):

| Datei | rot (C / Basis) | Grund |
|---|---|---|
| `asset/components/AssetGrantsSection.test.tsx` | 4 / 0 | Zeilen ohne Vergabezeile, Rollen zugeklappt, Textaktion statt Papierkorb |
| `library/components/LibraryDocumentList.test.tsx` | 9 / 0 | Herkunft hinter ⓘ, keine Ordner-Checkbox, Auswahlleiste erst mit Auswahl |
| `library/components/LibrarySourceSection.test.tsx` | 21 / 18 | Laufliste neu, kein Leser-Satz |
| `library/components/metadata/LibraryMetadataFieldsSection.test.tsx` | 2 / 0 | Wertelisten-Hinweis, Kontextpräfix als Zeile |
| `library/components/metadata/MetadataExtractionSettingsSection.test.tsx` | 4 / 0 | Extraktion als Zeile, Füllgrad als Tabelle |
| `library/pages/LibraryDetailPage.test.tsx` | 68 / 19 | Kopf ohne Kennzahlen, Steckbrief, Kontingent und Lauf nicht mehr im Kopf |
| `library/pages/LibraryDetailPage.privateLibrary.test.tsx` | 2 / 0 | Privat-Hinweis entfernt, Speicher im Steckbrief |

## Kennzahlen

Siehe `metrics.jsonl` (Runde 2). Runde 1: `overflowX` 0, Kontrast 0, Budgets 0; axe `target-size` (Steckbrief) und `label-content-name-mismatch` sind in Runde 2 behoben (Zahlen unten nach der Neuaufnahme).
