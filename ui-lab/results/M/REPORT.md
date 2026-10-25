# Variante M (Mischung, Gerüst A) – Bericht (25.10.2026)

Branch `feature/3084_ui-lab-detail-var-m`, abgezweigt vom Endstand A (Runde 4, `97b61b3`). Matrix: alle 11 Arten × `small scale failed empty partial slow` × 1280/320, nur hell, dazu `privat`, `upload-leser`, `confluence-leser`, `nextcloud-leser` in `small scale failed`. Neuer Bereich `abschluss` (Seite mit `?justCreated=1`). Bilder `<bereich>__<art>__<szenario>__light__<breite>[__full].jpg`, Kennzahlen in `metrics.jsonl` und `metrics-extra.jsonl`.

## Konzept

Alles wie A (eine Spalte 720 px, ein Zustandswort aus `libraryState.ts`, nichts zwischen Kopf und Reitern, `DetailSection`/`ListSearch`/`RowMenu`, Läufe als Tabelle im Reiter Quelle), dazu:

| Muster | Herkunft | Umsetzung in M |
|---|---|---|
| Titelstufe | C | Titel 20/28 px ab 600 px (eine Zeile, Tooltip), 17/22 px darunter, ungekürzt mit Silbentrennung, **keine reservierten Leerzeilen** |
| Kompakte Zustandszeile bei 320 px | C | Unter 600 px steht nur „● Wort · Zusatz“ mit einem Umschalter ⌄; die Kennzahlen (Bestand, Spaces, Eigentümer) auf Wunsch darunter. Ab 600 px unverändert eine Kennzahlzeile mit dem Wort am Ende |
| Nächste Schritte nach dem Anlegen | C | Nur mit `?justCreated=1` (Übergabe des Assistenten), unter der Reiterleiste: „Zugang prüfen“ zuerst, „Quelle verbunden ✓“ erst nach einem Lauf, der die Quelle gelesen hat, „In Space verwenden“, „Live-Abgleich einrichten“ (Confluence, S3), „Feld anlegen“, Upload „Dateien hochladen“. „Ausblenden“ für immer; alles erledigt → Block weg. Bei 320 px nur die offenen Schritte |
| Zeilen Titel · Wert · Textaktion für Einzelwerte | C | `ValueRow` im Raster des Reiters Quelle: Eigentümer (Übergeben), Suchdiagnose (Sperren/Entsperren), Herleitung (Anzeigen), Kontextpräfix (Ändern), Extraktion (Ändern). Ersetzt die Abschnitte mit eigener Überschrift |
| „Abgelehnte Dateien anzeigen“ | C | „Schließen“ faltet die Upload-Meldung zu „Abgelehnte Dateien anzeigen (n) · Verwerfen“ an derselben Stelle, bis zum nächsten Upload |
| Filter „Nur mit Fehlern (n)“ | B | „Alle · Nur mit Fehlern (n)“ (derselbe Umschalter wie in Freigaben) über der Laufliste, nur wenn er sie kürzt |
| „Verbindung ändern ›“ im Laufdetail | B | Ein an der Verbindung gescheiterter Lauf (Anmeldung abgelehnt, Nicht erreichbar, Zustimmung abgelaufen) ist wieder aufklappbar und führt mit „Verbindung ändern ›“ zur Zeile Verbindung |

Nicht übernommen (Juroren „nicht übernehmen“): Statusband und Reiter Verlauf (B), Steckbrief-Spalte und Link-Spalte (C), „Entziehen“ je Zeile (C), „mind.“, „Hand“, „Neuestes“.
