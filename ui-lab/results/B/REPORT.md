# Variante B „Statusband“ – Bericht Runde 4 (25.10.2026)

Stand: Matrix 3 nach REVIEW-1 und NACHCHECK-1, dazu Teilmatrix Runde 4 (nur hell, Kennzahlen in `metrics-r4.jsonl`): Verlauf und Quelle in `scale`/`partial`, alle Arten in `empty`, Upload und privat bei 320 px, alle Arten `scale` bei 320 px (Kopf). Branch `feature/3084_ui-lab-detail-var-b`.

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
- **Übrige Reiter:** gemeinsamer Baustein `shared/ui/TabSection` mit einer Breite (880 px), einem Überschriftenstil und der Aktion oben rechts an der Kante.

## Kennzahlen (Matrix 3, aus `metrics.jsonl`, `metrics-extra.jsonl`)

| Messung | Wert | Budget |
|---|---|---|
| `tabsTop` 1280, alle 11 Arten, privat und Leser | 249 in jedem Szenario, 255 in `scale` (± 0 je Szenario) | ≤ 260 ✓ |
| `tabsTop` 320 (Runde 4) | 296 in small, failed, partial, slow, empty; privat 328 (`scale` 351); `scale` 340 (zweizeiliger Titel) bzw. 357 (dreizeilig) | ≤ 360 ✓ |
| `uiWords` small (Dokumente · Quelle · Verlauf · Metadaten · Freigaben · Zuordnungen) | 67–89 · 27–57 · 29–39 · 23–51 · 32–45 · 24–37 | ≤ 150 ✓ |
| aufgeklappter Lauf `scale` | < 900 px | ≤ 900 ✓ |
| Kontrast (axe, hell und dunkel) | 0 | 0 ✓ |
| `overflowX` | 0 | 0 ✓ |
| axe übrig | `label-content-name-mismatch` 158 (Rollen-Select in Freigaben, Auswahl-Checkboxen), `target-size` 3 | ✗ |

## Status je D-Punkt

| Nr | Status | Beleg |
|---|---|---|
| D1 Ein Kopf | erfüllt: ± 0 je Szenario über alle Arten, privat und Leser; 320 px über dem Höhenbudget | `tabsTop` in `metrics*.jsonl` |
| D2 Ein Zustandsfeld | erfüllt: Feld „Stand“, Wort aus der festen Liste, Zusatz Datum oder %, Link zum Ort der Ursache | `dokumente__nextcloud__failed__light__1280` |
| D3 Zustand wahr | erfüllt: dieselbe Funktion `runState` für Kopf, Zeile und Detail; Teilfehler nie „Aktuell“ | `laeufe__smb__partial__light__1280`, `dokumente__smb__partial__light__1280` |
| D4 Ernstfall sichtbar | erfüllt: Band auf jedem Reiter; Zeile Verbindung „● Anmeldung abgelehnt · Ändern“ | `quelle__confluence__failed__light__1280` |
| D5 Kennzahlen ohne Doppelung | erfüllt: kein „Letzter Lauf“, kein „Aktualisiert am“; Spaces und Zuständige als Links in den Reiter | Kopf aller Bilder |
| D6 Rolle einmal | erfüllt: Rollen-Alerts und „nur Verwaltende“ entfernt, nur Chip | `quelle__confluence-leser__small__light__1280` |
| D7 Laufender Vorgang einmal | erfüllt: „Läuft · 62 %“ im Feld und dieselbe Zahl in der Laufzeile; Knopf ohne Zusatztext, keine Leiste (auch nicht beim Upload) | `dokumente__s3__slow__light__1280`, `laeufe__s3__slow__light__1280` |
| D8 Nichts zwischen Kopf und Reitern | erfüllt mit Abweichung: Umfang-Kurzform im Band (gewollte Doppelung der Richtung B, Kurzform mit Link); Markierung „· nicht lesbar“ am Eintrag mit Namen, auch bei Drive | `quelle__gdrive__partial__light__1280` |
| D9 Ein Zeilenraster | erfüllt: Web „Ganzes Verzeichnis“, RSS „Alle Einträge des Feeds“; Live-Aktualisierung bei Confluence und S3 | `quelle__*__small__light__1280` |
| D10 Verbindung mit Zustand | erfüllt: „Anmeldung ok · zuletzt 23.10.2026“ bzw. „erreichbar · …“ ohne Anmeldung | `quelle__nextcloud__small__light__1280` |
| D11 Betriebsart einheitlich | erfüllt: Vollabgleich im Zeitplan und in der Spalte Auslöser („Zeitplan · Vollabgleich“), nur bei Arten mit Rhythmus | `laeufe__confluence__scale__light__1280__full` |
| D12 Laufzeile als Tabellenzeile | erfüllt: feste Spalten, eine Zeile bei 1280, Knopf in einer Liste | `laeufe__*__scale__light__1280` |
| D13 Ereignisse gruppiert | erfüllt: Gruppe je Grund mit Anzahl, 5 Fundorte, Dialog „Alle n“; Hinweise getrennt | `laeufe__smb__scale__light__1280__full` |
| D14 Detail ohne Wiederholung | erfüllt: keine Meldung „Indizierung abgeschlossen“, kein „Nichts übersprungen“; Kennzahlen unter „Technische Angaben“ | dito |
| D15 Läufe als Liste von 10 | erfüllt für Konnektoren; Upload ohne Verlauf (siehe Schwächen) | `laeufe__*__scale` |
| D16 Eine Inhaltsbreite | erfüllt: eine Kante bei 880 px für Kopf, Band, Reiterleiste, Verlauf und Abschnitte; nur die Dokumentliste ist breiter | alle `*__light__1280` |
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

