# Variante A – Einheitlicher Rahmen

## Konzept

Wie bisher eine Seite je Schritt, aber jedes Konnektorformular sitzt auf `SourceSetupLayout` mit fester Folge **Verbindung → Zugangsdaten → Umfang → Zeitplan**. Fehlt einer Quelle ein Abschnitt, entfällt er, die Folge bleibt.
Fremde Objekte zeigt eine gemeinsame, mitwachsende `SelectionList`: bis 6 Einträge schlichte Checkboxen, ab 7 Suche, „n ausgewählt“ und „Alle n (Treffer) auswählen“, ab 100 virtualisiert. Hierarchien zeigt ein gemeinsamer Inline-Baum (`SelectionTree`): Nachladen je Ebene, Teilauswahl, Breadcrumb ab Ebene 3 oder ab 100 Geschwistern.
Ein Verbindungstest (`ConnectionCheck`) steht am Ort und läuft zusätzlich bei „Weiter“ und „Speichern“. Danach folgt ein Prüfschritt mit „Ändern“, das Bearbeiten öffnet denselben Rahmen im Dialog, und der Reiter „Quelle“ zeigt dieselben vier Abschnitte.

Bausteine: `frontend/src/features/library/components/setup/` (je einmal gebaut). Pro Konnektor bleibt nur eine Datei `*Setup.tsx`, die die Abschnitte füllt. In der Registry kamen `validateAll`, `checkStages` und `summary` hinzu.

## Begriffssatz

| Begriff | Verwendung |
|---|---|
| Verbindung · Zugangsdaten · Umfang · Zeitplan | Abschnittstitel in Assistent, Dialog, Prüfschritt, Reiter |
| Zugang | Verbindungsprofil (Kacheln stehen im Abschnitt Verbindung) |
| Zustimmung | OAuth-Einwilligung (Dropbox) |
| Adresse / Endpoint (S3) / Verzeichnispfad | Ort der Quelle |
| Kennung · Kennwort · Token · Schlüssel-ID · Geheimer Schlüssel · Schlüsseldatei | Zugangsdaten (nie „Benutzername“, „Passwort“, „Anmeldedaten“) |
| gesetzt · Ersetzen · Nicht ersetzen | gespeicherte Geheimnisse |
| Verbindung testen · Wird getestet … · Trotzdem fortfahren | Test |
| Adresse · Anmeldung · Rechte | Teststufen |
| … laden · Erneut laden · Erneut versuchen · Filter zurücksetzen | Auflistungen |
| Alle n auswählen · Alle n Treffer auswählen · Auswahl aufheben · n ausgewählt | Massenauswahl |
| Quelle bearbeiten · Speichern · Prüfen · Ändern · Bibliothek anlegen | Aktionen |

## Messung (volle Matrix, 604 Aufnahmen + `__full`)

8 Typen × 6 Szenarien × hell/dunkel × 1280/320. Detail und Bearbeiten wurden nach einem Kontrast-Fix noch einmal aufgenommen.
- Ein Zustand nicht erreicht: `wizard-validation dropbox small`. Dropbox ist mit Vorgabe „/“ (alle Ordner) gültig, deshalb geht „Weiter“ ohne Eingabe durch. Das ist gewollt.
- Horizontales Scrollen: 0. Deaktivierte Absende-Knöpfe: 0. `focusHidden`: 0.
- axe: hell 0 Befunde. Dunkel 5× `color-contrast` (geerbte Navy-Textfarbe in Altbausteinen der Detailseite).
- Mittlere Inhaltshöhe bei 1280 px (hell): Leer 1193, Validierung 1355, ausgefüllt 3197, Prüfen 1050, Detail 1185, Bearbeiten 4265. Ausgefüllt und Bearbeiten enthalten die innere Scrollhöhe der virtualisierten Listen (n × 40 px).

## Prüfliste

