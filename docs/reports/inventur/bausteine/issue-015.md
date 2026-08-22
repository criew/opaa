# Issue #15 — feat(ui): add admin sidebar with indexing controls
- Geschlossen: 2026-02-20 (completed)
- Labels: enhancement, mvp, frontend, size:S
- PRs: #31 (2026-02-20)

**Laut Issue:** Admin-Sidebar getrennt vom Chat-Bereich mit Button „Index Documents", Status-Polling alle 2–3s, Fortschrittsanzeige, Snackbar-Benachrichtigungen bei Erfolg/Fehler, Button während Indizierung deaktiviert.

**Geliefert:** PR #31 liefert genau den beschriebenen Umfang: `AdminDrawer`, `AdminDrawerToggle`, `IndexingSnackbar`, Polling alle 2s, stateful MSW-Mocks für den Trigger-Ablauf. Keine Abweichung vom Issue.

**Verifikation:** `AdminDrawer.tsx`/`AdminDrawerToggle.tsx` existieren im heutigen Code nicht mehr (`frontend/src/components/admin/` enthält nur noch `BrandingPreview.tsx` und `IndexingSnackbar.tsx`). Git-Historie zeigt, dass die Indizierung seit `feat(indexing): Indizierungsläufe auf wählbare Zielbibliothek umstellen` und PR #500 auf ein Bibliotheks-Modell (`/api/v1/libraries/{id}/indexing`) umgestellt wurde — die einfache globale Admin-Sidebar wurde durch die bibliotheksbezogene Verwaltung abgelöst. `IndexingSnackbar` besteht als einziges Überbleibsel fort.

**Themen:** frontend, admin-ui, indexing, mvp
