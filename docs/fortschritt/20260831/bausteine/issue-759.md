# Issue #759 — feat(models): Administrationsseite Modellverwaltung mit schreibgeschützter Einbettungsübersicht
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #765 (2026-08-22)

**Laut Issue:** Administrationsseite `admin/models` für `SYSTEM_ADMIN` — Liste, Anlegen, Bearbeiten, Löschen, Aktivieren, Verbindungstest je Chat-Modell, dazu ein schreibgeschützter Block zur Einbettungskonfiguration mit Begründung der Unveränderlichkeit. API-Schlüssel nie im Klartext zurückgeben, Löschschutz für das aktive Modell verständlich anzeigen.

**Geliefert:** Route `admin/models` (Sidebar nur für `SYSTEM_ADMIN`), Modellliste mit „Aktiv“-Chip, Anlegen-Dialog und Inline-Bearbeitung, Verbindungstest, „Aktiv setzen“, Löschen mit clientseitig deaktiviertem Button beim aktiven Modell plus serverseitiger 409-Anzeige. Schlüsselfeld zeigt nur gesetzt/nicht gesetzt. Zusätzlich zum Issue-Umfang wurde ein neuer Backend-Endpunkt `GET /api/v1/admin/models/embedding-info` (`EmbeddingInfoService`) ergänzt, weil die bestehende Admin-API dafür noch keinen Endpunkt hatte — im Issue nicht explizit gefordert, aber zur Erfüllung des schreibgeschützten Einbettungsblocks notwendig.

**Verifikation:** `LlmModelManagementPage.tsx`, `LlmModelManagementPage.test.tsx`, `llmModelStore.ts` und `CreateLlmModelDialog.tsx` existieren im Worktree unter `frontend/src/`.

**Themen:** modellverwaltung, frontend, admin-ui
