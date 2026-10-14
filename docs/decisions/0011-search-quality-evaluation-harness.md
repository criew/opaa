# ADR-0011: Aufbau der Suchqualitäts-Evaluierung und Ablage des Testkorpus

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt durch ADR-0010, ADR-0012, ADR-0013

## Kontext

OPAA konnte nicht beziffern, ob eine Änderung an Chunking, Embedding-Modell, `top-k` oder
Ähnlichkeitsschwelle das Retrieval verbessert oder verschlechtert.
`docs/discussions/discussion-rag-evaluation.md` klärte, was gemessen wird (Hit Rate, MRR, nDCG, Golden
Dataset, gepaarte Vergleiche); `docs/features/search-quality-evaluation.md` beschreibt das Vorhaben.
Strukturell festzulegen waren die Ablage des eingefrorenen Korpus, der Aufbau des Harness (eine zweite
Toolchain ist dauerhafte Wartungslast) und das Einbettungsmodell in der CI, von dem die Stabilität der
Baseline abhängt.

## Entscheidung

### 1. Korpus als reguläre Dateien im Repository

Der generierte Korpus liegt unter `eval/corpus/<domäne>/` mit einer `MANIFEST.sha256` über alle
Dateien; kein Git LFS. Die Rohquelle wird in `SOURCE.md` mit URL, Abrufdatum und SHA-256 dokumentiert;
eingefrorene Rohdaten liegen nur dort, wo der Generator sie braucht, unter `eval/generator/frozen/`.
Wächst der Gesamtkorpus unter `eval/corpus/` (ohne Rohdaten) über rund 25 MB, wechselt die betroffene Domäne auf ein
GitHub-Release-Artefakt, das Manifest bleibt im Repository; die Grenze wird mit jeder neuen Domäne
neu bewertet.

### 2. Generator als eigenständiges Python-Werkzeug

`eval/generator/` ist kein Teil des Gradle-Builds, deterministisch (stabile Sortierung, fester Seed,
byte-identischer Output) und läuft nur bei bewussten Korpus-Änderungen, nie in CI oder zur Laufzeit.
Nur CC0- oder CC-BY-Quellen kommen in den Korpus; CC BY-SA und Non-Commercial sind ausgeschlossen.
Übernommen werden strukturierte Faktenfelder, den Fließtext formuliert der Generator selbst. Die
Chunk-Zahl-Erwartung je Domäne regelt ADR-0010.

### 3. Java-nativer Retrieval-Harness

Der Harness ist ein JUnit-Integrationstest im Backend (`backend/src/evalTest/java/io/opaa/eval/`,
Gradle-Tasks in `backend/build.gradle.kts`), der mit Testcontainers (pgvector) den Korpus über die
produktive Indizierungspipeline einliest und die Vektorsuche direkt abfragt. Im Rohvektor-Pfad ist
kein LLM beteiligt. Der zusätzliche Pipeline-Pfad misst die Retrieval-Pipeline mit
Teilfragen-Zerlegung und braucht dafür ein Chat-Modell nach Entscheidung 4. Ein Python-Sidecar (RAGAS)
wird nicht eingeführt, solange nur Retrieval-Metriken gemessen werden.

### 4. Festgenagelte lokale Modelle über Ollama

Das Einbettungsmodell der CI ist `nomic-embed-text:v1.5` über Ollama (Testcontainer oder der externe
Endpunkt aus #1076, `EvalOllamaEndpoint`), auf Tag und Content-Digest
festgenagelt mit hartem Abbruch bei Drift (`EvalOllamaModel#ensurePresent`); ein neuer Digest ist eine
bewusste Neuziehung mit neuen Zahlen im PR. Dieselbe Disziplin gilt für das Chat-Modell des
Pipeline-Pfads (`EvalChatModel`: `qwen2.5:1.5b-instruct`, Digest-Pin, Temperatur 0, verdrahtet über
eine aktive Zeile in `llm_models` und `ActiveChatModelResolver` wie jede echte Anfrage). Ausschlaggebend
ist die Baseline-Stabilität: Ein gehostetes Modell kann still geändert werden, ein festgenageltes
lokales nicht; dazu kommt, dass Forks kein Secret haben. Temperatur 0 macht die Zerlegung nicht
deterministisch; ein zerlegender Lauf ist eine zweite, benannte Messung, kein Ersatz für die
deterministische Baseline (`docs/features/retrieval-benchmark.md`, Mehrfachlauf-Regel).

### 5. Regressionsjob nicht bei jedem Pull Request

`.github/workflows/retrieval-regression.yml` läuft nächtlich auf `main`, per `workflow_dispatch` und
per Label `evaluation` an einem PR, eine Matrix je Domäne. Er schlägt fehl, wenn eine Primärmetrik eine
harte Untergrenze unterschreitet oder um mehr als die Toleranz unter die committete Baseline fällt; das
Fehlerkriterium präzisiert ADR-0013.

### 6. Golden Dataset und Baseline als versionierte Dateien

`eval/golden/<domäne>.json` und `eval/baseline/<domäne>.json` liegen im Repository und werden wie Code
behandelt: Änderungen sind reviewpflichtige Commits mit begründeter Baseline-Aktualisierung
(`eval/baseline/README.md`).

## Konsequenzen

Einfacher: Der Regressionstest läuft ohne Netzzugriff auf Fremdquellen und ohne Secrets, auch in
Forks; Korpus-Änderungen sind im Diff sichtbar, unbeabsichtigtes Wandern fällt über das Manifest auf;
der Harness braucht kein laufendes System und keine Demo-Instanz.

Schwieriger: Das Repository wächst um den Korpus; Python als zweite Sprache im
Werkzeugkasten; ein Ollama-Container kostet Laufzeit, Regressionen fallen bis zu einem Tag verzögert
auf; die Baseline ist an Modellversion und Korpus gebunden, jede Änderung an einem von beiden
erfordert einen neuen Baseline-Lauf.

Restrisiken: Der HNSW-Index (`spring.ai.vectorstore.pgvector.index-type`) ist approximativ; die
Toleranz muss Schwankungen abfedern, oder der Indextyp wird für die Evaluierung konfigurierbar
gemacht; der Messvertrag führt ihn als `pgvectorIndexType` (ADR-0012). Der Harness misst nur Retrieval, nie die Generierung; was eine
Demo- oder Betriebsinstanz mit anderem Embedding- oder Chat-Modell erlebt, belegt er nicht.

## Verworfene Alternativen

- Git LFS oder Release-Artefakt von Anfang an — schlechtere Reviewbarkeit und Reproduzierbarkeit bei
  heute kleinem Korpus.
- Korpus-Generator im Gradle-Build — Generierung ist ein bewusster Schritt, kein Build-Artefakt.
- Python-Sidecar (RAGAS) — zweite Toolchain ohne Bedarf, solange nur Retrieval gemessen wird.
- Gehostete Embeddings (OpenAI) als Baseline — stille Modelländerungen lassen die Baseline driften,
  Forks haben kein Secret.
- Lauf bei jedem Pull Request — Modell-Pull und Einbettung des Korpus dauern Minuten.

## Offen

Nichts.
