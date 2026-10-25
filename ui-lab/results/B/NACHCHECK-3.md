# Variante B – Nachcheck 3 (Runde 4), 25.10.2026

Grundlage: Teilmatrix Runde 4 in `wt-detail-b/ui-lab/results/B/` (349 Aufnahmen laut `metrics-r4.jsonl`, Bilder 08:36, Commits `04a23df9` und `4f210524`) und REPORT-Abschnitt „Nachcheck-2 (Runde 4)“. Die Belege sind relativ zu `results/B/` angegeben. Die Zoom-Ausschnitte liegen in `review-detail-b/r4/crops/`.

## Urteil: **reif, sobald privat bei 1280 neu aufgenommen ist**

Alle sechs geprüften Punkte sind im Bild erledigt. Es bleibt ein blockierender Punkt. Er betrifft nur die Aufnahme und verlangt keine Codeänderung.

## Prüfpunkte

| Nr | Punkt | Urteil | Beleg |
|---|---|---|---|
| 1 | R1-53: Fundorte in der Mitte gekürzt, Endstück ganz | **erledigt** | **1280:** „… Gemarkungen Nord, S…  Anlage-2.pdf“, „… / Vorgang …  Anlage-14.pdf“. Der Dateiname ist immer ganz zu sehen (`laeufe__gdrive__scale__light__1280__full`). **320:** Alle Fundorte enden ungekürzt auf „Anlage-n.pdf“ bzw. „richtlinie-n.html“, geprüft bei gdrive, confluence, smb, sharepoint und web (`laeufe__*__scale__light__320__full`, Zoom `crops/fund320.png`, `crops/gd320.png`). **Umfang web@320:** „intranet.rheinfurt…. archiv-2019 · ● nicht lesbar“, Endstück und Markierung ganz (`quelle__web__partial__light__320`, Zoom `crops/web320.png`). Die Umfangsliste bei nextcloud 1280 zeigt „…/…Band 007 Grundstücksverkehrsgenehmigungsakte“ ganz (`quelle__nextcloud__scale__light__1280`). |
| 2 | R1-44: nextcloud `scale` hell | **erledigt** | „● Anmeldung ok · zuletzt 24.10.2026“ (`quelle__nextcloud__scale__light__1280`), Bild von 08:36. |
| 3 | privat@320: Rollen-Chip | **erledigt** | „Wissen 🔒 Nextcloud Eigentümer ⋯“ steht in einer Zeile, der Chip ist ganz und hat einen Rand (`dokumente__privat__small__light__320`, Zoom `crops/priv320head.png`). Auch in `scale` bei 320 ist er ganz (`laeufe__privat__scale__light__320`). |
| 4 | Upload@320: Leerzustand | **erledigt** | „Noch keine Dokumente. Dateien hochladen“, mit einer Handlung im Satz und ohne Zieh-Anweisung (`dokumente__upload__empty__light__320`). Mit Inhalt steht „Dateien hochladen“ als erste Aktion in der Werkzeugleiste (`dokumente__upload__small__light__320__full`). |
| 5 | Titel bei 320: kleiner, ungekürzt, Reiterleiste ≤ 360 | **erledigt** | Der Titel hat 14/17 px, ist halbfett und läuft über drei Zeilen ohne Ellipse. Das Wort wird mit Trennstrich getrennt: „Grundstücksverkehrsge-nehmigungsakten“ (`quelle__rss__scale__light__320`, `laeufe__gdrive__scale__light__320`). Er bleibt lesbar und hebt sich durch seine Stärke vom Beschreibungstext ab. `tabsTop` bei 320 in der Teilmatrix: Konnektoren 296 (empty, partial) bzw. 340/357 (scale), privat 317/340, Upload 296/317/340. Höchstwert 357 ≤ 360. `clippedText` im Kopf 0, `overflowX` 0. |
| 6 | Regressionen durch das ⋯-Menü und das Schloss | **eine blockierend, sonst ohne Befund** | siehe unten |

## Regressionen (Punkt 6)

- **Blockierend: Die privat-Aufnahmen bei 1280 sind veraltet.**
  - `04a23df9` (08:11) setzt `isPrivate` breitenunabhängig. Damit zeigt der Kopf auch bei 1280 das Schloss statt „Wissen · privat“ (`AssetDetailHeader.tsx:335`, `LibraryDetailPage.tsx`).
  - Die privat-Bilder bei 1280 stammen von 07:24 und zeigen noch „Wissen · privat“ (`dokumente__privat__small__light__1280`). Die Teilmatrix enthält privat nur bei 320.
  - Die Vorlage zeigt bei 1280 also einen Stand, den der Code nicht mehr erzeugt. Das Schloss bei 1280 ist nicht belegt.
  - Abhilfe: privat bei 1280 neu aufnehmen, hell und dunkel, alle Reiter.
- Ohne Befund:
  - axe meldet in der Teilmatrix keine neue Regel. Es bleiben `label-content-name-mismatch` (Freigaben) und `target-size` 1× (`quelle__dropbox__scale__light__320`, `.MuiIconButton-colorInherit`), dieselbe Zahl wie in Matrix 3. Kontrastverstöße: 0.
  - Das Schloss hat einen zugänglichen Namen („Private Bibliothek – nur Sie sehen sie“, `assetMarks.tsx:56`).
  - Das ⋯ misst bei 320 32 px und steht am Ende der Chipzeile, ohne etwas zu verdrängen.
  - Hauptaktion, Name ändern und Favorit liegen unter 600 px im Menü (`AssetDetailHeader.tsx:271–294`).
  - Bei 1280 stehen Hauptaktion, ☆, ⋯ und Stift unverändert (`quelle__nextcloud__scale__light__1280`).

## Hinweise (nicht blockierend)

- **Schloss ohne Wort:** Auf Touchgeräten erreicht man den Tooltip nicht. Sehende erkennen „privat“ nur am 16-px-Symbol, die Kachel zeigt dagegen Schloss und Wort (`PrivateMark`). Das gehört als Entscheidung in die Vorlage.
- **Upload-Werkzeugleiste bei 320:** „Neuer Ordner“ bricht in die zweite Zeile um und ist dabei um ca. 16 px eingerückt (x≈36 statt 20; `dokumente__upload__small__light__320__full`).
- **N3 bleibt bestehen:** `tabsTop` bei Upload `partial` 320 liegt bei 296 und 317, also nicht ± 0 im Szenario. Im REPORT („Für den Maintainer“ 1) ist der Fall nicht genannt.
- **Laufzeile bei 320:** Die Zeile ist nicht durchgängig zweizeilig. Ein langes Zustandswort bricht um („Teilweise / fehlgeschlagen“), das Ergebnis rückt dann in Zeile 3. „18.10. · Zeitplan · Vollabgleich“ reicht bis an das Chevron (`laeufe__gdrive__scale__light__320__full`).
- **Fundorte bei web:** Sie beginnen mit „https: / intranet.rheinfurt.exa…“, weil das Schema als Pfadglied gesetzt ist. Im Reiter Quelle steht dagegen „intranet.rheinfurt.example“ (`laeufe__web__scale__light__320__full` gegen `quelle__web__partial__light__320`).
- Zwischen Auslassung und Endstück fehlt teilweise der Abstand („Ak…Anlage-5.pdf“). Das ist kosmetisch.
