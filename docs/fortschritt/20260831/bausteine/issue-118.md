# Issue #118 — feat(workspace): document deletion and exclude mechanism
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:M, workspace
- PRs: keine

**Laut Issue:** Löschen manuell hochgeladener Dokumente (Editor: eigene, Admin/Owner: alle) sowie ein Exclude-Mechanismus für Konnektor-Dokumente (Ausschluss statt Löschen, mit Aufhebung durch System-Admin) samt Indexierungs-Integration. Teil von Epic #107, Phase 4.

**Geliefert:** Nichts im Sinne des Issues — nicht umgesetzt. Geschlossen als „completed" ohne PR wegen Ablösung durch das Space-/Asset-Modell (Epic #198). Beide Hälften leben laut Schließungskommentar in anderer Form weiter: Der Konnektor-Ausschluss geht in #207 auf (jetzt an der Bibliothek statt je Workspace), das Löschen manueller Uploads folgt aus den Asset-Rollen in #202 (EDITOR-Rolle an der Bibliothek berechtigt zu Upload und Löschung).

**Verifikation:** Kein workspace-bezogener Exclude-Mechanismus im Code; Rechte für Löschung/Ausschluss laufen über Asset-/Bibliotheksrollen (`io.opaa.library`), konsistent mit dem Schließungskommentar.

**Themen:** workspaces, spaces, wissensbibliothek, konnektoren, migration, verworfen
