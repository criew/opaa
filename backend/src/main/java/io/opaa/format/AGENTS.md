# Modul format

Pakete (`io.opaa.*`): format. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Die Dokumentformate: aus einer Datei oder einem gelieferten Text werden Chunks und die
Metadatenquellen eines Dokuments (`DocumentProperties`). Dazu gehören Zulassung
(`SupportedDocumentFormats`), Routing nach erkanntem Inhalt (`DocumentFormatRegistry`), die Reader
mit ihren schweren Bibliotheken (PDFBox, POI, Tika, Jakarta Mail) und der generische Tokenschnitt
samt Fundort (`format.chunk`). format hängt nur von foundation ab (`sourceaccess.BoundedStreams`)
und kennt weder indexing noch knowledge noch library.

## Invarianten und Stolpersteine

- **Ein Format besitzt Reader, Splitter, Chunkgröße und Anreicherung.** `id` und `version` stehen an
  jedem Chunk; die Version steigt nur, wenn sich Schnitt oder Strukturmetadaten ändern. Ein neues
  Format kostet eine Klasse und eine Bean in `IndexingConfiguration`.
- **Keine zweite Zulassungsliste:** `SupportedDocumentFormats` ist die Vereinigung der
  `admittedFormats()` aller Formate. Zwei Formate mit derselben Endung oder demselben Medientyp
  scheitern beim Start.
- **Parse-Fehler werfen, Leere meldet das Ergebnis.** `DocumentFormatRunner` übersetzt eine
  Exception in `PARSE_FAILED` und löscht die Temp-Dateien gefundener Anhänge.
- **Was von oben kommt, kommt als Schnittstelle:** Chunkgröße und Überlappung über `ChunkSizing`
  (implementiert von `IndexingProperties`). Dass kein Format einen Schemaschlüssel als
  Passthrough-Schlüssel deklariert, prüft `DocumentIngestService`, der diese Schlüssel schreibt.
- **Formatfelder** (`FormatMetadataField`) deklariert das Format, das sie füllt; Speicherung und
  Filter liegen in `indexing.metadata`.
- **`DocumentService#parseDocument`** ist der Tika-Weg des Rückfallformats und die Naht, an der
  `@OpaaMockedDocumentServiceIntegrationTest` das Parsen skriptet.

## Verweise

- ADRs (`docs/decisions/`): 0022, 0024, 0028
- Feature-Spezifikation: `docs/features/ingestion-pipelines.md`
- Handbuch: `docs/handbuch/format-*.md`
- Strukturtests: `DocumentFormatParityTest`, `ModularArchitectureTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.format.*' --tests 'io.opaa.architecture.*'
```

Ändert sich ein Schnitt, misst `./gradlew evaluateRetrieval` die Suchqualität (braucht Docker,
nicht Teil von `build`; siehe `eval/README.md`).
