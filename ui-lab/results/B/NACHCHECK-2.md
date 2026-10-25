# Variante B – Nachcheck 2 (Runde 3), 25.10.2026

Grundlage: Matrix 3 in `wt-detail-b/ui-lab/results/B/` (Commit `54de2dbd`), Querbögen `review-detail-b/r3/sheets/`, REPORT-Abschnitt „Nachcheck-1 (Runde 3)“, `metrics*.jsonl`. Belege sind relativ zu `results/B/`. Zoom-Ausschnitte liegen in `review-detail-b/r3/crops/`.

**Vorab zur Matrix:** 449 Bilder bei 1280 px tragen noch die Zeit 05:05 (Runde 2). Laut git sind sie in Matrix 3 byte-gleich neu entstanden und wurden deshalb nicht neu geschrieben, sie sind also nicht veraltet. Die drei in Runde 2 gekachelten Bilder (`metadaten__web__partial__light__1280`, `dokumente__upload__scale__dark__1280`, `dokumente__gdrive__small__dark__1280`) sind neu und sauber (1280 × 900).

## Urteil: **noch eine Runde** (kurz, gezielt)

Es bleiben 4 blockierende Punkte, alle klein und ohne Konzeptfrage. Danach genügt eine Teilmatrix: Verlauf `scale`/`partial`, Quelle `partial`/`scale`, privat 320, Upload 320.

## Offene Punkte aus NACHCHECK-1

