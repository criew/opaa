# Prüfliste Detailansicht – bereichsspezifische Ergänzungen (Detail-Lab, 24.10.2026)

Ergänzt `docs/design/ui-pruefliste.md` (P1–P50, Zählbudgets) um das, was die Detailseite einer Wissensbibliothek zusätzlich bestehen muss. Grundlage sind die Inventur (`inventory-detail.md`) und das Review (`ui-lab/results/ist-detail/REVIEW-1.md`, R1-n).

**Freigabe:** Der Maintainer hat die Freigabe delegiert. Jede Entscheidung unten ist begründet und **gilt ab jetzt für alle Varianten dieses Labs**. Die offenen Fragen am Ende ändern nichts an diesem Maßstab; sie betreffen die Umsetzung danach und andere Bereiche.

Die Nummern laufen fort: **D1–D24** gelten nur für diesen Bereich. Was über die Detailseite hinaus gilt, steht unter **P51–P55** als Vorschlag für die allgemeine Prüfliste (PR nach dem Lab).

## Zählbudgets (zusätzlich)

| Budget | Grenze | Messung |
|---|---|---|
| Höhe vom Seitenanfang bis zur Reiterleiste | ≤ 260 px bei 1280, ≤ 360 px bei 320; **gleich** über alle Arten und Szenarien (± 0 px) | gemessen: Oberkante `[role=tablist]` in allen Bildern eines Szenarios (neu in `measure.mjs`, siehe unten) |
| Zeilen der Kennzahlzeile | 1 bei 1280 px, höchstens 2 bei 320 px | Sicht |
| Zustandsanzeigen der Bibliothek | genau 1 Ort im Kopf; dazu 1 Detail am Ort der Ursache | Sicht (Querbogen `dokumente__slow`, `__partial`, `__failed`) |
| Elemente zwischen Kopf und Reiterleiste | 0; nur ein laufender Upload darf eine Zeile beanspruchen | Sicht |
| Laufzeile | 1 Textzeile bei 1280, höchstens 2 bei 320 | Sicht |
| Ereignisse aufgeklappt | höchstens 1 Zeile je Grund (Gruppe) + höchstens 5 Beispiele je Gruppe ohne Klick | Sicht in `laeufe__*__scale` |
| Höhe eines aufgeklappten Laufs | ≤ 900 px bei 1280 in `scale` (ein Bildschirm) | gemessen: `docHeight` von `laeufe__*__scale__*__full` minus derselbe Wert zugeklappt |
| Textzeilen je Listeneintrag (Dokument, Freigabe, Space, Lauf) | 2 (wie P6) | Sicht |
| Wörter im Hauptbereich je Reiter | 150 (Budget besteht), **auch** für Metadaten und Freigaben in `small` | gemessen `uiWords` |
| Erklärsätze je Reiter | höchstens 1 einzeiliger | Sicht |

## Neue Punkte für diesen Bereich

**Kopf**

- **D1 Ein Kopf für alle Arten.** Der Kopf zeigt die Felder in fester Reihenfolge: Chip Art · Chip Quellart · Titel · Beschreibung · Kennzahlzeile · Zustandsfeld · Hauptaktion. Ein Feld darf leer bleiben, wandert aber nie. Die Reiterleiste liegt in allen Arten und Szenarien auf derselben Höhe. *Begründung:* R1-16, Rückmeldung 1. – gemessen (Budget oben) und Querbogen.
- **D2 Ein Zustandsfeld.**
  - Der Zustand der Bibliothek steht an genau einer Stelle im Kopf, als **ein Wort** aus der festen Liste:
    - Aktuell
    - Läuft
    - Teilweise fehlgeschlagen
    - Fehlgeschlagen
    - Anmeldung abgelehnt
    - Zustimmung abgelaufen
    - Kontingent voll
    - Noch nie indiziert
    - Wird gelöscht
    - Nachfolge offen
  - Dazu kommt **eine** Zusatzangabe: Datum oder Prozent.
  - Das Wort ist ein Link zum Ort der Ursache.
  - Kein Banner, keine getönte Fläche; die Signalfarbe nur im Punkt (G 2.3).

  *Begründung:* R1-26, R1-27; erfüllt die Ausnahme der Hypothese (Handeln von jedem Reiter aus) ohne Zwischenbereich. – Sicht.
