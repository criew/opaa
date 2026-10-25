# Variante C – Nachcheck 2 (Runde 3)

Grundlage: `wt-detail-c/ui-lab/results/C/` (Stand d0f907fa), nur helle Bilder; Querbögen `review-detail-c/r3/sheets/` (hell); `metrics.jsonl` (912 helle Aufnahmen, alle `ok`); Code-Stichprobe im Worktree. Belege ohne `.jpg`, Querbögen mit `sheets/`. Es wurde nichts geändert.

Maintainer-Vorgaben angewandt: Private Bibliotheken dürfen in Spaces stehen. Der Titel wird bei 320 kleiner, die Reiterleiste liegt bei ≤ 360 px (± 0 je Szenario entfällt damit). Das dunkle Schema ist nachrangig.

## Urteil: **noch eine Runde (kurz)**

Drei Punkte blockieren. Zwei davon meldet der REPORT als erledigt, aber weder die Bilder noch der Code bestätigen das. Der dritte ist ein Widerspruch genau in dem Teil, nach dem die Variante heißt („mit Abschluss“). Alle drei sind klein und lassen sich in einer Runde ohne neue Konzeptarbeit beheben. Danach ist C reif für Videos und Vorlage.

## Blockierend

| # | Punkt | Beleg |
|---|---|---|
| B1 | **R1-19 / Regression 1 nicht erledigt, obwohl der REPORT es meldet** („bei 320 nur Feld und Leer“). Der Füllgrad hat bei 320 weiter 5 Spalten. „Leer“ ist abgeschnitten, Zahlen kleben am Feldnamen („Dokumentart0“, „Datum/Stand0“). Im Code gibt es keine schmale Fassung: `MetadataExtractionSettingsSection.tsx` rendert die Spalten Feld/Regel/Modell/Hand/Leer immer (Z. 200–206), und die Datei ist seit Runde 2 unverändert (fehlt in `df91f675`). | sheets/metadaten__small__light__320__p4 (s3, sharepoint, smb), metadaten__rss__small__light__320 |
| B2 | **R1-45 nicht erledigt, obwohl der REPORT es meldet** („Zuordnung einzeilig, Name kürzt“). Lange Space-Namen brechen bei 1280 weiter zweizeilig um, und „· seit 12.09.2026“ steht als eigene Zeile mit führendem Punkt. Bei 320 läuft „Space 006 …“ über 5 Zeilen plus die seit-Zeile. `AssetSpacesList.tsx` ist seit Runde 2 unverändert. Die Freigaben kürzen dieselben Namen bereits einzeilig, die Zuordnungen könnten das genauso tun. | sheets/zuordnungen__scale__light__1280__p2 (filesystem, gdrive, nextcloud: Space 003/006/009/012), zuordnungen__web__scale__light__320; Gegenbeispiel sheets/freigaben__scale__light__1280__p2 |
| B3 | **Abschluss: „✓ Quelle verbunden“ auch bei fehlgeschlagener Verbindung.** Der Steckbrief meldet „● Anmeldung abgelehnt“, direkt darunter steht als erledigter Schritt „✓ Quelle verbunden“. Ursache: `nextSteps.ts:101` setzt `done: true` ohne Bedingung. Gefordert ist: ✓ nur bei Zustand ≠ Anmeldefehler/nicht erreichbar, sonst als erster offener Schritt „Zugang prüfen ›“ (Ziel: Reiter Quelle). In einem Abschluss-Video wäre das der erste sichtbare Fehler. | sheets/abschluss__failed__light__320__p1 (confluence, dropbox), abschluss__confluence__failed__light__1280 |

## Bewertung `abschluss`@320 (Reiterleiste 356–462 px)

Gemessen (`metrics.jsonl`, hell, 320): Upload 356, die meisten Konnektoren 380, Confluence und S3 404 (4 Schritte). Mit langem Titel (`scale`) sind es 414–462. Alle übrigen Reiter liegen bei 242 bzw. 300/322 (`scale`) und damit im Budget.

