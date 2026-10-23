# Modul assistant

Pakete (`io.opaa.*`): query, chat, search, searchadmin, prompt. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Von der Frage zur belegten Antwort: die Abfragefassade mit Antwort und Zitaten (`query`), Chats
samt Suchbereich eines Turns (`chat`), der Leseweg ohne Generierung (`search`), die lesende
Betriebssicht auf das Retrieval (`searchadmin`) und die Prompt-Bibliothek als zweiter Asset-Typ
(`prompt`). Die Pipeline selbst ist das Modul retrieval (`retrieval/AGENTS.md`).

## Invarianten und Stolpersteine

- **Genau ein Weg zu den Daten:** `io.opaa.retrieval.KnowledgeRetrieval`, der Einstieg, den auch
  `POST /api/v1/query` nutzt. Den Suchbereich löst der Aufrufer über `chat.SearchScopeResolver`
  auf. `search` nennt aus `retrieval` nur den Einstieg und dessen `RetrievalPipelineResult` und
  greift weder auf den Vector Store noch auf `query.answer` zu.
- **`searchadmin` ändert keine Fachdaten.** Es schreibt nur Protokolleinträge (Diagnoselauf mit Rechteprofil ins
  Revisionsprotokoll, „Sicht als" ins Diagnoseprotokoll) und teilt die Proben der Statusseite (`search_status_probes`).
  Es liest das Erklärungsprotokoll der Pipeline, statt ihre Entscheidungen nachzubauen; die Diagnose fährt
  dieselbe Retrieval wie der Chat, Reranking eingeschlossen.
- **Kein Transaktionsrahmen um den Modellaufruf:** `QueryService#query` trägt kein
  `@Transactional`, `ChatService#appendTurn` läuft mit `NOT_SUPPORTED`, und nur
  `ChatMessageWriter#writeTurnOnce` öffnet eine Transaktion. Sonst hält der Schreibweg eine
  Verbindung über den LLM-Aufruf.
- **Ein Chat gehört seinem Autor.** Weder Raum- noch Systemverwaltung sieht ihn. Neue Fragen
  verlangen weiter die Raummitgliedschaft (`requireStillSpaceMember`).
- **Chats gesperrter Konten** löscht `LockedAccountChatCleanupService`, nur nach Sperre durch die Verwaltung.
- **Keine Auswertung der Prompt-Nutzung:** `chat_messages.used_prompt_id` nennt außer dem
  Entity-Mapping keine Abfrage.
- **`prompt` baut auf der Asset-Schale auf** und fragt `io.opaa.permission` nur nach Rollen. Es
  kennt `io.opaa.library` nicht, und kein Fachpaket kennt es (`PermissionPackageBoundaryTest`).
  Das gilt auch für `prompt.web`: Die Prompts eines Space holt `PromptService` über den Port
  `SpaceAssetDirectory`.
- **Zyklen:** keine zwischen Unterpaketen. `query.spike` hängt an `query`, nicht umgekehrt:
  `QueryService` kennt nur die Schnittstelle `ToolLoopQueryHandler`.

## Maßgebliche ADRs

- [ADR-0014](../../../../../../../docs/decisions/0014-produktausrichtung-oeffentliche-verwaltung.md) (Entscheidung 14): deterministische Belegvalidierung
- [ADR-0024](../../../../../../../docs/decisions/0024-metadatenschema-kernfelder.md) (Entscheidung 7): Metadaten im Beleg
- [ADR-0031](../../../../../../../docs/decisions/0031-gespraechsgedaechtnis.md): Gesprächsgedächtnis
- [ADR-0035](../../../../../../../docs/decisions/0035-fremdzugaenge-mcp-server-und-zugangstokens.md) (Entscheidung 5): ein Zugriffsweg für Fremdzugänge
- [ADR-0036](../../../../../../../docs/decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md) (Entscheidung 7): Diagnose im Gruppenkontext
- [ADR-0043](../../../../../../../docs/decisions/0043-anweisung-je-space.md) (Entscheidungen 6, 8): Space-Anweisung im Prompt

## Verweise

- Handbuch: `docs/handbuch/suche.md`, `docs/handbuch/prompt-bibliotheken.md`; Evaluierung: `eval/README.md`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.query.*' --tests 'io.opaa.chat.*' --tests 'io.opaa.search.*' \
  --tests 'io.opaa.searchadmin.*' --tests 'io.opaa.prompt.*' \
  --tests 'io.opaa.retrieval.*' --tests 'io.opaa.architecture.*'
```

Änderungen an Retrieval oder Antwort misst `./gradlew checkRetrievalBaseline` gegen die Baseline
(braucht Docker, nicht Teil von `build`; siehe `eval/README.md`).