- **D3 Der Zustand ist wahr.** Kopf, Laufzeile und Laufdetail nennen für denselben Lauf dasselbe Wort und dieselben Zahlen. Ein Lauf mit fehlgeschlagenen Dokumenten, nicht lesbaren Bereichen oder `incomplete` ist nie „Aktuell“ oder „Abgeschlossen“ in Grün. – Sicht in `partial`, `scale`, `slow`.
- **D4 Ernstfall sichtbar.** Abgelehnte Anmeldung, abgelaufene Zustimmung, fehlgeschlagene geplante Läufe und volles Kontingent sind im Kopf von **jedem** Reiter aus sichtbar (D2). Am Ort der Ursache stehen sie als Zustand mit Handlung: Zeile Verbindung „Anmeldung abgelehnt · Ändern“. – Sicht in `failed`, Video „fehler“.
- **D5 Kennzahlen ohne Doppelung.** Die Kennzahlzeile trägt Dokumente, Speicher (wo bekannt), Spaces und Zuständige, und nichts, was das Zustandsfeld schon sagt. Eine Kennzahl, die einen Reiter zusammenfasst, ist ein Link auf diesen Reiter und wird dort nicht wiederholt. *Begründung:* R1-9; G 5.4 nennt dieselben Angaben für die Kachel. – Sicht.
- **D6 Rolle einmal.** Die eigene Rolle steht als Chip im Kopf. Kein zusätzlicher Rollenhinweis, kein „sehen nur Verwaltende“: Was eine Rolle nicht sehen darf, fehlt einfach. Ausnahme: die administrative Sicht der Systemverwaltung als Chip „administrativ“ (vorhanden). *Begründung:* R1-8, P42. – Sicht in `*-leser`.
- **D7 Laufender Vorgang einmal.** Ein laufender Lauf zeigt seinen Fortschritt im Zustandsfeld („Läuft · 62 %“) und in der Laufzeile, beide aus derselben Zählung. Kein zusätzlicher Balken über die volle Breite, kein zweiter Text am Knopf. *Begründung:* R1-2, G 5.5 („Lauf läuft · 62 %“ als Text in `fg-2`). – Sicht in `slow`, Video.

**Zwischenbereich und Quelle**

- **D8 Nichts zwischen Kopf und Reitern.** Umfang, Teilfehler und quellartspezifische Hinweise stehen im Reiter Quelle an der Zeile, die sie betreffen. Nicht lesbare Bereiche werden **in der Zeile Umfang** markiert, mit Namen, nie mit ID (`drive:…`). *Begründung:* Hypothese des Maintainers für den quellartspezifischen Teil bestätigt (Inventur, Kapitel 4). – Sicht in `partial`.
- **D9 Ein Zeilenraster für alle Konnektoren.** Verbindung · Umfang · Zeitplan bei allen 10 Konnektoren, auch Web und RSS („Ganzes Verzeichnis“, „Alle Einträge des Feeds“). Eine vierte Zeile „Live-Aktualisierung“ nur, wo es sie gibt (Confluence, S3), mit demselben Raster. *Begründung:* R1-12, F-Verlust (Inventur, Kapitel 8). – Querbogen `quelle__small`.
- **D10 Verbindung trägt den letzten bekannten Zustand.** Im Detail „Anmeldung ok · zuletzt 23.10.2026“ oder „Anmeldung abgelehnt“, nie „nicht geprüft“, wenn ein Lauf das Gegenteil weiß. Der Aufbau ist für alle Arten „Zugang oder Adresse · Zustand“. *Begründung:* R1-14, R1-27. – Sicht.
- **D11 Betriebsart einheitlich.** Wo es Vollabgleich gibt, steht er im Zeitplan **und** am Lauf; wo nicht, nirgends. *Begründung:* R1-13. – Querbogen.

