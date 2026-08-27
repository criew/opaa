# Issue #451 — fix(audit): Schutz gegen Fluten der Protokollablage durch wiederholte abgewiesene Audit-Zugriffe
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, security
- PRs: keine

**Laut Issue:** PR #450 (#394) lässt jeden — auch abgewiesenen — Zugriffsversuch auf `/api/v1/audit/events/*` einen `DENIED`-Eintrag schreiben, unabhängig von der Rolle des Aufrufers. Ein Skript mit gewöhnlichem Nutzerkonto kann dadurch beliebig viele unlöschbare `DENIED`-Einträge erzeugen und das Protokoll fluten. Gefordert war ein Rate-Limit oder eine Zusammenfassung wiederholter Versuche desselben Kontos/Zeitfensters.

**Geliefert:** Nichts. Sub-Issue von Epic #457 (Phase 2), bewusst zurückgestellt zusammen mit den übrigen Nacharbeiten.

**Verifikation:** Kein Grep-Treffer im Zeitrahmen dieser Prüfung auf ein Rate-Limit für die Audit-Lese-Endpunkte; keine tiefergehende Prüfung vorgenommen, da als "not planned" geschlossen und laut Epic-Kommentar unverändert offen.

**Themen:** security, audit, backend