1. **320 px im Budget, aber nicht ± 0 je Szenario.** 296 px in den meisten Szenarien, privat 328 (Chip- und Kennzahlzeile brechen um), `scale` 340/357 (Titel 14 px, zwei oder drei Zeilen ungekürzt). Der kleinere Titel macht den Kopf unter 600 px weniger hierarchisch; Stift, Favorit und Hauptaktion sind nur über ⋯ erreichbar.
2. **Titel begrenzt** ab 600 px auf 2 Zeilen, voller Name im Tooltip; darunter ungekürzt in 14 px.
3. **Band ohne Rahmen** (Abweichung von der Skizze): Haarlinie oben und Trenner, kein Kasten. Gründe: Projektregel „kein ruhender Inhalt in einer Karte“ (`PageSection.test.tsx`) und P4.
4. **Umfang zweimal** (Band und Reiter Quelle): gewollte Doppelung der Richtung B, im Band nur Kurzform mit Link; auf dem Reiter Quelle selbst fehlt der Link.
5. **Upload ohne Verlauf** (D15, E6; keine API für Upload-Vorgänge). Der Upload-Stand liest die geladene Seite der Dokumentliste, nennt deshalb keine Zahlen; eine Zählung je Status fehlt im Backend.
6. **Upload drittes Feld „Neuestes“** (Dateiname) statt „Zuletzt hochgeladen“, weil das Datum jetzt im Stand steht (R1-35).
7. **Wortliste D2 erweitert** um „Noch leer“ (leere Upload-Bibliothek) und am Lauf „Erfolgreich“ (R1-40, R1-36).
8. **D24 mit Abstrichen**: Text zur vorgemerkten Löschung nur im Tooltip des Stands; bei „Nachfolge offen“ fehlt der Adressat im Kopf.
9. **axe** `label-content-name-mismatch` an Rollen-Select und Auswahl-Checkboxen ist offen.
10. **Mock- und Werkzeugänderungen** in diesem Branch (sollten auf die Basis): `CREDENTIALS_REJECTED` statt `AUTH`, R1-73 bis R1-83, `steady()` und `tabsTop` in `lib/measure.mjs`, Bereich `laeufe` über den Reiter „Verlauf“.
11. Ungenutzt, aber nicht gelöscht: `LibraryHeaderNote`, `LibraryScopeHero`, `LibraryRoleNotices`, `MetadataMaintenanceAnchor`.

## Gebrochene Unit-Tests

Die Basis ist schon rot: 107 Fehlschläge, vor allem im Assistenten und in den Quellformularen. Variante B (Runde 3): 226, also **119 zusätzliche**. Je Datei (Variante / Basis):

