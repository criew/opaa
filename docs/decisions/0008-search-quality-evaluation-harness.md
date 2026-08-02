# ADR-0008: Aufbau der Suchqualitäts-Evaluierung und Ablage des Testkorpus

## Status

Akzeptiert

## Kontext

OPAA kann heute nicht beziffern, ob eine Änderung an Chunking, Embedding-Modell, `top-k` oder
Ähnlichkeitsschwelle das Retrieval verbessert oder verschlechtert. `docs/discussions/discussion-rag-evaluation.md`
hat geklärt **was** gemessen werden soll (Hit Rate, MRR, nDCG, Golden Dataset, gepaarte Vergleiche);
offen ist **wie** das dauerhaft im Projekt verankert wird. Die Feature-Spezifikation
`docs/features/search-quality-evaluation.md` beschreibt das Vorhaben fachlich.

Drei Punkte darin sind strukturell und legen das Projekt langfristig fest:

1. **Wo liegt der Testkorpus?** Ein Regressionstest ist nur aussagekräftig, wenn der Korpus
   eingefroren ist. Repository, Git LFS und Release-Artefakt haben unterschiedliche Folgen für
   Reproduzierbarkeit, CI und Beitragende.
2. **Woraus besteht der Evaluierungs-Harness?** Spring AI bringt Evaluatoren mit, die
   umfassenderen Metriken existieren nur in Python. Ein zweiter Toolchain im Projekt ist eine
   dauerhafte Wartungslast.
3. **Welches Einbettungsmodell läuft in CI?** Davon hängen Kosten, Laufzeit und — entscheidend —
   ob die Baseline über die Zeit stabil bleibt.

## Entscheidung

**1. Der generierte Korpus liegt als reguläre Dateien im Hauptrepository** unter `eval/corpus/<domäne>/`,
zusammen mit einer `MANIFEST.sha256` über alle generierten Dateien. Kein Git LFS. Die Rohquelle
wird als Snapshot mit Quell-URL, Abrufdatum und SHA-256 dokumentiert, aber nicht committet. Wächst
der Gesamtkorpus über rund 25 MB, wird pro Domäne auf ein GitHub-Release-Artefakt gewechselt,
wobei das Manifest im Repository verbleibt. Diese Grenze wird bei der Ausweitung auf vier Domänen
erneut bewertet.

**2. Der Korpus-Generator ist ein eigenständiges Python-Werkzeug** unter `eval/generator/`, kein
Bestandteil des Gradle-Builds. Er ist deterministisch (stabile Sortierung, fixer Seed wo Zufall
im Spiel ist, byte-identischer Output) und wird nur bei bewussten Korpus-Änderungen ausgeführt —
nie in CI und nie zur Laufzeit.

**2a. Nur CC0- oder CC-BY-Quellen** kommen in den verbreiteten Korpus; CC BY-SA und
non-commercial-Lizenzen sind ausgeschlossen. Übernommen werden ausschließlich strukturierte
Faktenfelder; der Fließtext der Entitäts-Dokumente wird vom Generator selbst formuliert. Das hält
die Provenienz sauber **und** die Dokumentgröße so klein, dass eine Entität genau einem Chunk
entspricht — die Voraussetzung für eindeutige Ground Truth.

**3. Der Retrieval-Harness ist Java-nativ**: ein JUnit-Integrationstest im Backend, der Testcontainers
(pgvector) verwendet, den Korpus über die produktive Indexierungs-Pipeline einliest und
`VectorStore.similaritySearch` direkt abfragt. Kein LLM ist beteiligt. Ein Python-Sidecar (RAGAS)
wird **nicht** eingeführt, solange nur Retrieval-Metriken gemessen werden; er bleibt eine Option für
Generationsmetriken in einer späteren Phase.

**4. Das Einbettungsmodell in CI ist Ollama mit `nomic-embed-text`**, festgenagelt auf eine
Modellversion, als Testcontainer. OpenAI-Embeddings sind ein optionaler zusätzlicher Lauf für
Vergleichszwecke, nie die Grundlage der Baseline.

**5. Der Regressionsjob läuft nicht bei jedem Pull Request**, sondern nächtlich auf `main`, manuell
auslösbar und per Label an einem PR. Er schlägt fehl, wenn eine Primärmetrik eine harte Untergrenze
unterschreitet oder um mehr als eine definierte Toleranz unter die committete Baseline fällt.

**6. Das Golden Dataset liegt als versioniertes JSON im Repository** und wird wie Code behandelt:
Änderungen sind reviewpflichtige Commits mit begründeter Baseline-Aktualisierung.

## Konsequenzen

**Einfacher:**

- Der Regressionstest läuft ohne Netzzugriff auf Fremdquellen und ohne Secrets — auch in Forks.
- Korpus-Änderungen sind im Diff sichtbar und reviewbar; unbeabsichtigtes Wandern des Korpus fällt
  über das Manifest sofort auf.
- Der Harness braucht kein laufendes System, keine Demo-Instanz und kein LLM. Er ist damit
  unabhängig von der Demo-Bereitstellung entwickelbar.
- Die Demo-Ingestion kommt ohne neuen Ingestion-Code aus: derselbe Korpus wird über den
  bestehenden HTTP-Verzeichnis-Konnektor gelesen.

**Schwieriger:**

- Das Repository wächst um den Korpus. Bei vier Domänen ist die Obergrenze erreichbar und der
  Wechsel auf Release-Artefakte wird dann nötig.
- Das Projekt bekommt eine zweite Sprache im Werkzeugkasten (Python für den Generator). Sie ist
  bewusst außerhalb von Build und CI gehalten, bleibt aber Wartungsfläche.
- Ein Ollama-Container in CI kostet Laufzeit (Modell-Pull plus Einbettung des Korpus). Deshalb kein
  Lauf bei jedem Pull Request — Regressionen fallen dadurch mit bis zu einem Tag Verzögerung auf.
- Der pgvector-Index ist fest auf HNSW gesetzt (siehe Issue #77). Approximative Suche kann Metriken
  leicht schwanken lassen; die Toleranz muss das abfedern, oder der Indextyp wird für die
  Evaluierung konfigurierbar gemacht.
- Die Baseline ist an Modellversion **und** Korpus gebunden. Jede Änderung an einem der beiden
  erfordert einen bewussten neuen Baseline-Lauf.