| Punkt | Urteil | Beleg |
|---|---|---|
| R1-13 Kante 880 im Reiter Quelle | **erledigt** | „Ändern“ und Zeilenlinien enden bei x≈984 (`quelle__dropbox__small__light__1280`, `quelle__s3__partial__light__1280`, `quelle__privat__scale__light__1280`). |
| R1-15 (a) Markierung bei 320 gekürzt | **erledigt** | „Archiv-2019 · ● nicht lesbar“ bei 320 ganz zu sehen (`r3/sheets/quelle__partial__light__320__p1`). Auch bei web 320 bleibt „● nicht lesbar“ stehen (`quelle__web__partial__dark__320`). Die Kürzung des Pfades davor ist fehlerhaft, siehe R1-53. |
| R1-15 (b) Pfad zwischen Mustern | **erledigt** | eigener Block unter den Mustern, mit Abstand (`quelle__filesystem__partial__light__1280`). Klein: filesystem zeigt den Pfad relativ („Archiv-2019“), web absolut („intranet.rheinfurt.example / richtlinien / archiv-2019“). |
| R1-18 ein Pfadformat | **erledigt** | s3 „protokolle / 2025“, „satzungen“; Fundorte durchgängig mit „ / “ (`laeufe__smb__partial__light__1280`, `laeufe__gdrive__scale__light__1280__full`). |
| R1-22 / R1-46 (a) Ergebnisspalte | **erledigt** | „214 · 17 fehlgeschlagen · 1 nicht lesbar“ in einer Zeile unter „Dokumente“ (`laeufe__smb__partial__light__1280`). |
| R1-46 (b) Gruppe „Nicht lesbar“ | **erledigt** | auch bei confluence: „Nicht lesbar · 1 / Bauamt“ (`laeufe__confluence__partial__light__1280__full`), s3, sharepoint und smb bei 320 (`r3/sheets/laeufe__partial__light__320__p3`). |
| R1-23 Rhythmus bei dropbox | **erledigt** | „täglich 06:00 · Vollabgleich alle 7 Tage“ (`quelle__dropbox__small__light__1280`). |
| R1-25 Zeitplan für Leser | **erledigt** | Leser sehen Umfang und Zeitplan ohne „Ändern“ (`quelle__confluence-leser__failed__light__1280`). „In Space verwenden“ für Leser: Die Begründung trägt (ADR-0039, Z. 81: Zuordnen darf, wer im Space `CURATOR` ist und das Asset lesen kann). Verbindung: Die Begründung trägt. |
| R1-37 Ordnerzeile | **erledigt** | Ordnersymbol in der Checkbox-Spalte, Namen beginnen bei x=160 (`dokumente__upload__failed__light__1280`). |
| R1-44 nextcloud `scale` „nicht geprüft“ | **nicht erledigt** | Das neue Bild `quelle__nextcloud__scale__light__1280` (Commit 54de2dbd) zeigt weiter „● nicht geprüft“. Dunkel zeigt dasselbe Szenario „Anmeldung ok · zuletzt 24.10.2026“ (`quelle__nextcloud__scale__dark__1280`). Das Warten auf `/indexing/runs` reicht also nicht. Außerdem behauptet die Oberfläche während des Ladens „nicht geprüft“. Das widerspricht D10; solange die Läufe laden, gehört dort ein neutraler Zustand ohne Aussage hin. |
| R1-53 Fundorte in der Mitte kürzen | **nicht erledigt** | Gekürzt wird jetzt in der Mitte, aber das hintere Stück wird **zusätzlich am Ende** abgeschnitten, sodass der Dateiname wieder verloren geht. Bei 1280: „… Nord, Sü… Anlage-2.…“, „Anlage-5.p…“, „Anlage-14.p…“ (`laeufe__gdrive__scale__light__1280__full`, `laeufe__confluence__scale__dark__1280`). Bei 320 in fast jeder Zeile: „Vorg… Anlage-1…“, „Akte 008 /…Anl…“ (`r3/sheets/laeufe__scale__light__320__p1/p2`, Zoom `crops/gd320a.png`). Derselbe Fehler steckt im Umfang: „intranet.rheinfurt.exa… archi… · ● nicht lesbar“ (`quelle__web__partial__dark__320`, Zoom `crops/web320c.png`). Das Endstück muss ungekürzt bleiben (`flex-shrink: 0`), nur der Anfang darf schrumpfen. |
| R1-53 Laufzeile 320 dreizeilig | **begründet offen, Begründung nur teilweise tragfähig** | In `partial` steht in Zeile 3 nur der Auslöser („Hand“, „Zeitplan“; `r3/sheets/laeufe__partial__light__320__p3`). Dass Umbrechen besser ist als Abschneiden, stimmt. Der Auslöser passt aber in Zeile 1 hinter das Datum („24.10. · Hand“); damit bliebe das Budget „höchstens 2“ eingehalten. Nicht blockierend, als Entscheidung vorlegen. |
| R1-55 Ordnernamen 320 | **erledigt** | „Ordner 001 · 27 Dokumente“ (`r3/sheets/upload__partial__light__320`). |
| R1-66 Upload-Meldung | **erledigt, Begründung trägt** | Es ist der globale Toast, unten mittig, mit einem Grund und einer Datei (`upload__upload__partial__light__1280`). Dass er im Standbild eine Zeile verdeckt, liegt am Toast und ist zeitlich begrenzt. |
| R1-70 / Reg. 1 kaputte Aufnahmen | **erledigt** | siehe Vorbemerkung |
| R1-76 indiziert-Datum | **erledigt** | Dokumente „21.10.2026, 06:02“ = letzter erfolgreicher Lauf (`dokumente__smb__failed__light__1280` gegen `laeufe__smb__failed__light__1280`). |
| R1-78 Rest „wird verarbeitet“ in `scale` | **erledigt** | `dokumente__filesystem__scale__light__1280`: indiziert oder fehlgeschlagen. |
| R1-79 Fundorte je Art | **erledigt** | confluence „Rechtsamt / Sitzungsprotokolle / Anlage-1.pdf“, rss „meldung-1.html“ (`laeufe__confluence__partial__light__1280__full`, `dokumente__rss__small__light__1280`). |
| R1-83 (a)–(d) Upload-Fixtures | **erledigt** | 181,8 KB und 403 KB, „Neuestes“ ist die frische Datei (`upload__upload__partial__light__1280`), 50 MB von 50 MB bei 12 × ~3,8 MB, Klammernamen ganz (`dokumente__upload__scale__light__1280__full`). Rest: Nach dem Hochladen in `empty` steht „2 Dokumente“ bei „0 B von 2 GB“ (`r3/sheets/upload__empty__light__1280`). Das ist geringfügig, der Speicher folgt dem Upload nicht. |
| Reg. Upload „Läuft“ ohne Zusatz | **begründet offen, Begründung trägt** | `dokumente__upload__slow__light__1280`: „Läuft“ ohne Zahl. Eine Zahl aus der geladenen Seite wäre falsch (R1-43). Das ist eine Abweichung von D2 („eine Zusatzangabe“) und gehört in die Vorlage. |
| Reg. Upload `scale` Fehler im Kopf | **erledigt** | „Teilweise fehlgeschlagen · 23.10.2026“ (`dokumente__upload__scale__light__1280__full`). |
| Reg. doppeltes „Zugang“ | **erledigt** | „Zugang „Nextcloud intern“ · cloud.personen.example“ (`quelle__privat__scale__light__1280`). |
| Reg. Leerzustände (R1-20) | **erledigt** | „Noch keine Zuordnungen. In Space verwenden“ im Satz, oben rechts nichts (`r3/sheets/zuordnungen__empty__light__1280__p3`). Ausnahme Upload 320: siehe Regression N2. |
| Reg. Reiterleiste 320 (R1-39) | **erledigt** | Zwei Zeilen ohne Wortreste, der aktive Reiter ist immer unterstrichen. Das gilt für Konnektoren (3+3), Leser (3+2) und Upload (3+1) (`r3/sheets/freigaben__small__light__320__p1`, `metadaten__small__light__320__p1`, `upload__partial__light__320`). |
| Reg. Dauer „3 h 12 min“, Vollabgleich ohne Rhythmus | **erledigt** | Läufe dauern 4–7 min, „Zeitplan · Vollabgleich“ nur bei Arten mit Rhythmus (`laeufe__gdrive__scale__light__1280__full`). |

