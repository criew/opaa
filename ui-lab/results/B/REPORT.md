# Variante B „Statusband“ – Bericht Runde 1 (25.10.2026)

Stand: Matrix 1, vor dem Konsistenz-Review. Branch `feature/3084_ui-lab-detail-var-b`.

## Konzept

- **Kopf**, für alle Arten gleich, in dieser Reihenfolge:
  - Chips: Art · Quellart (Kurzform wie auf der Kachel) · Rolle
  - Titel, Beschreibung (eine Zeile, Platz auch ohne Text reserviert)
  - **eine** Kennzahlzeile ohne Icons, z. B. „1.250 Dokumente · in 34 Spaces › · Erika Muster ›“
  - das **Statusband**
- **Statusband:** drei feste Felder **Stand · Umfang · Nächster Lauf**, bei Upload **Stand · Speicher · Hochgeladen**.
  - Gestaltung: Haarlinie oben und senkrechte Trenner, **kein Kasten, keine Tönung, keine Icons**. Die Signalfarbe steht nur im Punkt vor dem Zustandswort.
  - Je Feld: Label und Link („Verlauf ›“, „Quelle ›“, „Zeitplan ›“) in der ersten Zeile, der Wert in der zweiten.
  - 320 px: eine Zeile je Feld, der Link ist dort auf das ganze Feld gestreckt.
  - Ein laufender Lauf zeigt „Läuft · 62 %“ und darunter eine 2-px-Leiste **im Feld**.
- **Zustand aus einer Quelle:** `libraryState.ts` liefert das Wort aus der festen D2-Liste für Kopf, Laufzeile und Laufdetail.
- **Reiterfolge:** Dokumente · **Verlauf** · Quelle · Metadaten · Freigaben · Zuordnungen.
  - Upload: ohne Verlauf und Quelle.
  - Leser: ohne Verlauf, weil die Laufliste erst ab Verwalter lesbar ist.
- **Verlauf:** Tabelle mit festen Spalten Zustand · Beginn · Dauer · Auslöser · Ergebnis.
  - Darüber links der Filter „Nur mit Fehlern (n)“, rechts „Jetzt indizieren · Vollabgleich starten“.
  - Die Zeile ist ein Knopf. Aufgeklappt zeigt sie Fehlergruppen je Grund mit 5 Fundorten und „Alle n anzeigen“ (Dialog mit Suche).
  - Hinweise und „Technische Angaben“ sind zugeklappt.
- **Quelle:** Zeilenraster von F ohne Läufe.
  - Verbindung trägt den letzten bekannten Zustand.
  - Umfang markiert nicht lesbare Einträge direkt am Eintrag.
  - Web und RSS haben eine Zeile Umfang.
  - Live-Aktualisierung ist die 4. Zeile bei Confluence und S3.
- **Übrige Reiter:** gemeinsamer Baustein `shared/ui/TabSection` mit einer Breite (720 px), einem Überschriftenstil und der Aktion oben rechts an der Kante.

## Kennzahlen (aus `metrics.jsonl`, `metrics-extra.jsonl`)

| Messung | Wert | Budget |
|---|---|---|
| `tabsTop` 1280, alle 11 Arten, jedes Szenario | 248 (± 0) | ≤ 260 ✓ |
| `tabsTop` 320, alle 11 Arten außer `scale` | 359 (± 0) | ≤ 360 ✓ |
| `tabsTop` 320 `scale` (Titel 4 Zeilen) | 439 | ✗ |
| `tabsTop` Leser 1280 / 320 | 238 / 313–409 | ✗ (± 0 verfehlt) |
| `uiWords` small (Dokumente · Quelle · Verlauf · Metadaten · Freigaben · Zuordnungen) | 69–132 · 22–59 · 29–39 · 20–88 · 43–61 · 22–35 | ≤ 150 ✓ |
| Laufseite `laeufe__*__scale__light__1280` (ganze Seite, aufgeklappt) | 1 641 px, davon aufgeklappter Lauf ca. 765 px | ≤ 900 ✓ |
| Kontrast (axe, dunkel und hell) | 0 | 0 ✓ |
| `overflowX` | 0 | 0 ✓ |
| axe übrig | `label-content-name-mismatch` 131 (Freigaben 110), `definition-list` 120 (Band), `scrollable-region-focusable` 69 (Metadaten-Tabelle), `heading-order` 42 (Verlauf, h4 in Gruppen), `target-size` 4 | ✗ |

