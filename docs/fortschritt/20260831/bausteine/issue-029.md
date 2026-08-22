# Issue #29 — feat: Add user document upload with personal workspace and cross-workspace sharing to product vision
- Geschlossen: 2026-02-20 (completed)
- Labels: documentation, enhancement, size:S
- PRs: #30 (2026-02-20)

**Laut Issue:** Reine Dokumentationserweiterung der Produktvision um einen neuen Use Case: nutzergesteuerter Dokumenten-Upload mit persönlichem Workspace („My Documents") und arbeitsbereichsübergreifender Freigabe ohne Duplizierung (zusätzliche `workspace_id`-Tags statt Kopien). Sechs Dokumente sollten konsistent aktualisiert werden (VISION.md, CONCEPTS.md, INDEX.md sowie drei Feature-Specs).

**Geliefert:** PR #30 aktualisiert genau die sechs genannten Dateien konsistent: Personal-Workspace-Konzept, Cross-Workspace-Sharing, Storage-Backend-Abstraktion, neue API-Endpunkte (Upload/Share/my-uploads) als Dokumentation. Reine Konzeptarbeit, keine Implementierung — wie im Issue vorgesehen.

**Verifikation:** `docs/VISION.md` und `docs/CONCEPTS.md` existieren weiterhin. Das Konzept „Workspace" wurde im Projekt später zu „Space" umbenannt (`feat(space)!: Workspace in Space umbenennen, Organisationsgrenze und neue Space-Rollen einführen`), die hier dokumentierten Konzepte (persönlicher Bereich, Freigabe, Storage-Backend) leben in der heutigen Space-/Library-Architektur fort, wenn auch unter neuer Terminologie. Kein Abgleich der Feindetails gegen den heutigen Stand vorgenommen (reine Vision-Doku, kein Code-Bezug).

**Themen:** dokumentation, vision, spaces, upload