| Datei | Variante | Basis |
|---|---|---|
| `LibraryDetailPage.test.tsx` | 77 | 19 |
| `AssetGrantsSection.test.tsx` | 26 | 0 |
| `LibrarySourceSection.test.tsx` | 22 | 18 |
| `LibraryMetadataFieldsSection.test.tsx` | 11 | 0 |
| `LibraryDocumentList.test.tsx` | 6 | 0 |
| `LibraryDetailPage.privateLibrary.test.tsx` | 5 | 0 |
| `MetadataExtractionSettingsSection.test.tsx` | 4 | 0 |
| `AssetSpacesList.test.tsx` | 2 | 0 |
| `AssetDetailHeader.test.tsx`, `AssetOwnerSection.test.tsx`, `LibraryExternalAccessSection.test.tsx` | je 1 | 0 |

Unverändert gegen die Basis sind `LibraryCreatePage*` (51), `LibrarySourceSection.sourceConnection` (5), `ConnectionProfileSelect` (4) sowie Quellformulare und Utils (9).

Ursachen:
- entfernte und geänderte Texte
- Läufe im neuen Reiter
- die Eigentümerin steht nicht mehr in der Liste
- ⋯-Menüs statt Knöpfen
- bei 0 Freigaben der Leerzustand statt des offenen Formulars

`lint`, `tsc` und `build` bestehen.

## `git diff --stat` (Code gegen die Basis, ohne Ergebnisbilder)