## Status je D-Punkt

| Nr | Status | Beleg |
|---|---|---|
| D1 Ein Kopf | erfüllt für die 11 Arten (248/359 px); Leser 10 px niedriger, `scale` 320 höher | `dokumente__*__small__light__1280`, `tabsTop` |
| D2 Ein Zustandsfeld | erfüllt: Feld „Stand“, Wort aus der festen Liste, Zusatz Datum oder %, Link zum Ort der Ursache | `dokumente__nextcloud__failed__light__1280` |
| D3 Zustand wahr | erfüllt: dieselbe Funktion `runState` für Kopf, Zeile und Detail; Teilfehler nie „Aktuell“ | `laeufe__smb__partial__light__1280`, `dokumente__smb__partial__light__1280` |
| D4 Ernstfall sichtbar | erfüllt: Band auf jedem Reiter; Zeile Verbindung „● Anmeldung abgelehnt · Ändern“ | `quelle__confluence__failed__light__1280` |
| D5 Kennzahlen ohne Doppelung | erfüllt: kein „Letzter Lauf“, kein „Aktualisiert am“; Spaces und Zuständige als Links in den Reiter | Kopf aller Bilder |
| D6 Rolle einmal | erfüllt: Rollen-Alerts und „nur Verwaltende“ entfernt, nur Chip | `quelle__confluence-leser__small__light__1280` |
| D7 Laufender Vorgang einmal | erfüllt: „Läuft · 62 %“ im Feld und dieselbe Zahl in der Laufzeile; Knopf ohne Zusatztext, keine Leiste über die Breite | `dokumente__s3__slow__light__1280`, `laeufe__s3__slow__light__1280` |
| D8 Nichts zwischen Kopf und Reitern | erfüllt mit Abweichung: Umfang-Kurzform im Band (gewollte Doppelung der Richtung B, Kurzform mit Link); Markierung „· nicht lesbar“ am Eintrag mit Namen, auch bei Drive | `quelle__gdrive__partial__light__1280` |
| D9 Ein Zeilenraster | erfüllt: Web „Ganzes Verzeichnis“, RSS „Alle Einträge des Feeds“; Live-Aktualisierung bei Confluence und S3 | `quelle__*__small__light__1280` |
| D10 Verbindung mit Zustand | erfüllt: „Anmeldung ok · zuletzt 23.10.2026“ bzw. „erreichbar · …“ ohne Anmeldung | `quelle__nextcloud__small__light__1280` |
| D11 Betriebsart einheitlich | erfüllt: Vollabgleich im Zeitplan und in der Spalte Auslöser („Zeitplan · Vollabgleich“), nur bei Arten mit Rhythmus | `laeufe__confluence__scale__light__1280__full` |
| D12 Laufzeile als Tabellenzeile | erfüllt: feste Spalten, eine Zeile bei 1280, Knopf in einer Liste | `laeufe__*__scale__light__1280` |
| D13 Ereignisse gruppiert | erfüllt: Gruppe je Grund mit Anzahl, 5 Fundorte, Dialog „Alle n“; Hinweise getrennt | `laeufe__smb__scale__light__1280__full` |
| D14 Detail ohne Wiederholung | erfüllt: keine Meldung „Indizierung abgeschlossen“, kein „Nichts übersprungen“; Kennzahlen unter „Technische Angaben“ | dito |
| D15 Läufe als Liste von 10 | erfüllt für Konnektoren; Upload ohne Verlauf (siehe Schwächen) | `laeufe__*__scale` |
| D16 Eine Inhaltsbreite | erfüllt: 720 px in allen Reitern; Verlaufstabelle und Band 880 px | Querbogen über Reiter |
| D17 Ein Überschriftenstil | erfüllt: 15/600 Satzschrift, keine Mono-Versalien | `metadaten__*`, `freigaben__*` |
| D18 Hinzufügen oben rechts | erfüllt: „Freigeben“, „Feld anlegen“, „In Space verwenden“ als Textknopf an der Kante | `freigaben__*__small__light__1280` |
| D19 Suche ab 8 | erfüllt: Freigaben (45), Zuordnungen (34), Werte; Freigaben ab 50 gruppiert | `freigaben__*__scale__light__1280` |
| D20 Kein Dauerhinweis | erfüllt: Bestand-Alert weg, privat ein Satz ohne Tönung | `dokumente__*__small` |
| D21 Leerzustände | erfüllt: „Noch keine Dokumente.“ ohne Suche, Filter und Blättern; Upload mit Ablagefläche | `dokumente__*__empty__light__1280` |
| D22 Füllgrad an einem Ort | erfüllt: Pflege-Anker entfernt, Filter „Ohne Titel …“ in der Werkzeugleiste | `dokumente__*__small` |
| D23 Upload gleich gebaut | erfüllt: gleicher Kopf und gleiches Band (Stand · Speicher · Hochgeladen), Fehler je Grund einmal, „Kontingent voll“ im Stand | `upload__upload__failed__light__1280` |
| D24 Nichts verlieren | erfüllt mit Abstrichen, siehe Liste unten | – |

