# Issue #481 — feat(frontend): Bibliotheksdetailseite mit typspezifischem Bereich
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #506 (2026-08-19)

**Laut Issue:** Eine neue Route `/libraries/:id` sollte Stammdaten, Freigaben, Dokumentliste und Dokumentzahl bündeln; `UPLOAD` zeigt Upload-Zone, Konnektortypen zeigen Konfiguration + „Jetzt indizieren" + Status; `DocumentsPage` und der Indizierungsabschnitt im Admin-Drawer sollten entfallen.

**Geliefert:** Wie gefordert. Neue Route `/libraries/:libraryId`, `DocumentsPage`, `AdminDrawer` und `AdminDrawerToggle` entfernt (waren ausschließlich Träger dieses einen Abschnitts). Zusätzlich zum Issue-Umfang: `sourceType` wurde in `LibraryListResponse` ergänzt (schließt die in #480 offen benannte Lücke). Ein offener Punkt wurde bewusst nicht gelöst: ob Zugangsdaten hinterlegt sind, zeigt die Seite nur als erklärenden Hinweistext, kein boolesches Flag — um nicht mit der parallel laufenden Verschlüsselung (#483) zu kollidieren.

**Verifikation:** `frontend/src/pages/LibraryDetailPage.tsx` existiert und ist die zentrale Detailseite; `DocumentsPage.tsx` und `components/admin/AdminDrawer.tsx` existieren im heutigen Code nicht mehr (bestätigt per `git log --follow`, letzte Commits zeigen den Entfernungs-Commit `fc9eeb5a`). Route und Ablösung sind also weiterhin Stand der Dinge.

**Themen:** frontend, spaces, retrieval, adr
