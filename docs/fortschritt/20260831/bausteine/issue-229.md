# Issue #229 — feat(demo): Rheinfurt-Korpus und RSS-Feed im Compose-Stack bereitstellen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, setup, size:M, demo
- PRs: #722 (2026-08-21)

**Laut Issue:** Der Rheinfurt-Demo-Korpus wird im Docker-Compose-Stack über einen statischen Webserver plus RSS-Feed bereitgestellt, indiziert über die **bestehenden** Konnektoren (`AutoindexCrawlerService`, RSS-Konnektor), ohne neuen Ingestion-Code. Drei `HTTP_DIRECTORY`-Unterverzeichnisse, Feed samt HTML-Detailseiten auf demselben Host, Compose-Profil `demo`, Allowlist-Eintrag für die Zielprüfung (#267). Das Ticket ersetzt eine frühere Fassung, die noch auf dem Superhelden-Eval-Korpus per Bind-Mount formuliert war.

**Geliefert:** Zwei neue Compose-Services (`demo-corpus`, `demo-presse`, beide `httpd:2.4-alpine` mit `IndexOptions FancyIndexing HTMLTable`) unter Profil `demo`; `demo-presse` unter eigenem Netzwerk-Alias `presse.stadt-rheinfurt.example`. Allowlist-Wert wandert nach Review von `docker-compose.yml` in `.env.docker` (Betreiberangabe), da `environment:` sonst jede eigene Einstellung überschrieben hätte. Bei der Verifikation gegen den echten Stack wurden zwei **vorbestehende** Bugs im `AutoindexCrawlerService` gefunden und mitbehoben, weil sie die geforderte vollständige Indizierung blockierten: gekürzte Dateinamen im `HTMLTable`-Layout wurden aus dem sichtbaren Linktext statt aus `href` gelesen, und ein literales `+` in Dateinamen wurde fälschlich zu Leerzeichen decodiert; dazu eine DB-Check-Constraint-Migration (057), die `FORMAT_MISMATCH` als Ereigniskategorie zuließ. Alle drei mit eigenem Reproduktionsnachweis.

**Verifikation:** `docker-compose.yml` enthält `demo-corpus`/`demo-presse`; `demo/webserver/httpd-demo-autoindex.conf` sowie `demo/README.md` existieren. `backend/src/main/java/io/opaa/indexing/AutoindexCrawlerService.java` und die Migration `057-widen-indexing-run-event-category-format-mismatch.yaml` sind im heutigen Code vorhanden.

**Themen:** demo, deployment, indexing, docker-compose, bugfix
