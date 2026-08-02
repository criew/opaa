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

`sha256sum -c` erkennt nur Abweichungen bei Dateien, die im Manifest stehen — keine zusätzlichen
`.md`-Dateien, die außerhalb eines Generator-Laufs ins Verzeichnis gelangt sind. Ergänzend prüfen:

```bash
diff <(ls comic-*.md | sort) <(cut -d' ' -f2 MANIFEST.sha256 | sed 's/^\*//' | sort)
```

Leere Ausgabe bedeutet: Dateiliste und Manifest stimmen exakt überein. Der Generator selbst prüft
das bei jedem Lauf automatisch (`verify_manifest_completeness` in `generate_corpus.py`).

## Umfang

1.448 Markdown-Dateien, größtes Dokument 2.573 Bytes, kleinstes 670 Bytes, Gesamtgröße rund 1,9 MB
(deutlich unter der 5-MB-Obergrenze aus den Abnahmekriterien von Issue #225 und der
25-MB-Prüfschwelle aus ADR-0008).

## Bekannte Eigenheiten der Bewertungsfelder

- **`overall_score` ist eine eigene Skala**, keine Ableitung aus den fünf Attributwerten
  (`intelligence_score`, `strength_score`, `speed_score`, `durability_score`, `combat_score`, alle
  auf einer 0–100-Skala). Im aktuellen Snapshot reicht `overall_score` numerisch von 1 bis 237,
  zusätzlich tritt bei 18 Entitäten der Wert `"∞"` als Zeichenkette auf (omnipotente Figuren). Der
  generierte Fließtext benennt das explizit als "separate overall ranking scale". Für
  Bereichs-/Filterfragen in #226 ist der gemischte Typ (int oder String `"∞"`) zu beachten.
- **`0` bei den fünf Attributwerten ist ein echter Wert**, kein Platzhalter für „fehlend" — mit
  einer dokumentierten Einschränkung: In 104 von 105 Zeilen, deren `overall_score` in der Quelle
  leer ist, sind zugleich alle fünf Attributwerte exakt `0`. Das deutet stark darauf hin, dass diese
  Kombination „unbewertete Figur" bedeutet statt fünf einzeln gemessener Nullen. Einzelne `0`-Werte
  außerhalb dieser Kombination (z. B. `intelligence_score: 0` bei sonst bewerteter Figur) werden
  unverändert als echte Werte behandelt. Details und Begründung stehen im Kommentar auf
  `parse_score()` in `generate_corpus.py`.
- **`height_cm`/`weight_kg`**: Die Quelle kodiert „unbekannt" nicht nur als `-`, sondern bei der
  Körpergröße auch als `0'0 • 0 cm` (16 Zeilen im aktuellen Snapshot). Beide Sentinel-Werte werden
  zu `null` normalisiert — kein Dokument behauptet eine Körpergröße oder ein Gewicht von 0.
