# Issue #550 — feat(indexing): HTTP_DIRECTORY versteht nur das HTMLTable-Autoindex-Layout
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M
- PRs: #612 (2026-08-20)

**Laut Issue:** Aus dem Review zu PR #549 stammender Befund: `AutoindexCrawlerService.parseDirectory` verstand ausschließlich das Apache-`HTMLTable`-Autoindex-Layout; ein Standard-`mod_autoindex` ohne diese Option lieferte eine `<pre>`- bzw. `<ul>`-Liste, an der der Parser scheiterte. Nutzerwirkung: eine erreichbare Verzeichnisseite meldete „0 unterstützte Dokumente gefunden" ohne Ursachenhinweis. Gefordert (mindestens eines, idealerweise beides): Parser um gängige Layouts (`<pre>`, `<ul>`, ggf. nginx-autoindex) erweitern, sowie Voraussetzung in Doku/UI dokumentieren und die Meldung schärfen.

**Geliefert:** Beides umgesetzt. Der Parser probiert zuerst das HTMLTable-Layout und fällt sonst auf eine linkbasierte Erkennung zurück, die jeden `<a href>` auswertet (Apache `<pre>`, nginx `autoindex on`, einfache `<ul>`-Listen wie Pythons `http.server`). `SourceConnectionTestService` unterscheidet jetzt „erreichbar, aber leeres Verzeichnis" von „erreichbar, aber kein erkennbares Verzeichnislisting". `docs/features/knowledge-sources.md` listet die vier unterstützten Layouts. Der PR-Body vermerkt einen zum Merge-Zeitpunkt bestehenden, unabhängigen Build-Blocker auf `main` (Issue #609), der nur lokal zur Verifikation umgangen wurde, nicht Teil dieses Commits war.

**Verifikation:** `AutoindexCrawlerService.java` enthält 10 Treffer für HTMLTable/linkbasiert/parseDirectory-Logik im Worktree.

**Themen:** indexing, retrieval, library, doku