## Neue Regressionen (Stichprobe)

- **N1 Rollen-Chip bei privat 320 abgeschnitten (blockierend):**
  - Seit Hauptaktion und ☆ ins ⋯ der Chipzeile gewandert sind, passt „Wissen · privat | Nextcloud | Eigentümer“ nicht mehr in die Zeile. Der Chip wird hart abgeschnitten: „Eigentüm“, ohne rechten Rand und ohne Ellipse.
  - Das gilt auf allen Reitern, hell und dunkel (`dokumente__privat__small__light__320`, `freigaben__privat__failed__dark__320`; Zoom `crops/chips320.png`). In Runde 2 war der Chip ganz (`crops/r2priv.png`).
  - Nach D6 ist der Chip die einzige Rollenanzeige.
- **N2 Upload 320: Hauptaktion nur noch im ⋯ (blockierend):**
  - „Dateien hochladen“ ist unter 600 px aus dem Kopf verschwunden.
  - Der Leerzustand sagt „Noch keine Dokumente. Dateien hierher ziehen“. Auf einem Touchgerät gibt es kein Ziehen, und darunter stehen „Ordner hochladen · Neuer Ordner“ statt der einen Handlung (`dokumente__upload__empty__light__320`). Das widerspricht R1-36 und D21.
  - Konnektoren lösen dasselbe mit dem Link „Jetzt indizieren“ im Satz (`r3/sheets/dokumente__empty__light__320__p1`). So sollte es auch hier sein: „Noch keine Dokumente. Dateien hochladen“.
- N3 (klein): Die Höhe des Upload-Kopfs in `partial` bei 320 liegt in 2 von 10 Bildern bei 365 statt 344. Nach dem Hochladen zeigt die Kennzahlzeile „216 Dokumente“ und bricht um. Das verletzt ±0 innerhalb eines Szenarios (D1), stammt aber aus dem Upload-Ablauf und kommt mit N2 in die Vorlage.
- Geprüft ohne Befund:
  - Kante 984 auf Metadaten, Freigaben, Zuordnungen und Verlauf bei 1280 (`metadaten__web__partial__light__1280`, `r3/sheets/freigaben__scale__light__1280__p2`).
  - Verlauf-Spalten bei 1280 hell und dunkel (`laeufe__confluence__scale__dark__1280`).
  - Freigaben `scale` dunkel bei 320 (`freigaben__sharepoint__scale__dark__320`).
  - Leser bei 320 (`zuordnungen__confluence-leser__small__light__320`).

