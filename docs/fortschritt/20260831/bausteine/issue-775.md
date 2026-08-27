# Issue #775 — Demo-Seed: Space↔Bibliothek-Zuordnungen mit ausliefern
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, size:S, demo
- PRs: #776 (2026-08-23)

**Laut Issue:** Der Demo-Seed legte Spaces und Bibliotheken zwar an, verknüpfte sie aber nicht — eine frische Demo-Installation konnte das Feature „Space↔Bibliothek-Zuordnung als Kuratierung" (#706) nicht zeigen. Gefordert war, `SpaceDef` um referenzierte Bibliotheken zu erweitern, einen idempotenten Seed-Schritt über `POST /v1/spaces/{spaceId}/libraries` zu ergänzen und die konkrete Zuordnung gemäß Rechtematrix des Drehbuchs umzusetzen (u. a. „Amtsleitung Bürgerbüro" mit allen fünf Bibliotheken, „Maria Weber – persönlich" bewusst ohne Zuordnung).

**Geliefert:** Wie gefordert. `SpaceDef` führt `library_names`, ein neuer Seed-Schritt 5/6 legt die Zuordnungen über die Session des jeweiligen Space-Eigentümers an (CURATOR-Schwelle plus VIEWER auf der Bibliothek), Idempotenz nutzt das bestehende 201-bei-Konflikt-Verhalten der API. `demo/README.md` und `docs/demo-walkthrough.md` wurden nachgezogen. Das E2E-Profil blieb bewusst unverändert (leeres `library_names`-Default).

**Verifikation:** `demo/seed/profiles.py` enthält `library_names` (4 Fundstellen im Worktree).

**Themen:** demo, spaces, seed, doku