**D24 im Einzelnen**
- Erreichbar:
  - Confluence-Webhook und S3-Ereignisse über die Zeile Live-Aktualisierung
  - „Letzte geplante Läufe fehlgeschlagen“ als Stand „Fehlgeschlagen“
  - Vollabgleich im Verlauf (aus dem ⋯-Menü dorthin verschoben)
  - Zugang zuordnen und Neu verbinden in Quelle › Verbindung
  - Obergrenze, Diagnosesperre, Externer Zugang, Herleitung, Eigentum übergeben, Metadatenfelder, Extraktion
  - In Space verwenden, Ordner, Feld setzen, Name und Beschreibung
  - Status unbekannt: Stand „Läuft · Stand unbekannt“ mit „Erneut versuchen“
- Abstriche:
  - Der Text zur vorgemerkten Löschung steht nur noch im Tooltip des Feldes Stand.
  - Bei „Nachfolge offen“ fehlt der Adressat im Kopf; der Link führt zu Freigaben.

## Schwächen und bewusste Abweichungen

1. **320 px `scale`:** Der lange Titel umbricht auf 4 Zeilen, `tabsTop` liegt bei 439 statt ≤ 360. Den Titel zu kürzen widerspräche dem Pflichtfall „Titel umbricht“.
2. **Leser 10 px niedriger** (ohne Knopf „Jetzt indizieren“ in der Chipzeile). Wird in Runde 2 mit einer festen Zeilenhöhe behoben.
3. **Unter 600 px** sind Chip- und Kennzahlzeile einzeilig und laufen in einer Blende aus. Ein vierter Chip (privat) wird angeschnitten.
4. **Band ohne Rahmen:** Abweichung von der Skizze (1-px-Rahmen um drei Felder). Gründe: Projektregel „kein ruhender Inhalt in einer Karte“ (`PageSection.test.tsx`) und P4.
5. **Upload ohne Reiter Verlauf:** gegen die Richtungsbeschreibung, aber nach D15 und E6. Es gibt keine API für Upload-Vorgänge.
6. **Upload-Zustand aus der geladenen Seite:** „n fehlgeschlagen“ zählt nur die gerade geladenen Dokumente, weil das Backend keine Zählung je Status liefert.
7. **axe:**
   - `definition-list` (Fortschrittsleiste als `div` im `dl`)
   - `heading-order` (h4 in den Laufgruppen)
   - `label-content-name-mismatch` (Rollen-Select in Freigaben)
   - `scrollable-region-focusable` (Tabelle der Extraktionsgüte)
   - offen für Runde 2
8. **Mock-Widersprüche (Kategorie F):**
   - Lauf „38 fehlgeschlagen“, aber 334 Fehler-Ereignisse in 4 Gruppen
   - Zeitplan „06:00“ gegen nächsten Lauf „07:00“ (Zeitzone, Tag der Zeitumstellung)
   - Mock-Fix in diesem Branch: Anmeldefehler hießen `AUTH`, das Backend sendet `CREDENTIALS_REJECTED`. Sollte auf die Basis.
