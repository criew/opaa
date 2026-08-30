# Discussion: Retrieval-Roadmap für OPAA — Phasen und bewusste Nicht-Entscheidungen

**Thema:** Ableitung konkreter Ausbauphasen des OPAA-Retrievals aus dem [Tech-Report zu Retrieval-Strategien](discussion-retrieval-strategien.md), einschließlich der Strategien, die bewusst **nicht** verfolgt werden sollten — mit Begründung.

**Status:** Diskussionsvorschlag. Entscheidungen (insbesondere die Aufhebung der #938-Zurückstellung der Hybrid-Suche und jede Phase-3-Wette) liegen beim Maintainer.

**Rahmenbedingungen, die jede Phase einhalten muss:**

1. **pgvector bleibt der einzige Vektorspeicher** (ADR-0014). Eine zweite Suchengine (Elasticsearch, OpenSearch, Vespa) ist ein Betriebs- und Souveränitätspreis, der nur bei nachgewiesener Unzulänglichkeit von PostgreSQL zur Debatte steht.
2. **Rechtefilter in der Suche, nie als Nachfilter** (ADR-0008) — gilt für jeden neuen Suchpfad (Volltext, Graph, Summary-Index) gleichermaßen.
3. **On-prem-Fähigkeit:** Jeder Baustein muss ohne Cloud-API betreibbar sein; Cloud-Varianten sind zulässige Alternativen, nie Voraussetzung.
4. **Messpflicht:** Keine Strategie wird ohne Golden-Fälle eingeführt, die ihren behaupteten Nutzen messen — und ohne Beleg, dass die bestehenden Baselines (ADR-0013) nicht regredieren. Der Eval-Harness muss dafür die **produktive Pipeline** messen können, nicht nur die rohe Vektorsuche (heutige Lücke, siehe Phase 0).
5. **Zitierpflicht:** Verfahren, deren Belege auf LLM-Zusammenfassungen statt Originaltext zeigen, sind unvereinbar mit der deterministischen Zitatvalidierung (#939).

---

## Phase 0 — Messbarkeit herstellen (Vorbedingung, kein Feature)

Bevor irgendeine Strategie eingeführt wird:

- **Pipeline-Evaluation:** Der Eval-Harness misst heute `VectorStore.similaritySearch` direkt und läuft an Teilfragen-Zerlegung, RRF, MMR und DocumentCompletion vorbei. Ergänzung eines Messpfads durch die produktive Query-Pipeline (ohne Antwortgenerierung), damit A/B-Vergleiche das messen, was Nutzer erleben.
- **Golden-Fälle für die bekannten Lücken:** (a) Fälle der #938-Klasse (wörtlicher Begriff im Dokument, Vektorsuche verfehlt); (b) Kennungs-Fälle (§-Referenzen, Aktenzeichen); (c) erste Multi-Hop-Fälle (Kette über ≥2 Dokumente) — Letztere sind die **Messgrundlage für die spätere Graph-Entscheidung** (Phase 3).
- **Deutschsprachige Verwaltungs-Evaldomäne:** Die bestehenden Korpora (Comic englisch/einchunkig, Städte deutsch/mehrchunkig) decken Verwaltungssprache nicht ab. Eine dritte Domäne aus synthetischen Verwaltungsdokumenten (Muster: Rheinfurt-Korpus, aber als eingefrorenes Messartefakt getrennt von der Demo) macht Aussagen über Amtssprache, Komposita und Kennungen erst belastbar.

## Phase 1 — Das Fundament: Hybrid-Suche und Reranking

Das Zielbild ([data-indexing-rag.md](../features/data-indexing-rag.md)) hat beide Fragen bereits mit „ja" beantwortet; #938 liefert den gemessenen Beweis des Bedarfs. Diese Phase schließt die Lücke zwischen Zielbild und Ist-Stand.

**1a — Lexikalischer Suchpfad in PostgreSQL.** Volltextsuche über die vorhandene Chunk-Tabelle (`tsvector` mit `german`-Konfiguration, GIN-Index), plus Behandlung der deutschen Besonderheiten: Schutz exakter Kennungen (§-Referenzen, Aktenzeichen, Erlassnummern — als zusätzliche, unzerlegte Tokens bzw. eigenes Feld) und perspektivisch Komposita-Zerlegung (PostgreSQL kann per ispell-Dictionary decompounden; Qualität gegen den german-decompounder-Ansatz messen). Der Rechtefilter (`library_id`) gilt im Volltextpfad identisch. **Kein neues System, kein neuer Betriebsaufwand** — der bewusste Unterschied zu RAGFlow/Onyx, erkauft mit BM25-Näherung (`ts_rank` statt echtem BM25; für den Fusion-Einsatz genügt die Rangordnung, und genau das misst Phase 0 nach).

**1b — Fusion über den vorhandenen RRF.** `ReciprocalRankFusion` fusioniert bereits die Teilfragen-Listen; der Volltextpfad wird eine weitere Eingangsliste je Teilfrage. RRF statt gewichteter Scores als Start (tuningfrei, robust); Score-Gewichtung erst, wenn Golden-Set-Messungen ein Tuning tragen (Evidenz: Bruch et al., TOIS 2023).

**1c — Cross-Encoder-Reranking.** Nach der Fusion, vor DocumentCompletion: Kandidatenmenge (fetch-k, ggf. angehoben auf ~50) durch einen Reranker, Top-k danach. On-prem-Kandidaten: `bge-reranker-v2-m3` (Apache 2.0, etabliert) oder `Qwen3-Reranker-4B` (Apache 2.0, deutlich stärker, größer); Modellwahl per Eval-Harness auf der Verwaltungs-Evaldomäne entscheiden, nicht per Leaderboard. Bereitstellung als eigener Aufgabentyp im Modell-Schichtenmodell ([llm-integration.md](../features/llm-integration.md) sieht die Rerank-Rolle bereits vor). Latenzbudget: &lt;200 ms (Zielbild) — auf CPU nur mit kleinen Modellen/Kandidatenmengen erreichbar, das ist eine zu messende Betriebsentscheidung.

**Erwarteter Effekt (Evidenzlage):** Die Kette Hybrid+Reranking ist die am besten belegte Einzelinvestition des Feldes (Azure: nDCG@3 43,8 → 60,1; Anthropic: Fehlerrate −49 % bzw. −67 % mit kontextualisiertem BM25 und Reranking; kapa.ai-Praxisbefund). Sie löst die #938-Klasse und Szenario 2 (Kennungen) strukturell.

## Phase 2 — Kontextqualität und Frageverstehen

Nach dem Fundament die gezielten Verbesserungen, jede einzeln messbar:

- **2a — Contextual Chunking ausbauen (#933-Folgearbeit):** Abschnittstitel und Bibliothekskontext in den Embedding-Präfix; die dokumentierte Benachteiligung einchunkiger Dokumente auflösen. Mit Phase 1 gewinnt das doppelt: Der Kontextpräfix speist dann auch den Volltextindex (Anthropics „contextual BM25").
- **2b — Strukturbewusstes Chunking pro Dokumenttyp:** Umsetzung der [Pipeline-pro-Dokumenttyp-Diskussion](discussion-retrieval-document-pipelines.md), konkretisiert durch die verwaltungsspezifische Dateityp-Tabelle in [discussion-dateitypen-und-metadaten.md](discussion-dateitypen-und-metadaten.md) — §/Absatz-Schnitt für Satzungen, Folien-Schnitt für PPTX, Überschriften für Markdown; Formatzulassung erweitern (ODF als Quick Win, XLSX/CSV, Scan-PDF/OCR als eigenes Epic). Chunk-Größen dabei erstmals messen statt setzen (Evidenz spricht für kleinere Chunks als die heutigen 1000 Token, mit Parent-Kontext ausgleichen).
- **2f — Metadatenschema pro Bibliothek mit geführtem Assistenten:** LLM-Vorklassifikation schlägt beim Anlegen einer Bibliothek Typ-Pipelines und ein Metadatenschema vor (Fassung, Rechtsebene, §, Projekt …), der Nutzer beschließt; Felder wirken als Filter, Kontextpräfix und Beleg-Anzeige. Konzept und Leitplanken: [discussion-dateitypen-und-metadaten.md](discussion-dateitypen-und-metadaten.md).
- **2c — Parent-Document/Small-to-Big:** Kleinere Chunks fürs Matching, größerer Elternabschnitt in den LLM-Kontext. Ergänzt DocumentCompletion; behebt zugleich die dokumentierte nicht-rangfaire Geschwister-Sortierung.
- **2d — Embedding-Modellwechsel evaluieren:** `nomic-embed-text` ist die gemessene Schwachstelle in #938. Kandidaten mit deutscher Evidenz: BGE-M3, Qwen3-Embedding (beide offen, on-prem). Voll-Reindex ist eingepreist (Modell bewusst nicht zur Laufzeit wechselbar); Entscheidung ausschließlich per Eval auf der Verwaltungsdomäne — nach Phase 1, weil Hybrid die Modellwahl entlastet und die Messung sonst zwei Variablen mischt.
- **2e — Query-Rewriting/HyDE selektiv:** Nur als Zusatzpfad in der Fusion und nur für erkennbar bürgersprachliche Fragen (Router-Kriterium), zur Überbrückung der Vokabellücke. Nicht als Default — die Evidenz zeigt Verschlechterung bei bereits guten Queries.

## Phase 3 — Erweiterte Frageklassen (bedingt, nach Nachweis)

Jeder Baustein dieser Phase hat eine **Eintrittsbedingung**, die Phase 0 messbar gemacht hat:

- **3a — Deep-Research-Modus** (im Zielbild bereits vorgesehen): eigener, sichtbar langsamer Recherche-Modus für Berichtsaufträge und Überblicksfragen (Szenario 6). Eintrittsbedingung: Nutzerbedarf an Berichtsaufträgen; Technik: iterative Suche über die Phase-1-Pipeline, keine neue Infrastruktur.
- **3b — Leichter Korrektur-Loop (CRAG-Muster):** Bewerter prüft das Retrieval-Ergebnis, bei Unbrauchbarkeit eine Umformulierungs-/Nachsuch-Runde, hartes Stoppkriterium. Eintrittsbedingung: messbarer Anteil an Fragen, die erst im zweiten Anlauf treffen.
- **3c — Wissensgraph:** Eintrittsbedingung: Die Multi-Hop-Golden-Fälle aus Phase 0 zeigen eine relevante, mit Phase 1+2 nicht schließbare Lücke, **und** solche Fragen kommen im realen Nutzungsprofil vor. Dann in dieser Reihenfolge prüfen: (1) **kuratierter Fachgraph** (Zuständigkeiten, Normverweise — deterministisch, rechtssicher, aber Pflegeaufwand beim Fachbereich ehrlich benennen); (2) automatische Extraktion nach HippoRAG-2-/LightRAG-Muster als PoC gemäß der offenen Empfehlung aus [GraphRAG.md](GraphRAG.md) (#317). Harte Auflage: Rechteprüfung im Graphen zur Abfragezeit (Kanten zu unlesbaren Dokumenten dürfen nicht existieren/sichtbar sein) — ungelöst in allen Frameworks, also Eigenbau-Anteil.
- **3d — Long-Context-Dokumentmodus:** „Nimm dieses eine Dokument ganz in den Kontext" für Zusammenfassungs-/Analyseaufträge auf ein per Retrieval oder Auswahl bestimmtes Dokument. Kein Ersatz für Retrieval (Rechte, Kosten, Context Rot), sondern ein expliziter Werkzeugwechsel.

---

## Bewusst nicht verfolgen

Jede Nicht-Entscheidung mit Begründung; „nicht verfolgen" heißt: kein Issue, kein PoC, Wiedervorlage nur bei geänderter Faktenlage.

| Strategie | Begründung |
|---|---|
| **Microsoft-GraphRAG-Vollindexierung** | ~1000× Indexkosten, ungelöstes Update-Problem, Repo im Wartungsmodus; Microsofts eigene Nachfolgelinie (LazyGraphRAG) verwirft das Modell „teuer vorab indexieren". Der Nutzen liegt in Frageklassen, die für OPAA erst nachzuweisen sind (→ 3c deckt den Bedarfsfall günstiger ab). |
| **RAPTOR / hierarchische Summary-Bäume** | Antworten belegen dann LLM-Zusammenfassungen statt Originaltext — unvereinbar mit Zitierpflicht und deterministischer Faktenprüfung (#939). Dazu nichtdeterministischer Index und Neuberechnung bei Dokumentänderung. Der legitime Kern (Überblicksfragen) wird von 3a besser und auditierbar bedient. |
| **Semantic Chunking** | NAACL-Findings 2025: Mehrkosten ohne konsistenten Gewinn. Für strukturierte Verwaltungsdokumente ist strukturbasiertes Schneiden (2b) das richtige Werkzeug, für den Rest genügt Token-Chunking mit Kontextpräfix. |
| **Self-RAG und RL-trainierte Retrieval-Agenten** | Erfordern eigenes Modelltraining; trainingsfreie Workflows erreichen laut Studienlage vergleichbare Ergebnisse. Für ein Open-Source-Projekt mit on-prem-Anspruch nicht wart- und reproduzierbar. |
| **SPLADE/ELSER (learned sparse)** | Für Deutsch nicht belegt; führende Modelle CC-BY-NC bzw. an Elastic-Lizenzen gebunden. PostgreSQL-Volltext (1a) deckt den lexikalischen Bedarf; Beobachtungsposten: OpenSearch Neural Sparse multilingual, falls Deutsch-Evidenz entsteht. |
| **ColBERT/Late Interaction als Baustein** | pgvector beherrscht kein natives MaxSim (ADR-0014-Konflikt), Speicherkosten 10–50×, bestes multilinguales Modell CC-BY-NC. Der Präzisionsgewinn bei Kennungen wird von 1a (exakte Felder) billiger erreicht. |
| **Zweite Suchengine (Elasticsearch/OpenSearch/Vespa/Infinity)** | Der RAGFlow-/Onyx-Weg — für OPAA ein dauerhafter Betriebs- und Kompetenzpreis bei jeder Behörden-Installation. Erst legitim, wenn Messungen zeigen, dass PostgreSQL-Volltext+pgvector die Qualitäts- oder Lastanforderungen nicht erfüllt. |
| **Long Context als RAG-Ersatz** | Rechtefilterung und belegpflichtige Fundstellen sind nur über Retrieval abbildbar; dazu Context-Rot-Evidenz und 8–82× Kosten. Als Werkzeugmodus für Einzeldokumente (3d) dagegen sinnvoll. |
| **Multi-Query-Expansion als Default** | Verschlechtert ohne Reranker die Präzision (ARAGOG); OPAAs Teilfragen-Zerlegung deckt den legitimen Kern bereits ab. Nach Phase 1c ggf. als Reranker-gestützte Recall-Stufe neu bewerten. |
| **LLM-as-Reranker im Antwortpfad** | 100+-fache Latenz gegenüber destillierten Cross-Encodern bei gleicher Qualität; gehört in die Offline-Evaluation (dort durchaus nützlich als Judge). |

---

## Offene Fragen für die Maintainer-Entscheidung

1. **#938-Zurückstellung aufheben?** Die dortige Entscheidung („bekannte Grenze dokumentieren, Hybrid nicht beauftragen") stammt aus dem Demo-Kontext. Dieser Vorschlag macht Hybrid zum Fundament von Phase 1 — das ist eine Umkehr, die bewusst getroffen werden sollte.
2. **Latenz- vs. Qualitätsbudget für Reranking on-prem:** GPU-Annahme für Behörden-Installationen ja/nein? Das entscheidet über die Modellklasse (bge-v2-m3 auf CPU vs. Qwen3-4B auf GPU).
3. **Verwaltungs-Evaldomäne:** eigener eingefrorener Korpus (Aufwand) vs. Wiederverwendung des Rheinfurt-Korpus als Messartefakt (Kopplungsrisiko Demo↔Eval)?
4. **Phasen-Zuschnitt in Issues:** Phase 0+1 sind nach diesem Vorschlag issue-reif; Phase 2 nach ersten Messergebnissen; Phase 3 ausdrücklich nicht vor Eintrittsbedingung.
