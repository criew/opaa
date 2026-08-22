# Issue #75 — 🔵 [LOW] Axios Error Response Type Assertion Unsafe
- Geschlossen: 2026-03-03 (completed)
- Labels: bug, frontend, size:S
- PRs: #94 (2026-03-03)

**Laut Issue:** `normalizeError()` in `frontend/src/services/api.ts` nutzte eine ungesicherte Typ-Assertion (`as ErrorResponse`) auf `err.response?.data` — bei nicht-JSON-Fehlerantworten (z. B. HTML-Fehlerseiten von Nginx/Spring) potenziell fehleranfällig. Gefordert: Type Guard, Fallback-Kette, Tests.

**Geliefert:** PR #94 ergänzt einen `isErrorResponse`-Type-Guard und eine Fallback-Kette (JSON-Fehler → HTTP-Status → Netzwerkfehler) mit 3 Tests für die genannten Szenarien. Deckt die Forderung ab.

**Verifikation:** Nicht erneut im Detail geprüft — reines Low-Priority-Frontend-Fix ohne strukturelle Tragweite; kein Hinweis in späteren PR-Änderungen auf einen Rückbau.

**Themen:** frontend, typsicherheit, error-handling
