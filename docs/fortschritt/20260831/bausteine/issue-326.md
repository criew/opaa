# Issue #326 — ADR-Bestand entschlacken: ADR-0001 und ADR-0002 aktualisieren, ADR-0008 in die Spezifikation überführen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:M
- PRs: #327 (2026-08-14)

**Laut Issue:** Drei Abweichungen zwischen ADR-Bestand und Realität: ADR-0001 beschreibt den Workflow noch für ein einzelnes KI-Werkzeug statt der tatsächlichen Mehrwerkzeug-Struktur (Claude Code, Codex, OpenCode, Copilot mit gemeinsamen Rollenverträgen); ADR-0002 nennt Keycloak nicht und spricht von drei statt vier Compose-Containern; ADR-0008 dupliziert zur Hälfte `docs/features/spaces-and-assets.md` und soll bis auf Systemvergleich und verworfene Alternativen entfallen.

**Geliefert:** PR #327 aktualisiert ADR-0001 (Mehrwerkzeug-Struktur, Verweis auf AGENT-ORGANIZATION.md), ADR-0002 (Keycloak/OAuth2 im Stack, vier statt drei Container, Versionsangaben gegen die Versionsdateien geprüft) und entfernt ADR-0008 vollständig; Systemvergleich und verworfene Alternativen sind als neuer Abschnitt in `spaces-and-assets.md` gewandert. Alle sechs Verweise auf ADR-0008 in fünf Dokumenten wurden umgehängt. Zusätzlich wurde `agents/roles/developer.md` korrigiert (nannte noch „Spring Boot 3.5"). Deckt sich mit dem Issue.

**Verifikation:** `docs/decisions/0008-space-and-asset-model.md` existiert im Worktree nicht mehr (bestätigt).

**Themen:** doku, adr, agenten-organisation
