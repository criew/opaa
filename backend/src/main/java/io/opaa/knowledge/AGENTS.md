# Modul knowledge

Pakete (`io.opaa.*`): knowledge, llm, indexing. Die Konnektoren darunter bilden das Modul
connectors. Ergänzt `backend/AGENTS.md`; für den Konnektorvertrag in `indexing.source` gilt zusätzlich `indexing/source/AGENTS.md`.

## Zweck und Grenze

Der Bestand einer Wissensbibliothek — Bibliothek, Ordner, Dokumente, Zugriffsprüfung, Ablage der
Originale (`knowledge`) —, der Weg vom Dokument in den Index (`indexing`) und die verwalteten
Chat-Modelle (`llm`). knowledge hängt nur von foundation, identity und rights ab.

## Invarianten und Stolpersteine

- **`knowledge` liegt unter `indexing` und `library` und nennt keines von beiden.** Was es von oben
  braucht, deklariert es als Schnittstelle, die das obere Paket implementiert
  (`FolderDocumentDeleter`).
- **Ein Aufnahmeweg:** Jede Quelle und jeder Upload geht durch `DocumentIngestService#ingest`
  (parsen, schneiden, speichern, markieren). Die `Document`-Zeile gehört `knowledge`.
- **`indexing.job` besitzt den Lauf** (Zeile, Lebenszyklus, Protokoll, Zeitpläne) und weiß nichts
  von Parsen, Schneiden oder Chunk-Speichern. `maintenance` hängt von `document` ab, nie umgekehrt.
- **Formate:** Ein `DocumentFormat` besitzt Reader, Splitter, Chunkgröße und Anreicherung eines
  Formats. `id` und `version` stehen an jedem Chunk; die Version steigt nur, wenn sich der Schnitt
  ändert. `SupportedDocumentFormats` ist die Vereinigung der Zulassungen der Dateiformate und führt
  keine eigene Liste; ein neues Format kostet eine Klasse und eine Bean. Alle dateibasierten Wege
  entscheiden die Zulassung gleich.
- **`IndexingConfiguration` verdrahtet den Kern und kennt keinen Konnektor**; die Unterpakete
  kennen voneinander nichts über das Wurzelpaket `indexing`.
- **Zyklen zwischen den Unterpaketen von `indexing`** sind in
  `ModularArchitecture.KNOWN_SUBPACKAGE_CYCLE_EDGES` eingefroren. Jede weitere Kante auf einem
  Zyklus lässt `ModularArchitectureTest` fehlschlagen.
- **Chat-Modelle:** `LlmModelService` ist der einzige Einstieg. Der optionale API-Schlüssel wird
  über `SettingsEncryptor` verschlüsselt, bevor die Datenbank ihn sieht; jede Änderung wird
  protokolliert. Genau ein Modell ist systemweit aktiv.

## Verweise

- ADRs (`docs/decisions/`): 0017, 0018, 0020, 0022, 0024, 0026, 0028, 0030
- Handbuch: `docs/handbuch/indexierung.md`, `docs/handbuch/metadaten.md`,
  `docs/handbuch/format-*.md`
- Strukturtests: `DocumentFormatParityTest`, `ModularArchitectureTest`

## Tests bei Änderungen

```bash
./gradlew test -PtestShard=indexing      # io.opaa.indexing.* und io.opaa.llm.*
./gradlew test --tests 'io.opaa.knowledge.*' --tests 'io.opaa.architecture.*'
```

Bei Schemaänderungen: Changeset mit eigenem Delta-Test nach `backend/AGENTS.md`, Abschnitt
„Liquibase“; die Baseline-Tests prüfen nur die Baseline, nicht die Änderung.
Ändert sich Schnitt, Einbettung oder Metadaten, misst `./gradlew evaluateRetrieval` die Suchqualität (braucht Docker, nicht Teil von
`build`; siehe `eval/README.md`).
