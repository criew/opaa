# Variante A – Nachcheck 2 (Runde 3), 25.10.2026

Grundlage: `NACHCHECK-1.md` (Teil a und b) mit den offenen Punkten und Regressionen, `REPORT.md` Abschnitt „Nachcheck 1 – Runde 3“, Einzelbilder Runde 3 unter `wt-detail-a/ui-lab/results/A/` (nur hell, Belege ohne `.jpg`), Querbögen `review-detail-a/r3/sheets/` (nur hell), `metrics.jsonl`/`metrics-extra.jsonl`. Ausschnitte unter `review-detail-a/r3/crops/`. Jeder Punkt wurde am Bild geprüft, nicht am REPORT.

Maintainer-Entscheidungen berücksichtigt: private Bibliotheken dürfen in Spaces; Titel bei 320 px kleiner, Reiterleiste ≤ 360 px; dunkles Schema nachrangig.

## Urteil

**Noch eine Runde, eine kurze.** Die großen Punkte aus Nachcheck 1 sind am Bild erledigt: Kennzahlzeile bei 320 px, Pfadformat und Kürzung, benannte nicht lesbare Bereiche, Upload-Zustand, Laufzeile bei 320 px, Indizierdatum bei S3, privat und Upload. Vier Punkte stören aber in genau den Bildern, die in die Videos kommen (Metadaten, Upload, Fehlerfall). Der REPORT meldet dazu „alle offenen Punkte umgesetzt“, das stimmt nicht: Regression a7 ist weder umgesetzt noch erwähnt.

## Messwerte

- `tabsTop`: 1280 px in allen 900 Bildern 212. Bei 320 px 331, in allen `scale`-Bildern 350 (120-Zeichen-Titel, vier Zeilen). Beides liegt unter 360, die Maintainer-Vorgabe ist eingehalten.
- axe: 0 Verstöße. `overflowX` 0, Budgetverletzungen 0. 28 Zeilen `not-found` in `metrics-extra.jsonl` sind erwartet: Leser ohne Läufe, Upload-Leser ohne Datei-Eingabe.

## Offene Punkte aus Nachcheck 1

