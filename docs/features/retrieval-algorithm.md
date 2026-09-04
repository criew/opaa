# Retrieval-Algorithmus (Ist-Stand)

Dieses Dokument beschreibt, **wie das Retrieval heute tatsächlich arbeitet** — Klasse für Klasse, Parameter
für Parameter, Stand `main` nach dem Epic
[#1045](https://github.com/criew/opaa/issues/1045). Es ist damit die
Ergänzung zu [Wissensschicht und Retrieval](./data-indexing-rag.md), das überwiegend das **Zielbild**
beschreibt (siehe dessen „Lesehinweis zum Umsetzungsstand"): Abschnitte wie
[Hybride Suche](./data-indexing-rag.md#hybride-suche) und [Reranking](./data-indexing-rag.md#reranking)
dort sind inzwischen beide gebaut: Die **hybride Suche** — der mit
[#1048](https://github.com/criew/opaa/issues/1048) gebaute lexikalische Suchpfad (Schritt 3b) — ist seit
[#1049](https://github.com/criew/opaa/issues/1049) eine Eingangsliste der Fusion und bestimmt die
Endauswahl mit, und ein **separates Reranking-Modell** gibt es seit
[#1050](https://github.com/criew/opaa/issues/1050) als eigene Modellrolle (Schritt 5b) — mit einem
eigenen Schalter und **per Voreinstellung aus**. Wer wissen will, was gebaut ist, liest dieses
Dokument; wer wissen will, wohin es geht, liest `data-indexing-rag.md`.

Die Stellschrauben-Tabelle in `data-indexing-rag.md` bleibt die eine Quelle der Wahrheit für Parameter und
ihre Defaults (siehe [Stellschrauben und ihre Wirkung](./data-indexing-rag.md#stellschrauben-und-ihre-wirkung));
sie wird hier nicht dupliziert, nur je Schritt referenziert. Quelle des Codes ist
`backend/src/main/java/io/opaa/query/`, vor allem `RetrievalPipeline`, die `*Stage`-Klassen und
`QueryProperties`.

---

## Die Pipeline als benannte Stufen (#1046)

Seit Arbeitspaket 1 von [Hybride Suche mit Reranking](./hybrid-retrieval.md#arbeitspaket-1-die-pipeline-als-benannte-stufen)
sind die Schritte 1 bis 6 keine Verzweigungen in `QueryService` mehr, sondern **registrierte Stufen** mit
einer gemeinsamen Schnittstelle (`RetrievalStage`): Kandidatenlisten rein, Kandidatenlisten raus, plus
Erklärprotokoll als Pflicht-Rückgabewert. Drei Eigenschaften sind baulich zugesichert und nicht bloß
verabredet:

- **Keine Stufe verändert den Rechtekontext.** Suchbereich und Parameter stehen im unveränderlichen
  `RetrievalContext`; eine Stufe bekommt ihn nur lesend.
- **Keine Stufe sieht mehr Kandidaten, als ihr übergeben wurden.** Nur eine Suchstufe erweitert den
  Kandidatenpool (`RetrievalState#withSearchResults`); alle übrigen schöpfen daraus.
- **Keine Stufe kann stumm bleiben.** `StageOutcome` hat keinen Konstruktor ohne `StageExplanation`.

**Die Reihenfolge steht an genau einer Stelle**: `QueryConfiguration#retrievalPipeline`. Dort werden auch
der Volltextpfad (AP 2) und die Reranking-Stufe (AP 4) eingehängt.

**Jede Stufe ist einzeln abschaltbar** über `opaa.query.pipeline.disabled-stages` (Ebene-1-Wert, in keiner
Administrationsoberfläche); die abgeschaltete Stufe entfällt dann vollständig, statt neutralisiert
mitzulaufen — der Unterschied zweier Läufe ist so der Beitrag der Stufe und nicht der Unterschied zweier
Codepfade. Einzige Ausnahme ist die Stufe `SEARCH_SCOPE`: „ohne Rechtefilter" ist keine Messvariante,
sondern eine Rechteumgehung, und wird beim Start abgelehnt (ADR-0008 §5).

**Für den Benchmark ist der Schalter (noch) nicht zugelassen.** Der Harness weist eine nicht-leere Menge
ab (`PipelineHarnessSupport#requireMeasurableConfiguration`): Kein Feld eines Pipeline-Reports hält fest,
welche Stufen liefen — ein Lauf mit abgeschalteter Stufe trüge denselben `runConfiguration`-Abdruck wie ein
vollständiger, und seine Zahlen würden gegen die committete Baseline als Codeänderung verbucht. Die
Stufen-Auswahl zur Messgröße zu machen ist ein bewusster Vertragsnachtrag (neuer Fixpunkt, erhöhte
Vertragsversion, neu gezogene Baselines), keine Property-Entscheidung.

**Das Erklärprotokoll** (`RetrievalExplanation`) enthält je registrierter Stufe einen Eintrag — auch für
eine abgeschaltete oder nicht erreichte Stufe — mit hereingekommenen, hinausgegangenen und verworfenen
Kandidaten, Begründung aus einem festen Vokabular (`VerdictReason`) und dem stufeninternen Wert
(Ähnlichkeit, RRF-Score, Verdrängungsstufe der Dokument-Vervollständigung). Erzeugt wird es immer; ob es
festgehalten wird, entscheidet der Aufrufer: `QueryService#query` verwirft es, die Admin-Diagnose
([#1053](https://github.com/criew/opaa/issues/1053)) wertet es aus.

Die Nummerierung der folgenden Schritte ist zugleich die Reihenfolge der registrierten Stufen.

---

## Teil 1: Ablauf einer Anfrage

Eine Anfrage (`QueryService#query`) durchläuft die folgenden Schritte der Reihe nach; die Schritte 1
bis 6 einschließlich 3b sind die registrierten Stufen der `RetrievalPipeline`, Schritt 7 liegt außerhalb. Jeder Schritt
arbeitet nur mit dem, was der vorherige ihm übergibt — kein Schritt weitet die Berechtigungs- oder
Ähnlichkeitsschwellengrenze eines früheren wieder auf (die Kandidatenmenge selbst schöpft Schritt 6
weiterhin aus dem in Schritt 3 gebildeten Pool, siehe dort).

### 1. Scope-Bestimmung

`QueryService#query` unterscheidet zunächst zwei Fälle: eine **persistierte Chat-Anfrage** (`chatId` löst
über `ChatService#findOwnedChat` auf einen vom Aufrufenden selbst angelegten Chat auf) oder eine
**ephemere Anfrage** (kein `chatId`, oder er löst nicht auf). Bei einem persistierten Chat bestimmt
`ChatService#effectiveLibraryScope` den Suchbereich aus dessen eigenem `useKnowledge`/
`referencedLibraryIds` — die Request-Parameter `useKnowledge`/`requestedLibraryIds` werden dann ignoriert,
nicht nur überschrieben. Bei einer ephemeren Anfrage gilt `useKnowledge = true` → alle für den Aufrufenden
lesbaren Bibliotheken, `useKnowledge = false` → `requestedLibraryIds ∩ readableLibraryIds` (nie über die
lesbare Menge hinaus erweitert). Die lesbaren Bibliotheken selbst liefert
`LibraryAccessService#readableLibraryIds`; der resultierende Suchbereich (`searchScope`, eine Menge von
Bibliotheks-Kennungen) wird als `library_id IN (...)`-Filter (`SearchScopeStage#libraryFilter`) **Teil des
`similaritySearch`-Aufrufs selbst**, nicht ein Filter auf dessen Ergebnis (siehe
[Durchsetzung zur Abfragezeit](./spaces-and-assets.md#durchsetzung-zur-abfragezeit) sowie die
Javadoc-Begründung an `QueryService#query`). Ein leerer Suchbereich überspringt die Schritte 2–6
vollständig (`relevantChunks = List.of()`, keine der dortigen Aufrufe läuft) — Schritt 7 läuft trotzdem,
mit null Chunks im Kontext, und markiert das Ergebnis über `QueryOutcome#answeredWithoutKnowledge`.

### 2. LLM-Teilfragen-Zerlegung/Reformulierung

`SubQueryDecompositionStage` ruft, sofern `opaa.query.query-decomposition-enabled`
(`OPAA_QUERY_DECOMPOSITION_ENABLED`, Default `true`) aktiv ist, `QueryDecompositionService#decompose`
auf: Die aktuelle Frage geht zusammen mit dem bisherigen Gesprächsverlauf an das systemweit aktive
Chat-Modell (`ActiveChatModelResolver`, dieselbe Anbindung wie die Antwortgenerierung), das 1 bis
`opaa.query.max-sub-queries` (`OPAA_QUERY_MAX_SUB_QUERIES`, Default `3`) eigenständige, vollständige
Suchanfragen zurückgibt und dabei Folgefragen kontextuell auflöst sowie Tippfehler normalisiert. Scheitert
der Aufruf (Zeitüberschreitung, kein aktives Modell, unparsebare oder leere Antwort), liefert `decompose`
eine leere Liste, und `SubQueryDecompositionStage#buildSearchQuery` übernimmt als Rückfallebene das Verhalten von vor
#923: die reine Frage, oder — bei laufender Konversation — die erste Nutzernachricht der Historie,
vorangestellt. Dasselbe gilt seit #1254 für eine **degenerierte** Ausgabe: Eine Teilfrage, die zu
Frage und Gesprächsverlauf keinen Wortbezug hat, hat die Nutzerfrage ersetzt statt sie
umzuformulieren und wird verworfen; bleibt danach keine übrig, greift dieselbe Rückfallebene — mit
WARN-Log und dem Zähler `opaa.query.decomposition_fallback`, nie stillschweigend.
Details, Diagramm und die Vorher/Nachher-Messung stehen in
[Teilfragen-Zerlegung und Query-Reformulierung](./data-indexing-rag.md#teilfragen-zerlegung-und-query-reformulierung-multi-query-retrieval-923).

### 3. Vektorsuche je Teilfrage

`VectorSearchStage` ruft für **jede** Suchanfrage aus Schritt 2 einen eigenen
`VectorStore#similaritySearch`-Aufruf gegen PostgreSQL/pgvector auf,
mit identischem Rechtefilter (`searchScope` aus Schritt 1) und identischer Ähnlichkeitsschwelle für jede
Teilfrage. Parameter: `opaa.query.fetch-k` (`OPAA_QUERY_FETCH_K`, Default `25`) Kandidaten je Aufruf,
`opaa.query.similarity-threshold` (`OPAA_QUERY_SIMILARITY_THRESHOLD`, Default `0,3`) als Mindest-Kosinus-
Ähnlichkeit — ein Chunk unterhalb der Schwelle wird gar nicht erst zum Kandidaten. Eine Einthemen-Frage
ohne Kontextbezug decodiert typischerweise zu genau einer Suchanfrage; dieser Fall läuft denselben Pfad
wie vor #923, nur ohne die vorangestellte Verlaufs-Heuristik. Details zu beiden Parametern in der
Stellschrauben-Tabelle.

### 3b. Volltextsuche je Teilfrage (#1048/#1049)

`FullTextSearchStage` führt für **jede** Suchanfrage aus Schritt 2 eine PostgreSQL-Volltextabfrage gegen
`chunk_full_text` aus — mit **identischem Rechtefilter** wie Schritt 3 (`library_id = ANY(...)` als Teil
der `WHERE`-Klausel, nie ein Nachfilter, ADR-0008 §5) und identischem `opaa.query.fetch-k`. Sortiert wird
nach `ts_rank`.

**Die Suchanfrage wird genauso gebaut wie der Index** (`FullTextChunkSearch`), aus zwei Hälften:

- die `german`-Analysekette über die Wörter der Frage, ODER-verknüpft (`to_tsquery('german', 'w1 | w2 | …')`).
  ODER, nicht UND: Der Pfad liefert Kandidaten für eine Fusion; eine Frage, deren sämtliche Wörter
  vorkommen müssten, träfe nichts.
- die **unzerlegten Kennungs-Tokens** (`FullTextIdentifiers`), ODER darübergelegt. Sie tragen im Index das
  Gewicht `A` gegen das `D` des Fließtexts — der Mechanismus, der „§ 34" und „§ 35" in einer Rangliste mit
  Konkurrenz auseinanderhält und nicht nur im Treffer. Erkannt werden Paragrafenverweise (auch
  Aufzählungen hinter `§§`), gerichtliche Aktenzeichen sowie Dienstanweisungs-, Formular-, Erlass- und
  Drucksachennummern — jedes schlüsselwortgeführte Muster mit einem schlüsselwortfreien Gegenstück,
  weil ein Dokument die Nummer hinter einem Schlüsselwort nennt und eine Frage sie nackt.

Beide Hälften entstehen aus bereinigten Tokens (Wörter auf Buchstaben und Ziffern reduziert,
Kennungs-Lexeme per Konstruktion ASCII-alphanumerisch); kein Zeichen der Nutzerfrage erreicht
`to_tsquery` als Operator.

**Zwei Tore, beide verengend:** `opaa.query.full-text-search-enabled` (`OPAA_QUERY_FULL_TEXT_SEARCH_ENABLED`,
Ebene-1-Wert, Default `true`) und
das Backfill-Tor — eine Bibliothek, deren Volltext-Backfill nicht abgeschlossen ist, wird nicht durchsucht
(`FullTextBackfillGate`, siehe [Arbeitspaket 2a](./hybrid-retrieval.md#arbeitspaket-2a-backfill-des-bestands)).
Ein halb gefüllter Volltextindex liefert Treffer und verschweigt den Rest; das ist schlechter als nichts
zu liefern.

**Ein Fehlschlag degradiert den Pfad, nie die Antwort.** Eine defekte oder fehlende Volltextspalte darf
Suchqualität kosten, aber nie zum Fehler für den fragenden Menschen werden; die Rückfallebene ist eine
**leere** Kandidatenliste, nie eine ungefilterte.

**Eingangsliste der Fusion (#1049).** Die Listen dieser Stufe werden im Pipeline-Zustand
weitergereicht, genau wie die der Vektorsuche, und in Schritt 5 rangbasiert mit ihnen zusammengeführt.
Die Stufe ist damit — neben der Vektorsuche — die zweite, die dem Lauf Kandidaten hinzufügt; alles,
was sie hinzufügt, hat denselben Rechtefilter passiert, und der Kandidatenpool bleibt die Obergrenze
für Schritt 6. Abgeschaltet (`opaa.query.full-text-search-enabled=false`) ist die Stufe die Identität:
Die Auswahl ist dann bit-identisch zu der ohne sie — die Messvariante `vector-only`.

### 4. MMR-Auswahl je Teilfrage

`MmrSelectionStage` narrowt die `fetch-k` Kandidaten **jeder Liste einzeln** — je Teilfrage und je
Suchpfad, nie über die zusammengeführte Gesamtmenge — mittels `MmrSelector#select` auf das **Kandidatenbudget** des Laufs
(`RetrievalContext#candidateBudget`). Ohne Reranking ist das `opaa.query.top-k`
(`OPAA_QUERY_TOP_K`, Default `8`) — das Verhalten von vor #1050. Läuft die Rerank-Stufe, ist es
stattdessen `opaa.query.rerank-candidate-count`: Ein Reranker, der nur `top-k` Kandidaten je
Liste zu sehen bekäme, könnte nichts mehr hochziehen, was die vorgelagerten Stufen bereits
verworfen haben, und wäre damit zwecklos. Steuernder Parameter ist `opaa.query.mmr-lambda`
(`OPAA_QUERY_MMR_LAMBDA`, Default `1,0` = Vielfaltsauswahl per Voreinstellung deaktiviert, reine
Top-`k`-Relevanz). Bei `mmrLambda < 1,0` wird die paarweise Ähnlichkeit über die **echten
Chunk-Embeddings** berechnet (Kosinus-Ähnlichkeit), die `ChunkEmbeddingLookup` in einem einzigen,
über alle Teilfragen gepoolten SQL-Roundtrip direkt aus der pgvector-Tabelle nachliest (`id::text = ANY(?)`
gegen `spring.ai.vectorstore.pgvector.*`) — kein zusätzlicher Aufruf beim Einbettungsdienst. Bei
`mmrLambda = 1,0` (Default) entfällt dieser Roundtrip vollständig, weil der Diversitätsterm dann stets mit
null multipliziert wird. Details, Messwerte und die Begründung des Defaults in
[MMR](./data-indexing-rag.md#stellschrauben-und-ihre-wirkung).

### 5. Reciprocal Rank Fusion

`RankFusionStage` führt über `ReciprocalRankFusion` die pro Teilfrage und **je Suchpfad** per MMR
ausgewählten Ranglisten zu einer einzigen zusammen — seit #1049 also zwei Listen je Teilfrage, die der
Vektor- und die der Volltextsuche. Jeder Chunk erhält je Liste, in der er vorkommt, den Beitrag
`1 / (60 + Rang)` (Rang 1-basiert, Dämpfungskonstante 60 nach Cormack et al.), die Beiträge werden über
alle Listen summiert, absteigend sortiert und auf das Kandidatenbudget gedeckelt (`top-k` ohne
Reranking, `rerank-candidate-count` mit — die Deckelung auf `top-k` übernimmt dann Schritt 5b). Keine Gewichtung je Pfad: RRF ist
tuningfrei, und ein ungetuntes Gewicht kippt die Suche unbemerkt auf eine Modalität
(siehe [Hybride Suche mit Reranking](./hybrid-retrieval.md#arbeitspaket-3-fusion)).
Dedupliziert wird per Chunk-Kennung
(`Document#getId()`), nie per Score — Ähnlichkeitswerte verschiedener Suchvektoren sind nicht vergleichbar,
genau die Wurzel des in [#912](https://github.com/criew/opaa/issues/912) dokumentierten Fehlerbilds; ein
Chunk, den beide Pfade finden, ist **ein** Kandidat mit zwei Beiträgen. Von zwei Instanzen desselben
Chunks überlebt die aus der **früheren** Liste, nicht die mit dem höheren Score: Seit dem zweiten
Suchpfad können das eine Kosinus-Ähnlichkeit und ein `ts_rank` sein, und die größere der beiden Zahlen
bedeutet nichts. Da die Pipeline ihre Listen in Stufenreihenfolge übergibt, ist die frühere die der
Vektorsuche — der angezeigte Relevanzwert bleibt damit eine Ähnlichkeit, außer bei einem Chunk, den nur
der Volltextpfad gefunden hat; was der Wert für diesen Fall bedeuten soll, ist offen
([#1102](https://github.com/criew/opaa/issues/1102)). Bei
genau einer Suchanfrage und abgeschaltetem Volltextpfad läuft die Stufe mit und ist dort nachweislich die Identität: innerhalb einer Liste
sind alle Ränge verschieden, die fusionierten Werte also streng fallend in der Listenreihenfolge, und die
Deckelung ist durch das Listenbudget bereits erfüllt. Vor #1046 wurde dieser Fall stattdessen verzweigt
übersprungen; dass beides dasselbe auswählt, sichert `RetrievalPipelineParityTest` gegen die
Vorher-Implementierung ab.

### 5b. Reranking (#1050)

`RerankStage` bewertet die fusionierte Kandidatenmenge mit der **Rerank-Modellrolle** neu und deckelt
sie wieder auf `top-k`. Die Stufe steht zwischen Fusion und Dokument-Vervollständigung: Die
Vervollständigung ergänzt Geschwister-Chunks bereits ausgewählter Dokumente und muss deshalb auf der
endgültigen Rangfolge arbeiten, nicht auf einer, die der Reranker gleich wieder umsortiert
(siehe [Hybride Suche mit Reranking](./hybrid-retrieval.md#arbeitspaket-4-reranking-als-modellrolle)).

**Die Rolle ist per Voreinstellung aus.** `OPAA_RERANK_ENABLED` ist ein eigener, expliziter Schalter,
getrennt von den Endpunktangaben `OPAA_RERANK_BASE_URL`, `OPAA_RERANK_MODEL` und
`OPAA_RERANK_API_KEY` — „Reranking aus" soll eine Aussage der Betreiberin sein und nicht das
ununterscheidbare Ergebnis einer fehlenden Konfigurationszeile. Ein Widerspruch (Schalter an, Rolle
unbelegt oder Endpunkt stumm) wird beim Start als Fehler geloggt und bleibt danach über
`RerankRoleStatusProvider#currentStatus()` abfragbar — dem schmalen Vertrag, den die
Administrationsseite ([#1053](https://github.com/criew/opaa/issues/1053)) liest. Die Suche
läuft weiter, aber nicht unbemerkt.

Die Kandidatenzahl steuert `opaa.query.rerank-candidate-count`
(`OPAA_QUERY_RERANK_CANDIDATE_COUNT`, Default `50`, Ebene-1-Wert). `0` schaltet die Stufe über ihren
eigenen Parameter ab — dieselbe explizite Abwahl, die `max-chunks-per-document = 1` für die
Dokument-Vervollständigung ist. Der Startwert 50 entspricht dem, was zwei Suchpfade bei
`fetch-k = 25` je Teilfrage überhaupt liefern können.

**Das Fenster vergrößert die Reichweite nicht.** Was keine Suchstufe zurückgegeben hat, kann weder
die Fusion noch der Reranker heben; die Reichweite ist `fetch-k` je Liste mal der Zahl der Listen im
Lauf (Teilfragen mal aktive Suchpfade). Wer die Fundstelle aus [#938](https://github.com/criew/opaa/issues/938)
— dort auf Rang 50 einer Vektorliste — allein über das Reranking erreichen will, muss `fetch-k`
mitheben; im Auslieferungsstand mit `fetch-k = 25` liegt Rang 50 einer einzelnen Liste außerhalb
dessen, was die Suche überhaupt liefert.

Angebunden wird über denselben Weg wie Chat und Einbettung: ein `POST {base-url}/rerank` mit
`{model, query, documents}`, das vLLM, Text Embeddings Inference, Infinity, Jina, Cohere und Voyage
gleichermaßen sprechen. **Ein Fehlschlag kostet die Reihenfolge, nie die Antwort**: Bleibt der
Endpunkt stumm, behält die Stufe die fusionierte Reihenfolge, stellt die `top-k`-Deckelung wieder her
und vermerkt im Erklärprotokoll den Status `UNAVAILABLE` statt so zu tun, als hätte sie entschieden.

**Diese Reihenfolge ist allerdings nicht die des Laufs ohne Reranking.** Fällt der Endpunkt mitten
im Lauf aus, hat die MMR-Auswahl je Liste bereits `rerank-candidate-count` statt `top-k` Einträge
behalten — Ränge 9 bis 50 jeder Liste sind also in die Fusion eingeflossen, und ein Chunk mit
Übereinstimmung in beiden Listen überholt dort einen Rang-3-Treffer aus nur einer. Das Ergebnis ist
ein **dritter Zustand**: weder die Auswahl des rerankten Laufs noch die der Konfiguration ohne
Reranking. Kein Fehler — die Antwort bleibt eine Antwort mit `top-k` Chunks —, aber der Grund,
warum ein Messlauf mit unterbrochenem Reranking nicht als Messwert gilt (siehe
[eval/variants/README.md](../../eval/variants/README.md)).

**Der Zustand der Rolle reist mit, nicht ein Ja/Nein.** `RetrievalContext#rerankAvailability`
unterscheidet „abgeschaltet", „an, aber nicht nutzbar" und „nutzbar", und das Erklärprotokoll führt
die ersten beiden getrennt: `DISABLED` für die Betreiberentscheidung, `UNAVAILABLE` für die Störung.
Ein leerer Kandidatensatz bekommt eine eigene Notiz statt der Behauptung, die Rolle sei nicht
nutzbar gewesen. **Das Diagnosewerkzeug liest denselben Rollenzustand wie der Chatpfad**, sonst
erklärte es eine Suche, die so nie gelaufen ist. Wird die Stufe über
`opaa.query.pipeline.disabled-stages` abgeschaltet, während die Rolle an ist, nimmt die Pipeline das
verbreiterte Kandidatenfenster mit zurück — sonst stellte niemand die `top-k`-Deckelung wieder her.

### 6. Dokument-Vervollständigung

`DocumentCompletionStage` (`DocumentCompletion#complete`, #932/#934/#935) läuft **zuletzt** und zieht ausschließlich
aus den bereits berechtigungs- und schwellenwertgefilterten Kandidaten von Schritt 3 nach — nie darüber
hinaus. Ziel: Ein Dokument, das mit einem Chunk bereits in der Auswahl vertreten ist, darf bis zu
`opaa.query.max-chunks-per-document` (`OPAA_QUERY_MAX_CHUNKS_PER_DOCUMENT`, Default `2`) Chunks stellen,
bevor ein Chunk eines anderen Dokuments den verbleibenden Platz bekommt — die Abhilfe für den Fall, dass
der Gebühren-Chunk eines Dokuments in RRF/MMR gegen Chunks *anderer* Dokumente verlor, weil der
Einleitungs-Chunk desselben Dokuments dort besser rankte und dessen Platz belegte
(siehe [#932](https://github.com/criew/opaa/issues/932)). Zwei Verdrängungsstufen: **Stufe 1** entfernt den
auswahlrang-schwächsten Chunk eines anderen, bereits mit ≥2 Chunks vertretenen Dokuments — die
Dokumentvielfalt sinkt dabei nie unter das, was Fusion/MMR bereits hergestellt hatten. **Stufe 2** greift
nur, wenn Stufe 1 keine Quelle findet: Sie darf den auswahlrang-letzten Chunk der Gesamtauswahl verdrängen,
aber nur wenn das vervollständigende Dokument mit seinem besten Chunk strikt besser rankt als das Opfer,
gedeckelt auf `max(1, top-k / 4)` Verdrängungen je Aufruf — ohne diesen Deckel könnte eine
Acht-Themen-Auswahl in einem einzigen Aufruf auf eine Handvoll Dokumente schrumpfen. Zwei Schutzregeln
gelten stufenübergreifend: Ein in diesem Aufruf bereits vervollständigtes Dokument scheidet für ein
weiteres Dokument als Stufe-1-Quelle aus (`completedDocumentKeys`), und ein Chunk, den eine Vervollständigung
in diesem Aufruf hinzugefügt hat, kann in keiner der beiden Stufen selbst zum Opfer werden — eine
Vervollständigung kann eine frühere im selben Aufruf also nie rückgängig machen. `max-chunks-per-document
= 1` schaltet die Vervollständigung vollständig ab (Verhalten von vor #932).

### 7. Antwortgenerierung, Zitatvalidierung, Quellen-Mapping

`AnswerGenerationService#generateAnswer` formatiert die final ausgewählten Chunks als Kontextblock (mit
`document_id`, `chunk_index` und Dateiname je Chunk-Kopf) und ruft das systemweit aktive Chat-Modell auf,
mit der Zitierpflicht `【source: <document_id>#<chunk_index> | <file_name>】` im Systemprompt. Danach
extrahiert `CitationParser` die im Antworttext vorkommenden Zitate, und `CitationValidator#validate` prüft
deterministisch (kein zweiter Modellaufruf) für jedes Zitat, ob Dokument-Kennung, Abschnittsnummer und
Dateiname zu ein und demselben tatsächlich abgerufenen Chunk passen. Ein so gültiges Zitat wird
zusätzlich inhaltlich geprüft (Stufe 1, #937, geschärft im #939-Review): `CitationFactChecker`
extrahiert aus dem Satz vor dem Zitatmarker den nächstgelegenen harten Fakt (Geldbetrag, Datum,
Paragraphen-Referenz, sonstige Zahl mit Tausendertrennzeichen oder Dezimalkomma) und vergleicht ihn
nach Gattung normalisiert gegen den Text aller abgerufenen Chunks des zitierten Dokuments (nicht nur
des einen vom Marker genannten Chunks, wegen der #932-Dokumentvervollständigung) — fehlt ein Fakt
derselben Gattung dort völlig, bleibt das Zitat unangetastet; nur ein abweichender Wert derselben
Gattung stuft zurück. Ein Satz mit einer Näherung oder Summe ("rund", "etwa", "zusammen" u. Ä.) sowie
ein Satz ohne extrahierbaren Fakt bleiben ebenfalls unangetastet (bewusst konservativ: ein fälschlich
geflaggtes korrektes Zitat wiegt schwerer als ein übersehener Fehler). Eine LLM-gestützte
Stützungsprüfung (Stufe 2) ist als möglicher Folgeausbau denkbar, aber nicht Teil dieser Prüfung.
Ungültige Zitate werden im Antworttext belassen, aber in der zugehörigen `ChatSource` als
`citationValid = false` markiert
(`QueryService#mapSources`). Details zur Belegvalidierung stehen unter
[Zitierzwang](./data-indexing-rag.md#zitierzwang).

**Was `relevanceScore` bedeutet (#1102).** Der Relevanzwert einer Quellenangabe ist der **Kehrwert
ihrer Position in der Quellenliste der Antwort** — 1,0 für die erste Quelle, 0,5 für die zweite,
0,33 für die dritte —, **keine Ähnlichkeit**. Gezählt werden Quellen, nicht Chunks: Ein Dokument
darf mehrere Chunks zur Auswahl beisteuern (`max-chunks-per-document`), belegt in der Quellenliste
aber genau eine Position — die Rangvergabe erfolgt deshalb erst, nachdem die Chunks je Dokument zu
einer Quellenangabe zusammengefasst sind, und die Rangfolge ist lückenlos. Der Rohwert eines Chunks
(`Document#getScore()`) taugt dafür seit #1049 nicht mehr: Ein Chunk, den nur der lexikalische Pfad
gefunden hat, trägt einen `ts_rank` (grob 0,03–0,1), einer aus der Vektorsuche eine
Kosinus-Ähnlichkeit (grob 0,3–0,9); die beiden Skalen sind nicht vergleichbar, und eine nach ihnen
sortierte Belegliste hätte eine rein lexikalisch gefundene Fundstelle selbst dann ans Ende gestellt,
wenn die Fusion sie auf Rang 1 gesetzt hat. Der Rang dagegen bedeutet für jede Fundstelle dasselbe,
unabhängig vom Pfad. Eine synthetische Quellenangabe zu einem erfundenen Beleg (#386) hat gar keinen
Rang und trägt weiterhin 0. Das Belegfenster (`SourceEvidenceDrawer`) sortiert entsprechend nach der
Reihenfolge des `sources`-Arrays — also nach der Auswahlreihenfolge der Pipeline, nicht nach einem
Zahlenwert — und beschriftet die Zeile mit „Rang n" statt mit einem Prozentgewicht. Auch die
Beschriftung leitet sich allein aus der Position in `sources` ab, nicht aus dem Zahlenwert und nicht
aus der Zeilenposition im Belegfenster: Die Liste stellt zitierte Quellen vor unzitierte, während
„Rang n" dieselbe Position meint, deren Kehrwert `relevanceScore` trägt. So bleibt die Beschriftung
auch für vor #1102 gespeicherte Nachrichten richtig, deren `sources`-JSON noch Rohwerte trägt.

### Zusammenfassung als Ablauf

```
Frage + Gesprächsverlauf
        ↓
1. Scope-Bestimmung (persistierter Chat / ephemer, Rechtefilter)
        ↓
2. LLM-Teilfragen-Zerlegung (1..max-sub-queries Suchanfragen, Fallback: Einzelfrage)
        ↓
3. Je Suchanfrage: similaritySearch (fetch-k Kandidaten, Rechtefilter + Schwelle)
        ↓
3b. Je Suchanfrage: Volltextsuche (fetch-k, identischer Rechtefilter, Backfill-Tor)
        ↓
4. Je Liste (Teilfrage × Suchpfad): MMR-Auswahl auf das Kandidatenbudget (mmr-lambda)
        ↓
5. Reciprocal Rank Fusion über alle Listen beider Pfade, gedeckelt auf das Kandidatenbudget
        ↓
5b. Reranking der Kandidatenmenge durch die Rerank-Modellrolle, zurück auf top-k
    (per Voreinstellung aus: OPAA_RERANK_ENABLED)
        ↓
6. Dokument-Vervollständigung (max-chunks-per-document, zweistufige Verdrängung)
        ↓
7. Antwortgenerierung, Zitatvalidierung, Quellen-Mapping
```

Die Schritte 1 bis 6 sind über `QueryService#retrieveRelevantChunksInGivenScope(question, history, searchScope)`
auch einzeln aufrufbar — der Einstieg, über den der Pipeline-Messpfad des Retrieval-Harness genau
diese Kette misst, ohne Schritt 7 (siehe
[Retrieval-Benchmark](./retrieval-benchmark.md#1-messpfad-durch-die-produktive-pipeline)). Der
Suchbereich wird dort übergeben, nicht aufgelöst: Berechtigungen bestimmt weiterhin ausschließlich
Schritt 1 in `QueryService#query`.

---

## Teil 2: Mögliche Verbesserungen

Ideen und bekannte Schwächen, keine Zusagen. Konsolidiert aus den verstreuten Verbesserungs-Hinweisen in
`data-indexing-rag.md` und den #912-Folge-Issues.

### Zurückgestellt, aber ausgearbeitet

- **MMR-Default-Aktivierung.** Gebaut, aber Opt-in (`mmr-lambda: 1,0`, siehe Schritt 4 oben). Gegen die 20
  `multi_topic`-Golden-Fälle aus #915 erreichte die reine `top-k`-Anhebung auf 8 ohne Vielfaltsauswahl
  20 von 20 Fällen, `mmr-lambda: 0,7` mit echten Chunk-Embeddings dagegen 19 von 20 — ein Fall schlechter.
  Details und Messmethodik in
  [MMR ist gebaut, aber per Voreinstellung deaktiviert](./data-indexing-rag.md#stellschrauben-und-ihre-wirkung).
  Eine künftige, größere oder heterogenere Mehrthemen-Stichprobe kann diese Einschätzung revidieren.

### Umgesetzt

- **Contextual Chunking** ([#933](https://github.com/criew/opaa/issues/933), umgesetzt). Wurzelanalyse
  (#932/#938): Ein Detail-Chunk (Gebührentabelle, Fristen, Kontaktdaten, ein einzelner Paragraph einer
  Satzung) trägt im Embedding kaum Signal, *wovon* er handelt, weil ihm der Dokumentkontext beim
  Zerteilen verloren geht — ein Gebühren-Chunk für „Personalausweis" rankte deshalb für eine
  Kostenfrage schwächer als der Einleitungs-Chunk desselben Dokuments, und ein einzelner
  Satzungs-Chunk mit sechs Paragraphen rankte in #938s Live-Diagnose so schwach, dass er selbst im
  echten Leserechte-Scope weit außerhalb jedes plausiblen Top-k-Fensters lag (Rang 50/97 bzw.
  96/147). Umsetzung: `io.opaa.indexing.FileProcessingService#storeChunks` stellt jedem Chunk eines
  Dokuments, das beim Chunking in **2 oder mehr Chunks zerfiel**, vor dem Einbetten einen aus dem
  Dateinamen abgeleiteten, bereinigten Titel voran (`ChunkContextTitle#deriveTitle`, z. B. `"[prag]"`
  statt des rohen `"city-0022_prag.md"`) - ausschließlich für die `EMBED`-Formatierung der
  Einbettung, nicht für den gespeicherten Chunk-Text (siehe
  `CHUNK_EMBED_CONTENT_FORMATTER_WITH_PREFIX` für die vollständige Begründung, inklusive der
  Wechselwirkung mit `chunk-size`). Ein Dokument, das **ein einziger Chunk** blieb, bekommt bewusst
  **keinen** Präfix (`CHUNK_EMBED_CONTENT_FORMATTER_NO_PREFIX`, bit-identisch zum Stand vor #933) -
  ein Detail-Chunk verliert Kontext durch das Zerteilen, ein ungesplittetes Dokument trägt seinen
  vollen Kontext bereits selbst. Diese Eingrenzung kam erst in einer zweiten Korrekturrunde: Ein
  Präfix aus dem rohen Dateinamen auf *jeden* Chunk (auch ungesplitteter Dokumente) verbesserte die
  einchunkige comic-characters-Domäne, regressierte aber die mehrchunkige city-landmarks-Domäne
  (`multi_topic`/`allExpectedDocumentsHitAt10` 1,000→0,850); ein humanisierter Titel auf jeden Chunk
  behob city-landmarks, regressierte aber stattdessen comic-characters (`hitRateAt5` 0,521→0,496) -
  erst die Beschränkung auf mehrchunkige Dokumente hielt beide Domänen zugleich unauffällig
  (comic-characters bit-identisch, city-landmarks vollständig erholt). Details und alle drei
  Messreihen in der Beschreibung von PR [#940](https://github.com/criew/opaa/pull/940). Erfordert
  einen vollständigen Reindex jeder Bibliothek (siehe
  [Deployment-Handbuch](../handbuch/deployment.md#was-ein-update-mit-dem-index-macht)) - Alt-Chunks
  ohne Präfix und Neu-Chunks mit Präfix ranken sonst inkonsistent gegeneinander.

### Bekannte offene Schwächen (aus den #912-Verifikationen)

- **Chunk-Granularität allgemein.** Contextual Chunking (oben) mildert das Signalverlust-Problem für
  den häufigsten Fall (Dateiname als Minimalsignal), löst es aber nicht vollständig - ein Chunk trägt
  weiterhin keinen Abschnittstitel oder Bibliothekskontext. Dokument-Vervollständigung (Schritt 6)
  behebt zusätzlich das Symptom bei Geschwister-Chunks desselben Dokuments (der richtige Chunk fällt
  nicht mehr aus der Auswahl, sofern er unter den `fetch-k` Kandidaten war), nicht die Ursache.
- **Einchunkige Dokumente konkurrieren ohne Kontextsignal gegen präfixierte mehrchunkige.** Contextual
  Chunking gibt nur mehrchunkigen Dokumenten ein Kontext-Präfix (siehe oben) - ein einchunkiges
  Dokument in derselben Bibliothek rankt dadurch relativ zu einem thematisch verwandten, jetzt
  präfixierten mehrchunkigen Dokument tendenziell schlechter als vor #933, selbst wenn es inhaltlich
  ebenso einschlägig ist. Live im #938-Kontext beobachtet: `02_gebuehrenbefreiung-beduerftigkeit.docx`
  (einchunkig) fällt für `maria.weber`s Leserechte-Scope von Rang 26/147 auf Rang 119/120 (siehe PR
  [#940](https://github.com/criew/opaa/pull/940), Abschnitt „Offener Punkt gegen #938"). Kein
  behobener Fall, sondern ein bekannter, noch offener Zielkonflikt dieses Ansatzes.
- **Nicht-rangfaire Geschwister-Sortierung über Teilfragen.** Bei mehreren Suchanfragen bestimmt
  `DocumentCompletion#complete`, welcher noch nicht ausgewählte Chunk eines zu vervollständigenden
  Dokuments als nächstes nachrückt, über die Reihenfolge im Kandidatenpool — der schlichten
  Aneinanderreihung der `fetch-k`-Kandidatenlisten aller Teilfragen (`RetrievalState#candidatePool`,
  `DocumentCompletion#unusedCandidatesByDocument`). Das ist die Ankunftsreihenfolge der Suchanfragen, keine
  über Teilfragen hinweg vergleichbare Rangfolge: Ein Geschwister-Chunk aus der zweiten oder dritten
  Teilfrage rankt in dieser Poolreihenfolge strukturell schlechter als ein gleich relevanter aus der
  ersten, unabhängig vom tatsächlichen Relevanzunterschied. Betrifft nur die Auswahl **zwischen mehreren
  Kandidaten desselben zu vervollständigenden Dokuments** — der äußere `topK`-Wettbewerb zwischen
  verschiedenen Dokumenten läuft weiterhin über die rangfaire Reciprocal Rank Fusion (Schritt 5).
- **Reine Vektorsuche verfehlt Dokumente, deren Embedding-Signal trotz wörtlicher Begriffstreffer zu
  schwach ist — akzeptierte Grenze (Maintainer-Entscheidung in #938).** Der belegte Fall: Für „Was
  gilt bei Gebührenbefreiung wegen Bedürftigkeit?" rankt `01_verwaltungsgebuehrensatzung.pdf` im
  Leserechte-Scope eines Kfz-Kontos auf Rang 50 (Score 0,553 gegen 0,59–0,63 thematisch fremder
  Konkurrenz), obwohl § 3 VGS die Anfragebegriffe („Befreiung", „Bedürftigkeit") wörtlich enthält.
  Keine Auswahlmechanik dieses Dokuments (topK, MMR, Dokument-Vervollständigung,
  Teilfragen-Zerlegung) kann einen solchen Rückstand überbrücken — sie ordnen nur, was die
  Vektorsuche liefert. Der passende Mechanismus wäre eine lexikalische Suchkomponente
  (Hybrid-Suche, siehe unten); sie ist als Arbeitspaket 2 von
  [Hybride Suche mit Reranking](./hybrid-retrieval.md) beauftragt und mit #1048/#1049 zur Hälfte
  gebaut: Der lexikalische Pfad findet ein Dokument jetzt auch dann, wenn sein Embedding die Anfrage
  nicht erreicht, sofern der Anfragebegriff wörtlich darin steht (auf der Verwaltungsdomäne löst der
  Pipeline-Pfad seither zwei der neun `literal_term_weak_embedding`-Fälle, die Hit Rate@5 dieser Klasse
  stieg von 0,556 auf 0,889). Was fehlt, ist die zweite Hälfte: ein Reranker für die Fälle, in denen die
  richtige Fundstelle im Fenster liegt, aber nicht weit genug oben (Diagnose vollständig in #938).

### Etablierte Verfahren, die OPAA noch nicht nutzt

Als Ideen zu verstehen, ohne Zusage einer Umsetzung.

- ~~**Hybrid-Suche (BM25 + Vektor).**~~ Gebaut mit #1048/#1049 — Schritt 3b und die erweiterte Fusion
  in Schritt 5. Was hier als Trade-off stand (zweiter Suchindex, score-unabhängige Zusammenführung),
  ist eingelöst: Der Index liegt in derselben PostgreSQL-Datenbank (`tsvector`/GIN statt einer eigenen
  Engine), zusammengeführt wird rangbasiert. Es ist `ts_rank` statt BM25 — die bekannte Grenze steht in
  [Hybride Suche mit Reranking](./hybrid-retrieval.md#die-bekannte-grenze-ts_rank-ist-kein-bm25).
- **Cross-Encoder-Reranking.** Ebenfalls Zielbild in `data-indexing-rag.md`
  ([Reranking](./data-indexing-rag.md#reranking)), heute nicht gebaut. Ein Modell, das Frage und Passage
  gemeinsam statt getrennt bewertet, erkennt Passung genauer als eine reine Vektorähnlichkeit — kostet
  aber einen zusätzlichen Modellaufruf je Kandidat (oder Batch) und damit Latenz und Betrieb eines weiteren
  Modells.
- **HyDE (Hypothetical Document Embeddings).** Das Sprachmodell erzeugt vor der Suche eine hypothetische
  Antwort und embedded diese statt der Frage — eine Antwort ähnelt einem Fundstellentext oft mehr als eine
  Frage. Trade-off: ein zusätzlicher LLM-Aufruf vor jeder Suche, und eine plausible, aber falsche
  hypothetische Antwort kann die Suche in eine falsche Richtung lenken.
- **Query-Expansion.** Die Suchanfrage wird um Synonyme oder verwandte Begriffe ergänzt (klassisch
  lexikalisch, oder LLM-gestützt) statt in eigenständige Teilfragen zerlegt zu werden — anders als
  Teilfragen-Zerlegung (Schritt 2), die Themen trennt, verbreitert Query-Expansion ein einzelnes Thema.
  Trade-off: mehr Trefferabdeckung bei mehrdeutigen Begriffen, aber mehr Rauschen, wenn die Expansion am
  eigentlichen Sinn vorbeigeht.
