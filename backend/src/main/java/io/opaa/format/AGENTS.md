# Modul format

Pakete (`io.opaa.*`): format. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Die Dokumentformate: aus einer Datei oder einem gelieferten Text werden Chunks und die
Metadatenquellen eines Dokuments (`DocumentProperties`). Dazu gehören Zulassung
(`SupportedDocumentFormats`), Routing nach erkanntem Inhalt (`DocumentFormatRegistry`), die Reader
mit ihren Bibliotheken (PDFBox, POI, Tika, mime4j, commons-csv) und der generische Tokenschnitt
samt Fundort (`format.chunk`, Tokenzählung mit jtokkit).

## Invarianten und Stolpersteine

- **Ein Format besitzt Reader, Splitter, Chunkgröße und Anreicherung.** `id` und `version` stehen an
  jedem Chunk; die Version steigt nur, wenn sich Schnitt oder Strukturmetadaten ändern. Ein neues
  Format kostet eine Klasse und eine Bean in `format.config.FormatConfiguration`; ein Datenformat,
  das nur eine Quelle liefert, registriert deren Konfiguration (Confluence).
- **Keine zweite Zulassungsliste:** `SupportedDocumentFormats` ist die Vereinigung der
  `admittedFormats()` aller Formate; zwei mit derselben Endung oder demselben Medientyp scheitern beim Start.
- **Parse-Fehler werfen, Leere meldet das Ergebnis.** `DocumentFormatRunner` übersetzt eine
  Exception in `PARSE_FAILED` und löscht die Temp-Dateien gefundener Anhänge.
- **Was von oben kommt, kommt als Schnittstelle:** Chunkgröße und Überlappung über `ChunkSizing`
  (implementiert von `IndexingProperties`). Dass kein Format einen Schemaschlüssel als
  Passthrough-Schlüssel deklariert, prüft `DocumentIngestService`, der diese Schlüssel schreibt.
- **Eine Zielgröße für den Strukturschnitt:** die Bean `HeadingSectionSplitter` aus
  `opaa.indexing.chunk-target-characters` (`ChunkTargetProperties`); Formate bekommen sie per
  Konstruktor. In Tests `HeadingSectionSplitterFixture.DEFAULT`. Jedes Format darauf meldet sie als
  `chunkingFingerprint()`; sie steht an jedem Chunk, und der Nachzug zerlegt bei Abweichung neu.
- **Formatfelder** (`FormatMetadataField`) deklariert das Format, das sie füllt; Speicherung und
  Filter liegen in `metadata`.
- **Leser brauchen nur Schlüssel:** Suche und Chat lesen den Fundort über `ChunkMetadataKeys`,
  nicht über den Schnitt.
- **Bekannte Grenze:** format trennt noch keine öffentliche Schnittstelle von Interna. Konnektoren
  greifen auf `shared.Whitespace`, `file.html.HtmlContentRoots` und die konkreten Klassen
  `HtmlDocumentFormat` und `ConfluenceStorageFormat` zu. Vor steckbaren Formaten oder einem
  Gradle-Modul braucht es diese Trennung.
- **`DocumentService#parseDocument`** ist der Tika-Weg des Rückfallformats und die Naht, an der
  `@OpaaMockedDocumentServiceIntegrationTest` das Parsen skriptet.

## Maßgebliche ADRs

- [ADR-0002](../../../../../../../docs/decisions/0002-mvp-technology-stack.md) (Entscheidung 4): Formate als eigenes Modul
- [ADR-0010](../../../../../../../docs/decisions/0010-ein-chunk-invariante-evaluierungskorpus.md) (Entscheidung 4): Chunkgröße an die Baseline gekoppelt
- [ADR-0022](../../../../../../../docs/decisions/0022-anhang-als-eigenes-dokument.md) (Entscheidungen 8, 10): Anhänge als zweiter Rückgabekanal
- [ADR-0024](../../../../../../../docs/decisions/0024-metadatenschema-kernfelder.md) (Entscheidung 6): Formate raten keine Metadaten

## Verweise

- Feature-Spezifikation: `docs/features/ingestion-pipelines.md`
- Handbuch: `docs/handbuch/formate/*.md` (Übersicht in `formate/README.md`)

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.format.*' --tests 'io.opaa.architecture.*'
```

Ändert sich ein Schnitt, misst `./gradlew evaluateRetrieval` die Suchqualität (braucht Docker,
nicht Teil von `build`; siehe `eval/README.md`).
