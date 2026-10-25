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

## Review-Befunde

– (REVIEW-1 steht aus)
