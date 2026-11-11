# Thema search-admin

Suchverwaltung: Suchstatus je Bibliothek, Suchdiagnose mit Abrufprotokoll und deutscher Übersetzung der Protokollzeilen, Chunk-Inspektion, Metadaten- und Kontextpräfix-Nachlauf, Neu-Einbettung des Vektorindex. Regeln für alle Themen: [frontend/AGENTS.md](../../../AGENTS.md).

- **Grenze:** Die Seite unter `pages/` erreicht nur `app` über `routes.ts`. `index.ts` gibt nichts frei; das Ende eines Indizierungslaufs meldet der Indizierungsstore von `library` über `notify`. `utils/retrieval-note-templates.json` ist der Export von `RetrievalNoteTest` im Backend, das die Datei an diesem Ort sucht.
- **Spec-Tags:** `search-admin` (`opaa-api/src/main/openapi/search.yaml`), dazu die Aufrufe unter `/api/v1/admin/indexing/*` des Tags `indexing` (`indexing.yaml`).
- **Backend-Modul:** assistant (Paket `io.opaa.searchadmin`), für `/admin/indexing/*` knowledge (Paket `io.opaa.indexing`).
- **Erlaubte Abhängigkeiten:** `library`.
- **Genutzt von:** kein anderes Thema.