9. Ungenutzt, aber nicht gelöscht: `LibraryHeaderNote`, `LibraryScopeHero`, `LibraryRoleNotices`, `MetadataMaintenanceAnchor`.

## Gebrochene Unit-Tests

Die Basis `feature/3084_ui-lab-detail-base` ist schon rot: 107 Fehlschläge, vor allem im Assistenten und in den Quellformularen.

Variante B: 171 Fehlschläge in `library`, `asset`, `prompt` und `shared/ui`, also **64 zusätzliche**. Zusätzliche Fehlschläge je Datei (Variante gegen Basis):

| Datei | Zusätzlich | Variante | Basis |
|---|---|---|---|
| `LibraryDetailPage.test.tsx` | +41 | 60 | 19 |
| `LibraryDetailPage.privateLibrary.test.tsx` | +5 | 5 | 0 |
| `LibraryDocumentList.test.tsx` | +5 | 5 | 0 |
| `LibrarySourceSection.test.tsx` | +4 | 22 | 18 |
| `AssetGrantsSection.test.tsx` | +4 | 4 | 0 |
| `MetadataExtractionSettingsSection.test.tsx` | +2 | 2 | 0 |
| `LibraryMetadataFieldsSection.test.tsx` | +1 | 1 | 0 |
| `LibraryExternalAccessSection.test.tsx` | +1 | 1 | 0 |
| `AssetOwnerSection.test.tsx` | +1 | 1 | 0 |
| `PageSection.test.tsx` | 0 | 0 | 0 |

Ursachen: entfernte Texte (Rollenhinweise, Bestand-Alert, Pflege-Anker), Läufe im neuen Reiter, Wortlaute. `lint`, `tsc` und `build` bestehen.

## `git diff --stat` (Code gegen die Basis, ohne Ergebnisbilder)

```
 37 files changed, 2749 insertions(+), 1346 deletions(-)
 neu: LibraryStatusBand.tsx (225), LibraryRunHistory.tsx (519), libraryState.ts (180),
      scopeFacts.ts (41), shared/ui/TabSection.tsx (73)
 groß: LibraryDetailPage.tsx, LibrarySourceSection.tsx, AssetGrantsSection.tsx,
       LibraryMetadataFieldsSection.tsx, MetadataExtractionSettingsSection.tsx
 Harness: flows/library-detail.mjs (Reiter „Verlauf“), lib/measure.mjs (tabsTop)
```

## Review-Befunde (REVIEW-1)

**A · Doppelungen**
- R1-1 erledigt: Eigentümerin nicht mehr in der Berechtigungsliste; privat ohne „Nur Sie sehen …“.
- R1-2 erledigt: über dem Verlauf nur „Vollabgleich starten“ (wo es ihn gibt).
- R1-3 erledigt: bei abgelehnter Anmeldung nur „Verbindung ändern ›“; andere Fehler behalten den Grund der Quelle.
- R1-4 erledigt: Zeile Zeitplan ohne „nächster Lauf“; kein Band-Link auf den offenen Reiter.
- R1-5 erledigt: Web/RSS ohne Band-Link (keine Umfangswahl).
- R1-6 erledigt: „n nicht lesbar“ in Grundfarbe, zuerst; in Quelle Warnfarbe nur im Punkt.
- R1-7, R1-20 erledigt: Leerzustände „Noch keine …“ mit der einen Handlung.
- R1-8 erledigt: Listenfuß nur beim Blättern oder Suchen.
- R1-9 erledigt: Filter nur, wenn er die Liste kürzt.
- R1-10 erledigt: Dauer eines laufenden Laufs „–“.
- R1-11 erledigt: Abschnitt „Extraktion“, Schalter „Leere Felder ergänzen“; „Muster“ einmal.
- R1-12 erledigt: Frist nur, wenn es eine gibt.