## `tabsTop`-Überschreitungen: als Maintainer-Entscheidung vertretbar?

Die Messwerte aus `metrics*.jsonl`: 1280 = 249, in `scale` 255. 320 = 344, `empty` 365, privat 365, `scale` 391, Upload `partial` 344/365.

| Fall | Ursache (im Bild) | Vertretbar? |
|---|---|---|
| `scale` 391 | dreizeiliger Titel als Stresstest (Budget D-Titel: 3 Zeilen unter 600) und Kennzahlzeile „1.250 Dokumente · in 34 Spaces ›“ / „Erika Muster ›“ | **ja.** Ein Grenzfall aus der Mengenfixture. Kürzer ginge es nur über 2 Titelzeilen, das kann der Maintainer abwägen. |
| privat 365 | vierte Kennzahl (Speicher „2,4 GB von 10 GB“) erzwingt die zweite Zeile | **ja**, die Kennzahl gehört nach D5 in den Kopf. Wegen N1 ist die Chipzeile von privat aber ohnehin anzufassen. |
| `empty` 365 | „0 Dokumente · in keinem Space ›“ / „Erika Muster ›“ bricht nur wegen des Wortlauts um | **bedingt.** Das ist mit Text lösbar, etwa „kein Space ›“ oder „0 Spaces ›“. Es sollte vor der Vorlage versucht werden; wenn nicht, mit diesem Hinweis vorlegen. |
| 1280 `scale` 255 gegen 249 | zweizeiliger Titel | ja, innerhalb ≤ 260. Die Regel „±0 über Szenarien“ ist damit aber formal verletzt, das gehört in dieselbe Entscheidung. |

Gesamt: Vertretbar, wenn die Vorlage alle drei ausdrücklich als Abweichung von D1 nennt, mit Ursache, dazu die 6 px bei 1280. Die Begründung in REPORT Schwäche 1 trägt jetzt, anders als in Runde 2: Aktionszeile zusammengelegt, Kennzahlzeile nicht mehr reserviert. Beim Zusammenlegen der Aktionszeile sind allerdings N1 und N2 entstanden.

## Blockierend für die Vorlage (4)

1. **R1-53:** Bei der Mittelkürzung von Fundorten und Umfangspfaden wird das Endstück mitgekürzt. Der Dateiname fehlt bei 1280 (lange Pfade) und bei 320 (fast alle).
2. **R1-44:** nextcloud `scale` hell zeigt weiter „nicht geprüft“, dunkel „Anmeldung ok“. Ursache: Die Oberfläche zeigt beim Laden eine falsche Aussage, dazu kommt eine Wettlaufsituation bei der Aufnahme.
3. **N1:** Der Rollen-Chip „Eigentüm…“ ist bei privat 320 auf allen Reitern abgeschnitten (D6).
4. **N2:** Upload 320 hat keine sichtbare Hauptaktion, und der Leerzustand verlangt Ziehen auf dem Touchgerät (R1-36, D21).

## Für die Vorlage als bewusste Entscheidungen (nicht blockierend)

- `tabsTop` 320 `empty`, privat und `scale` sowie 1280 `scale` +6 px (siehe oben; `empty` möglichst vorher per Wortlaut beheben).
- Laufzeile bei 320 dreizeilig in `partial`. Der Auslöser könnte in Zeile 1 rücken.
- Hauptaktion und ☆ unter 600 px im ⋯-Menü (gilt für alle Arten).
- Upload „Läuft“ ohne Zusatz, Upload ohne Verlauf, Wortliste D2 um „Noch leer“ und „Erfolgreich“ erweitert.
- Verbindung für Leser nicht sichtbar (Backend), „In Space verwenden“ für Leser (ADR-0039).
- axe `label-content-name-mismatch` (158) und `target-size` (3) offen. Das ist keine Gestaltungsfrage, muss aber in der Vorlage stehen.
