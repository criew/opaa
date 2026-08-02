# Evaluierungskorpus

Enthält die Testkorpora für die Suchqualitäts-Evaluierung (siehe
[`docs/features/search-quality-evaluation.md`](../docs/features/search-quality-evaluation.md) und
[ADR-0011](../docs/decisions/0011-search-quality-evaluation-harness.md)). Dieses Verzeichnis liegt
bewusst außerhalb des Gradle-Builds und der CI — die Generatoren laufen nur bei bewussten
Korpus-Änderungen, nie automatisch.

```
eval/
├── generator/                       Python-Werkzeuge, ein Skript je Domäne
│   ├── generate_corpus.py           Domäne Comichelden (Issue #225)
│   ├── README.md                    Reproduktionsanleitung
│   └── raw-source/                  gecachte Rohdaten, gitignored
└── corpus/                          generierte Markdown-Dokumente, committet
    └── comic-characters/
        ├── *.md                     ein Dokument je Entität
        ├── MANIFEST.sha256          SHA-256 über alle Dokumente dieser Domäne
        └── SOURCE.md                Quelle, Lizenz, Abrufdatum
```

Aktuell umgesetzt: die Domäne **Comichelden** (Issue #225). Die weiteren drei Domänen (Filme,
Reiseziele, Tiere) folgen über denselben Aufbau (Issue #234).