**Läufe**

- **D12 Laufzeile als Tabellenzeile.** Feste Spalten: Zustand · Beginn · Dauer · Auslöser · Ergebnis („1 320 · 17 fehlgeschlagen“). Eine Zeile, kein Umbruch bei 1280; die Zeile ist ein Knopf in einer Liste, keine Überschrift. *Begründung:* R1-31, Rückmeldung 2. – Sicht, axe.
- **D13 Ereignisse gruppiert.** Aufgeklappt stehen die Ereignisse nach Grund gruppiert: „Format nicht unterstützt · 84“, darunter höchstens 5 Fundorte und „alle 84 anzeigen“ (Dialog oder Seiten). Fehler und Hinweise (entfernt, gedrosselt) sind getrennt, und nur Fehler zählen als „fehlgeschlagen“. *Begründung:* R1-29, R1-30, R1-5. – Budget „Höhe eines aufgeklappten Laufs“.
- **D14 Laufdetail ohne Wiederholung.** Das Detail zeigt nur, was die Zeile nicht hat: Gruppen, Dauer, technische Kennzahlen (zugeklappt unter „Technische Angaben“). Kein „Indizierung abgeschlossen“, kein „Nichts übersprungen“, wenn die Zeile Zahlen nennt. *Begründung:* R1-4, R1-28. – Sicht.
- **D15 Läufe sind eine Liste von 10.** Der Platz der Läufe ist gleich für alle Konnektoren. Upload hat keine Läufe; seine Upload-Ergebnisse stehen am Dokument und im Zustandsfeld (D2). *Begründung:* R1-19. – Querbogen.

**Reiter**

- **D16 Eine Inhaltsbreite.** Alle Reiter nutzen dieselbe Inhaltsbreite und dieselbe rechte Kante für „Ändern“ und Hinzufügen-Aktionen. Die Tabellen der Reiter Dokumente und Freigaben dürfen breiter sein, beginnen aber an derselben linken Kante. *Begründung:* R1-17, P46. – Querbogen über Reiter (neu: Querbogen je Art über alle Reiter).
- **D17 Ein Überschriftenstil.** Abschnittstitel in allen Reitern im selben Stil (Satzschrift, Gewicht 600, wie im Reiter Quelle von F), keine Mono-Versalien. *Begründung:* R1-18, P11. – Sicht.
- **D18 Hinzufügen-Aktion an einem Ort.** „Freigeben“, „In Space verwenden“ und „Feld anlegen“ stehen als Textknopf oben rechts im Abschnitt, auf der Kante von D16. *Begründung:* R1-20, P45. – Querbogen.
- **D19 Listen ab 8 Einträgen mit Suche.** Freigaben, Zuordnungen, Metadatenwerte und Ordner bekommen ab 8 Einträgen eine Suche. Ab 50 Einträgen wird geblättert oder gruppiert (Freigaben: Gruppen · Personen · Alle). *Begründung:* R1-34, R1-35, P20 auf eigene Objekte übertragen. – Sicht in `scale`.
- **D20 Kein Dauerhinweis ohne Handlung.** Insbesondere entfällt „Dieser Bestand stammt aus der Quelle …“: Dass Löschen fehlt, zeigt die fehlende Aktion. *Begründung:* R1-41, P13. – Sicht.
- **D21 Leerzustände je Reiter.** Bei 0 Dokumenten nur der Leerzustand mit der einen Handlung („Jetzt indizieren“ bzw. „Dateien hochladen“). Keine Metadaten-Pflege, keine Suche, kein Blättern. *Begründung:* R1-32, P38. – Sicht in `empty`.
- **D22 Metadaten-Füllgrad an einem Ort.** Der Füllgrad steht nur im Reiter Metadaten; im Reiter Dokumente höchstens ein Filter „ohne Wert“ in der Werkzeugleiste. *Begründung:* R1-6. – Sicht.
- **D23 Upload gleich gebaut.** Upload hat denselben Kopf mit Zustandsfeld (Hochgeladen · Kontingent voll · n fehlgeschlagen) und dieselbe Reiterfolge ohne Quelle. Upload-Fehler bleiben nach dem Schließen am Dokument sichtbar. *Begründung:* R1-10, R1-19, R1-37. – Querbogen mit `upload`.
- **D24 Nichts verlieren.** Jede Variante erreicht alles, was F oder `main` auf der Detailseite anbietet. Die Liste steht in Kapitel 8 der Inventur, einschließlich Webhook, S3-Ereignissen und „letzte geplante Läufe fehlgeschlagen“. – Prüfung gegen die Liste im REPORT.

