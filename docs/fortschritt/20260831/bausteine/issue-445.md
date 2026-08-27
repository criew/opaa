# Issue #445 — Berechtigungsunabhängige Nutzersuche für die Rechtevergabe (Grants)
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, backend, size:S, auth
- PRs: keine im Chunk verknüpft — tatsächlich geliefert über #778 (2026-08-23)

**Laut Issue:** Ein `MANAGER` ohne Systemrolle kann in der Rechteverwaltung einer Wissensbibliothek keine Personen auswählen, um ihnen eine Freigabe zu erteilen, weil `GET /api/v1/admin/users` administrativ geschützt ist. Gefordert war ein berechtigungsunabhängiger Endpunkt (analog `GET /api/v1/me/groups`), der angemeldeten Nutzern eine Suche/Liste von Personen der eigenen Organisation erlaubt, sowie die Umstellung von `LibraryGrantsDialog` darauf.

**Geliefert:** Die Verknüpfung im Chunk-Datensatz ist unvollständig — der Issue-Datensatz selbst trägt keinen PR. Laut Abschlusskommentar von Epic #458 ("Zuletzt geliefert: #445 … erledigt durch #777/#778") und eigener Prüfung: PR #778 ("fix(workspace): Mitgliederauswahl für alle Nutzer, Standard-Space-Formular, Eigentümer-Badge", gemergt 2026-08-23) hat `UserSearchController` (`GET /api/v1/users`) eingeführt — org-beschränkte Personensuche, nur `id`/`email`/`displayName`, serverseitig gefiltert (min. 2 Zeichen, max. 20 Treffer). `LibraryGrantsDialog` nutzt seither `useUserSearch`/`getUserSummaries`; der Freitext-UUID-Fallback bleibt nur als Ausweichlösung für Fehlerfälle. #777 ist im Repo keine PR-Nummer (vermutlich ein Issue oder Tippfehler im Kommentar).

**Verifikation:** `backend/src/main/java/io/opaa/auth/UserSearchController.java` existiert im Worktree. `frontend/src/components/LibraryGrantsDialog.tsx` importiert `useUserSearch` und `getUserSummaries` (Zeile 38, 114).

**Themen:** auth, spaces, rechtevergabe, frontend
