# Issue #693 — fix(indexing): Upgrade-Redirect http→https auf demselben Host wird fälschlich als fremder Host abgewiesen
- Geschlossen: 2026-08-21 (completed)
- Labels: bug, backend, size:S, security
- PRs: #699 (2026-08-21)

**Laut Issue:** Produktionsbefund: RSS-Lauf der Bibliothek "Düsseldorf Pressedienst" scheiterte komplett (0 verarbeitet, 13 übersprungen), weil die Redirect-Härtung aus #651 einen harmlosen `http→https`-Upgrade-Redirect auf demselben Host als fremde Origin wertete (`sameOrigin` vergleicht auch das Schema). Gefordert: Upgrade bei gleichem Host/Standardport zulassen, Downgrade weiter verbieten, Credentials nach dem Upgrade weiter senden.

**Geliefert:** PR #699 behebt #693 wie gefordert (`isRedirectOriginTrusted`-Ausnahme für den Schema-Upgrade bei gleichem Host/Port) und bündelt dabei zusätzlich zwei benachbarte, im selben Arbeitsstrang koordinierte Vorgänge: #267 (SSRF-Zielprüfung `TargetAddressValidator` gegen Loopback/Link-Local/private Bereiche) und #617 (Zugangsdaten-Fallback erzwingt jetzt den gespeicherten Proxy/`insecureSsl` der Bibliothek statt den des Aufrufers). Beide sind eigenständige Issues außerhalb dieses Chunks — hier nur als Kontext vermerkt, nicht als Lieferung für #693 selbst gewertet. Reproduktionsnachweis für alle drei Teile im PR mit rotem/grünem Lauf dokumentiert.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/TargetAddressValidator.java` und `AutoindexCrawlerService.java` existieren im Worktree.

**Themen:** indexing, security, ssrf, redirect-härtung, rss