## Vorschläge für die allgemeine Prüfliste (P51–P55)

- **P51 Ein Zustand, eine Quelle.** Derselbe Zustand trägt überall dasselbe Wort und dieselben Zahlen (aus D3).
- **P52 Gleiche Kopfhöhe je Objektart.** Der Kopf einer Detailseite ist über alle Unterarten gleich hoch (aus D1).
- **P53 Eigene Objekte ab 8 Einträgen mit Suche** (Gegenstück zu P20 für fremde Objekte, aus D19).
- **P54 Ein Überschriftenstil je Seite** (aus D17; schärft P11).
- **P55 Rolle einmal sagen** (aus D6; schärft P42).

## Pflicht-Szenarien für jede Variante

- **Szenarien:** `small`, `scale`, `failed`, `empty`, `partial`, `slow` (= läuft) × alle 11 Arten × hell und dunkel × 1280 und 320 px.
- **Sonderfälle:** `privat`, `upload-leser`, `confluence-leser`, `nextcloud-leser` in `small`, `scale` und `failed`.
- **Werkzeug:** Aufnahme mit `e2e/ui-lab/flows/library-detail.mjs`.
- **Videos** je Reiter aus `flows/library-detail-videos.mjs`, jeweils ok, viele und fehler.

Pflichtfälle, die eine Variante sichtbar bestehen muss:

| Fall | Bild | erwartet |
|---|---|---|
| Anmeldung abgelehnt | `dokumente__*__failed` | Zustandsfeld „Anmeldung abgelehnt“ im Kopf, auch im Reiter Dokumente |
| Teilfehler | `quelle__*__partial` | Markierung an der Zeile Umfang mit Namen, nichts über den Reitern |
| Lauf läuft | `dokumente__*__slow` | „Läuft · n %“ einmal im Kopf, gleiche Zahl in der Laufzeile |
| 500 Ereignisse | `laeufe__*__scale__full` | aufgeklappter Lauf ≤ 900 px |
| 45 Freigaben | `freigaben__*__scale` | Suche, „Freigeben“ oben, Einträge zweizeilig |
| Upload, Kontingent voll | `upload__upload__failed` | Zustandsfeld „Kontingent voll“; Fehler einmal |
| Leser | `*-leser` | Chip „Leser“, kein Alert, kein Erklärsatz |
| 320 px, langer Titel | `*__scale__light__320` | Titel umbricht, Reiterleiste mit Überlaufhinweis |

## Messung (Ergänzung der Werkzeuge, im nächsten Schritt)

- `measure.mjs`: neue Kennzahl `tabsTop` (Oberkante der Reiterleiste). `compare.py` meldet die Spanne je Szenario über die Arten.
- `measure.mjs`: `clippedText` auch auf `h1` und `[role=heading]` anwenden (R1-33: abgeschnittener Titel blieb ungemessen).
- `contact_sheets.py`: Querbogen „je Art über alle Reiter“ für D16.

## Entscheidungen (delegiert)

