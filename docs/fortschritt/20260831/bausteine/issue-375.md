# Issue #375 — fix(indexing): Dateisystem- und Netzindizierung führen unterschiedliche Endungslisten
- Geschlossen: 2026-08-14 (completed)
- Labels: bug, backend, size:S
- PRs: #405 (2026-08-14)

**Laut Issue:** `DocumentService` (Dateisystem/Upload) und `UrlIndexingExecutor` (Netz) ließen unterschiedliche Dateitypen zu (`.doc` nur im Netzweg). Verlangt: gemeinsame, an einer Stelle geführte Festlegung, Entscheidung ob auf Inhaltserkennung umgestellt wird, abgewiesene Dokumente werden gemeldet statt still übersprungen.

**Geliefert:** Neue Klasse `SupportedDocumentFormats` als einzige Stelle für beide Wege. `.doc` bleibt für beide (Begründung: Tika unterstützt es tatsächlich, geprüft anhand des Classpath — 245 unterstützte Medientypen insgesamt). Abgewiesene Dokumente zählen jetzt in `documentsSkipped`/`documentsTotal` des Indizierungsauftrags. Inhaltserkennung ausdrücklich **nicht** umgesetzt — als eigener Folgevorgang #404 herausgelöst (dort erledigt, siehe eigener Baustein). Reproduktionsnachweis mit rotem/grünem Lauf erbracht. Nebenbefund: `docs/STATUS.md` führte XLSX fälschlich als gebautes Format, im selben PR korrigiert.

**Verifikation:** `SupportedDocumentFormats.java` existiert im Worktree unter `backend/src/main/java/io/opaa/indexing/`.

**Themen:** indexierung, dateiformate, backend, bugfix
