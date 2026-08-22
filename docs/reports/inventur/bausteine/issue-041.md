# Issue #41 — feat(frontend): Loading-Indicator während Dokument-Indizierung
- Geschlossen: 2026-02-27 (completed)
- Labels: enhancement, mvp, frontend
- PRs: #52 (2026-02-27)

**Laut Issue:** Während der Dokument-Indizierung fehlte jegliches visuelles Feedback. Gefordert war ein Loading-Indicator (Spinner/Progress-Bar) mit klaren Zuständen Idle → Indizierung läuft → Abgeschlossen/Fehler, basierend auf einem vom Backend abgefragten Indizierungsstatus.

**Geliefert:** PR #52 liefert dies zusammen mit Issue #44 (Backend-Async-Umbau) in einem gemeinsamen PR: bestimmter Progress-Bar ("X von Y Dokumenten indiziert") in einer neuen `AdminDrawer`-Komponente, gespeist aus dem neu asynchron gewordenen Backend-Indizierungsstatus (inkl. Job-Tracking, HTTP 202/409). Deckt damit sowohl Frontend- als auch Backend-Seite ab.

**Verifikation:** `AdminDrawer.tsx` existiert im heutigen Code nicht mehr (`find` liefert nichts); an seiner Stelle steht heute `frontend/src/components/admin/IndexingSnackbar.tsx`. Auf Backend-Seite ist die Indizierung inzwischen auf ein bibliotheksbezogenes Modell umgestellt (`git log` zeigt u.a. #500 "Indizierungsanstoß je Bibliothek", #473 Executor-Registry) — die Grundidee (asynchrones Feedback zum Indizierungsfortschritt) lebt fort, nur in umgebauter Form.

**Themen:** frontend, indexing, progress-feedback, admin-ui
