# Thema capability

Berechtigungen der Installation: Übersicht, wer welche Fähigkeit nutzen darf (alle Konten, ausgewählte Konten und Gruppen, nur Administration), und deren Änderung. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt die Zugriffsbausteine (`AccessBadge`, `AccessEditor`, `capabilityAccess`, `confirmAccess`/`applyAccess`), `presentationOf` und `getCapabilityOverview` frei, mit denen die Verbindungsverwaltung den Zugriff je Konnektortyp und Profil zeigt und ändert.
- **Spec-Tag:** `capability` (`opaa-api/src/main/openapi/capabilities.yaml`).
- **Backend-Modul:** rights (Paket `io.opaa.permission`).
- **Erlaubte Abhängigkeiten:** `principal`. Genutzt wird das Thema von `connection`.
