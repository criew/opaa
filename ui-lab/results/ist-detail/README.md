# Ist-Stand Detailansicht (Variante F) – Matrix und Videos

Aufnahme mit `e2e/ui-lab/capture.mjs --flow library-detail` gegen das Frontend im Mock-Modus, nach den Korrekturen aus `REVIEW-1.md` (F-Befunde).

## Bilder

`<bereich>__<art>__<szenario>__<schema>__<breite>[__full].jpg`

| Achse | Werte |
|---|---|
| Bereiche | `dokumente`, `quelle`, `laeufe`, `metadaten`, `freigaben`, `zuordnungen`, `upload` |
| Arten | `upload`, `filesystem`, `web`, `rss`, `confluence`, `sharepoint`, `nextcloud`, `smb`, `s3`, `gdrive`, `dropbox` |
| Sonderfälle | `privat`, `upload-leser`, `confluence-leser`, `nextcloud-leser` in `small`, `scale` und `failed` |
| Szenarien | `small`, `scale`, `failed`, `empty`, `partial`, `slow` (= ein Lauf läuft) |
| Schemata und Breiten | hell und dunkel, 1280 und 320 px |

- `laeufe` ist der Reiter Quelle, zu den Läufen gescrollt, mit aufgeklapptem neuesten Lauf.
- `upload` zeigt drei hochgeladene Dateien; im Szenario `failed` scheitern sie alle am Kontingent.
- `__full` (ganze Seite, halbe Breite) gibt es nur für hell; bei 16 000 px ist die Aufnahme abgeschnitten.

Die Kennzahlen stehen in `metrics.jsonl`, die der Sonderfälle in `metrics-extra.jsonl`. Wenn ein Zustand fehlt, ist das so gewollt: Leser haben keine Läufe und kein Upload-Feld.

## Videos (`videos/`, 1280×800, hell)

Je Reiter (`kopf` = Kopf und der Bereich über den Reitern) wird derselbe Reiter für mehrere Quellarten nacheinander gezeigt. Die Fälle sind `ok` (`small`), `viele` (`scale`) und `fehler` (`failed`). Ablauf: `e2e/ui-lab/flows/library-detail-videos.mjs`.

| Reiter | Quellarten | Länge ok · viele · fehler (s) |
|---|---|---|
| kopf | Upload, Dateisystem, RSS, Confluence, SharePoint, Nextcloud, S3, Dropbox | 56 · 67 · 57 |
| dokumente | Upload, Confluence, Nextcloud (Ordner, Suche; fehler: Upload am Kontingent) | 26 · 38 · 28 |
| quelle | Dateisystem, Confluence, SharePoint, S3 (Umfang „Ändern“) | 44 · 50 · 44 |
| laeufe | Confluence, Nextcloud, RSS (neuester und vierter Lauf aufgeklappt, Ereignisse) | 29 · 46 · 28 |
| metadaten | Upload, Confluence | 16 · 21 · 16 |
| freigaben | Upload, SharePoint | 15 · 21 · 15 |
| zuordnungen | Upload, Confluence | 15 · 21 · 16 |

## Review

`REVIEW-1.md` enthält das Konsistenz-Review mit 6 Prüfern und 47 Befunden. Die Auswertung steht in `ui-lab/inputs/inventory-detail.md`.