```
 49 files changed, 3993 insertions(+), 1734 deletions(-)
 neu: LibraryStatusBand.tsx, LibraryRunHistory.tsx, libraryState.ts, scopeFacts.ts,
      shared/ui/TabSection.tsx (mit ListSearchField)
 groß: LibraryDetailPage.tsx, LibrarySourceSection.tsx, AssetGrantsSection.tsx,
       LibraryMetadataFieldsSection.tsx, MetadataExtractionSettingsSection.tsx, Mock-Fixtures
 Harness: flows/library-detail.mjs, lib/measure.mjs (tabsTop, steady)
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
- R1-24 erledigt: Aktionszeile und Titelzeile (mit und ohne Stift) mit fester Höhe; Leser jetzt auf derselben Höhe (`tabsTop` ± 0).
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
- R1-55 erledigt: Namen 1–2 Zeilen mit Tooltip, URLs einzeilig, Titel mit Trennstrich („Grund-stücks…“, `dokumente__smb__scale__light__320`).
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

## Nachcheck-1 (Runde 3)

- **R1-13** erledigt: Der Reiter Quelle endet jetzt an derselben Kante (880 px ab Inhaltsbeginn) wie Kopf, Band, Verlauf und die übrigen Reiter.
- **R1-15** erledigt: Nicht lesbare Pfade unter einem ganzen Verzeichnis (filesystem, web) stehen in einem eigenen Block unter den Mustern. Der Pfad wird in der Mitte gekürzt, die Markierung „· ● nicht lesbar“ nie.
- **R1-18** erledigt: Es gibt ein Pfadformat mit „ / “:
  - Fundorte im Verlauf werden auf diesen Trenner normalisiert, die Fixtures schreiben ihn auch.
  - Ein Bucket ohne Präfix steht ohne Schrägstrich am Ende.
- **R1-22, R1-46, Regression 1/2** erledigt:
  - Die Spalte heißt „Dokumente“, die Zahl steht ohne Einheit: „214 · 17 fehlgeschlagen · 1 nicht lesbar“ passt bei 1280 in eine Zeile.
  - Die Gruppe „Nicht lesbar“ entsteht auch aus den gemeldeten Bereichsschlüsseln (confluence, nextcloud, sharepoint, s3, gdrive, smb, dropbox), mit dem gespeicherten Namen.
- **R1-25** erledigt (Zeitplan): Leser sehen im Reiter Quelle die Zeilen Umfang und Zeitplan, beide ohne „Ändern“. Bewusst nicht (Verbindung): Das Backend liefert Lesern die Verbindung nicht.
  - „In Space verwenden“ für Leser bleibt: Laut ADR-0039 darf zuordnen, wer im Space `CURATOR` ist und das Asset lesen kann. Die Rolle an der Bibliothek muss dafür nicht höher sein.
- **R1-37** erledigt: Ordner haben ihr Symbol in der Checkbox-Spalte statt einer blassen Checkbox. Ordner- und Dokumentnamen beginnen an derselben Kante.
- **R1-44** erledigt: Das „nicht geprüft“ bei Nextcloud `scale` war ein Aufnahmefehler; die Laufliste kam erst nach dem Bild an. Der Flow wartet jetzt auf `/indexing/runs`.
- **R1-53** erledigt:
  - Fundorte werden in der Mitte gekürzt, der Dateiname bleibt bei jeder Breite.
  - Die Laufzeile bei 320 px: Zustand und Datum in Zeile 1, Ergebnis und Auslöser darunter. Diese Zeile umbricht statt abzuschneiden; bei langem Ergebnis wird die Laufzeile dreizeilig, über dem Budget „höchstens 2 bei 320“. Abschneiden war im Review der schwerere Fehler.
- **R1-55** erledigt: Ordnernamen bei 320 px zweizeilig statt „Ordn…“.
- **R1-66** erledigt:
  - Es gibt keine Inline-Meldung über der Liste. Die Meldung im Bild ist die globale Popup-Benachrichtigung (G 5.9: unten mittig, Rahmen und Schatten der Ebene 2, schließbar, 6 s).
  - Sie nennt den Grund einmal und höchstens drei Dateien, ohne Formatliste.
  - Ihren roten Rahmen bestimmt der gemeinsame `NotificationHost`, nicht die Variante.
- **R1-70 / Regression 1** erledigt: Die Aufnahme nimmt das Viewport-Bild erst, wenn zwei Aufnahmen byte-gleich sind (`stableShot`).
- **R1-76, R1-79, R1-83, Regression 6** erledigt (Fixtures):
  - Das indiziert-Datum stammt aus dem letzten erfolgreichen Lauf.
  - Confluence-Fundorte haben die Form Space / Seite / Anhang; web- und rss-Dokumente heißen `.html`.
  - Upload-Größen und Kontingent sind plausibel, „Neuestes“ ist die frisch hochgeladene Datei, Namen werden nicht in Klammern gekürzt.
  - Dropbox und Drive haben einen Vollabgleich-Rhythmus; Läufe dauern gleich lang.
- **Regression 2/3 (Upload-Stand)**:
  - Teilweise: Ein fehlgeschlagenes Dokument geht im Stand jetzt vor „Läuft“.
  - Bewusst ohne Prozent: „Läuft“ hat bei Upload keine Zusatzangabe. Es gibt keine Zählung des Fortschritts; eine Zahl aus der geladenen Seite wäre falsch (R1-43).
- **Regression 4** erledigt: Beginnt der Zugangsname schon mit „Zugang“, bekommt er kein zweites Präfix; das Fixture heißt „Nextcloud intern“.
- **Regression 6 (R1-20)** erledigt: In allen Reitern gilt dasselbe Muster für leere Listen: „Noch keine … “ und die Handlung im selben Satz. Leer entfällt die Aktion oben rechts.
- **Regression 7 (R1-39)** erledigt: Unter 600 px brechen die Reiter auf zwei Zeilen um, statt zu scrollen. Es gibt keine Wortreste und keine wechselnde Startlage mehr; der aktive Reiter ist immer sichtbar und unterstrichen.
- **`tabsTop` bei 320 px**: Unter 600 px stehen Hauptaktion und Favorit im ⋯-Menü der Chipzeile, und die Kennzahlzeile wird nur bei Bedarf zweizeilig. Ergebnis siehe Kennzahlen.

## Nachcheck-2 (Runde 4)

- **R1-53** erledigt:
  - Bei Fundorten und Umfangspfaden schrumpft nur der Anfang, das Endstück (Dateiname) nie (`flex-shrink: 0`).
  - Die Laufzeile bei 320 px ist jetzt zweizeilig: Zustand, Datum und Auslöser in Zeile 1, das Ergebnis darunter.
- **R1-44** erledigt:
  - Solange die Läufe laden, zeigt die Zeile Verbindung keinen Zustand statt „nicht geprüft“.
  - Die Aufnahme wartet auf den geladenen Zustand.
- **N1** erledigt: Die Chipzeile bricht um statt abzuschneiden, das ⋯ bleibt am Ende der ersten Zeile.
- **N2** erledigt:
  - Upload leer: „Noch keine Dokumente. Dateien hochladen“, ohne Zieh-Anweisung; Dateien lassen sich trotzdem darauf ziehen.
  - Unter 600 px steht „Dateien hochladen“ als erster Knopf in der Werkzeugleiste.
  - „Ordner hochladen“ ist auch im ⋯-Menü erreichbar.
- **`empty` bei 320 px** erledigt per Wortlaut: „kein Space ›“ statt „in keinem Space ›“. Die Kennzahlzeile ist einzeilig, `tabsTop` wie in den anderen Szenarien.
- **Maintainer-Entscheid Titel bei 320 px** umgesetzt: Unter 600 px ist der Titel 14/17 px ohne Zeilenbegrenzung; Stift (Name ändern), Favorit und Hauptaktion stehen im ⋯-Menü (32 px); privat zeigt einen eigenen Chip „🔒 Privat“ (Schloss mit Wort, wie auf der Kachel) statt „Wissen · privat“; Bandzeilen kompakter. Ergebnis: 296 / privat 328 (`scale` 351) / `scale` 340–357, alle ≤ 360, kein Abschneiden.
- **Nachcheck Runde 4:** privat bei 1280 neu aufgenommen (alle Szenarien, hell; `metrics-r4b.jsonl`); Fundorte zeigen URLs ohne Schema („intranet… / richtlinien / …“); „Neuer Ordner“ bei 320 px bündig.
- **Maintainer-Entscheid privat in Spaces:** „in n Spaces“ bleibt bei privat stehen.

## Für den Maintainer

Bewusste Abweichungen, die diese Variante zur Entscheidung vorlegt:

1. **Kopfhöhe bei 320 px nicht mehr ± 0 je Szenario** (alle ≤ 360, die früheren Ausnahmen entfallen): privat 328 statt 296, weil Chipzeile und Kennzahlzeile umbrechen; in `scale` 340 oder 357 je nach Titellänge (zwei oder drei Zeilen, ungekürzt).
2. **Kopfhöhe 1280 px in `scale` 6 px höher** (255 statt 249), weil der Titel zweizeilig ist (auf 2 Zeilen begrenzt). Innerhalb des Szenarios ist sie über alle Arten gleich.
3. **Unter 600 px stehen Hauptaktion und Favorit im ⋯-Menü** der Chipzeile, damit es keine eigene Aktionszeile gibt (Budget). Für Upload ist „Dateien hochladen“ dort zusätzlich als erster Knopf der Werkzeugleiste sichtbar.
4. **Reiterleiste unter 600 px zweizeilig** statt scrollbar: alle Reiter ganz sichtbar, der aktive unterstrichen.
5. **Band ohne Rahmen:** Haarlinie oben und Trenner zwischen den Feldern statt eines Kastens aus drei Feldern. Gründe: Projektregel „kein ruhender Inhalt in einer Karte“ und P4.
6. **Upload ohne Reiter Verlauf** (D15, E6). „Läuft“ bei Upload hat keine Prozentangabe, weil es keine Fortschrittszählung gibt.
7. **Wortliste D2 erweitert** um „Noch leer“ (Upload) und am Lauf „Erfolgreich“ (Ergebnis eines Laufs).
8. **axe-Rest:**
   - `label-content-name-mismatch` am Rollen-Select in Freigaben und an den Auswahl-Checkboxen: Der zugängliche Name ist ausführlicher als der sichtbare Text.
   - `target-size` an einzelnen ⋯-Knöpfen.
9. **Upload-Popup:** Sein roter Rahmen kommt vom gemeinsamen `NotificationHost` (G 5.9) und gehört nicht zur Variante.
10. **Mock- und Werkzeugänderungen dieses Branches gehören auf die Basis:**
    - Fixtures: R1-73 bis R1-83, `CREDENTIALS_REJECTED`
    - Werkzeug: `tabsTop`, `steady`, `stableShot`, Warten auf Läufe und Verbindung, Bereich `laeufe` über den Reiter Verlauf.
