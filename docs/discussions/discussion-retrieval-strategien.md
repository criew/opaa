# Discussion: Retrieval-Strategien für OPAA — Tech-Report

**Thema:** Systematische Übersicht moderner Retrieval-/RAG-Strategien (Stand 2025/2026) mit Vor- und Nachteilen, heruntergebrochen auf Einsatzszenarien der öffentlichen Verwaltung.

**Kontext:** Aufbauend auf der [Ist-Stand-Spezifikation des Retrieval-Algorithmus](../features/retrieval-algorithm.md) (#936), der [GraphRAG-Recherche](GraphRAG.md) (#317), den Diskussionen zu [Embeddings](discussion-embeddings.md), [Dokument-Pipelines](discussion-retrieval-document-pipelines.md) und [RAG-Evaluation](discussion-rag-evaluation.md) sowie dem Zielbild in [data-indexing-rag.md](../features/data-indexing-rag.md). Die Roadmap-Ableitung für OPAA steht separat in [discussion-retrieval-roadmap-opaa.md](discussion-retrieval-roadmap-opaa.md).

**Leseanleitung:** Teil I erklärt die Grundkonzepte für Einsteiger. Teil II behandelt jede Strategie-Familie im Detail (Funktionsweise → Evidenz → Verwaltungsbezug). Teil III vergleicht real existierende Systeme. Teil IV bricht alles auf konkrete Verwaltungs-Use-Cases herunter. Wer nur die Essenz will: Abschnitt 14 (Gesamtvergleichstabelle) und Abschnitt 16 (Synthese).

---

# Teil I — Grundkonzepte (für Einsteiger)

## 1. Was Retrieval in einem RAG-System leistet

Ein RAG-System (Retrieval-Augmented Generation) beantwortet Fragen nicht aus dem „Gedächtnis" des Sprachmodells, sondern sucht zuerst passende Textstellen aus einem Dokumentenbestand und lässt das Sprachmodell die Antwort **aus diesen Fundstellen** formulieren — mit Quellenangabe. Die Kette:

```
Frage → [Retrieval: passende Textstellen finden] → [Generation: Antwort aus Fundstellen formulieren] → Antwort mit Belegen
```

Die zentrale Konsequenz: **Die Retrieval-Qualität deckelt die Antwortqualität.** Was die Suche nicht findet, kann das Sprachmodell nicht belegen — es kann höchstens halluzinieren. Deshalb ist Retrieval der wichtigste Qualitätshebel eines RAG-Systems, wichtiger als die Wahl des Antwortmodells.

## 2. Die zwei Grundarten der Suche

**Semantische Suche (dense retrieval):** Ein Embedding-Modell übersetzt Texte in Zahlenvektoren, sodass bedeutungsähnliche Texte nahe beieinander liegen. Die Frage wird ebenfalls in einen Vektor übersetzt; gesucht werden die nächstgelegenen Text-Vektoren. Stärke: findet „Fahrerlaubnis", wenn nach „Führerschein" gefragt wird. Schwäche: exakte Kennungen. „§ 35 BauGB" und „§ 34 BauGB" liegen im Vektorraum fast aufeinander, trennen rechtlich aber Welten (Außen- vs. Innenbereich). Aktenzeichen wie „4 K 1023/24.NW" zerfallen im Tokenizer in bedeutungsarme Fragmente.

**Lexikalische Suche (sparse retrieval, klassisch BM25):** Zählt gewichtete Wortübereinstimmungen — wie eine klassische Volltextsuche, mit statistischer Gewichtung seltener Begriffe. Stärke: exakte Treffer auf Fachbegriffe, Paragrafen, Aktenzeichen, Erlassnummern. Schwäche: findet „Fahrerlaubnis" **nicht**, wenn nach „Führerschein" gefragt wird (Vokabellücke).

Die beiden Verfahren versagen an **komplementären** Stellen — deshalb ist ihre Kombination („Hybrid-Suche") seit Jahren der Branchenkonsens (Teil II, Abschnitt 6).

## 3. Chunking: Dokumente in Suchhäppchen zerlegen

Dokumente sind zu lang, um sie als Ganzes zu vektorisieren. Sie werden in „Chunks" (Abschnitte von typischerweise 200–1000 Token) zerlegt; jeder Chunk wird einzeln eingebettet und gefunden. Wie man schneidet, entscheidet mit über die Suchqualität: Ein Chunk „Die Gebühr beträgt 27,20 €" ohne Umgebung ist wertlos, wenn nicht erkennbar ist, dass es um den Personalausweis geht. Die Familie der Chunking-Strategien behandelt genau dieses Kontextproblem (Teil II, Abschnitt 5).

## 4. Die Verfeinerungsstufen im Überblick

Moderne Systeme stapeln Stufen, jede mit eigenem Kosten-Nutzen-Profil:

| Stufe | Frage, die sie beantwortet | Beispiele |
|---|---|---|
| **Query-Transformation** | „Ist die Frage so, wie sie ist, eine gute Suchanfrage?" | Umformulierung, Zerlegung in Teilfragen, HyDE |
| **Erststufen-Retrieval** | „Welche ~25–150 Kandidaten kommen infrage?" | Vektorsuche, BM25, Hybrid |
| **Fusion** | „Wie werden mehrere Ergebnislisten zusammengeführt?" | Reciprocal Rank Fusion (RRF), gewichtete Scores |
| **Reranking** | „Welche der Kandidaten sind wirklich die besten 5–20?" | Cross-Encoder, der Frage und Text gemeinsam liest |
| **Kontext-Aufbereitung** | „Was genau bekommt das Sprachmodell zu sehen?" | Parent-Document, Dokumentvervollständigung |
| **Antwort-Absicherung** | „Sind die Belege echt und tragen sie die Aussage?" | Zitatvalidierung, Faktenprüfung |
| **Steuerschleife (agentisch)** | „Reicht das Gefundene — oder muss nachgesucht werden?" | Selbstkorrektur-Loops, Deep Research |

Zwei strukturelle Alternativen ergänzen den Stapel: **Wissensgraphen** (GraphRAG-Familie, Abschnitt 9) für Beziehungs- und Überblicksfragen, und **Long Context** (Abschnitt 12) — ganze Dokumente direkt ins Sprachmodell statt Chunks.

## 5. Wo OPAA heute steht

Vollständig spezifiziert in [retrieval-algorithm.md](../features/retrieval-algorithm.md); Kurzfassung:

- **Reine Vektorsuche** (pgvector, Kosinus, HNSW; Embedding `nomic-embed-text` lokal via Ollama), Rechtefilter (`library_id`) als Teil der Suche, nie als Nachfilter (ADR-0008).
- **LLM-Teilfragen-Zerlegung** (#923): 1–3 Suchanfragen pro Nutzerfrage, Zusammenführung per **Reciprocal Rank Fusion**.
- **MMR-Diversifizierung** vorhanden, per Default aus (λ=1,0, #914).
- **DocumentCompletion** (#932/#935): bis zu 2 Chunks je Dokument, zweistufige Verdrängungslogik.
- **Contextual Chunking light** (#933/#940): Dateiname-Titel als Embedding-Präfix bei mehrchunkigen Dokumenten.
- **Deterministische Zitatvalidierung + Faktenprüfung** (#939): Belege werden gegen die tatsächlich abgerufenen Chunks geprüft.
- **Eval-Harness** (ADR-0011–0013): zwei Korpora, Golden Datasets, Hit Rate/MRR/nDCG/Recall, nächtliche CI-Regression.

**Nicht gebaut:** lexikalische Suche (BM25/Volltext), Cross-Encoder-Reranking — beides im Zielbild ([data-indexing-rag.md](../features/data-indexing-rag.md)) bereits mit „ja" beantwortet, aber nicht beauftragt. Der Live-Fall **#938** markiert die Grenze der reinen Vektorsuche: Die Verwaltungsgebührensatzung enthält die Anfragebegriffe („Befreiung", „Bedürftigkeit") **wörtlich**, rankt aber auf Platz 50 — für keine nachgelagerte Auswahlmechanik erreichbar. Eine lexikalische Komponente hätte den Fall trivial getroffen.

---

# Teil II — Die Strategien im Detail

Jede Strategie mit: Funktionsweise, Evidenz (Benchmarks/Studien), Vor-/Nachteilen, und **Verwaltungsbezug** (wo sie trägt, wo sie scheitert).

## 5. Chunking-Strategien

### 5.1 Fixed/Recursive Chunking (Basis, heute in OPAA)

Feste Token-Fenster mit Überlappung. Chromas Messreihe: Optimum ~200–512 Token mit 10–20 % Überlappung, 85–89,5 % Recall ([Chroma Research](https://www.trychroma.com/research/evaluating-chunking)). Microsofts RAG-Messung kommt auf dasselbe Fenster: 512 Token/25 % Überlappung schlagen 1024–8191 Token deutlich (Recall@50: 43,9 vs. 34,9–37,5) ([Azure-Benchmark](https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/azure-ai-search-outperforming-vector-search-with-hybrid-retrieval-and-reranking/3929167)). OPAA nutzt 1000 Token/100 Überlappung — beides gesetzt, nicht gemessen (ADR-0010).

- **Trägt:** überall als Basis; billig, deterministisch, updatefreundlich.
- **Scheitert:** kontextlose Chunks („die Gebühr beträgt…" — wofür?); zerschnittene Sinneinheiten (Tatbestand ohne Rechtsfolge).

### 5.2 Strukturbasiertes Chunking (nach Überschriften, Paragrafen, Folien)

Schneiden entlang der Dokumentstruktur statt Token-Zählung: bei Satzungen und Gesetzen auf §/Absatz-Ebene, bei Präsentationen pro Folie, bei Markdown pro Überschriftenabschnitt. Für Rechtstexte Stand der Technik — naives Chunking zerschneidet Tatbestand und Rechtsfolge. Eine klinische Studie misst 50 % vs. 87 % Antwortgenauigkeit für fixed vs. strukturadaptives Chunking ([PMC](https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12649634/)). Die Umsetzungsideen pro Dokumenttyp stehen in [discussion-retrieval-document-pipelines.md](discussion-retrieval-document-pipelines.md); die verwaltungsspezifische Dateityp-Tabelle (inkl. ODF, XLSX, Scan-PDF/OCR, E-Mail, LegalDocML) und das Metadaten-Wizard-Konzept in [discussion-dateitypen-und-metadaten.md](discussion-dateitypen-und-metadaten.md).

- **Trägt:** Satzungen, Dienstanweisungen, Formulare — genau der OPAA-Korpus. Ein §-genauer Chunk ist zugleich die zitierfähige Fundstelle.
- **Scheitert:** unstrukturierte Alttexte, OCR-Rohtext ohne erkennbare Gliederung → Fallback auf 5.1 nötig.

### 5.3 Contextual Retrieval (Anthropic)

Ein LLM stellt jedem Chunk 50–100 Token erklärenden Dokumentkontext voran — für Embedding **und** BM25-Index. Anthropics Zahlen (Fehlerrate = 1−Recall@20): −35 % nur mit Embeddings, −49 % mit kontextualisiertem BM25 dazu, **−67 % mit Reranking obendrauf** (5,7 % → 1,9 %). Einmalkosten ~1,02 $/1M Dokument-Token mit Prompt Caching ([Anthropic Engineering](https://www.anthropic.com/engineering/contextual-retrieval)). OPAA hat mit #940 eine LLM-freie Minimalvariante (Dateiname-Präfix); die bekannten offenen Punkte (Abschnittstitel, Bibliothekskontext, Benachteiligung einchunkiger Dokumente) stehen in retrieval-algorithm.md.

- **Trägt:** mehrchunkige Dokumente, deren Abschnitte ohne Kontext mehrdeutig sind (Gebührentabellen!).
- **Scheitert:** häufig rotierende Korpora (jede Änderung = LLM-Neuverarbeitung); generisch geratener Kontext bringt nichts.

### 5.4 Late Chunking (Jina)

Das ganze Dokument läuft durch den Transformer, Chunk-Vektoren entstehen erst durch Pooling **nach** der Aufmerksamkeitsberechnung — jeder Chunk „weiß" von seinem Dokument, ohne LLM-Kosten. Ø +3,6 % nDCG@10, bei kleinen Chunks bis +24 % ([arXiv:2409.04701](https://arxiv.org/abs/2409.04701)). Voraussetzung: Long-Context-Embedder mit Token-Zugriff (BGE-M3, Jina) — mit reinen Embedding-APIs (OpenAI-Schnittstelle) nicht machbar.

- **Trägt:** als billige Alternative zu 5.3, falls das Embedding-Modell gewechselt wird.
- **Scheitert:** am aktuellen OPAA-Stack (OpenAI-kompatible Embedding-API abstrahiert den Token-Zugriff weg).

### 5.5 Semantic Chunking — Evidenz dagegen

Schneiden an semantischen Bruchstellen (Embedding-Ähnlichkeit benachbarter Sätze). Die NAACL-Findings-2025-Studie „Is Semantic Chunking Worth the Computational Cost?" findet **keine konsistenten Gewinne**; Vorteile zeigen sich nur auf künstlich zusammengewürfelten Dokumenten ([arXiv:2410.13070](https://arxiv.org/abs/2410.13070)). Für strukturierte Verwaltungsdokumente ist strukturbasiertes Schneiden (5.2) das bessere Werkzeug.

## 6. Lexikalische Suche und Hybrid-Fusion

### 6.1 BM25 und die deutsche Sprache

BM25 ist die robusteste Zero-Shot-Baseline der Retrieval-Forschung — im BEIR-Benchmark unterliegen Dense-Modelle ihr out-of-domain regelmäßig ([arXiv:2104.08663](https://arxiv.org/abs/2104.08663)). Für Deutsch braucht sie aber eine Analyzer-Kette, sonst verfehlt sie Komposita: Ohne Decompounding findet „Genehmigung" das „Baugenehmigungsverfahren" nicht. Etablierte Kette: lowercase → Stoppwörter → Schutzliste für Kennungen („BauGB" darf nicht zerlegt/gestemmt werden) → [german-decompounder](https://github.com/uschindler/german-decompounder) → Normalisierung → light-Stemmer ([Elastic-Anleitung](https://www.elastic.co/search-labs/blog/compound-word-search)). Zusätzlich gehören Normzitate und Aktenzeichen in **exakte Keyword-Felder** — sie sind Identifikatoren, keine Wörter.

Learned-Sparse-Verfahren (SPLADE, ELSER) schlagen BM25 auf Englisch deutlich, sind für Deutsch aber dünn belegt und teils lizenzproblematisch (ELSER: nur Englisch + Elastic-Platinum; SPLADE-Modelle CC-BY-NC) — Beobachtungskandidaten, kein Baustein.

- **Trägt:** Aktenzeichen, Paragrafen, Erlassnummern, Eigennamen, Fachterme — der dokumentierte #938-Fall. Kein Training, CPU-only, vollständig on-prem.
- **Scheitert:** Vokabellücke Bürgersprache ↔ Amtssprache („Führerschein" vs. „Fahrerlaubnis"), Tippfehler, OCR-Fehler.

### 6.2 Hybrid-Fusion: RRF vs. gewichtete Scores

Beide Ergebnislisten (Vektor + BM25) werden verschmolzen. Zwei Schulen:

- **Reciprocal Rank Fusion (RRF):** Score = Σ 1/(60+Rang) über alle Listen. Nutzt nur Ränge, keine (inkommensurablen) Roh-Scores — robust, tuningfrei, Default bei Azure AI Search, Elasticsearch, OpenSearch, Qdrant ([Cormack et al. 2009](https://cormack.uwaterloo.ca/cormacksigir09-rrf.pdf)). **OPAA hat RRF bereits implementiert** (`ReciprocalRankFusion`, für Teilfragen-Fusion) — die Zusammenführung eines BM25-Pfads wäre derselbe Mechanismus mit einer Liste mehr.
- **Gewichtete Score-Fusion (Convex Combination):** normalisierte Scores mit Gewicht α. Bruch et al. (TOIS 2023) zeigen: mit wenigen Trainingsbeispielen fürs α-Tuning schlägt sie RRF in- und out-of-domain ([arXiv:2210.11934](https://arxiv.org/abs/2210.11934)). RAGFlow nutzt Default 0,7 Keyword / 0,3 Vektor; Weaviate „relativeScoreFusion".

**Wie viel Hybrid bringt — die Zahlenlage:**

| Quelle | Messung |
|---|---|
| Microsoft/Azure ([Benchmark](https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/azure-ai-search-outperforming-vector-search-with-hybrid-retrieval-and-reranking/3929167)) | nDCG@3 Kundendaten: BM25 40,6 · Vektor 43,8 · Hybrid 48,4 · **Hybrid+Reranker 60,1** |
| Anthropic ([Contextual Retrieval](https://www.anthropic.com/engineering/contextual-retrieval)) | BM25-Zuschaltung allein senkt Top-20-Fehlerrate 3,7 % → 2,9 % |
| IBM Blended RAG ([arXiv:2404.07220](https://arxiv.org/pdf/2404.07220)) | nDCG@10 0,67 auf NQ, 98 % Top-10-Accuracy TREC-COVID, ohne Finetuning |

Faustregel: Hybrid gewinnt, sobald Anfragen Identifikatoren oder Fachterme neben natürlicher Sprache enthalten — **der Verwaltungs-Normalfall**.

- **Trägt:** praktisch jedes Verwaltungsszenario; die einzelne Maßnahme mit dem besten Aufwand-Nutzen-Verhältnis im ganzen Katalog.
- **Scheitert:** an nichts Grundsätzlichem; Risiko ist nur ein falsch kalibriertes Gewicht, das die Suche faktisch auf eine Modalität kippt. Deshalb: mit RRF starten, Gewichte erst mit Golden-Set-Messung tunen.

## 7. Reranking (Cross-Encoder)

**Funktionsweise:** Die Erststufe (Hybrid) optimiert Recall und holt 50–150 Kandidaten; ein Cross-Encoder liest dann Frage und Kandidat **gemeinsam** (echte Interaktion statt getrennter Vektoren) und ordnet die Top 5–20 fürs Sprachmodell. Der qualitativ stärkste Einzelhebel nach Hybrid: Praxisumfragen über 100+ RAG-Teams nennen Hybrid+Reranking als wirksamste Investition überhaupt ([kapa.ai](https://www.kapa.ai/blog/rag-best-practices)); typischer Effekt +5–15 nDCG-Punkte bzw. +10–17 % Recall@5; in Anthropics Kette drückt Reranking die Fehlerrate von 2,9 % auf 1,9 %.

**Modelle (Stand 2026):**

| Modell | Lizenz/Betrieb | Einordnung |
|---|---|---|
| bge-reranker-v2-m3 | Apache 2.0, on-prem (Mittelklasse-GPU) | De-facto-Standard multilingual |
| **Qwen3-Reranker 4B** | Apache 2.0, on-prem | Großer Qualitätssprung: MMTEB-R 72,74 vs. 58,36 (bge-v2-m3) |
| Cohere Rerank 3.5 | API (auch Azure/Bedrock) | Stark auf Deutsch; 2 $/1.000 Suchen |
| Voyage rerank-2.5 | API | +7,9 % über Cohere 3.5 über 93 Datensätze |

LLM-as-Reranker (RankGPT-Familie) ist listwise am genauesten, aber destillierte Cross-Encoder erreichen dieselbe Qualität bei bis zu 173× Geschwindigkeit ([arXiv:2405.07920](https://arxiv.org/html/2405.07920v3)) — LLM-Reranking gehört in Offline-Evaluation, nicht in den Antwortpfad.

- **Trägt:** überall dort, wo die Erststufe die richtige Fundstelle zwar in den Top 50, aber nicht in den Top 8 hat — die zweite Hälfte des #938-Problems (Rang 50 → Reranker-Reichweite beginnt genau dort). Latenz 80–400 ms für 100 Kandidaten auf einer L4-GPU, kompatibel mit dem OPAA-Zielwert (&lt;200 ms bei kleineren Kandidatenmengen).
- **Scheitert:** wenn die Erststufe die Fundstelle gar nicht liefert (Reranker kann fehlenden Recall nicht heilen); bei sehr langen Kandidaten (Truncation bei 512–1024 Token); on-prem ohne GPU wird die Latenz spürbar.

## 8. Query-Transformation

Alle Varianten kosten mindestens einen zusätzlichen LLM-Aufruf (0,5–15 s) — der Preis ist Latenz, der Gewinn hängt stark vom Fragetyp ab. OPAA hat mit der Teilfragen-Zerlegung (#923, ~157 ms) bereits eine Variante produktiv.

| Technik | Funktionsweise | Evidenz | Verwaltungsbezug |
|---|---|---|---|
| **Query-Rewriting** | LLM formuliert die Frage suchtauglich um | +1–4 EM-Punkte ([arXiv:2305.14283](https://arxiv.org/abs/2305.14283)); bei starkem Retriever und guter Query **verschlechternd** ([arXiv:2407.01219](https://arxiv.org/abs/2407.01219)) | Genau für die Bürger→Amtssprache-Lücke sinnvoll; bei geübten Sachbearbeiter-Queries eher schädlich |
| **Multi-Query** | Mehrere Umformulierungen parallel suchen, fusionieren | ARAGOG: Präzision **verschlechtert** ohne nachgeschalteten Reranker ([arXiv:2404.01037](https://arxiv.org/html/2404.01037v1)) | Nur als Recall-Instrument mit Reranker dahinter; Azure nutzt es so (bis 10 Rewrites, +4 nDCG-Punkte) |
| **HyDE** | LLM schreibt ein hypothetisches Antwortdokument, dessen Embedding sucht | nDCG@10 61,3 vs. 44,5 auf DL19 ([arXiv:2212.10496](https://arxiv.org/abs/2212.10496)); beste Transformation in Vergleichsstudien, aber Latenz bis 11 s | Beste Vokabular-Brücke: „Was kostet ein neuer Ausweis?" → hypothetischer Gebührentext trifft die PAuswGebV. Risiko: halluziniertes Hypothesendokument zieht die **falsche** Norm an |
| **Step-Back** | Erst die allgemeinere Regelwerksfrage stellen | +7–27 % auf Wissens-Benchmarks ([arXiv:2310.06117](https://arxiv.org/abs/2310.06117)), als RAG-Technik dünn belegt | Detailfrage → Regelwerk („Carport 2,80 m?" → verfahrensfreie Vorhaben der LBO) |
| **Decomposition/iterativ** | Frage in Teilschritte zerlegen, ggf. iterativ nachsuchen (IRCoT) | bis +21 Punkte Recall Multi-Hop ([arXiv:2212.10509](https://arxiv.org/abs/2212.10509)); auf Single-Hop **schädlich**; bis ~17 s/Query | Nur mit Router, der einfache Fragen vorbeileitet. OPAAs #923 ist die milde, latenzarme Form |

## 9. Graph-basiertes RAG

### 9.1 Die Idee

Klassisches RAG findet nur, was in wenigen, lokal ähnlichen Chunks steht. Zwei Fragetypen sprengen das Muster: **Multi-Hop** („Wer ist zuständig, wenn der Fachdienstleiter befangen ist und sein Vertreter im Urlaub?" — die Antwort verteilt sich über mehrere Dokumente, die einander referenzieren) und **globale Überblicksfragen** („Welche unserer Dienstanweisungen berühren den Datenschutz?"). GraphRAG-Verfahren lassen ein LLM aus dem Korpus einen Wissensgraphen (Entitäten + Beziehungen) extrahieren und befragen dessen Struktur.

### 9.2 Die Familie

- **Microsoft GraphRAG** ([arXiv:2404.16130](https://arxiv.org/abs/2404.16130)): LLM-Entitätsextraktion → Leiden-Community-Hierarchie → LLM-Reports pro Community; Global Search (Map-Reduce über Reports), Local Search, DRIFT (Hybrid). 72–83 % Win-Rate bei „Comprehensiveness" gegen Vektor-RAG — gemessen mit LLM-Judge auf Zusammenfassungsfragen, **nicht** Faktenkorrektheit. Kosten: Microsoft beziffert die Indexierung selbst auf das **1000-fache** von Vektor-RAG; Global Search ~331.000 Token/Query vs. ~900 bei Vanilla-RAG; inkrementelle Updates strukturell ungelöst (Community-Reports hängen global am Graphen). **Das Repo ist seit 2025 offiziell im Wartungsmodus.**
- **LazyGraphRAG** ([MS Research](https://www.microsoft.com/en-us/research/blog/lazygraphrag-setting-a-new-standard-for-quality-and-cost/)): Kehrt das Kostenmodell um — Indexierung ohne LLM (Nomen-Phrasen + Kookkurrenz + Graphstatistik), alle LLM-Arbeit zur Query-Zeit mit einstellbarem „relevance test budget". Indexkosten wie Vektor-RAG (0,1 % von Voll-GraphRAG), Global-Query-Qualität bei &gt;700× geringeren Kosten. Aber: **nicht als Open Source erschienen**, nur in Microsoft-Produkte geflossen — als Bauplan lehrreich, als Baustein nicht verfügbar.
- **LightRAG** (HKU, [arXiv:2410.05779](https://arxiv.org/abs/2410.05779)): Graph + Vektorindizes, Dual-Level-Retrieval (Entitäts- vs. Themen-Keywords), keine Community-Reports → ~60 % weniger Indexierungs-Tokens, inkrementelle Updates. In der GraphRAG-Recherche (#317) als Top-Kandidat benannt. Aber die Evidenz bröckelt: Eine unverzerrte Re-Evaluation lässt die berichtete 66,7-%-Win-Rate auf **39,06 %** schrumpfen ([arXiv:2506.06331](https://arxiv.org/pdf/2506.06331)); auf extraktivem QA erreicht es F1-Werte von 1,6–2,4 % gegen 45–75 % für Dense Retrieval ([arXiv:2502.14802](https://arxiv.org/html/2502.14802v2)).
- **HippoRAG 2** ([arXiv:2502.14802](https://arxiv.org/abs/2502.14802)): aktuell bestes Graph-Verfahren — Recall@5 auf Multi-Hop-Benchmarks 74,7/90,4/96,3 (vs. 69,7/76,5/94,5 für starkes Dense Retrieval) — und stürzt auf einfachen Fragen **nicht** ab. Der ernsthafteste Kandidat, falls Multi-Hop-Bedarf nachgewiesen wird.
- **KAG** (Ant Group, [arXiv:2409.13731](https://arxiv.org/abs/2409.13731)): schema-geführte Graphen, +19,6 % F1 HotpotQA; einziger dokumentierter E-Government-Produktivfall (China, Eigeneval). Hoher Modellierungsaufwand.

### 9.3 Die kritische Evidenz — konsistent über Studien

- „RAG vs. GraphRAG" ([arXiv:2502.11371](https://arxiv.org/pdf/2502.11371)): RAG gewinnt Single-Hop, GraphRAG Multi-Hop knapp; Indexbau 135 s vs. 5.560 s.
- „Do We Still Need GraphRAG?": auf allgemeinem QA Ø **+0,47 Punkte — praktisch nichts**; bei echtem Multi-Hop Ø +27,2.
- GraphRAG-Bench ([arXiv:2506.05690](https://arxiv.org/pdf/2506.05690)): Graph **verliert** bei einfachem Fakten-Retrieval (49,29 vs. 60,92 %).
- Sicherheit: „GraphRAG under Fire" — Graph-Poisoning-Angriffe mit bis zu 98 % Erfolgsrate ([arXiv:2501.14050](https://arxiv.org/abs/2501.14050)).
- Verwaltungsspezifisch (aus [data-indexing-rag.md](../features/data-indexing-rag.md)): Die **Rechteprüfung muss auch im Graphen zur Abfragezeit greifen** — schon eine Kante zu einem unlesbaren Dokument verrät dessen Existenz. Kein Framework löst das mitgeliefert.

### 9.4 Einordnung

Für den Verwaltungs-Normalfall „Frage → Fundstelle" ist Hybrid+Reranking gleich gut oder besser, um Größenordnungen billiger und ohne Update-Problem. Der Graph lohnt für zwei echte Bedarfe: nachgewiesenes Multi-Hop (Zuständigkeits-/Verweisketten) und korpusweite Überblicksfragen. Und es gibt einen dritten, oft übersehenen Weg: einen **kuratierten Fachgraphen** (Zuständigkeiten, Normverweise, Organigramm) statt automatischer Extraktion aus Fließtext — deterministisch, pflegbar, rechtssicher, aber Pflegeaufwand beim Fachbereich.

## 10. Hierarchische und strukturierte Indizes

- **Parent-Document / Small-to-Big:** Klein einbetten (präzises Matching), groß in den Kontext geben (der Eltern-Abschnitt statt des Mini-Chunks). Quasi-Standard, kein LLM beim Ingest, Updates trivial — der sinnvollste Default dieser Familie. OPAAs DocumentCompletion (#935) ist funktional ein Verwandter (Geschwister-Chunks nachziehen).
- **RAPTOR** ([arXiv:2401.18059](https://arxiv.org/abs/2401.18059)): rekursiver Cluster-und-Zusammenfassungs-Baum; Zusammenfassungsknoten werden mitindexiert. Beeindruckende End-to-End-Zahlen, aber der reine Retrieval-Effekt ist klein (+2,7–10,2), der Index nichtdeterministisch, und Dokumentänderungen erfordern Neuberechnung. Gravierend für die Verwaltung: Antworten zitieren dann **LLM-Zusammenfassungen statt Originaltext** — mit OPAAs Zitierpflicht und Faktenprüfung (#939) unvereinbar.
- **Document Summary Index:** eine LLM-Zusammenfassung pro Dokument als zusätzlicher Retrieval-Einstieg. Updatefreundlich (1 Aufruf pro geändertem Dokument); Risiko: die Zusammenfassung lässt genau das gefragte Detail weg → Recall-Deckel. Als **Zusatzpfad** (nicht Ersatz) plausibel, verwandt mit OPAAs Contextual-Chunking-Ausbau.
- **Sentence-Window:** satzgenaues Matching mit Kontextfenster; in ARAGOG höchste Retrieval-Präzision, aber schlechtere Antworten — im Rechtskontext gefährlich (ein Satz ohne seine Ausnahmen ist eine falsche Auskunft).

## 11. Agentic RAG (iterative Steuerschleifen)

Das Retrieval wandert in eine LLM-Schleife: Das Modell entscheidet, **ob** und **wie oft** gesucht wird, prüft die Ergebnisse und sucht nach.

| Muster | Funktionsweise | Kosten/Latenz | Einordnung |
|---|---|---|---|
| **CRAG-Muster** ([arXiv:2401.15884](https://arxiv.org/abs/2401.15884)) | Leichter Bewerter prüft Retrieval-Ergebnis; bei „unbrauchbar" Korrekturpfad (Umformulierung, alternative Quelle) | 2–3 Aufrufe, 3–10 s | Bestes Aufwand-Nutzen-Verhältnis der Familie; „Websuche als Fallback" wäre in der Verwaltung durch andere interne Bibliotheken zu ersetzen |
| **ReAct-Loop** ([arXiv:2210.03629](https://arxiv.org/pdf/2210.03629)) | LLM mit Such-Tools im Reasoning-Loop | 3–8 Aufrufe, 10–60 s | De-facto-Produktionsstandard für „Agentic RAG"; Azure „Agentic Retrieval" (+40 % Relevanz bei komplexen Fragen, tokenbasiert abgerechnet) ist die verwaltete Variante |
| **Self-RAG** ([arXiv:2310.11511](https://arxiv.org/pdf/2310.11511)) | Trainierte Reflection-Tokens | Erfordert eigenes Modelltraining | Forschungsartefakt; Prompt-Nachbauten lassen den Kernmechanismus weg |
| **Deep Research** | Minutenlange autonome Recherche mit Berichtsynthese | Minuten, ~15× Token-Kosten | Für Berichtsaufträge („Stelle alle Regelungen zu X zusammen"), nie für interaktive Auskunft. In OPAAs Zielbild bereits als eigener Modus (Phase 2) vorgesehen |

Fehlerbilder der Familie: Drift-Schleifen ohne Stoppkriterium, Kostenexplosion, überkonfidenter Bewerter. Grundregel: Die Schleife gehört **hinter** einen Router — einfache Fragen laufen am Loop vorbei.

## 12. Late Interaction (ColBERT) und Long Context

**ColBERT/Late Interaction:** Ein Vektor pro Token statt pro Chunk, Scoring per MaxSim — Token-Präzision (relevant für §§/Aktenzeichen!) ohne Cross-Encoder-Kosten pro Kandidat. Produktionsreif in Vespa, als Reranking-Muster in Qdrant. Aber: **pgvector kann kein natives MaxSim** — im OPAA-Stack (ADR-0014: pgvector als einziger Vektorspeicher) nur als applikationsseitiges Reranking über eine Kandidatenliste denkbar; Speicherkosten 10–50× Dense unkomprimiert; das beste multilinguale Modell (Jina-ColBERT-v2) ist CC-BY-NC. Beobachten, nicht bauen.

**Long Context vs. RAG:** Kontextfenster von 200k–1M+ Token nähren die „RAG-is-dead"-These. Die Empirie dagegen ist eindeutig: NoLiMa zeigt, dass ohne wörtliche Überlappung zwischen Frage und Fundstelle bei 32k Token bereits **11 von 12 Modellen unter 50 % ihrer Kurzkontext-Leistung** fallen ([arXiv:2502.05167](https://arxiv.org/abs/2502.05167)); Chromas „Context Rot" misst monotone Degradation bei allen 18 getesteten Modellen ([Chroma Research](https://www.trychroma.com/research/context-rot)); RAG ist 8–82× günstiger ([DeepMind-Vergleich](https://arxiv.org/abs/2407.16833)). Dazu das K.-o.-Kriterium der Verwaltung: **Berechtigungen und belegpflichtige Fundstellen sind nur über Retrieval sauber abbildbar.** Sinnvoll bleibt der Mittelweg: Retrieval wählt das Dokument, Long Context verarbeitet das **ganze gewählte Dokument** — etwa für „Fasse diese Satzung zusammen".

## 13. Evaluierung als Querschnitt

Jede Strategieentscheidung dieses Reports ist nur so gut wie ihre Messung — und die Benchmark-Lage mahnt zur Vorsicht: Englischlastige Modelle verlieren auf Deutsch oft 5–15 nDCG-Punkte, MTEB-Kontamination ist dokumentiert ([arXiv:2506.21182](https://arxiv.org/pdf/2506.21182)), und LLM-Judge-Metriken tragen Positions-/Verbosity-/Self-Preference-Bias ([arXiv:2410.21819](https://arxiv.org/pdf/2410.21819)). OPAA ist hier ungewöhnlich gut aufgestellt (deterministischer Eval-Harness, Golden Datasets, CI-Regression, ADR-0011–0013). Die bestehende Lücke: Der Harness misst die rohe Vektorsuche und läuft an Teilfragen-Zerlegung/RRF/DocumentCompletion vorbei — produktionsnahe Pipeline-Messung ist Vorbedingung für jeden A/B-Vergleich der hier diskutierten Strategien. Methodenvorbild für Rechtstexte: LegalBench-RAG mit Span-genauen Metriken ([arXiv:2408.10343](https://arxiv.org/abs/2408.10343)).

---

# Teil III — Was die anderen bauen

## 14. Systemvergleich

### Microsoft

- **Azure AI Search** ist die klarste Referenzarchitektur des Marktes: Hybrid (BM25+Vektor, RRF) → Semantic Ranker (Bing-Cross-Encoder über Top 50) → optional generatives Query-Rewriting (bis 10 Umformulierungen, SLM) → „Agentic Retrieval" für komplexe konversationale Fälle (LLM-Query-Planning, parallele Subqueries, +40 % Relevanz laut Eigenmessung). Microsofts eigene Benchmark-Botschaft seit 2023 unverändert: **Chunking ~512 Token + Hybrid + Reranking** — nDCG@3 von 43,8 (nur Vektor) auf 60,1 (Hybrid+Ranker).
- **GraphRAG** hat Microsoft selbst faktisch eingeordnet: Das OSS-Repo ist im Wartungsmodus, die Weiterentwicklung (LazyGraphRAG) verschiebt alle LLM-Arbeit in die Query-Zeit und floss nur in Produkte. Die Lehre: **Nicht der Graph war die bleibende Idee, sondern das Kostenmodell** — billig indexieren, Qualität zur Query-Zeit dosieren.
- **M365 Copilot** (Semantic Index): hybrides Retrieval mit strikter Rechte-Übernahme (Index erzeugt keine neuen Zugriffsrechte) — architektonisch dasselbe Prinzip wie OPAAs ADR-0008-Rechtefilter, in Tenant-Größe.

### RAGFlow (infiniflow)

Das vollständigste Open-Source-Einzelsystem: **DeepDoc**-Parsing (eigene Vision-Modelle für Layout- und Tabellenstruktur-Erkennung, Positions-Highlighting im Original-PDF), Template-Chunking pro Wissensbasis (u. a. Templates `laws`, `table`, `presentation`), Hybrid-Suche (gewichtete Fusion 0,7 Keyword/0,3 Vektor auf Elasticsearch/Infinity), optional Reranker, RAPTOR, GraphRAG (mit expliziter Entity Resolution — ein bewusster Unterschied zu Microsoft), seit 2026 „Knowledge Compilation" und Agentic RAG mit Denkstufen. Preis: schwerer Betriebs-Fußabdruck (Elasticsearch/Infinity + MySQL + MinIO + Redis, hoher RAM-Bedarf durch Vision-Modelle). Die wichtigste Anregung für OPAA ist nicht die Featureliste, sondern die Reihenfolge: **Dokumentenverständnis zuerst** („Quality in, quality out") — Layout-/Tabellen-Parsing als Fundament, auf dem alles andere aufsetzt.

### Weitere Systeme in Kürze

| System | Ansatz | Lehre für OPAA |
|---|---|---|
| **Onyx** (ehem. Danswer) | Hybrid in Vespa + Cross-Encoder-Reranking + Quell-ACL-Übernahme | Enterprise-Governance und Rechte zuerst — OPAAs Linie bestätigt |
| **LightRAG** | Graph+Vektor, Dual-Level, inkrementell | Der Kandidat für kosteneffizientes Graph-RAG, aber Evidenz kritisch prüfen (9.2) |
| **Haystack / LlamaIndex** | Frameworks; Fundus etablierter Muster (QueryFusion, Auto-Merging, Sentence-Window) | Muster-Katalog, kein Systemvorbild |
| **AnythingLLM / Open WebUI** | Einfaches Vektor-RAG (+optional Hybrid) | Zeigen die Untergrenze: naives Chunking + Vektor-only reicht für ernsthafte Korpora nicht |
| **Qdrant / Weaviate / Elastic / OpenSearch** | Hybrid+RRF nativ, teils Late Interaction | Hybrid ist **Commodity** geworden — ein System ohne lexikalische Komponente ist 2026 begründungspflichtig |

### Deutsche Verwaltungsprojekte

F13 (Baden-Württemberg, seit 07/2025 Open Source auf openCode, dedizierter RAG-Microservice), LLMoin (Hamburg/Dataport, ~60.000 Nutzende länderübergreifend, nicht quelloffen), PLAIN (Bund, Daten-/KI-Plattform), MUCGPT (München, Open Source), KIPITZ (ITZBund), Parla (Berlin). Der Befund ist für OPAA strategisch wertvoll: Diese Projekte dokumentieren Organisation und Souveränität gründlich, ihre **Retrieval-Algorithmik dagegen kaum** — keines publiziert Retrieval- oder Halluzinationsmetriken, keines betreibt öffentlich dokumentiert fassungsbewusstes, normzitat-genaues Rechts-Retrieval. Ein sauber gemessenes Hybrid+Reranking mit deterministischer Zitatvalidierung wäre im deutschen Verwaltungsumfeld bereits **Spitzenfeld, nicht Aufholjagd**.

---

# Teil IV — Verwaltungsszenarien und Gesamtbewertung

## 15. Use-Cases: Welche Strategie trägt wo?

Die Szenarien knüpfen an die Rheinfurt-Demo an ([demo-drehbuch.md](../market/demo-drehbuch.md)) und gehen darüber hinaus.

### Szenario 1: Bürgernahe Faktenauskunft („Was kostet ein Personalausweis für eine 22-Jährige?")

Demo-Frage 1. Ein Dokument, eine Fundstelle, klare Begriffe. **Reine Vektorsuche reicht hier bereits** — das belegt die Demo. Verbesserungen bringen: strukturbewusstes Chunking (Gebührentabellen intakt halten), Contextual Chunking (Tabellenzeile weiß, zu welcher Leistung sie gehört). Limitation aller Strategien: Wenn die Gebührenordnung veraltet im Index liegt, ist jede noch so gute Fundstelle falsch — Aktualität ist ein Indexierungs-, kein Retrieval-Problem.

### Szenario 2: Exakte Kennung („Was regelt § 3 der Verwaltungsgebührensatzung?", „Vorgang AZ 31/2-2026-0815")

Die Domäne der lexikalischen Suche. Vektorsuche versagt hier systematisch (Tokenizer-Fragmente, Nachbar-Paragrafen ununterscheidbar) — der dokumentierte Fall #938 gehört in diese Klasse. **Hybrid mit Keyword-Feldern ist die einzige verlässliche Lösung**; Reranking hilft zusätzlich, weil Cross-Encoder wörtliche Übereinstimmung stark gewichten. Kein Graph, keine Query-Transformation nötig. Limitation: Schreibvarianten von Aktenzeichen erfordern Normalisierung beim Indexieren.

### Szenario 3: Vokabellücke Bürger- vs. Amtssprache („Mein Ausweis ist kaputt, was tun?" → „Personaldokument, Neuausstellung wegen Beschädigung")

Embedding-Modelle überbrücken milde Lücken; für harte Lücken sind Query-Rewriting oder HyDE die Werkzeuge der Wahl. Limitation von HyDE: Das halluzinierte Hypothesendokument kann die falsche Norm anziehen — deshalb nur als **Zusatzpfad** in der Fusion, nie als Ersatz der Originalquery. Ein besseres mehrsprachiges Embedding-Modell (BGE-M3, Qwen3-Embedding) verkleinert das Problem an der Wurzel.

### Szenario 4: Verstreute Antwort („Welche Unterlagen brauche ich für die Ummeldung, und was kostet das Ganze mit neuem Ausweis?")

Demo-Fragen 2/6/7. Die Antwort verteilt sich über mehrere Dokumente aus mehreren Bibliotheken. OPAAs Teilfragen-Zerlegung + RRF + DocumentCompletion adressieren genau das. Grenze: Wenn ein Teildokument (wie in #938) im Erststufen-Ranking chancenlos ist, hilft die beste Zusammenführung nichts — **Hybrid hebt zuerst den Erststufen-Recall, dann greifen die vorhandenen Mechanismen.**

### Szenario 5: Echtes Multi-Hop („Wer vertritt den Fachdienstleiter bei Befangenheit, und welche Wertgrenze gilt dann für seine Freigaben?")

Kette über Dienstanweisung → Vertretungsregelung → Wertgrenzenerlass. Hier haben Graph-Verfahren ihre nachgewiesene Stärke (+27 Punkte im Schnitt bei echtem Multi-Hop), ebenso iterative Retrieval-Loops (IRCoT-Muster). Aber Vorsicht vor dem Fehlschluss: **Erst messen, ob solche Fragen im realen Nutzungsprofil vorkommen** — die Studienlage zeigt, dass Graphen auf allen anderen Fragetypen nichts bringen oder schaden. Ein kuratierter Zuständigkeits-Fachgraph kann dieselbe Frage deterministisch beantworten.

### Szenario 6: Überblick/Aggregation („Welche unserer Dienstanweisungen berühren personenbezogene Daten?", „Fasse die Änderungen des letzten Jahres zusammen")

Die Domäne von Global-Search-Verfahren (GraphRAG-Communities, Document-Summary-Index) und Deep-Research-Modi. Klassisches Top-k-Retrieval ist hier **strukturell** überfordert — es liefert 8 Chunks, die Frage braucht eine Durchsicht. Der ehrlichste Weg ohne Graph-Investition: ein expliziter Recherche-Modus (Deep Research, in OPAAs Zielbild Phase 2), der sichtbar länger läuft und einen Bericht liefert. Limitation: Kosten (~15× Token), niemals für interaktive Auskunft.

### Szenario 7: Berechtigungssensitive Auskunft (Demo-Frage 5: verschiedene Konten sehen Verschiedenes)

OPAAs Stärke durch ADR-0008 (Rechtefilter in der Suche). Jede neue Strategie muss diese Invariante erhalten — das ist das härteste Ausschlusskriterium gegen naive Graph-Übernahme (Kanten verraten Existenz unlesbarer Dokumente) und gegen „alles in den Kontext"-Long-Context-Ansätze.

### Szenario 8: Ehrliches Nichtwissen (Demo-Frage 8: Fischereierlaubnis — nicht im Bestand)

Retrieval-Strategien erhöhen hier das Risiko: Query-Expansion und HyDE **erzeugen** Scheintreffer, wo nichts ist. Gegenmittel: Ähnlichkeits-Schwellen (vorhanden: 0,3), kalibrierte Reranker-Scores als zweite Schwelle, und die deterministische Zitatvalidierung (#939) als letzte Verteidigungslinie. Die Stanford-Studie zu kommerziellen Legal-Tools mahnt: Selbst kuratiertes RAG halluziniert 17–34 %, dominant als „misgrounded" — echtes Zitat, das die Aussage nicht trägt ([arXiv:2405.20362](https://arxiv.org/abs/2405.20362)). OPAAs Faktenprüfung zielt genau auf diese Fehlerklasse und ist damit ungewöhnlich weit vorn.

### Szenario 9: Fassungs- und Ebenenfragen („Galt die Regelung auch schon 2024?", Landes- vs. Bundesrecht)

Zwei deutschlandspezifische Fehlerbilder, die **keine** generische Retrieval-Strategie löst: Zeitscheiben (Fassungen) und die Verwechslung von 16 semantisch fast identischen Landesgesetzen. Beides sind **Metadaten-Probleme**: Fassungs-/Ebenen-/Bundesland-Metadaten am Chunk plus harte Filter in der Suche. Wer hier nur Embeddings tuned, verliert. (Perspektivisch relevant: NeuRIS/Rechtsinformationsportal-APIs für fassungsbewusste Rechtsquellen.)

## 16. Gesamtvergleich

Bewertung bezogen auf den OPAA-Kontext (on-prem-fähig, PostgreSQL-only, Verwaltungskorpora, Zitierpflicht). Nutzen = erwarteter Qualitätseffekt im Verwaltungs-Nutzungsprofil.

| Strategie | Reifegrad | Aufwand | Betriebskosten | Nutzen | Hauptrisiko |
|---|---|---|---|---|---|
| **Hybrid BM25+Vektor (RRF)** | Standard/Commodity | mittel | gering (CPU) | **sehr hoch** (#938-Klasse) | Fusion falsch gewichtet |
| **Cross-Encoder-Reranking** | Standard | mittel | GPU oder API | **hoch** | Latenz on-prem ohne GPU |
| **Strukturbasiertes Chunking** | Standard | mittel | keine | hoch | Fallback für strukturlose Texte nötig |
| **Contextual Chunking (Ausbau)** | etabliert | gering–mittel | LLM beim Ingest | mittel–hoch | Reindex-Kosten bei Änderungen |
| **Parent-Document/Small-to-Big** | Standard | gering | keine | mittel | — |
| **Besseres Embedding-Modell** | Standard | gering (+Voll-Reindex) | ggf. größere GPU | mittel–hoch | Deutsch-Qualität nur per eigener Messung belegbar |
| **Query-Rewriting/HyDE (selektiv)** | etabliert | gering | +1 LLM-Call/Query | mittel (Bürgersprache) | Scheintreffer, Latenz |
| **CRAG-artiger Korrektur-Loop** | etabliert | mittel | 2–3 LLM-Calls | mittel | Schleifen ohne Stoppkriterium |
| **Deep-Research-Modus** | etabliert | hoch | ~15× Token | hoch (eigene Frageklasse) | Kosten, Erwartungsmanagement |
| **Document-Summary-Index** | etabliert | gering–mittel | LLM beim Ingest | mittel | Recall-Deckel der Zusammenfassung |
| **Kuratierter Fachgraph** | reif (klassische Technik) | hoch (Pflege!) | gering | mittel (Nische Multi-Hop) | unbezahlte Pflegearbeit im Fachbereich |
| **GraphRAG (automatisch, HippoRAG-2-Klasse)** | reifend | hoch | Index-LLM + Betrieb | nur bei Multi-Hop-Nachweis | Kosten, Updates, Rechte im Graphen |
| **MS-GraphRAG-Vollindexierung** | im Wartungsmodus | sehr hoch | ~1000× Vektor-RAG | gering für OPAA-Profil | Kosten, Update-Problem, tote OSS-Linie |
| **RAPTOR** | reifend | hoch | LLM beim Ingest | gering für OPAA-Profil | zitiert Summaries statt Originaltext |
| **Semantic Chunking** | widerlegt für den Zweck | mittel | Embedding-Mehrkosten | ~null | Evidenz dagegen (NAACL 2025) |
| **ColBERT/Late Interaction** | reifend | hoch | 10–50× Speicher | mittel | pgvector kann kein MaxSim; Lizenzfallen |
| **SPLADE/ELSER** | für Deutsch unreif | mittel | GPU | unklar für DE | Lizenz + fehlende Deutsch-Evidenz |
| **Long Context statt RAG** | widerlegt als Ersatz | — | 8–82× teurer | negativ (Rechte!) | Context Rot, keine Fundstellen |
| **Self-RAG (trainiert)** | Forschung | sehr hoch (Training) | — | — | nicht praktikabel |

## 17. Synthese

1. **Der State of the Art ist konvergiert.** Strukturbewusstes Parsing → dokumenttyp-spezifisches Chunking mit Kontextanreicherung → Hybrid-Retrieval → Cross-Encoder-Reranking → optionale Spezialpfade (Graph für Multi-Hop, Deep Research für Berichte) → Absicherung durch deterministische Validierung. Microsoft, RAGFlow, Onyx und die Framework-Welt bauen alle dieselbe Pyramide.
2. **OPAA hat die Spitze der Pyramide vor dem Fundament gebaut** — und das ist kein Vorwurf: Teilfragen-Zerlegung, RRF, DocumentCompletion und die (im Feld seltene!) deterministische Zitatvalidierung sind vorhanden; es fehlt die lexikalische Erststufe und das Reranking darunter. #938 ist der gemessene Beweis der Lücke.
3. **Die Verwaltung verschiebt die Gewichte.** Exakte Kennungen (§§, Aktenzeichen) machen die lexikalische Komponente wichtiger als in generischen Benchmarks; Zitierpflicht disqualifiziert Summary-zitierende Verfahren (RAPTOR); Rechteprüfung disqualifiziert naive Graph-Übernahme; Fassungs-/Ebenenfragen sind Metadaten-, nicht Embedding-Probleme.
4. **Graph RAG ist eine Wette auf ein Fragenprofil, das erst nachzuweisen ist.** Die Evidenz ist konsistent: große Gewinne nur bei echtem Multi-Hop, sonst nichts bis negativ — bei 1000-fachen Indexkosten im Voll-Ausbau. Der rationale Weg: Multi-Hop-Fälle ins Golden Dataset aufnehmen, messen, dann entscheiden. Microsofts eigene Kehrtwende (LazyGraphRAG, Wartungsmodus) bestätigt die Skepsis.
5. **Im deutschen Verwaltungsumfeld ist die Messlatte niedrig.** Kein vergleichbares Projekt publiziert Retrieval-Metriken. OPAAs Eval-Harness plus ein sauber gemessener Hybrid+Reranking-Ausbau wäre dokumentierbar führend.

Die konkrete Phasen-Ableitung mit Begründungen der bewussten Nicht-Entscheidungen: [discussion-retrieval-roadmap-opaa.md](discussion-retrieval-roadmap-opaa.md).

---

## Quellen (Auswahl, thematisch)

- **OPAA-intern:** [retrieval-algorithm.md](../features/retrieval-algorithm.md), [data-indexing-rag.md](../features/data-indexing-rag.md), [GraphRAG.md](GraphRAG.md), Issues #912–#942 (Retrieval-Härtung), #938 (Hybrid-Grenzfall), #317 (GraphRAG-Recherche)
- **Hybrid/Reranking:** [Azure-Benchmark](https://techcommunity.microsoft.com/blog/azure-ai-foundry-blog/azure-ai-search-outperforming-vector-search-with-hybrid-retrieval-and-reranking/3929167) · [RRF (Cormack 2009)](https://cormack.uwaterloo.ca/cormacksigir09-rrf.pdf) · [Bruch et al., TOIS 2023](https://arxiv.org/abs/2210.11934) · [Blended RAG](https://arxiv.org/pdf/2404.07220) · [kapa.ai Best Practices](https://www.kapa.ai/blog/rag-best-practices)
- **Chunking:** [Chroma: Evaluating Chunking](https://www.trychroma.com/research/evaluating-chunking) · [Anthropic Contextual Retrieval](https://www.anthropic.com/engineering/contextual-retrieval) · [Late Chunking](https://arxiv.org/abs/2409.04701) · [Semantic-Chunking-Kritik](https://arxiv.org/abs/2410.13070)
- **GraphRAG:** [Edge et al.](https://arxiv.org/abs/2404.16130) · [LazyGraphRAG](https://www.microsoft.com/en-us/research/blog/lazygraphrag-setting-a-new-standard-for-quality-and-cost/) · [LightRAG](https://arxiv.org/abs/2410.05779) · [Unverzerrte Re-Evaluation](https://arxiv.org/pdf/2506.06331) · [HippoRAG 2](https://arxiv.org/abs/2502.14802) · [RAG vs. GraphRAG](https://arxiv.org/pdf/2502.11371) · [GraphRAG-Bench](https://arxiv.org/pdf/2506.05690) · [GraphRAG under Fire](https://arxiv.org/abs/2501.14050)
- **Query/Agentic:** [HyDE](https://arxiv.org/abs/2212.10496) · [Searching-for-Best-Practices-Vergleich](https://arxiv.org/abs/2407.01219) · [IRCoT](https://arxiv.org/abs/2212.10509) · [CRAG](https://arxiv.org/abs/2401.15884) · [ReAct](https://arxiv.org/pdf/2210.03629) · [Azure Agentic Retrieval](https://learn.microsoft.com/en-us/azure/search/agentic-retrieval-overview)
- **Long Context:** [NoLiMa](https://arxiv.org/abs/2502.05167) · [Context Rot](https://www.trychroma.com/research/context-rot) · [DeepMind LC vs. RAG](https://arxiv.org/abs/2407.16833)
- **Recht/Verwaltung:** [Stanford „Hallucination-Free?"](https://arxiv.org/abs/2405.20362) · [Large Legal Fictions](https://arxiv.org/abs/2401.01301) · [LegalBench-RAG](https://arxiv.org/abs/2408.10343) · [german-decompounder](https://github.com/uschindler/german-decompounder) · [Rechtsinformationsportal](https://www.rechtsinformationsportal.de/)
- **Systeme:** [RAGFlow-Doku](https://ragflow.io/docs/release_notes) · [Infinity Hybrid-Search](https://infiniflow.org/blog/best-hybrid-search-solution) · [Onyx-Architektur](https://onyx-dot-app-onyx.mintlify.app/architecture) · [F13 auf openCode](https://f13-os.de/) · [LLMoin](https://www.dataport.de/nachricht/kuenstliche-intelligenz-hamburg-startet-mit-ki-assistent-llmoin-in-den-regelbetrieb/)
