# Thema library

Bibliotheken: Anlegen mit Quelle und Zeitplan, Detailseite mit Dokumenten, Metadaten, Quellverbindung, Indexierung und Freigaben, die Quellformulare je Konnektor, der Rückweg aus der Kontoanbindung (`ConnectionCallbackPage`), Diagnosesperre und privater Speicher. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Seiten unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt die Stores (`useLibraryStore`, `useDocumentStore`, `useIndexingStore`), die Dokumentvorschau (`useDocumentPreview`, `DocumentTextPreviewDialog`), `PrivateStorageSection` für die Einstellungen sowie die Bausteine frei, die Chat und Suchverwaltung brauchen.
- **Spec-Tags:** `library` (`libraries.yaml`), `library-metadata`, `indexing`, `diagnostic-access` (je gleichnamige Datei unter `opaa-api/src/main/openapi/`).
- **Backend-Module:** library (Paket `io.opaa.library`), knowledge (Pakete `io.opaa.indexing`, `io.opaa.metadata`), workspace (Paket `io.opaa.diagnosticaccess`).
- **Erlaubte Abhängigkeiten:** `asset`, `principal`, `succession`, `connection`, `external-access`.
- **Genutzt von:** `chat`, `search-admin`, `app`.
