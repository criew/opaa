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
    ├── MANIFEST.sha256                       SHA-256 über alle Dokumente
    ├── SOURCE.md                             Quellen, Lizenzen, Hinweis auf synthetische Inhalte (vom Generator geschrieben)
    └── THIRD-PARTY-LICENSES/                 Volltext der MIT-Lizenz des LHM-Dienstleistungen-Corpus
```

## Korpus neu erzeugen

```bash
cd demo/generator
pip install -r requirements.txt
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

### Was #229 für die Pressemitteilungen-Bibliothek konkret leisten muss

Der RSS-Feed unter `demo/corpus/pressemitteilungen/rss.xml` referenziert seine Detailseiten absolut
unter `http://presse.stadt-rheinfurt.example/<slug>.html` (siehe `generator/presse.py`,
`FEED_BASE_URL`) — bewusst eine realistische Domain statt `localhost`, weil die Demo das
RSS_FEED-Konnektorverhalten so vorführt, wie es auch gegen eine echte Domain liefe. Damit der
Compose-Stack das einlöst, braucht #229 zwei Dinge, nach dem Vorbild von
[`e2e/docker-compose.e2e.yml`](../e2e/docker-compose.e2e.yml):

1. **Netzwerk-Alias im Compose-Netzwerk:** Der Webserver-Container, der `demo/corpus/` ausliefert,
   muss unter dem Hostnamen `presse.stadt-rheinfurt.example` erreichbar sein (Compose
   `networks.<netz>.aliases`), damit der `rss.xml`-Feed und seine `<link>`-Einträge innerhalb des
   Compose-Netzwerks auflösbar sind.
2. **Allowlist-Eintrag für die Zielprüfung ausgehender Abrufe:** `opaa.indexing.target-validation`
   (#267, standardmäßig aktiv) lehnt Compose-interne Adressen in privaten/Loopback-Bereichen ab.
   `presse.stadt-rheinfurt.example` muss deshalb in
   `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` eingetragen werden — siehe
   `e2e/docker-compose.e2e.yml` für das bereits erprobte Muster mit dem e2e-Profil.

Dieselbe Überlegung gilt für den HTTP_DIRECTORY-Webserver, der `leistungen-meldewesen-ausweise/`,
`leistungen-kfz-zulassung/` und `satzungen-gebuehrenordnungen/` ausliefert.
