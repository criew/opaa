# GraphRAG – Was es ist, wie es funktioniert, Quellen & Implementierungen

> Recherche-Dokument für OPAA · Stand: 2026-06-15 · Issue #317
> Zweck: Entscheidungsgrundlage, ob und wie GraphRAG in OPAA (DSGVO-konformes, selbst-gehostetes Enterprise-KI-Wissenssystem) eingesetzt werden sollte.

---

## 1. Kurzfassung (TL;DR)

**GraphRAG** ist eine Weiterentwicklung von Retrieval-Augmented Generation (RAG), bei der die Wissensbasis nicht nur als flache Sammlung von Text-Chunks in einem Vektor-Index liegt, sondern zusätzlich als **Wissensgraph** (Knowledge Graph) strukturiert wird. Entitäten (Personen, Organisationen, Orte, Konzepte, Ereignisse) werden zu **Knoten**, ihre Beziehungen zu **Kanten**.

Der zentrale Vorteil: GraphRAG kann **globale, übergreifende Fragen** über einen gesamten Korpus beantworten („Was sind die zentralen Themen dieses Datenbestands?", „Wie hängen Projekt A und Abteilung B zusammen?") – also genau die Fragen, an denen klassisches Vektor-RAG scheitert, weil es nur die k ähnlichsten Einzel-Chunks findet und den größeren Zusammenhang verliert.

Der Preis dafür: deutlich **teurere und langsamere Indexierung** sowie höhere Komplexität im Betrieb. Diese Kosten sind 2024–2026 allerdings dramatisch gesunken (siehe Abschnitt 6).

---

## 2. Das Problem mit klassischem (Vektor-)RAG

Bei Standard-RAG werden Dokumente in Chunks zerlegt, jeder Chunk wird in einen Embedding-Vektor übersetzt und in einer Vektordatenbank abgelegt. Bei einer Anfrage werden die `k` semantisch ähnlichsten Chunks gesucht und dem LLM als Kontext mitgegeben.

Schwächen dieses Ansatzes:

- **Strukturverlust:** Jeder Chunk ist ein isolierter Vektor. Überschriften, narrativer Fluss, Querverweise zwischen Absätzen – all das geht verloren.
- **Kein „Verbinden der Punkte":** Informationen, die über viele Dokumente verstreut sind, werden nicht zusammengeführt. Ähnlichkeitssuche findet lokal Ähnliches, nicht global Zusammenhängendes.
- **Schlechte Antworten auf globale Fragen:** „Fasse die wichtigsten Risiken über alle Projektberichte hinweg zusammen" lässt sich nicht über Top-k-Ähnlichkeit beantworten – die Antwort steckt im Gesamtbild, nicht in 5 Einzel-Chunks.

GraphRAG adressiert genau diese Lücke.

---

## 3. Wie GraphRAG funktioniert

GraphRAG besteht aus zwei Phasen: einer **Indexierungs-Pipeline** (einmalig/inkrementell, teuer) und einer **Abfrage-Pipeline** (zur Laufzeit).

### 3.1 Indexierung (Graph-Aufbau)

1. **Chunking:** Quelldokumente werden in Text-Einheiten zerlegt (wie bei klassischem RAG).
2. **Entitäts- & Beziehungs-Extraktion:** Ein LLM liest jeden Chunk und extrahiert Entitäten (Knoten) sowie deren Beziehungen (Kanten) inkl. Beschreibungen. Jeder Knoten bleibt mit seinem Quell-Chunk verknüpft → **Nachvollziehbarkeit/Provenienz** der Belege.
3. **Graph-Konstruktion:** Aus den Extraktionen wird ein Wissensgraph gebaut. Gleiche Entitäten aus verschiedenen Chunks werden zusammengeführt (Entity Resolution).
4. **Community-Erkennung (Clustering):** Mit dem **Leiden-Algorithmus** wird der Graph hierarchisch in „Communities" (eng zusammenhängende Teilgraphen) partitioniert – rekursiv, bis zu Blatt-Communities. So entstehen mehrere Granularitätsebenen.
5. **Community-Summaries:** Für jede Community auf jeder Ebene erstellt das LLM eine Zusammenfassung. Diese Summaries sind der Schlüssel für globale Fragen: Sie verdichten den gesamten Korpus „divide and conquer" zu navigierbaren Übersichten.

