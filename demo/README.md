# Demo-Instanz „Stadt Rheinfurt"

Verwaltungskorpus der fiktiven Demo-Instanz "Stadt Rheinfurt" (Epic #708, Konzept:
[`docs/features/demo-instance.md`](../docs/features/demo-instance.md)). Dieses Verzeichnis liegt
wie `eval/` bewusst außerhalb von Gradle-Build und CI — der Generator läuft nur bei bewussten
Korpus-Änderungen, nie automatisch.

```
demo/
├── generator/     Python-Generator, siehe generator/README.md für Reproduktion und Werkzeugwahl
└── corpus/        generierter Korpus, committet
    ├── leistungen-meldewesen-ausweise/       .md
    ├── leistungen-kfz-zulassung/             .md, .txt
    ├── satzungen-gebuehrenordnungen/         .pdf
    ├── pressemitteilungen/                   rss.xml + .html
    ├── interne-dienstanweisungen-meldewesen/ .docx, .pdf, .pptx
    ├── MANIFEST.sha256                       SHA-256 über alle 156 Dokumente
    └── SOURCE.md                             Quellen, Lizenzen, Hinweis auf synthetische Inhalte
```

## Korpus neu erzeugen

```bash
cd demo/generator
pip install python-docx python-pptx reportlab
python generate_corpus.py
```

Läuft erneut, wenn sich eine der fünf Bibliotheken inhaltlich ändern soll (z. B. weitere
Pressemitteilungen, andere Leistungsauswahl). Zwei Läufe erzeugen byte-identische Dateien; die
Prüfsumme steht in `corpus/MANIFEST.sha256`:

```bash
cd demo/corpus
sha256sum -c MANIFEST.sha256
```

Details zum Reproduktionsverfahren, den verwendeten Quellen und der Werkzeugwahl für
PDF/DOCX/PPTX: [`generator/README.md`](generator/README.md) und [`corpus/SOURCE.md`](corpus/SOURCE.md).

## Umfang außerhalb dieses Issues

Dieses Verzeichnis liefert ausschließlich den Korpus. Nicht Teil von Issue #711:

- Bereitstellung im Compose-Stack (Webserver, Feed-Hosting) — #229
- Nutzer, Spaces, Bibliotheken, Rechte, Indizierung — #712
- Demo-Drehbuch und Installationsanleitung — #713