**Urteil: Als bewusste Abweichung vertretbar, kein Blocker.** Begründung:
- Der Zustand ist einmalig. Er gilt nur mit `justCreated` und nur für Verwaltende, Lesende sehen ihn nicht (confluence-leser 262 bzw. 311). Er verschwindet mit „Ausblenden“ oder wenn alle Schritte erledigt sind. Die Schritte sind dort die Hauptaufgabe der Seite und keine Kopflast.
- Auch in der ersten Bildschirmhöhe bleibt genug Inhalt sichtbar: 3 Dokumentzeilen (small) bzw. 4 (scale), dazu Suche und Filter (abschluss__confluence__small__light__320).

**Empfohlen, aber nicht blockierend:** Zwei billige Verdichtungen bringen den Normalfall unter 360 px:
1. „Ausblenden“ rechts in die Zeile „Nächste Schritte“ statt in eine eigene Zeile (≈ −26 px).
2. Bei 320 die erledigte Zeile „✓ Quelle verbunden“ weglassen (≈ −24 px).

Damit käme Confluence/S3 auf ≈ 354, die übrigen Konnektoren auf ≈ 330 und Upload auf ≈ 330. Über dem Budget bliebe dann nur `scale` (≈ 365–410), was mit dem Maintainer-Entscheid zum langen Titel zusammen tragbar ist. Ohne diese Verdichtung muss der REPORT die Abweichung ausdrücklich als Ausnahme von D1 führen. Das tut er bereits unter Kennzahlen, es fehlt aber der Hinweis in den Schwächen.

## Offene Punkte aus Nachcheck 1

| Punkt | Ergebnis | Beleg |
|---|---|---|
| R1-3 aufgeklappt bei 320 | weiter nicht prüfbar (kein Bild) | – |
| R1-4 Upload leer | **erledigt**: nur Ablagefläche, „Hochladen“ im Kopf | dokumente__upload__empty__light__1280, …__320 |
| R1-8 S3 Namen doppelt | **teilweise**: „protokolle / 2025“, „satzungen/“ und darunter „nicht lesbar: satzungen“; SMB/SharePoint mit Markierung ohne Namen. Nicht blockierend | sheets/quelle__partial__light__320__p3 |
| R1-11 privat 257 | **erledigt**: privat 242. Die Ausreißerbilder mit 40-px-Rand sind behoben | dokumente__privat__small__light__320, quelle__smb__partial__light__320 |
| R1-14 „Ausblenden“ grau | **erledigt** (Linkfarbe) | abschluss__confluence__small__light__320 |
| R1-19 Füllgrad 320 | **nicht erledigt** → B1 | |
| R1-23 Fortschritt im Knopf | **nicht erledigt**: „Hochladen“ ohne Fortschritt, Zustand „Läuft ›“. Nicht blockierend (Fortschritt ist über den Steckbrief erreichbar) | upload__upload__slow__light__1280 |
| R1-25 Ordnerkante | **erledigt**: Ordner- und Dokumentnamen an einer Kante, ohne Icon, „Ordner · n Dokumente“, ungekürzt. Rest: Ordner „⋮“ gegen Dokument „⋯“ | dokumente__upload__partial__light__1280, dokumente__upload__scale__light__320 |
| R1-26 Pfadtrenner | bewusst nicht, **tragfähig** (geteiltes Assistenten-Raster) | |
| R1-27 Kopf 320 | **erledigt** nach Maintainer-Entscheid: 242 (normal), 300/322 (`scale`), Titel ungekürzt | sheets/dokumente__scale__light__320__p3 |
| R1-28 ✓/○, Schritte bei 320 | **erledigt** (✓ und ○, bei 320 offen sichtbar). Neuer Fehler → B3 | sheets/abschluss__small__light__320__p1 |
| R1-33/34 Upload-Zustand | **erledigt**: „Teilweise fehlgeschlagen · mind. 13 fehlgeschlagen“, „Kontingent voll · mind. 4 fehlgeschlagen“, Fehlgeschlagene oben. Rest: die zweite Zeile beginnt mit „·“ | dokumente__upload__partial__light__1280, dokumente__upload__failed__light__1280 |
| R1-36 Auflage | im Code berücksichtigt (`curatedSpaceIds`) | AssetSpacesList.tsx |
| R1-42 Kurzzeile hart abgeschnitten | **erledigt** (Aufnahmefehler behoben) | quelle__smb__partial__light__320 |
| R1-43 Ergebnis 1280 | **teilweise, tragfähig**: Das Ergebnis bricht um („· 1 / nicht lesbar“) statt zu kürzen. D12 verlangt eigentlich eine Zeile, die Information geht aber nicht verloren. Fundorte des Dateisystems sind relativ (erledigt) | quelle__s3__partial__light__1280, laeufe__filesystem__scale__light__1280__full |
| R1-44 hängendes „·“ | **nicht erledigt**: „…gserviceaccount.com ·“ am Zeilenende. Nicht blockierend | quelle__gdrive__scale__light__1280 |
| R1-45 Zuordnung einzeilig | **nicht erledigt** → B2 | |
| R1-46/47 Zeilen 320 | **erledigt**: Größe · Status in Zeile 2, Freigabe ohne leere Befristung, kein „· —“, kein „06:02·“ | dokumente__upload__small__light__320, freigaben__sharepoint__small__light__320__full |
| R1-49 | **erledigt**: „Nicht erreichbar“ in der Laufliste | laeufe__rss__failed__light__320 |
| R1-64 | **erledigt**: einer von zwei Bereichen nicht lesbar, manuelle Läufe 09:12. Rest: In SMB hängt „● nicht lesbar“ eingerückt unter /Hauptamt, ohne Namen; welcher Ordner gemeint ist, bleibt unklar | sheets/quelle__partial__light__320__p3 |
| R1-67 Fixtures | teilweise (Upload-Speicher stimmt). „Ordner · 0 Dokumente“, „in 34 Spaces“ und Füllgrad 100 % leer bleiben. Für Videos `small` nutzen | dokumente__upload__small__light__320 |
| Reg. Kachel-/Weißbilder | **erledigt**: 0 weiße und 0 gekachelte Bilder unter 2.112 hellen Dateien (Pixelprüfung) | – |
| Reg. „aus“ ohne Bezug | **erledigt** im Steckbrief („Zeitplan aus“). Im Reiter Quelle steht „aus“ unter der Überschrift Zeitplan, also mit Bezug | |
| Reg. Lücke unter Reitern 320 | **erledigt** | dokumente__privat__small__light__320 |
| Reg. Upload leer 320, Knöpfe | **erledigt** | dokumente__upload__empty__light__320 |
| Reg. „Aufbewahrungsfris/t“ | **erledigt** („Aufbewahrungs-frist“) | metadaten__dropbox__scale__light__1280__full |
| Reg. Freigaben leer mit Suchtext | **erledigt** („Noch keine Freigaben.“). Rest: Darüber steht ein leerer Tabellenkopf „Empfänger · Rolle · Befristung“ | freigaben__web__empty__light__1280 |

