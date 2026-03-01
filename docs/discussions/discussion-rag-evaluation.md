# Discussion: RAG-Qualität messen und vergleichen

**Thema:** Wie misst man die Qualität eines RAG-Systems? Welche Metriken, Frameworks, Benchmarks und Best Practices gibt es?

**Kontext:** Aufbauend auf den Diskussionen zu [Embedding-Modellen](discussion-embeddings.md) und [Retrieval-Pipelines](discussion-retrieval-document-pipelines.md). Ziel ist es, Aussagen wie *"Modell X mit Embedding Y liefert N% bessere Antworten als Modell Z mit Embedding W"* fundiert treffen zu können.

**Bezug zum Projekt:** OPAA nutzt Spring AI 1.1.2, pgvector, Ollama/OpenAI

---

## 1. RAG-Evaluation: Die drei Ebenen

Ein RAG-System hat zwei Hauptkomponenten, die separat und zusammen gemessen werden sollten:

```
Query → [Retrieval] → relevante Chunks → [Generation] → Antwort
         ↑                                   ↑
   Retrieval-Metriken               Generations-Metriken
         └───────────── End-to-End ──────────┘
```

---

## 2. Metriken im Detail

### 2.1 Retrieval-Metriken (Wie gut findet die Suche relevante Dokumente?)

| Metrik | Was sie misst | Berechnung |
|---|---|---|
| **Hit Rate @k** | Ist mindestens ein relevantes Dokument in den Top-k? | Binär: 1 wenn ja, 0 wenn nein. Durchschnitt über alle Queries. |
| **MRR (Mean Reciprocal Rank)** | Wie hoch ist das erste relevante Ergebnis gerankt? | Durchschnitt von 1/Position des ersten relevanten Treffers |
| **Precision @k** | Welcher Anteil der Top-k Ergebnisse ist relevant? | Relevante in Top-k / k |
| **Recall @k** | Welcher Anteil aller relevanten Dokumente ist in Top-k? | Relevante in Top-k / Alle relevanten |
| **NDCG @k** | Qualität der gesamten Ranking-Liste | Gewichtete Summe (höhere Positionen zählen mehr), normalisiert gegen ideales Ranking |
| **Context Precision (RAGAS)** | Sind relevante Chunks höher gerankt als irrelevante? | Positionsgewichteter Mean Average Precision |
| **Context Recall (RAGAS)** | Sind alle nötigen Informationen im abgerufenen Kontext? | LLM prüft, ob jeder Satz der Ground-Truth-Antwort im Kontext zuordenbar ist |

**Praxis-Empfehlung:** Hit Rate für schnelles Debugging, MRR + Recall beim Retriever-Tuning, NDCG + Hit Rate für Systemvergleiche.

### 2.2 Generations-Metriken (Wie gut ist die generierte Antwort?)

| Metrik | Was sie misst | Berechnung |
|---|---|---|
| **Faithfulness** | Ist die Antwort faktisch im Kontext verankert? | Antwort wird in Einzelaussagen zerlegt, jede wird gegen Kontext geprüft. Score = gestützte / alle Aussagen |
| **Answer Relevancy** | Beantwortet die Antwort die Frage? | Aus der Antwort werden hypothetische Fragen generiert → Cosine Similarity mit Originalfrage |
| **Hallucination Rate** | Enthält die Antwort nicht-gestützte Behauptungen? | 1 - Faithfulness |
| **Answer Correctness** | Ist die Antwort faktisch korrekt? (braucht Ground Truth) | F1 über extrahierte Fakten + semantische Ähnlichkeit zur Referenzantwort |

### 2.3 End-to-End-Metriken

| Metrik | Was sie misst |
|---|---|
| **Answer Similarity** | Semantische Ähnlichkeit zwischen generierter und erwarteter Antwort |
| **Latency (P50, P95, P99)** | Antwortzeit von Query bis Response |
| **Token Efficiency** | Verhältnis nützlicher Kontext-Tokens zu total abgerufenen Tokens |
| **Cost per Query** | Token-Verbrauch und API-Kosten pro Anfrage |

---

## 3. Evaluation-Frameworks

### 3.1 Java / Spring AI (nativ verfügbar!)

Spring AI 1.1.x hat **zwei eingebaute Evaluatoren**, die sofort nutzbar sind:

#### RelevancyEvaluator
- Prüft, ob die KI-Antwort relevant zur Frage ist (bezogen auf den abgerufenen Kontext)
- Gibt `isPass()` true/false zurück
- Nutzt ein LLM zur Bewertung