**B · Inkonsistenzen**
- R1-13 erledigt: eine Kante bei 880 px für Kopf, Band, Reiterleiste, Verlauf und alle Abschnitte; nur die Dokumentliste ist breiter.
- R1-14, R1-19 erledigt: Zeilenaktionen im ⋯-Menü an der Kante, „Ändern“ durchgängig. Teilweise: In der Dokumentzeile bleiben ⓘ und ↗ als Knöpfe; sie sind Ansichten, keine Verwaltungsaktionen.
- R1-15 erledigt: eine Form „Eintrag · ● nicht lesbar“, nicht lesbare Einträge zuerst; keine Sammelzeile. Ganze Verzeichnisse (filesystem, web, rss) listen den Pfad als Eintrag.
- R1-16 erledigt: kein Personen-Icon; Spaces und Zuständige als Links mit „›“ wie im Band.
- R1-17, R1-45 erledigt: Band und Zeile Umfang aus derselben Funktion (Zeile „Umfang“ des Setups); „Cloud“ steht nur noch an der Verbindung.
- R1-18 teilweise: Fundorte aus dem Umfang je Konnektor (Mock), Pfade mittig gekürzt. Bewusst nicht: eine gemeinsame Pfadfunktion für Dokumentzeile, Quelle und Verlauf; dafür gehört die Darstellung der Pfade in einen Baustein des Umsetzungs-Issues.
- R1-21 erledigt: Reiter mit einem Abschnitt ohne Titel (Zuordnungen, Verlauf); mehrere Abschnitte mit Titel links und Aktion rechts.
- R1-22 erledigt: Ergebnis „n Dokumente · n fehlgeschlagen · n nicht lesbar“.
- R1-23 erledigt: Zeitplan überall „Rhythmus · Termin“, „kein Zeitplan“. Bewusst nicht: Vollabgleich-Rhythmus bei Dropbox, das Fixture setzt keinen.
- R1-24 erledigt: Aktionszeile mit fester Höhe, unter 600 px immer eigene Zeile.
- R1-25 teilweise: Kernfelder lesend als Text, Diagnosesperre für Leser ausgeblendet. Bewusst nicht: Verbindung und Zeitplan für Leser – das Backend liefert sie Lesern nicht.
- R1-26 erledigt: „Technische Angaben“ überall als Textlink mit `aria-expanded`.
- R1-27 erledigt: Links nur bei Hover unterstrichen; Ausnahme „Mitglieder ausblenden“ aus `principal`.
- R1-28 erledigt: Label unabhängig vom Link (feste Zeilen), ganzes Feld als Link unter 600 px mit zugänglichem Namen; „Noch nie indiziert“ führt in den Verlauf.
- R1-29 erledigt: TT.MM.JJJJ und Tausenderpunkt.
- R1-30 erledigt: Verlauf-Aktion immer rechts.
- R1-31 erledigt: „Zustand · zuletzt Datum“ in jedem Fall, Ziel einzeilig mit Tooltip, kein Trenner am Zeilenende.
- R1-32 erledigt: siehe R1-5.
- R1-33 erledigt: privat als „Wissen · privat“ im ersten Chip; „Wird gelöscht“ nur im Stand.
- R1-34 erledigt: Titelspalte der Zeilen 152 px (gilt auch für Assistent und Prüfen).
- R1-35 erledigt: Upload-Stand mit Datum des letzten Uploads; drittes Feld „Neuestes“ (Dateiname) statt Datum.
- R1-36 erledigt: „Dateien hochladen“ als Hauptaktion im Kopf; leer heißt der Stand „Noch leer“.
- R1-37 erledigt: eine Kante, eine Checkbox, ein Suchfeld (`ListSearchField`).

