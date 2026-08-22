# Issue #712 — feat(demo): Seed-Mechanismus mit den Datenprofilen demo und e2e
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, size:L, auth, demo
- PRs: #724 (2026-08-21)

**Laut Issue:** Ein wiederverwendbarer Seed-Mechanismus soll über die öffentliche API Nutzer, Spaces, Bibliotheken, Rechte, Uploads und Indizierung einrichten, mit zwei Datenprofilen (`demo`: Rheinfurt via Keycloak; `e2e`: minimal via dev-Auth), idempotent, sowie die Compose-Profil-Frage für den `keycloak`-Service klären und begründen.

**Geliefert:** Deckungsgleich — `demo/seed/seed.py` spricht ausschließlich die öffentliche API an, provisioniert Nutzer über deren erste authentifizierte Anfrage, legt Spaces/Bibliotheken/VIEWER-Rechte/Uploads/Indizierung an. Neuer Keycloak-Client `opaa-seed` (Resource-Owner-Password-Grant, getrennt vom Frontend-Client). Entscheidung: `keycloak` zusätzlich dem Compose-Profil `demo` zugeordnet, damit `docker compose --profile demo up` allein genügt — im PR begründet. Idempotenz und VIEWER-Matrix im PR gegen einen isolierten Teststack verifiziert. Bibliotheken bewusst mit `visibility: PRIVATE` statt `ORGANIZATION` angelegt, um die Rechtematrix nicht auszuhebeln.

**Verifikation:** `demo/seed/seed.py`, `demo/seed/profiles.py`, `keycloak/realm-export.json` existieren im Worktree.

**Themen:** demo, seed, auth, keycloak, idempotenz
