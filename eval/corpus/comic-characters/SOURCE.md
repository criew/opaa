# Quelle: Domäne Comichelden

| | |
|---|---|
| **Datensatz** | [`jrtec/Superheroes`](https://huggingface.co/datasets/jrtec/Superheroes) auf HuggingFace |
| **Lizenz** | CC0-1.0 (gemeinfrei, keine Attributionspflicht) |
| **Abgerufener Commit** | `a2f7f35c36a4d551625a0607c7759ae7916fc6be` |
| **Abrufdatum** | 2026-08-02 |
| **Verwendete Dateien** | `train.csv`, `test.csv` (Splits des Datensatzes, zusammen verwendet) |
| **SHA-256 `train.csv`** | `6db455fcb39c5eb1cce639c4d92e971bad96a53daa7cc2d2c04c3c73dca89f0c` |
| **SHA-256 `test.csv`** | `565ab276d1f754a9ef35ebc7d11df087fab92c5c9de4d004f5b184033f9b0103` |
| **Zeilen im Snapshot** | 1.158 (`train.csv`) + 290 (`test.csv`) = 1.448 |
| **Sampling** | Keins — der vollständige Datensatz wird verwendet |

Ein Quellenhinweis wird geführt, obwohl CC0-1.0 ihn nicht verlangt — Nachvollziehbarkeit gehört
zum Charakter dieses Korpus (siehe `docs/features/search-quality-evaluation.md`,
Abschnitt „Lizenz-Rahmen").

## Wie diese Dateien entstanden sind

Erzeugt durch [`eval/generator/generate_corpus.py`](../../generator/generate_corpus.py); siehe
[`eval/generator/README.md`](../../generator/README.md) für den vollständigen Reproduktionslauf
und die Verwerfungsregel. Jedes generierte Dokument trägt zusätzlich `source` und `license` im
eigenen YAML-Frontmatter.

## Integritätsprüfung

```bash
cd eval/corpus/comic-characters
sha256sum -c MANIFEST.sha256
```

## Umfang

1.448 Markdown-Dateien, Gesamtgröße rund 1,8 MB (deutlich unter der 5-MB-Obergrenze aus den
Abnahmekriterien von Issue #225 und der 25-MB-Prüfschwelle aus ADR-0008).
