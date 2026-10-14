# Thema asset

Gemeinsame Schale aller Asset-Typen (Bibliothek, Prompt-Bibliothek): Katalog mit Kacheln und Filtern, Anlegen über den Katalog, Freigaben mit Herleitung des Zugriffs, Eigentum und dessen Übertragung, Favoriten und Zuordnung zu Spaces. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt die Bausteine frei, aus denen die Typen ihre Detail- und Anlegeseiten zusammensetzen (Kopf, Namens-, Eigentums- und Freigabefelder, Freigabe-, Eigentums- und Space-Abschnitt, Kachel und Kachelauswahl), dazu `useCatalogStore`, `useGrantStore`, die Space-Zuordnung aus `assetApi` und den `SpaceListContext`. Typspezifisches gehört in das Thema des Typs.
- **Spec-Tag:** `asset` (`opaa-api/src/main/openapi/assets.yaml`, einschließlich Katalog, Freigaben und Space-Zuordnung).
- **Backend-Modul:** rights (Paket `io.opaa.asset`).
- **Erlaubte Abhängigkeiten:** `principal`, `succession`. Genutzt wird das Thema von `space`, `library`, `prompt` und `external-access`.
