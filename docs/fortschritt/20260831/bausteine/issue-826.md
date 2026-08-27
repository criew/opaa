# Issue #826 — refactor: Backend-Architekturreview 2026-08 — Befunde und Behebungsphasen
- Geschlossen: 2026-08-25 (completed)
- Labels: epic, backend, size:L
- PRs: keine (Epic)

**Laut Issue:** Epic, das die Befunde eines Backend-Architekturreviews (sechs parallele Code-Reviews über 16 Pakete, ~34.000 Zeilen) bündelt: Modulzyklen (B1), dezentrale Identität/Autorisierung (B2), manuelle Audit-/Rechtehistorie-Doppelbuchführung (B3), CHECK-Constraint-Enum-Vokabulare (B4), Transaktions-Kartenhaus im Chat-Pfad (B5), globale Dokumentidentität (B6), verstreuter Quellenzugriff (B7), Web-Schicht in der Domäne (B8), Single-Instance-Annahmen ohne Klammer (B9), plus Over-Engineering- und Build/Test-Befunde. Tickets werden bewusst erst phasenweise angelegt, nicht vorab.

**Geliefert:** Kein eigener PR — die Arbeit steckt vollständig in den als Sub-Issues verknüpften Tickets. Phase 1 (Sofortmaßnahmen): #832 (CI-Cache), #833 (lastLoginAt-Drosselung), #834 (Audit-Indizes), #835 (Build-Dedup), #836 (Crawler-Limits), #837 (chunk_index, ohne eigenen PR), #838 (VectorChunkStore), #839 (Proxy-Parsing), #840 (Archivierungsprüfung). Phase 2 (Konventionen): #842 (Kommentar-Konvention), #843 (Test-Kontexte konsolidiert; #844 als Fortsetzung not planned), #845 (Single-Instance-ADR). Phase 3/4 (Querschnitte/Struktur): #860 (DTO-Leak, ohne eigenen PR, siehe dessen Baustein), #862 (CHECK-Constraints ablösen), #875 (Domain-Exceptions), #876 (Quellenzugriff-Paket), #877 (Dokumentidentität scopen), #884 (CurrentUser). Alle Epic-Abnahmekriterien sind laut Issue-Body als erledigt abgehakt.

**Verifikation:** Sub-Issues einzeln geprüft (siehe jeweilige Bausteine). Kein eigener Verifikationsaufwand für das Epic selbst nötig.

**Themen:** architektur, backend, epic, refactoring, technische-schulden
