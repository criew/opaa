# Issue #241 — Befristung und Rezertifizierung von Einzelgrants
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:M, security
- PRs: keine

**Laut Issue:** Die Freigabekette erzeugt Einzelgrants an Personen, die über Jahre niemand zurücknimmt. Gefordert: optionale Befristung je Grant mit automatischem Verfall, ein Rezertifizierungslauf des Eigentümers, Anzeige ungenutzter Grants, und Rücknahme einzelner Grants ohne die Agentenfreigabe insgesamt zu widerrufen.

**Geliefert:** Teilweise als Datenmodell. Laut Statuskommentar zu Epic #198 (23.08.2026) existiert bereits `AssetGrant.expiresAt` mit Verfallswirkung als Datenmodell-Teilstück. Der eigentliche Kern — Rezertifizierungslauf, Anzeige ungenutzter Grants, gezielte Rücknahme ohne Gesamtwiderruf — ist laut demselben Kommentar ("Rest: Rezertifizierung") nicht geliefert. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme.

**Verifikation:** Nicht separat geprüft (kein Grep auf `AssetGrant`/`expiresAt` durchgeführt); Aussage stützt sich auf den Epic-Statuskommentar.

**Themen:** rechteverwaltung, security, agenten