| # | Entscheidung | Begründung |
|---|---|---|
| E1 | Hypothese des Maintainers **angenommen mit Ausnahme**: Quellartspezifisches wandert in den Reiter Quelle (D8). Handlungspflichtige Zustände stehen als ein Zustandsfeld im Kopf (D2), nicht als Banner. | Inventur, Kapitel 4: Die Ausnahme ist nötig (P41, G 5.7), das Banner verstößt gegen P4 und D1. |
| E2 | Läufe bleiben im Reiter Quelle; ein eigener Reiter „Läufe“ wird **nicht** Pflicht. | Der Reiter Quelle von F ist entschieden. Richtung B prüft den eigenen Reiter als Alternative. |
| E3 | Webhook und S3-Ereignisse kommen als vierte Zeile in den Reiter Quelle (D9). | Erhält das Zeilenraster von F, macht den F-Verlust rückgängig. |
| E4 | Fortschrittsbalken über die volle Breite entfällt. | G 5.5 schreibt Text vor; R1-2. |
| E5 | Freigaben-Liste ab 8 Einträgen mit Suche, ab 50 gruppiert. | P20 sinngemäß; 45 Freigaben sind realistisch (Landesbehörde mit Referatsgruppen). |
| E6 | Upload bekommt keinen Reiter „Quelle“; seine Angaben (Kontingent, Upload-Ergebnisse) stehen im Kopf und im Reiter Dokumente. | Ein leerer Reiter wäre Schein-Konsistenz; der Kopf ist gleich gebaut (D23). |

## Offene Fragen an den Maintainer

**F1 · Soll der Kopf überhaupt Kennzahlen tragen, die einen Reiter zusammenfassen (Spaces, Zuständige)?**
- A: ja, als Links – Beispiel: „1 250 Dokumente · in 34 Spaces › · Erika Muster ›“. Folge: Der Kopf bleibt informativ wie die Kachel im Katalog (G 5.4), D5 verlangt die Link-Form. Gilt auch für Prompt-Bibliotheken (geteilter Kopf).
- B: nein, nur Dokumente und Zustand – Beispiel: „1 250 Dokumente · Aktuell · 24.10.2026“. Folge: kürzester Kopf; Spaces und Zuständige nur in ihren Reitern; weicht von der Kachel ab.

Empfehlung: **A**. Der Katalog zeigt dieselben Angaben, und die Links ersparen den Weg über die Reiter. Der Kopf bleibt mit D1 einzeilig.

**F2 · Wo steht die Wahl „Vollabgleich starten“?**
- A: im ⋯-Menü des Kopfs (wie heute). Beispiel: ⋯ › „Vollabgleich starten“. Folge: unsichtbar für die meisten, nur bei 4 Arten.
- B: an der Laufliste. Beispiel: Überschrift „Läufe“, rechts „Jetzt indizieren · Vollabgleich“. Folge: beide Startknöpfe am selben Ort; der Kopf behält „Jetzt indizieren“ als Hauptaktion.

Empfehlung: **B**, zusätzlich zum Kopfknopf. Wer einen Vollabgleich braucht, schaut gerade auf die Läufe.

**F3 · Upload-Ergebnisse: wie lange sichtbar?**
- A: Banner bis zum Schließen (heute). Folge: nach einem Seitenwechsel weg, verstößt gegen G 5.9.
- B: am Dokument (Chip „fehlgeschlagen“ + Grund im ⓘ) und im Zustandsfeld „n fehlgeschlagen“, bis das Dokument gelöscht oder neu hochgeladen ist. Folge: braucht im Backend den Fehlerstatus je Dokument, der als `FAILED` schon existiert; nur Uploads, die abgelehnt wurden, bevor ein Dokument entstand (Kontingent, Format), bleiben ein Popup mit Liste.

Empfehlung: **B**.

**F4 · Gilt D1/D2 auch für die Prompt-Bibliothek?**
- A: ja, im selben PR. Beispiel: Zustandsfeld „Aktuell“ entfällt dort mangels Läufen, die Kennzahlzeile bleibt. Folge: der Verlauf auf `main` verschwindet für beide.
- B: nur Wissensbibliothek. Folge: zwei Köpfe auf `AssetDetailHeader`.

Empfehlung: **A**, weil `AssetDetailHeader` geteilt ist.
