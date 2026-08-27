# Issue #240 — Nachfolge statt Sperre: Assets ausgeschiedener Eigentümer
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, size:M, auth
- PRs: keine

**Laut Issue:** Ein Konto muss immer sofort deaktivierbar sein, auch wenn es Assets besitzt. Dessen Assets gehen in den Zustand "Nachfolge offen": nutzbar, Rechte unverändert, aber Reichweite eingefroren (keine neuen Grants, keine höhere Freigabestufe). Benannter Adressat und Frist, mit Eskalation nach oben.

**Geliefert:** Nicht umgesetzt. Aktualisierung (23.08.2026): Die im Issue vorgesehene Zuständigkeit "Kurator der Organisationseinheit" existiert seit #330 nicht mehr (Kuratorenrollen gestrichen); zuständig wäre stattdessen der System-Admin über seine Governance-Arbeitsliste, wie es andere Bausteine (#204, #209) bereits vorsehen — die Abhängigkeit "Kuratorenrollen an Organisationseinheiten" entfällt damit ersatzlos. Geschlossen im Zuge der Schließung von Epic #198 als Ticket-Hygiene-Maßnahme.

**Verifikation:** Nicht separat geprüft.

**Themen:** auth, spaces, rechteverwaltung, governance
