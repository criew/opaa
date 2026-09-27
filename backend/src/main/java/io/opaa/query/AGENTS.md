# Modul assistant

Pakete (`io.opaa.*`): query, chat, search, searchadmin, prompt, health. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Von der Frage zur belegten Antwort: die Abfragefassade mit Antwort und Zitaten (`query`), Chats
samt Suchbereich eines Turns (`chat`), der Leseweg ohne Generierung (`search`), die lesende
Betriebssicht auf das Retrieval (`searchadmin`), die Prompt-Bibliothek als zweiter Asset-Typ
(`prompt`) und die Health-Indikatoren der Modelle (`health`). Die Pipeline selbst ist das Modul
retrieval (`retrieval/AGENTS.md`). assistant hängt von allen Modulen außer connectors, external und
app ab.

## Invarianten und Stolpersteine

- **Genau ein Weg zu den Daten:** `io.opaa.retrieval.KnowledgeRetrieval`, der Einstieg, den auch
  `POST /api/v1/query` nutzt. Den Suchbereich löst der Aufrufer über `chat.SearchScopeResolver`
  auf. `search` greift weder auf den Vector Store noch auf die übrigen Klassen von `retrieval` noch
  auf `query.answer` zu.
- **`searchadmin` ändert keine Fachdaten.** Es schreibt nur Protokolleinträge: einen
  Diagnoselauf mit Rechteprofil ins Revisionsprotokoll, „Sicht als" über
  `io.opaa.diagnosticaccess` ins Diagnoseprotokoll. Es liest das Erklärungsprotokoll der
  Pipeline, statt ihre Entscheidungen nachzubauen; die Diagnose fährt dieselbe Retrieval wie der
  Chat, Reranking eingeschlossen.
- **Kein Transaktionsrahmen um den Modellaufruf:** `QueryService#query` trägt kein
  `@Transactional`, `ChatService#appendTurn` läuft mit `NOT_SUPPORTED`, und nur
  `ChatMessageWriter#writeTurnOnce` öffnet eine Transaktion. Sonst hält der Schreibweg eine
  Verbindung über den LLM-Aufruf.
- **Ein Chat gehört seinem Autor.** Weder Raum- noch Systemverwaltung sieht ihn. Neue Fragen
  verlangen weiter die Raummitgliedschaft (`requireStillSpaceMember`).
- **Keine Auswertung der Prompt-Nutzung:** `chat_messages.used_prompt_id` nennt außer dem
  Entity-Mapping keine Abfrage.
- **`prompt` baut auf der Asset-Schale auf** und fragt `io.opaa.permission` nur nach Rollen. Es
  kennt `io.opaa.library` nicht, und kein Fachpaket kennt es. Das gilt auch für `prompt.web`: Die
  Prompts eines Space holt `PromptService` über den Port `SpaceAssetDirectory`.
- **Web-Schicht:** `prompt.web`, `chat.web`, `query.web`, `search.web`, `searchadmin.web`.
- **Zyklen:** Nur `query` ↔ `query.spike` ist in `KNOWN_SUBPACKAGE_CYCLE_EDGES` eingefroren; neue
  Kanten auf einem Zyklus brechen den Test.

## Verweise

- ADRs (`docs/decisions/`): 0010, 0011, 0012, 0013, 0031, 0035 (Entscheidung 5)
- Handbuch: `docs/handbuch/suche.md`, `docs/handbuch/prompt-bibliotheken.md`; Evaluierung:
  `eval/README.md`
- Strukturtests: `SearchDependencyStructureTest`, `SearchDiagnosisRerankParityTest`,
  `ChatTurnTransactionBoundaryTest`, `UsedPromptQueryGuardTest`, `PermissionPackageBoundaryTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.query.*' --tests 'io.opaa.chat.*' --tests 'io.opaa.search.*' \
  --tests 'io.opaa.searchadmin.*' --tests 'io.opaa.prompt.*' --tests 'io.opaa.health.*' \
  --tests 'io.opaa.retrieval.*' --tests 'io.opaa.architecture.*'
```

Bei Schemaänderungen: neue Datei unter `db/changelog/assistant/` mit eigenem Delta-Test
(`MasterChangelog.filesExcept(...)`), Regeln in `backend/AGENTS.md`, „Liquibase: Changelog je
Modul“; dazu `ChangelogLayoutTest`, `ChangelogModuleBoundaryTest`, `ChangelogOrderTest`.
Änderungen an Retrieval oder Antwort misst `./gradlew checkRetrievalBaseline` gegen die Baseline
(braucht Docker, nicht Teil von `build`; siehe `eval/README.md`).