| Punkt | REPORT | Nachcheck 2 | Beleg |
|---|---|---|---|
| R1-3 (+ b7) | erledigt | **bestätigt erledigt**: Kennzahlzeile bricht um, Eigentümer bei 320 px vollständig („Erika Muster ›“ in eigener Zeile), kein „·“ am Zeilenanfang oder -ende, „in keinem Space“ ohne „›“ | `dokumente__filesystem__scale__light__320`, `dokumente__upload__small__light__320`, `freigaben__s3__scale__light__320`, `upload__upload__failed__light__320`, `quelle__confluence__empty__light__320` |
| R1-6 | erledigt | **bestätigt erledigt**: Verwalter-Zeile „Jonas Albrecht“, Eigentümerin nur unter Eigentümer | `freigaben__gdrive__partial__light__1280` |
| R1-11 | erledigt | **bestätigt erledigt**: kein Verbindungspräfix mehr in den Fundorten. Mittenkürzung lässt das letzte Segment stehen („Vorgang 009 – Fachbereich … / Anlage-9.pdf“) | `laeufe__filesystem__partial__light__1280`, `laeufe__nextcloud__scale__light__1280` |
| R1-36 | erledigt | **teilweise**: Dropbox, S3, Fundorte durchgehend „a / b / c“. SharePoint mischt weiter „·“ und „/“: „Rathaus · Dokumente / Ratsbeschlüsse“, genau das Beispiel aus Nachcheck 1 | `quelle__sharepoint__failed__light__1280` ↔ `quelle__dropbox__scale__light__1280` |
| R1-13, R1-51 (+ a4/b1) | erledigt | **bestätigt erledigt**: „Nicht lesbare Bereiche · 1“ mit „Gebührensatzungen“ bzw. „archiv“, RSS ohne Gruppe. Die Markierung im Umfang hat zwei Formen, siehe Hinweis 2 | `laeufe__filesystem__partial__light__1280`, `laeufe__web__partial__light__1280`, `laeufe__rss__partial__light__1280`, `quelle__web__partial__light__1280` |
| R1-16 (+ a1) | erledigt | **bestätigt erledigt**: „Teilweise fehlgeschlagen · 17 fehlgeschlagen“. Das Wort steht damit zweimal, siehe Hinweis 5 | `dokumente__upload__partial__light__1280` |
| R1-22 | geklärt | **erledigt** durch Maintainer-Entscheidung; Zuordnungen bei privat mit 34 Spaces | `zuordnungen__privat__scale__light__1280` |
| R1-40 | erledigt | **bestätigt erledigt**: Zeitplan bei 320 px „täglich 06:00 · Vollabgleich alle 7 Tage“ / „nächster Lauf …“, kein hängender Trenner | `quelle__confluence__failed__light__320`, `quelle__dropbox__failed__light__320` |
| R1-46 | erledigt | **bestätigt erledigt**: „in 3 Spaces ›“ und „Erika Muster ›“ ganz in Linkfarbe | `freigaben__gdrive__partial__light__1280`, `dokumente__dropbox__scale__light__1280` |
| R1-55 | erledigt | **bestätigt erledigt**: „nur **/*.pdf“ als Eintrag. Am Rand: In `partial` steht das Muster unter „satzungen“, es bleibt offen, ob es für einen oder beide Präfixe gilt. „1 Bucket oder Präfix“ (`small`) klingt holprig | `quelle__s3__small__light__1280`, `quelle__s3__partial__light__1280` |
| R1-58 | erledigt | **bestätigt erledigt**: Leser sehen nur Eigentümer und Herleitung | `freigaben__confluence-leser__small__light__1280` |
| R1-72 (+ b2) | erledigt | **teilweise**: S3 „23.10.2026, 10:24“ = Lauf 10:17 + 7 min, Upload „24.10.2026, 09:30“ = Kopf „Hochgeladen · 24.10.2026“. Im Fehlerfall `failed` nicht stimmig, siehe Blocker 3 | `dokumente__s3__small__light__1280` ↔ `laeufe__s3__small__light__1280`; `dokumente__upload__small__light__1280__full`; `dokumente__gdrive__failed__light__1280__full` ↔ `laeufe__gdrive__failed__light__1280` |
| R1-78 | erledigt | **teilweise**: `failed` stimmig (12 × 7,7 MB, „100 MB von 100 MB“). In `small` (Upload läuft) bleibt der Speicher bei „99,1 KB von 2 GB“, obwohl die zwei neuen Dateien 84 KB und 402,3 KB groß sind. Der REPORT nennt 412 KB / 86 KB / 2,3 MB, im Bild steht etwas anderes | `upload__upload__small__light__1280__full` |
| R1-79 | erledigt | **bestätigt erledigt**: S3 „Admin · Verwalter“ passt zum Chip, gdrive-Umfang gleich. Die Begründung für privat „2,4 GB von 10 GB“ bei 3 Dokumenten (Summe aller privaten Bibliotheken) trägt nur, wenn die Zahl so beschriftet ist („Kontingent“). Im Kopf liest sie sich als Speicher dieser Bibliothek | `dokumente__privat__small__light__1280` |
| R1-38 (Rest) | – | nicht behandelt, weiter klein; nicht blockierend | `quelle__s3__partial__light__1280` |
| Regr. a2/b5 | erledigt | **bestätigt erledigt**: 320 px zweizeilig, „1.250 Dokumente · 38 fehlgeschlagen“ ganz | `laeufe__confluence__scale__light__320`, Bogen `laeufe__partial__light__320__p2` |
| Regr. a3/b3 | erledigt | **teilweise, neue Regression**: „Anzeigen“ jetzt an allen Feldern, der Metadaten-Filter ist nicht im Bild prüfbar. Das Label bricht aber mitten im Wort ohne Trennstrich um: „Aufbewahrungsfri“ / „st“. Siehe Blocker 1 | `metadaten__smb__scale__light__1280`, `metadaten__confluence__scale__light__1280`, `metadaten__confluence-leser__scale__light__1280` |
| Regr. a5 | erledigt | **bestätigt erledigt**: Gruppe „Nicht abrufbar · 4“ | `laeufe__filesystem__partial__light__1280` |
| Regr. a6 | erledigt | **bestätigt erledigt**: abgelehnte Läufe ohne Pfeil | `laeufe__dropbox__failed__light__1280`, `laeufe__gdrive__failed__light__1280` |
| Regr. a7 | – (fehlt im REPORT) | **nicht erledigt**: Unter 8 Dokumenten steht „Neuer Ordner“ weiter allein rechts in einer eigenen Zeile zwischen Meldung und Liste | `upload__upload__small__light__1280`, `dokumente__upload__small__light__1280__full`, `dokumente__upload__small__light__320` |
| Regr. a8 | erledigt | **bestätigt erledigt**: Stift bei 320 px rechts in der Aktionszeile | `dokumente__upload__small__light__320` |
| Regr. b4 | erledigt | **bestätigt erledigt**: „Läuft · 214 von 216 · 17 fehlgeschlagen“ | `upload__upload__partial__light__1280`, `upload__partial__light__320` (Bogen) |
| Regr. b6 | erledigt | **teilweise**: Der aktive Reiter ist ganz sichtbar und nicht überdeckt. Links vom Pfeil bleibt aber ein Bruchstück des Nachbarn stehen: „‹ le“ (von „Quelle“) auf Freigaben bei 320 px | Bogen `freigaben__scale__light__320__p2` (filesystem, gdrive, nextcloud), `freigaben__s3__scale__light__320` |
| Randnotiz SharePoint 12:51 | bleibt | Begründung tragfähig (täglicher Vollabgleich) | `quelle__sharepoint__failed__light__1280` |

**Zähler:** 17 bestätigt erledigt (davon R1-22 durch den Maintainer) · 6 teilweise (R1-36, R1-72, R1-78, R1-79-Begründung, a3/b3, b6) · 1 nicht erledigt und nicht berichtet (a7).

## Blockierende Punkte

1. **Füllgrad-Label bricht mitten im Wort.** „Aufbewahrungsfri“ / „st“ ohne Trennstrich, bei 1280 px in jedem `scale`-Bild aller Arten, auch beim Leser. Das entstand mit dem Fix zu a3/b3 (Umbruch statt Kürzung). Abhilfe: `hyphens: auto` mit `lang="de"` oder eine breitere Labelspalte. Bei 320 px stehen Label und Wert untereinander, dort tritt der Fehler nicht auf. Belege: `metadaten__smb__scale__light__1280`, `metadaten__confluence__scale__light__1280`, `metadaten__confluence-leser__scale__light__1280`.
2. **Upload-Meldung bei 320 px beginnt mit „·“, und es gibt zwei Formen der Meldung.** „● Das Dateiformat wird nicht unterstützt“ / „· nicht hochgeladen: Datenbank Altbestand.mdb“: Die Folgezeile beginnt mit dem Trenner, also genau dem Muster, das R1-3 und R1-40 gerade beseitigt haben. Dazu hat dieselbe Meldungsart zwei Formen: in `failed` „● Nicht hochgeladen: a, b, c“ (R1-28), in `small`/`partial` „● Grund · nicht hochgeladen: Datei“. Belege: `upload__upload__small__light__320`, Bogen `upload__partial__light__320` ↔ `upload__upload__failed__light__320`, bei 1280 `upload__upload__partial__light__1280`.
3. **Fehlerfall `failed`: Dokumentzustände ohne Lauf, der sie erklärt.** Die Läufe zeigen nur die drei abgelehnten Anmeldungen (21., 22., 23.10.). Der erfolgreiche Lauf vom 20.10., auf den „Indiziert · 20.10.2026, 06:07“ verweist, fehlt in der Liste. Gleichzeitig stehen Dokument 001–005 auf „Fehlgeschlagen“, ohne dass ein sichtbarer Lauf Fehler gezählt hätte (D3 „Zustand ist wahr“). Das betrifft gdrive, confluence und confluence-leser, also genau das Material für das Video „fehler“. Belege: `dokumente__gdrive__failed__light__1280__full` ↔ `laeufe__gdrive__failed__light__1280`, `dokumente__confluence-leser__failed__light__1280` ↔ `laeufe__confluence__failed__light__1280`.
4. **Regression a7 offen und nicht berichtet:** „Neuer Ordner“ steht allein in einer eigenen Zeile über der Liste. Bei 8 Dokumenten oder mehr steht er in der Werkzeugleiste. Im Upload-Video ist das der erste Bildschirm. Belege: `upload__upload__small__light__1280`, `dokumente__upload__small__light__320`.
5. **Upload bei 320 px: Ordnername gekürzt, obwohl Platz ist.** „Ordner 0…“ neben „Ordner 002“ (gleich lang); die beiden Ordner sind nicht unterscheidbar (vgl. R1-17). Dazu bleibt in `small`, während ein Upload läuft, der Speicher bei 99,1 KB, obwohl 486 KB dazukommen (R1-78). Belege: Bogen `upload__partial__light__320`, `upload__upload__small__light__1280__full`.

## Hinweise (nicht blockierend)

1. **Leerraum im 320-px-Kopf bei kurzem Titel.** Drei fest reservierte Titelzeilen und zwei Kennzahlzeilen lassen bei „Rundschreiben“, „Protokolle (S3)“ usw. etwa 40 px Leerraum unter dem Titel und etwa 40 px über der Reiterleiste. Das folgt aus D1 und der Maintainer-Vorgabe und sollte bei der Vorlage ausdrücklich genannt werden, damit es nicht als Fehler gelesen wird (Bogen `zuordnungen__small__light__320__p4`).
2. **Zwei Formen der Markierung „nicht lesbar“ im Umfang:** „Bauamt ● nicht lesbar“ am Eintrag (Confluence, Dropbox, S3) gegen „● Nicht lesbar: Gebührensatzungen“ als eigene Zeile zwischen Zusammenfassung und Ausschlussmustern (Dateisystem, Web). Bei Dateisystem liest sich die Zeile wie das erste Muster (Bogen `quelle__partial__light__1280__p1`).
3. SharePoint-Pfad mit „·“ (R1-36, Rest).
4. „‹ le“-Bruchstück in der Reiterleiste bei 320 px (b6, Rest).
5. „Teilweise fehlgeschlagen · 17 fehlgeschlagen“ wiederholt das Wort. Wie der Fortschritt würde „· 17 Fehler“ oder „· 17 von 214“ mit eigener Form ausreichen; Entscheidung bei D2.
6. privat „2,4 GB von 10 GB“: ohne Beschriftung als Kontingent missverständlich (R1-79).
7. Zuordnungen privat: „● nicht alle Mitglieder lesen“ ist kein Satz. Besser „nicht für alle Mitglieder lesbar“ (`zuordnungen__privat__scale__light__1280`).
8. Dropbox `scale`, Umfang: Die Liste zeigt Ordner 001, 002, 004, 005, 007; 003 und 006 fehlen ohne erkennbaren Grund (Fixture, `quelle__dropbox__scale__light__1280`).

## Stichprobe ohne Befund

Dokumente `scale` 320 (s3, sharepoint, smb), Metadaten `scale` 320 (confluence, confluence-leser, dropbox), Freigaben `scale` 320, Zuordnungen `small` 320, Läufe `partial` 320 (gdrive, nextcloud, rss), Quelle `partial` 1280 (confluence, dropbox, filesystem), Quelle `failed` 320 (confluence, dropbox, web), Läufe `scale` 1280 (nextcloud, Summen 10+10+9+9 = 38), Freigaben S3/Leser 1280.