## Neue Befunde (Stichprobe alle Reiter, 1280 + 320), nicht blockierend

1. Doppelte Trenner „Teilweise fehlgeschlagen › · 24.10.2026“ im Zustand (1280, alle Arten). „›“ und „·“ stehen direkt hintereinander (abschluss__confluence__scale__light__1280).
2. Reiterpfeil-Fragment bei 320: Links schaut ein Rest des vorigen Reiters heraus („‹ ꞉“, „‹ ›te“) (freigaben__sharepoint__small__light__320__full, sheets/metadaten__small__light__320__p4).
3. Metadaten 320: Zwischen „Aktenzeichen“ und „Kontextpräfix“ stehen zwei Trennlinien mit Leerraum (sheets/metadaten__small__light__320__p4).
4. Die `metrics` melden für `laeufe`@320 negative `tabsTop`-Werte (−564 bis 132), weil die Aufnahme zum aufgeklappten Lauf scrollt. Das ist ein Messartefakt und keine Layoutverletzung, gehört aber im REPORT erwähnt.
5. In der Kopfzeile der Kennzahlen steht `tabsTop` 320 als „234–322“, gemessen sind 242–322 (234 nur im Kontrast-Stichlauf `metrics-extra`).

Keine neuen Überläufe (`overflowX` 0), axe hell 1 × `target-size` (zuordnungen__upload__partial__light__320).

## Für Runde 4 (Minimum)

B1 Füllgrad 320 auf Feld · Leer, B2 Zuordnung einzeilig mit Ellipse und „seit“ in derselben Zeile, B3 ✓ Quelle nur bei verbundener Quelle. Optional die beiden Verdichtungen im Abschluss@320. Danach genügt ein gezielter Nachcheck an den genannten Bildern, eine volle Matrix ist nicht nötig.