| # | | Beleg |
|---|---|---|
| 1 | ✓ | Teststufen: Zeichen + Wort, keine Chips; Lauf-Chips in Signalfarbe durch Text ersetzt (`LibrarySourceSection`) |
| 2 | ✓ | Reiter unverändert: Text + Unterstrich |
| 3 | ✓ | keine Deko-Icons an Abschnittsköpfen |
| 4 | ◐ | neue Bausteine ohne Tönung; die Zustimmung ist jetzt ein Haken + Satz statt grüner Alert-Fläche. Kacheln „Zugang“ (Altbestand) bleiben getönt |
| 5 | ✓ | Akzent nur an Knöpfen und Links |
| 6 | ✓ | Listenzeile einzeilig: Name + 1 Sekundärangabe (Art, Space-Key), Chevron öffnet |
| 7 | ✓ | Feldfehler: roter Rahmen + eine Zeile (`wizard-validation__*`) |
| 8 | ✓ | „Änderungen an der Quelle verwerfen?“: zwei Knöpfe, ohne Warnbox |
| 9 | ✓ | Schritte: Quellart · Quelle · Name · Freigaben · Prüfen |
| 10 | – | keine Filterleisten im Umfang |
| 11 | ✓ | Hierarchie über h3/Größe, keine Kästen um Abschnitte |
| 12 | ✓ | Labels 1–3 Wörter (Kennung, Kennwort, Endpoint, Bucket von Hand hinzufügen = Ausnahme 4) |
| 13 | ◐ | Hinweise einzeilig. Ausnahme: der Satz unter „Erste Indizierung“ kommt aus der Registry und ist 2-zeilig |
| 14 | ✓ | Platzhalter = Beispiele (`/data/dokumente`, `smb://dateiserver/Ablage`) |
| 15 | ✓ | Fehler in einem Satz („Bitte die Kennung eingeben.“); Backend-Texte unverändert durchgereicht |
| 16 | ✓ | Status: „gesetzt“, „Wird getestet …“ |
| 17 | ✓ | Verben: Speichern, Ersetzen, Hinzufügen, Entfernen, Trotzdem fortfahren |
| 18 | ✓ | Verwerfen-Frage ohne Doppelungen |
| 19 | ✓ | ein Begriffssatz für alle Konnektoren (s. o.) |
| 20 | ✓ | `scale`: 450 Spaces / 300 Bereiche / 200 Ordner mit Suche ab 7, „n ausgewählt“, virtualisiert ab 100 (`wizard-filled__confluence__scale__*`, `…gdrive…`, `…nextcloud…`) |
| 21 | ✓ | Site → Bibliothek → Ordner im selben Baum, Breadcrumb „Sites › Rathaus › Dokumente › …“, Auswahl über Ebenen in „Ausgewählt: n“ mit Pfad (`wizard-filled__sharepoint__scale__light__1280__full`) |
| 22 | ✓ | Ellipse + Tooltip ab 44 Zeichen; 0× Überlauf bei 320 px |
| 23 | ✓ | Laden „… werden geladen …“ (nach 300 ms), Fehler je Ebene mit „Erneut versuchen“, 0 Treffer (`*__empty__*`, `*__failed__*`, `*__slow__*`) |
| 24 | ✓ | „Alle n auswählen“ / „Auswahl aufheben“ je Liste und Ebene |
| 25 | ✓ | 320 px: overflowX 0/604 |
| 26 | ✓ | Fokus auf der Schrittüberschrift; `document.title` = „Schritt 2 von 5: Quelle einrichten – Neue Wissensbibliothek“ |
| 27 | ◐ | 55 verdeckte Tab-Stopps im Messlauf, meist Chevron-Knöpfe in der virtualisierten Liste und die Fußleiste im Leerzustand. Nicht behoben |
| 28 | ✓ | `disabledSubmits` 0. Weiter/Anlegen/Speichern melden Fehler statt zu sperren; im Lauf `aria-disabled` (BusyButton) |
| 29 | ✓ | alle Fehler auf einmal am Feld + Zusammenfassung mit Sprunglinks oben (Fokus), Titel „Fehler: …“. Nach dem ersten Absenden verschwinden behobene Fehler beim Tippen |
| 30 | ◐ | „Zurück“ verliert nichts (State je Quellart). Browser-Zurück nicht eigens behandelt |
| 31 | ✓ | Dialog fragt nur bei echtem Unterschied (Vergleich mit Ausgangswerten); Assistent wie bisher |
| 32 | ✓ | Schritt „Prüfen“ mit „Ändern“ je Abschnitt; „Weiter“ springt danach zurück zur Prüfung (`review__*`) |
| 33 | ✓ | ein Muster: Knopf am Ort + automatisch bei Weiter/Speichern; Stufen Adresse · Anmeldung · Rechte; Ergebnis verfällt bei Änderung; „Trotzdem fortfahren“ als ausdrückliche Wahl |
| 34 | ✓ | `edit__*`: „Kennung und Kennwort · gesetzt · Ersetzen“, Schlüsseldatei „gesetzt“ |
| 35 | ✓ | Objekte der Quelle als Liste/Baum mit Suche ab 7, keine Kacheln; Quellart bleibt Kachel |
| 36 | ✓ | Teilauswahl: Checkbox `indeterminate` + `aria-checked="mixed"`; Klick wählt den Knoten und ersetzt die Auswahl darunter |
| 37 | ✓ | „Alle 37 Treffer auswählen“ wirkt nur auf Gefiltertes; der Zähler zählt auch Ausgeblendetes |
| 38 | ✓ | erstmals leer („Ordner laden“ + Satz) · 0 Treffer („Keine … passen zu „x“.“ + Filter zurücksetzen) · kein Zugriff (Fehler + Erneut versuchen) |
| 39 | ✓ | Ladeanzeige erst nach 300 ms (`useDelayedFlag`); Test und Weiter zeigen „Wird getestet …“ im Knopf |
| 40 | – | Indizierungsstatus nicht im Umfang (Kopf unverändert) |
| 41 | ✓ | „Quelle gespeichert“ als Popup; jeder Fehler steht am Ort |
| 42 | ◐ | Leser sehen im Reiter nur „Umfang“ + Satz. Restliche Seiten unverändert |
| 43 | ◐ | 35-Zeichen-Kompositum bei 320 px ohne Überlauf. `clippedText` > 0 in 158 Aufnahmen, Ursache meist Stepper/visually-hidden, nicht einzeln geprüft |
| 44 | ◐ | axe hell 0, dunkel 5 Kontrastbefunde (Altbausteine) |