#### FactCheckingEvaluator
- Prüft faktische Korrektheit der Antwort gegen den abgerufenen Kontext
- Erkennt Halluzinationen
- Gibt ebenfalls pass/fail zurück

**Evaluierungs-Modell-Tipp:** `bespoke-minicheck` (Bespoke Labs) ist speziell für Fact-Checking trainiert, sehr effizient (nur yes/no Output), und top-gerankt auf dem LLM-AggreFact Leaderboard.

**Vorteil:** Native Java, integriert direkt in JUnit-Tests und CI/CD.

### 3.2 Python-Frameworks (umfangreichere Metriken)

Alle großen RAG-Evaluation-Frameworks sind Python-basiert:

#### RAGAS (de-facto Standard)
- Open-Source, referenz-frei (braucht keine Ground Truth für viele Metriken)
- LLM-as-a-Judge Ansatz
- Metriken: Faithfulness, Answer Relevancy, Context Precision, Context Recall
- Paper: arXiv:2309.15217

#### DeepEval
- pytest-artig mit pass/fail Thresholds
- Integriert RAGAS-Metriken
- G-Eval: Eigene Bewertungskriterien mit Chain-of-Thought
- Gut für CI/CD-Integration

#### TruLens
- "RAG Triad": Context Relevance + Groundedness + Answer Relevance
- OpenTelemetry-basiertes Tracing
- Gut für qualitative Analyse

#### LangSmith
- Kommerziell (LangChain)
- Offline- und Online-Evaluation
- Pairwise-Vergleiche
- REST API für Java-Integration

#### Weitere
- **Giskard:** Fokus auf Safety/Halluzinations-Tests
- **Langfuse:** Open-Source Observability mit RAGAS-Integration
- **Phoenix (Arize):** LLM-Observability mit OpenTelemetry

### 3.3 Java-Integration: Hybrid-Ansatz

Da alle umfassenden Frameworks Python-basiert sind:

```
┌─────────────────────────────────────────────────────┐
│ CI/CD Pipeline                                      │
│                                                     │
│  JUnit Tests                Python Sidecar (Docker) │
│  ┌─────────────────┐       ┌─────────────────────┐  │
│  │ Spring AI        │       │ RAGAS / DeepEval    │  │
│  │ RelevancyEval    │       │ - Faithfulness      │  │
│  │ FactCheckingEval │       │ - Answer Relevancy  │  │
│  │ Hit Rate, MRR    │       │ - Context Precision │  │
│  └────────┬────────┘       │ - Context Recall    │  │
│           │                └────────┬────────────┘  │
│           └──────────┬─────────────┘                │
│                      ▼                              │
│              Shared Golden Dataset (JSON)            │
└─────────────────────────────────────────────────────┘
```

---

## 4. Benchmark-Datensätze

### 4.1 Standardisierte Retrieval-Benchmarks

| Benchmark | Beschreibung | Sprachen | Primärmetrik |
|---|---|---|---|
| **MTEB** | 58 Datasets, 8 Aufgabentypen, DER Standard für Embedding-Vergleiche | primär EN | diverse |
| **BEIR** | 18 Datasets für Zero-Shot Retrieval (Subset von MTEB) | EN | NDCG@10 |
| **MS MARCO** | 1M+ Bing-Fragen mit menschlich annotierten Antworten | EN | MRR@10 |
| **Natural Questions** | Echte Google-Suchanfragen mit Wikipedia-Antworten | EN | EM/F1 |
| **HotpotQA** | Multi-Hop-Fragen (Infos aus mehreren Dokumenten kombinieren) | EN | EM/F1 |

### 4.2 End-to-End RAG-Benchmarks

| Benchmark | Beschreibung | Besonderheit |
|---|---|---|
| **RAGBench** | 100k+ Beispiele, 5 Domänen, annotiert für Halluzination/Faithfulness | arXiv:2407.11005 |
| **Open RAG Benchmark (Vectara)** | 1000 arXiv-PDFs, 3045 Q&A-Paare, Text + Tabellen + Bilder | Multimodal, PDF-fokussiert |
| **MultiHop-RAG** | Multi-Hop Reasoning über mehrere Dokumente | Komplexe Retrieval-Szenarien |

### 4.3 Deutsche / mehrsprachige Benchmarks

