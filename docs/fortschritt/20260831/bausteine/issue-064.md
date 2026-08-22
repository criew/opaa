# Issue #64 — 🚨 [CRITICAL] Missing conversationId Input Validation
- Geschlossen: 2026-02-28 (completed)
- Labels: bug, backend, size:S, security
- PRs: #79 (2026-02-28)

**Laut Issue:** `conversationId` in `QueryService` wurde ohne Validierung übernommen — Risiko für Memory Exhaustion und (bei künftiger Persistierung) Injection. Gefordert: Regex-Validierung `^[a-zA-Z0-9-]{1,50}$` mit klarer Fehlermeldung und Tests.

**Geliefert:** PR #79 validiert `conversationId` exakt mit dem vorgeschlagenen Regex in `QueryService` und `MockQueryController`, liefert 400 bei ungültigem Format über `GlobalExceptionHandler`, inkl. Unit- und Integrationstests (auch gegen XSS/SQLi/Path-Traversal-Payloads).

**Verifikation:** Der heutige `QueryService` verwendet `conversationId` als String-Parameter nicht mehr — die Query-API wurde im Zuge der Chat-/Workspace-Einführung auf typisierte `chatId` (UUID) umgestellt (`query(String question, UUID chatId, UUID currentUserId, ...)`), wodurch die ursprüngliche Regex-Validierung gegenstandslos wurde: eine UUID ist durch den Typ selbst validiert. Der damalige Fix ist damit nicht mehr im Code sichtbar, aber das zugrunde liegende Risiko (freiform String als Schlüssel) ist durch die Typänderung strukturell mit erledigt.

**Themen:** security, input-validierung, backend, query
