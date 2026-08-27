# Issue #447 — fix(audit): DENIED-Erfassung auf weitere Ablehnungspfade in AssetGrantService ausweiten
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, security
- PRs: keine

**Laut Issue:** #392 (PR #444) hat `outcome=DENIED` nur für den Fall verdrahtet, den die Spezifikation wörtlich nennt (Selbsterhöhungsversuch in `AssetGrantService#upsertGrant`). Gefordert war, weitere Ablehnungspfade (403 aus `requireManageable`, 403/409 aus `revokeGrant`, 409-Pfad aus `upsertGrant`s Last-OWNER-Schutz) ebenfalls mit `DENIED`-Einträgen zu versehen, samt Tests je Pfad.

**Geliefert:** Nichts. Sub-Issue von Epic #457 (Phase 2), zusammen mit den übrigen Nacharbeiten bewusst zurückgestellt (Epic-Abschlusskommentar: "bekannt, aber ohne offene Tickets, bis das Thema wieder ansteht").

**Verifikation:** `grep -n "DENIED" backend/src/main/java/io/opaa/library/AssetGrantService.java` liefert 5 Treffer — konsistent mit dem im Issue beschriebenen Ist-Stand (nur der eine Selbsterhöhungsfall), keine Ausweitung erkennbar.

**Themen:** security, auth, audit, spaces
