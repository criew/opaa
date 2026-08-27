# Issue #798 — Selbstauskunft und Auskunftsexport für Audit-Daten
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:S, security
- PRs: keine

**Laut Issue:** Aus #239 herausgelöste Betroffenenrechte für Audit-Daten: ein nicht delegierbarer Selbstauskunfts-Endpunkt (jede Person sieht ausschließlich ihre eigenen, über die Pseudonymzuordnung aufgelösten Protokollsätze), ein Export dieser Daten in einem gängigen Format, eine Auskunftsdokumentation (Datenarten, Granularität, Aufbewahrungsdauer) sowie eine eigene Audit-Selbstprotokollierung jedes Selbstauskunfts-Zugriffs. Der Kern der Audit-Governance (Pseudonymisierung, Vier-Augen-Prinzip, Aufbewahrung/Löschung, Selbstprotokollierung) war laut Issue bereits über #391–#395 geliefert.

**Geliefert:** Nichts — das Issue wurde ohne PR als „not planned“ geschlossen. Laut Maintainer-Kommentar (`gh issue view 798 --comments`) ist das eine bewusste Ticket-Hygiene-Entscheidung: Selbstauskunft und Auskunftsexport werden zurückgestellt, nicht verworfen, und sollen bei Bedarf neu bewertet werden — etwa im Zuge einer Dienstvereinbarung oder DSGVO-Konkretisierung. Der Audit-Kern selbst (#391–#395) ist laut Issue-Text unabhängig davon bereits geliefert.

**Verifikation:** Kein Code zu verifizieren, da nichts gemergt wurde. Kein `AuditController`-Endpunkt für Selbstauskunft im heutigen Stand erwartet (nicht separat geprüft, da PR-los und explizit zurückgestellt).

**Themen:** security, audit, dsgvo, backend, zurückgestellt