**C · Kaputtes und Irreführendes**
- R1-38 erledigt: Stand bei 320 px zweizeilig (Wort / Datum); „n nicht lesbar“ vor der Kurzform.
- R1-39 erledigt: Reiterleiste mit Pfeilen unter 600 px, aktiver Reiter wird ins Bild geholt.
- R1-40 erledigt: „Erfolgreich“ am Lauf, „Aktuell“ nur im Stand.
- R1-41 erledigt: Upload „Läuft“, solange hochgeladen wird oder Dokumente verarbeitet werden; Ablehnungen als Popup mit Liste.
- R1-42 erledigt: Fixture (Kontingent in `failed` wirklich voll).
- R1-43 erledigt: keine Zahlen aus der geladenen Seite mehr im Stand.
- R1-44 erledigt: Verbindung aus dem letzten abgeschlossenen Lauf, auch während eines Laufs.
- R1-46 erledigt: Laufzeile nennt „nicht lesbar“; Gruppe „Nicht lesbar“ statt „Nicht erreichbar“.
- R1-47 erledigt: Leser bekommen keinen Link „Verbindung ›“; nicht lesbare Einträge stehen auch für Leser im Umfang.
- R1-48 erledigt: Web/RSS in `failed` „Fehlgeschlagen“, Verbindung „nicht erreichbar · Datum“.
- R1-49 erledigt: siehe R1-1.
- R1-50, R1-51 erledigt: Freigabe zweizeilig, Select fester Breite + ⋯ in einer Zeile auch bei 320 px.
- R1-52 erledigt: Metadaten unter 600 px mit ⋯, Füllgrad als Liste statt Tabelle.
- R1-53 erledigt: Laufzeile bei 320 px zweizeilig (Zustand / Datum · Ergebnis · Auslöser), Fundorte mittig gekürzt.
- R1-54 erledigt: Kennzahlzeile umbricht unter 600 px auf 2 reservierte Zeilen; Chipzeile mit drei Chips passt.
- R1-55 teilweise: Namen 1–2 Zeilen mit Tooltip, URLs einzeilig. Bewusst nicht: Trennstrich im Titel-Kompositum – `hyphens: auto` greift im Headless-Chromium mangels Wörterbuch nicht; der Umbruch bleibt ohne Strich.
- R1-56 erledigt: Fortschrittsleiste entfernt, auch beim Upload.
- R1-57 erledigt: leerer Abschnitt „Externer Zugang“ ausgeblendet.
- R1-58 erledigt: keine Extraktionsgüte bei 0 Dokumenten.

**D · Hilfetexte**
- R1-59 erledigt: Beispiel statt Regex, keine Schlüssel in Chips.
- R1-60 erledigt: ein Satz „Eingeschaltet gehen Inhalte an OpenAI (…)“, Warnpunkt nur bei eingeschalteter Funktion.
- R1-61 erledigt: Erklärung in die Bestätigungsfrage, Abschnitt nur mit Zustand und Aktion.
- R1-62 erledigt: Zahl unter „Technische Angaben“.
- R1-63 erledigt: „seit TT.MM.JJJJ · von X“.

**E · KI-Look**
- R1-64 bewusst nicht: Guidelines 5.5 verlangen die Asset-Art „stets mit dem Icon der Art“; bei Widerspruch gilt die Guideline.
- R1-65 erledigt: „Löschen“ im ⋯-Menü, Rot erst im Bestätigungsdialog.
- R1-66 erledigt: kein getöntes Banner, Popup mit Liste. Teilweise: kein Filter „Fehlgeschlagen“, die Dokumenten-API kennt keinen Statusfilter.
- R1-67 erledigt: Signalfarbe nur noch im Punkt (R1-6).

**F · Mock und Aufnahme** (Fixture- und Werkzeugänderungen; sollten auf die Basis)
- R1-68 beim Orchestrator.
- R1-69, R1-70, R1-71 erledigt: `steady()` in `lib/measure.mjs` wartet auf stabile Breite und Reiterleiste und nimmt den Fokus weg.
- R1-72 erledigt: `__full` in Originalbreite (höchstens 1280 px), dunkle `__full` weiter weggelassen.
- R1-73 bis R1-82 erledigt: Fehlerzahl = Fehlerereignisse, eine Uhr (Europe/Berlin), `slow` plausibel, Ablehnungen in Sekunden, `partial` mit genau einem nicht lesbaren Eintrag, gemischte erste Seite, Fundorte aus dem Umfang, fester Umfang je Bibliothek, gültige RSS-Adressen, privat in höchstens einem Space.
- R1-83 teilweise: Speicher, Datum, Ordner, Namen, `slow`-Upload und Banner je Szenario stimmen. Offen: Fuß „938“ (Wurzelebene) gegen Kopf „1.250“ (ganze Bibliothek) – echtes Verhalten, mit Fixtures nicht zu lösen.