| Benchmark | Sprachen | Beschreibung |
|---|---|---|
| **MEMERAG** | 5 Sprachen (inkl. DE) | Multilingual RAG Meta-Evaluation, basiert auf MIRACL |
| **MIRAGE-Bench** | 18 Sprachen (inkl. DE) | 11.195 Evaluation-Paare, menschlich generierte Fragen |
| **XRAG** | 5 Sprachen (inkl. DE) | Cross-lingual RAG (Query-Sprache ≠ Dokument-Sprache) |

**Realität:** Deutsche RAG-Benchmarks sind noch begrenzt. Für domänenspezifische Evaluation muss ein eigener Golden Dataset erstellt werden.

---

## 5. Golden Dataset aufbauen (eigene Testdaten)

### Was ist ein Golden Dataset?

Eine kuratierte, versionierte Sammlung von **(Frage, erwartete relevante Dokumente, erwartete Antwort)** — die Ground Truth für euer System.

### Schritt-für-Schritt

#### 1. Synthetische Daten generieren ("Silver Dataset")
- LLM generiert Frage-Antwort-Paare aus euren echten Dokumenten
- RAGAS bietet einen `TestsetGenerator` dafür
- Schnell skalierbar (hunderte Paare in Minuten)

#### 2. Experten-Review ("Silver → Gold")
- Fachexperten prüfen und korrigieren die generierten Paare
- Inter-Annotator Agreement messen (Cohen's Kappa)
- Falsche, uneindeutige oder triviale Fragen aussortieren

#### 3. Struktur pro Testfall

```json
{
  "id": "q-042",
  "query": "Welche Chunking-Strategie wird für PowerPoint empfohlen?",
  "expected_documents": ["discussion-retrieval-document-pipelines.md"],
  "expected_answer_contains": ["pro Folie", "Kontext anreichern", "Folientitel"],
  "category": "architecture",
  "difficulty": "medium",
  "language": "de",
  "type": "factual"
}
```

#### 4. Diversität sicherstellen
- Verschiedene Fragetypen: faktisch, vergleichend, Multi-Hop
- Verschiedene Dokumenttypen abdecken
- Verschiedene Schwierigkeitsgrade
- Edge Cases (leere Dokumente, sehr kurze Chunks, gemischtsprachig)

#### 5. Empfohlene Größe
- **Start:** 50–100 hochwertige Testfälle
- **Umfassend:** 200–500
- **Qualität > Quantität** — 50 saubere Paare sind besser als 500 unsaubere

#### 6. Versionierung
- Golden Dataset wie Code behandeln: Git, Reviews, Changelog
- Aktualisieren wenn sich der Dokumentenkorpus ändert

---

## 6. LLM-as-a-Judge: Best Practices

### Grundprinzip
Ein LLM bewertet die Ausgabe eines anderen LLM anhand definierter Kriterien.

### Empfehlungen für zuverlässige Bewertungen

1. **Strukturierte Rubrics:** Explizite Bewertungskriterien (1–5 Skala oder binär) mit klaren Beschreibungen pro Stufe
2. **Kalibrierung:** Pilot-Runde, in der Mensch und LLM dieselben Samples bewerten → Alignment messen (Cohen's Kappa)
3. **Mehrere Judge-Modelle:** 2–3 verschiedene LLMs bewerten, Mehrheitsentscheid oder Durchschnitt
4. **Position Bias vermeiden:** LLMs bevorzugen oft die erste/letzte Option bei Paarvergleichen → Reihenfolge randomisieren
5. **Chain-of-Thought:** Judge soll seine Bewertung begründen → interpretierbar und auditierbar

---

## 7. Konfigurationen statistisch sauber vergleichen

### Das Ziel
> "Konfiguration A (Modell X + Embedding Y) liefert signifikant bessere Antworten als Konfiguration B"

### Methode: Gepaarter Vergleich

#### Schritt 1: Metriken definieren
- 2–3 Primärmetriken auswählen (z.B. Faithfulness + Answer Relevancy + Hit Rate@5)
- Ggf. Gewichtung für Composite Score

#### Schritt 2: Gepaarte Evaluation
- **Beide** Konfigurationen auf **denselben** Golden Dataset laufen lassen
- Pro Query: (Score_A, Score_B) → Paarung eliminiert Dataset-Varianz

#### Schritt 3: Mehrere Durchläufe
- Bei Randomisierung (LLM Temperature, Sampling): Jeden Lauf mehrfach wiederholen
- Verteilung statt Einzelwert

#### Schritt 4: Statistische Signifikanz

**Empfohlen: Paired Bootstrap Test**
1. Gepaarte Multi-Seed-Evaluation durchführen
2. BCa (Bias-corrected and accelerated) Bootstrap-Konfidenzintervalle auf Score-Deltas berechnen
3. Sign-Flip Permutationstest auf Per-Seed-Deltas
4. Verbesserung nur behaupten wenn: Konfidenzintervall komplett über 0 UND p < 0.05

**Einfachere Alternative:** Wilcoxon Signed-Rank Test (nicht-parametrisch, funktioniert gut bei kleinen Samples)

#### Reporting-Template

```
Konfiguration A: Ollama/nomic-v2 + TokenSplitter(1000), top_k=5
Konfiguration B: Ollama/nomic-v2 + ParagraphPdfReader + TokenSplitter(600), top_k=5

Golden Dataset: 100 Queries, 3 Dokumentkategorien, DE + EN

Ergebnisse (Mean ± 95% CI):
  Faithfulness:     A: 0.82 ± 0.04   B: 0.89 ± 0.03   Δ: +0.07 (p=0.008)
  Answer Relevancy: A: 0.79 ± 0.05   B: 0.84 ± 0.04   Δ: +0.05 (p=0.021)
  Hit Rate@5:       A: 0.85 ± 0.04   B: 0.91 ± 0.03   Δ: +0.06 (p=0.014)

Fazit: Konfiguration B zeigt signifikante Verbesserung über alle Metriken
       (alle p < 0.05, Paired Bootstrap Test).
```

### Was man NICHT tun sollte
- Einzelne Aggregatzahl ohne Konfidenzintervall berichten
- Verbesserung auf Basis eines einzigen Laufs behaupten
- Verschiedene Golden Datasets für A und B verwenden

---

## 8. Empfohlener Ansatz für OPAA

### Phase 1: Sofort (Spring AI nativ)

- `RelevancyEvaluator` + `FactCheckingEvaluator` in Integrationstests einbauen
- Hit Rate und MRR direkt über pgvector-Queries messen
- 50 Testfälle als Golden Dataset erstellen (JSON, im Repo versioniert)

### Phase 2: Kurzfristig (Retrieval-Metriken)

- Retrieval-Metriken (Hit Rate, MRR, Precision@k) als JUnit-Tests
- Golden Dataset auf 100–200 Testfälle erweitern
- A/B-Vergleiche bei Pipeline-Änderungen (z.B. `ParagraphPdfReader` vs. `TikaReader`)

### Phase 3: Mittelfristig (umfassende Evaluation)

- Python-Sidecar (Docker) mit RAGAS für Faithfulness, Context Precision/Recall
- Shared Golden Dataset zwischen Java und Python (JSON)
- Automatisierte Vergleiche in CI/CD bei Modell- oder Pipeline-Änderungen

### Phase 4: Langfristig

- Eigener deutscher Benchmark aus Domänendaten
- Online-Evaluation auf Produktionsdaten (Langfuse oder Phoenix)
- Regelmäßige Recalibration der LLM-as-Judge gegen menschliche Bewertungen

---

## 9. Zusammenfassung der Kernerkenntnisse

| Erkenntnis | Detail |
|---|---|
| Spring AI hat eingebaute Evaluatoren | `RelevancyEvaluator` + `FactCheckingEvaluator` — sofort nutzbar in JUnit |
| Umfassende Frameworks sind Python-only | RAGAS, DeepEval, TruLens — Integration über Docker-Sidecar möglich |
| Golden Dataset ist der Schlüssel | 50–100 kuratierte Testfälle, versioniert wie Code |
| Retrieval und Generation separat messen | Unterschiedliche Metriken für unterschiedliche Probleme |
| Statistische Signifikanz ist Pflicht | Gepaarter Bootstrap-Test oder Wilcoxon, nie Einzelwerte vergleichen |
| Deutsche Benchmarks sind begrenzt | MEMERAG und MIRAGE-Bench existieren, aber eigener Golden Dataset ist nötig |
| `bespoke-minicheck` als Evaluierungs-LLM | Effizienter und genauer als GPT-4o für Fact-Checking |

---

## 10. Referenzen

- RAGAS: https://docs.ragas.io/
- DeepEval: https://deepeval.com/
- TruLens: https://www.trulens.org/
- Spring AI Evaluation: https://docs.spring.io/spring-ai/reference/api/testing.html
- RAGBench: arXiv:2407.11005
- MEMERAG: arXiv:2502.17163
- MIRAGE-Bench: https://mirage-bench.github.io/
- Paired Bootstrap Protocol: arXiv:2511.19794
- BES4RAG Framework: ACL Anthology 2025
