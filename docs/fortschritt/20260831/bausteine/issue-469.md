# Issue #469 — feat(admin): Quellentyp im Indizierungsformular wählbar machen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:S
- PRs: keine

**Laut Issue:** Im Indizierungsformular (`AdminDrawer.tsx`) sollte der Quellentyp explizit wählbar werden statt implizit aus der Belegung des Adressfelds abgeleitet zu werden — mit Dateisystem als Voreinstellung, typabhängig eingeblendeten Feldern, neu erzeugten OpenAPI-Typen und einem Erklärtext je Typ.

**Geliefert:** Kein PR verknüpft. Der Umbau wurde nicht in dieser Form umgesetzt, sondern durch die Ein-Typ-Regel aus ADR-0018 (#475) strukturell überholt: Der Epic-Text zu #486 vermerkt ausdrücklich „#469 wird umformuliert" — die Typauswahl wandert von einem einmaligen Anstoß-Formular im Admin-Drawer in die Bibliotheksanlage selbst (#480, PR #498) und die Bibliotheksdetailseite (#481, PR #506). Der Admin-Drawer samt Indizierungsabschnitt wurde mit #481 vollständig entfernt. Das Issue ist also nicht direkt geliefert, sondern durch ein umfassenderes Modell ersetzt worden, das dieselbe Nutzerabsicht (Typklarheit vor dem Lauf) an anderer Stelle löst.

**Verifikation:** `frontend/src/components/admin/AdminDrawer.tsx` existiert im heutigen Code nicht mehr (entfernt mit PR #506, `git log` bestätigt). Die Typauswahl findet sich stattdessen in `frontend/src/pages/LibraryCreatePage.tsx` (Nachfolger von `CreateLibraryDialog.tsx`) und der Bibliotheksdetailseite `frontend/src/pages/LibraryDetailPage.tsx`.

**Themen:** retrieval, indexing, admin-oberfläche, ersetzt-durch-epic
