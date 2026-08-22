# Issue #471 — test(e2e): RSS-Quelle über die Admin-Oberfläche indizieren
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #510 (2026-08-19)

**Laut Issue:** Ein Playwright-E2E-Test sollte den kompletten RSS-Weg über die Oberfläche prüfen: Quellentyp wählen, Lauf anstoßen, Ergebnis im Bestand sehen — inkl. Positivpfad (Einträge + Anlage), Negativpfad (404 bricht Lauf nicht ab) und zweitem Lauf ohne neue Dokumente. Ein erfundener Beispiel-Feed sollte als statischer Inhalt im Compose-Stack der E2E-Suite ausgeliefert werden.

**Geliefert:** Wie gefordert, mit einer im PR selbst dokumentierten Anpassung: Der Test läuft nicht mehr über den Admin-Drawer (der existierte zum Zeitpunkt der Umsetzung schon nicht mehr, siehe #480/#481), sondern über die Bibliotheksanlage aus Template + „Jetzt indizieren" auf der Detailseite. Positiv- und Fehlerfall laufen bewusst als zwei getrennte Feeds/Bibliotheken, weil der bedingte Feed-Abruf (ETag/304) den Zweitlauf sonst nicht wie im Issue beschrieben (Pro-Eintrag-`pubDate`-Skip) hätte prüfbar gemacht — der Test prüft deshalb nur das gemeinsame Ergebnis (`documentCount: 0`).

**Verifikation:** `e2e/tests/rss-feed-library.spec.ts` existiert. Die im PR beschriebenen Fixtures lagen ursprünglich unter `e2e/fixtures/rss-feed/htdocs/`; heute liegen sie unter `demo/seed/e2e-data/rss-feed/htdocs/` (`feed-ok.xml`, `feed-error.xml`, `anlagen/`, `seiten/` bzw. `webverzeichnis/` vorhanden) — ein späterer Umbau hat die E2E-Fixtures und die Demo-Korpus-Daten zusammengeführt, ohne die Funktionalität zu ändern. `e2e/docker-compose.e2e.yml` bindet den `rss-feed`-Service weiterhin ein.

**Themen:** e2e, feeds, retrieval, ci