## Bekannte Schwächen

- Webhook- und S3-Ereigniskonfiguration (alte `StoredView`) fehlt im neuen Reiter „Quelle“. Webverzeichnis/RSS laufen noch auf den alten Formularen (`LegacySourceSections`).
- Die Teststufen werden aus `reachable`/`credentialsVerified` plus Textmustern geraten. Das Backend liefert keine Stufe.
- Der Teilauswahlzustand kennt nur bereits geladene Vorfahren. Bei SharePoint-Ordnern aus gespeicherten Einstellungen ist deshalb nur die Bibliothek als „teilweise“ markiert.
- SharePoint im Bearbeiten-Modus: Die Auswahl wird erst nach „Sites suchen“ im Baum sichtbar, vorher als Textzeile.
- Gruppenzeilen (Sites) haben doppelte Bedienung: Name als Knopf und Chevron.
- `__full`-Aufnahmen mit virtualisierten Listen wachsen auf die innere Scrollhöhe. Das Export-Skript schneidet leeren Rand ab.
- Die Kachel des Nextcloud-Zugangs zeigt weiter „Vorgaben: edition: INTERN“ (Altbestand, Tests hängen daran).
- Der Detailkopf schneidet bei 320 px „Vollabgleich starten“ ab (nicht im Umfang).

## Gebrochene Unit-Tests (bewusst, nicht repariert)

`pnpm exec vitest run src/features/library src/features/asset`: 96 von 832 rot.

| Datei | rot |
|---|---|
| `library/pages/LibraryCreatePage.test.tsx` | 40 (Schritte, Prüfschritt, neue Formulare) |
| `library/components/EditLibrarySourceDialog.test.tsx` | 24 |
| `library/pages/LibraryDetailPage.test.tsx` | 17 (Reiter „Quelle“ neu gegliedert) |
| `library/components/sources/connectedSourceForms.test.tsx` | 6 |
| `library/pages/LibraryCreatePage.sourceConsent.test.tsx` | 4 |
| `library/components/LibrarySourceSection.test.tsx` | 3 |
| `asset/pages/CatalogPage.test.tsx` | 2 (nur unter Last; einzeln 24/24 grün) |

Lint, `tsc -b`, `pnpm run build`, Prettier: grün.

## git diff --stat gegen origin/feature/3084_ui-lab-base (ohne results/)

```
 frontend/src/features/asset/components/AssetNameFields.tsx              |   7 +-
 frontend/src/features/library/components/ConnectionProfileSelect.tsx    |  13 +-
 frontend/src/features/library/components/CreateReviewStep.tsx           |  60 ++++
 frontend/src/features/library/components/CreateSourceSetupStep.tsx      | 112 ++++---
 frontend/src/features/library/components/EditLibrarySourceDialog.tsx    | 243 ++++++----
 frontend/src/features/library/components/LegacySourceSections.tsx       |  77 +++++
 frontend/src/features/library/components/LibraryConnectionPanel.tsx     |   2 +-
 frontend/src/features/library/components/LibraryCreateFooter.tsx        |  28 +-
 frontend/src/features/library/components/LibraryScheduleForm.tsx        |   8 +-
 frontend/src/features/library/components/LibrarySourceSection.tsx       | 269 ++++-----
 frontend/src/features/library/components/SourceConsentPanel.tsx         |  23 +-
 frontend/src/features/library/components/SourceSummarySections.tsx      | 109 +++++
 frontend/src/features/library/components/libraryCreateWizard.ts         |  19 +-
 frontend/src/features/library/components/setup/* (27 neue Dateien)     | ~3400 +
 frontend/src/features/library/components/sources/* (9 Registrierungen)  | 463 +++---
 frontend/src/features/library/pages/LibraryCreatePage.tsx               | 246 ++++---
 ui-lab/harness/capture.mjs                                              |   8 +-
 52 files changed, 4585 insertions(+), 466 deletions(-)
```