### 3.2 Abfrage (Retrieval & Generierung)

Microsoft GraphRAG kennt mehrere Such-Modi:

- **Local Search (lokal):** Für gezielte Fragen zu konkreten Entitäten. Startet bei den relevanten Knoten, traversiert deren Nachbarschaft (N Hops), zieht zugehörige Text-Chunks und Beziehungen heran. Antwortet auf „Was weißt du über X?".
- **Global Search (global):** Für ganzheitliche Fragen über den gesamten Korpus. Nutzt die **Community-Summaries** in einem Map-Reduce-Verfahren: Jede relevante Summary liefert einen Teilbeitrag (Map), die Teilbeiträge werden zur finalen Antwort zusammengeführt (Reduce). Antwortet auf „Was sind die übergreifenden Themen / Risiken / Zusammenhänge?".
- **DRIFT Search** (Dynamic Reasoning and Inference with Flexible Traversal): Kombiniert global + lokal. Startet mit Community-Kontext (Big Picture) und verfeinert dann lokal (Details). Deckt reale Fragen ab, die beides brauchen, bei kontrolliertem Kosten-/Qualitäts-Tradeoff.

---

## 4. GraphRAG vs. Vektor-RAG – wann was?

| Kriterium | Vektor-RAG | GraphRAG |
|---|---|---|
| Lokale Faktenfragen („Was steht in Dokument X?") | ✅ Sehr gut, günstig | ✅ Gut (Local Search) |
| Globale Synthese-Fragen („Hauptthemen über alles?") | ❌ Schwach | ✅ Stärke (Global Search) |
| Mehrschritt-Reasoning über verstreute Fakten | ❌ Schwach | ✅ Stark |
| Indexierungskosten | 💰 Niedrig (~2–5 $/Korpus) | 💰💰 Höher (20–500 $/Korpus, sinkend) |
| Indexierungsgeschwindigkeit | Schnell | Langsam (viele LLM-Calls) |
| Nachvollziehbarkeit / Provenienz | Mittel | Hoch (Knoten ↔ Quell-Chunk) |
| Betriebskomplexität | Niedrig | Höher (Graph-DB, Re-Indexing, Drift) |
| Inkrementelle Updates | Einfach | Aufwändig (Risiko Embedding-/Community-Drift) |

**Faustregel:** GraphRAG lohnt sich, wenn der Wert in den **Zusammenhängen** zwischen Informationen liegt (Enterprise-Wissen, Untersuchungen, narrative/private Daten). Für reine FAQ-/Lookup-Szenarien ist Vektor-RAG oft ausreichend und günstiger. In der Praxis setzen viele Systeme **hybrid** auf beides (Vektor-Recall + Graph-Reasoning).

---

## 5. Open-Source-Implementierungen

| Implementierung | Herkunft | Charakteristik | Eignung für OPAA |
|---|---|---|---|
| **microsoft/graphrag** | Microsoft Research | Referenz-Implementierung. Voller Funktionsumfang (Leiden-Communities, Global/Local/DRIFT Search). Schwergewichtig, teure & langsame Indexierung. Jan-2025-Update: „Dynamic Community Selection" senkt Token-Verbrauch um ~79 %. | Funktional vollständig, aber ressourcenintensiv. Gute Referenz/Benchmark. |
| **LazyGraphRAG** | Microsoft Research (2024/25) | Verschiebt Kosten von Index- zur Query-Zeit; senkt Indexierungskosten auf ~0,1 % bei vergleichbarer Qualität. Neuer Qualitäts-/Kosten-Standard. | Sehr interessant für kostensensible Self-Hosting-Szenarien. |
| **LightRAG** (HKU, `lightrag-hku`) | Akademisch (Uni Hongkong) | Verzichtet auf Community-Detection, nutzt **Dual-Level-Retrieval**. Erreicht 70–90 % der GraphRAG-Qualität zu ~1/100 der Kosten. Schnelle, günstige Indexierung. | Top-Kandidat für ein schlankes, selbst-gehostetes OPAA-Feature. |
| **nano-graphrag** | Community (gusye1234) | ~1.100 Zeilen Code, leicht lesbar/anpassbar. Async; unterstützt Faiss, Neo4j, Ollama. | Ideal zum schnellen Prototyping / Verstehen der Mechanik. |
| **Neo4j GraphRAG-Ökosystem** | Neo4j | Cypher-Graph-Queries + Text-Chunk-Retrieval; kombiniert mit Vektor-Recall. Integration mit LangChain. Enterprise-tauglich. | Stark, wenn ohnehin eine Graph-DB betrieben wird; on-prem möglich. |
| **Graphiti** (Zep) | Zep AI | Temporaler, inkrementell aktualisierbarer Wissensgraph (Fokus auf Agenten-Memory/Echtzeit-Updates). | Relevant, falls OPAA kontinuierliche/aktuelle Wissens-Updates braucht. |

Alle genannten Implementierungen lassen sich grundsätzlich **selbst hosten** und mit **lokalen LLMs (z. B. via Ollama)** sowie offenen Embedding-Modellen betreiben – passend zur OPAA-Anforderung „digitale Souveränität, kein Vendor Lock-in". LightRAG, nano-graphrag und Neo4j+Ollama haben dokumentierte Local-Setups.

---

## 6. Kosten- & Produktionsaspekte

- **Kosten-Cliff:** Die Indexierung eines Datensatzes kostete Anfang 2024 noch ~33.000 $; bis Mitte 2025 auf einen Bruchteil (~0,1 %) gefallen – getrieben durch LazyGraphRAG, Dynamic Community Selection und günstigere/lokale Modelle.
- **Zwei Kostenzentren:** (1) Graph-Konstruktion (Indexierung) und (2) Graph-Retrieval (Query-Zeit). Optimierungen zielen darauf, Arbeit von der Index- in die Query-Phase zu verlagern (LazyGraphRAG) oder Community-Auswahl dynamisch zu beschränken.
- **Betriebs-Herausforderungen in Produktion:**
  - **Embedding-/Community-Drift** bei inkrementellen Updates (neue vs. alte Vektoren/Communities werden inkonsistent).
  - **Re-Indexing-Kosten** bei kontinuierlichen Datenströmen; oft Offline-Batch nötig.
  - **Latenz** (Sub-50ms-Anforderungen treiben Infrastruktur).
  - **Multi-Tenancy** und Mandantentrennung (für OPAA-Enterprise relevant).

---

## 7. Relevanz & Empfehlung für OPAA

GraphRAG passt strategisch sehr gut zu OPAAs Kernversprechen, **Organisationswissen über RAG und LLMs zugänglich zu machen** – gerade weil Enterprise-Wissen typischerweise stark vernetzt und über viele Dokumente verteilt ist (genau GraphRAGs Stärke).

**Empfohlenes Vorgehen (Vorschlag, noch zu priorisieren):**

1. **Hybrid-Architektur** anstreben: bestehendes Vektor-RAG behalten, GraphRAG für globale/Reasoning-Fragen ergänzen.
2. **Leichtgewichtige Implementierung evaluieren:** Start mit **LightRAG** oder **nano-graphrag** (günstig, self-hostable, Ollama-kompatibel) statt der schweren MS-Referenz.
3. **DSGVO/Souveränität:** Komplett on-prem mit lokalem LLM + offenen Embeddings – kein Datenabfluss. Deckt sich mit OPAAs Kernprinzipien.
4. **PoC mit echtem OPAA-Korpus** aufsetzen, Indexierungskosten und Antwortqualität (lokal vs. global) gegen das aktuelle Vektor-RAG messen.

> Nächster Schritt nach Freigabe: technisches Spike-/PoC-Issue für eine GraphRAG-Evaluierung (LightRAG vs. nano-graphrag, on-prem, Ollama) anlegen.

---

## 8. Quellen

**Grundlagen & Microsoft GraphRAG**
- [GraphRAG: Unlocking LLM discovery on narrative private data – Microsoft Research](https://www.microsoft.com/en-us/research/blog/graphrag-unlocking-llm-discovery-on-narrative-private-data/)
- [Project GraphRAG – Microsoft Research](https://www.microsoft.com/en-us/research/project/graphrag/)
- [GraphRAG – offizielle Doku](https://microsoft.github.io/graphrag/)
- [microsoft/graphrag – GitHub](https://github.com/microsoft/graphrag)
- [From Local to Global: A Graph RAG Approach to Query-Focused Summarization (arXiv 2404.16130)](https://arxiv.org/pdf/2404.16130)

**Such-Modi & Algorithmen**
- [DRIFT Search – GraphRAG Doku](https://microsoft.github.io/graphrag/query/drift_search/)
- [Global Community Summary Retriever – graphrag.com](https://graphrag.com/reference/graphrag/global-community-summary-retriever/)
- [GraphRAG: From Local to Global – Medium (Shashank Vats)](https://medium.com/@shashankvats/graphrag-from-local-to-global-031c8a4156c9)

**Erklärungen & Vergleiche**
- [GraphRAG Explained: Enhancing RAG with Knowledge Graphs – Zilliz/Medium](https://medium.com/@zilliz_learn/graphrag-explained-enhancing-rag-with-knowledge-graphs-3312065f99e1)
- [GraphRAG: Graph-Based Retrieval-Augmented Generation – DataCamp](https://www.datacamp.com/tutorial/graphrag)
- [VectorRAG vs GraphRAG: Technical Challenges – FalkorDB](https://www.falkordb.com/blog/vectorrag-vs-graphrag-technical-challenges-enterprise-ai-march25/)
- [Graph RAG in Production: Microsoft GraphRAG vs LightRAG vs Neo4j Graphiti – paperclipped.de](https://www.paperclipped.de/en/blog/graph-rag-production/)

**Implementierungen**
- [LightRAG (HKU) – PyPI](https://pypi.org/project/lightrag-hku)
- [nano-graphrag – GitHub](https://github.com/gusye1234/nano-graphrag)
- [Get started with GraphRAG: Neo4j ecosystem tools – Neo4j Blog](https://neo4j.com/blog/news/graphrag-ecosystem-tools/)
- [Implementing 'From Local to Global' GraphRAG with Neo4j and LangChain](https://neo4j.com/blog/developer/global-graphrag-neo4j-langchain/)
- [Running GraphRAG locally with Neo4j and Ollama – Medium](https://sandeep14.medium.com/running-graphrag-locally-with-neo4j-and-ollama-text-format-371bf88b14b7)
- [5 Best Open Source Graph RAG Tools (2026) – TypeGraph](https://typegraph.ai/blog/best-open-source-graph-rag-tools)

**Kosten & Produktion**
- [LazyGraphRAG sets a new standard for quality and cost – Microsoft Research](https://www.microsoft.com/en-us/research/blog/lazygraphrag-setting-a-new-standard-for-quality-and-cost/)
- [The GraphRAG Cost Cliff: How $33,000 Became $33 – Medium (Graph Praxis)](https://medium.com/graph-praxis/the-graphrag-cost-cliff-how-33-000-became-33-in-eighteen-months-be1b0fbe37e4)
- [Reduce GraphRAG Indexing Costs – FalkorDB](https://www.falkordb.com/blog/reduce-graphrag-indexing-costs/)
- [How Microsoft GraphRAG works with Graph Databases – Memgraph](https://memgraph.com/blog/how-microsoft-graphrag-works-with-graph-databases)
