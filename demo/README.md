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

## Compose-Stack starten (#229)

Der Korpus wird im Docker-Compose-Stack unter dem Profil `demo` bereitgestellt — zwei zusätzliche,
rein lesende Webserver-Services, über die **bestehende** Konnektoren (`AutoindexCrawlerService` für
`HTTP_DIRECTORY`, der RSS-Konnektor für `RSS_FEED`) den Korpus indizieren, ohne eine Zeile neuen
Ingestion-Codes. Dieser Stack läuft im **dev-Auth-Modus** (`SPRING_PROFILES_ACTIVE=docker,dev`, siehe
`.env.docker.example`), also ohne echte Anmeldung — das ist noch **nicht** die fertige Demo-Instanz
mit Keycloak-Anmeldung, Nutzern, Spaces und Rechten aus
[`docs/features/demo-instance.md`](../docs/features/demo-instance.md); die bringen erst #712 (Seed)
und #713 (Drehbuch, Installationsanleitung).

Voraussetzung wie für jeden Compose-Start (siehe [`docs/deployment.md`](../docs/deployment.md),
Abschnitt „Schnellstart"): eine eigene `.env.docker` aus der Vorlage:

```bash
cp .env.docker.example .env.docker
```

Für das `demo`-Profil zusätzlich in dieser `.env.docker` eintragen (siehe unten, „Zielprüfung
ausgehender Abrufe"):

```bash
OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST=demo-corpus,presse.stadt-rheinfurt.example
```

Dann:

```bash
docker compose --profile demo up
```

Das startet zusätzlich zu `postgres`/`backend`/`frontend`:

- **`demo-corpus`** (`httpd:2.4-alpine`) liefert die drei `HTTP_DIRECTORY`-Bibliotheken als getrennte
  Unterverzeichnisse aus: `leistungen-meldewesen-ausweise/`, `leistungen-kfz-zulassung/`,
  `satzungen-gebuehrenordnungen/`. `interne-dienstanweisungen-meldewesen/` (die `UPLOAD`-Bibliothek,
  #712) wird bewusst **nicht** gemountet — nichts davon darf über HTTP erreichbar sein.
- **`demo-presse`** (`httpd:2.4-alpine`) liefert `pressemitteilungen/` (RSS-Feed + HTML-Detailseiten)
  unter dem Compose-Netzwerk-Alias `presse.stadt-rheinfurt.example`, weil `rss.xml` seine Detailseiten
  absolut unter dieser Domain verlinkt (siehe `generator/presse.py`, `FEED_BASE_URL`) — bewusst eine
  realistische Domain statt `localhost`, damit die Demo das `RSS_FEED`-Konnektorverhalten so vorführt,
  wie es auch gegen eine echte Domain liefe.

Beide Services binden standardmäßig nur an `127.0.0.1` (Ports `OPAA_DEMO_CORPUS_PORT`, Default 8091,
und `OPAA_DEMO_PRESSE_PORT`, Default 8092) — zum Prüfen im Browser, nicht als öffentlicher Zugang; das
Backend erreicht beide ohnehin über das Compose-Netzwerk unter ihrem Servicenamen bzw. Alias, ein
Hafen nach außen ist dafür nicht nötig:

- <http://127.0.0.1:8091/leistungen-meldewesen-ausweise/> (ebenso für die anderen beiden Verzeichnisse)
- <http://127.0.0.1:8092/rss.xml>

Listing-Format: Apache `IndexOptions FancyIndexing HTMLTable`
(`webserver/httpd-demo-autoindex.conf`) — die erprobte Referenz, seit #550 aber keine Notwendigkeit
mehr, siehe [`docs/features/demo-instance.md`](../docs/features/demo-instance.md), „Installation und
Seed".

Die Zielprüfung ausgehender Abrufe (`opaa.indexing.target-validation`, #267, standardmäßig aktiv)
lehnt Compose-interne Adressen in privaten Bereichen ab — ohne Allowlist-Eintrag würde jede
Indizierung dieser Quellen mit „Zieladresse liegt in einem gesperrten Adressbereich" abgelehnt. Der
Eintrag steht bewusst **nicht** in `docker-compose.yml`: Der Backend-Service dort läuft immer, mit
oder ohne `demo`-Profil, und ein dort fest eingetragener `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST`
würde jede eigene Belegung dieser Variablen aus einer Betreiber-`.env.docker` überschreiben (Vorrang
von `environment:` vor `env_file:` in Compose) — auch dort, wo das `demo`-Profil nie gestartet wird.
Stattdessen trägt die eigene `.env.docker` den Wert ein (siehe oben, „Compose-Stack starten"), nach
dem Vorbild von [`e2e/docker-compose.e2e.yml`](../e2e/docker-compose.e2e.yml), das denselben Eintrag
für sein eigenes, isoliertes `e2e.env` setzt. `.env.docker.example` führt die Variable bereits
auskommentiert mit diesem Demo-Wert als Beispiel.

Bibliotheken, Berechtigungen und das Auslösen der Indizierung selbst richtet der Seed aus #712 ein;
bis dahin lassen sich Quellen manuell oder über die API anlegen (`sourceType: HTTP_DIRECTORY` mit
`sourceUrl: http://demo-corpus/<verzeichnis>/`, bzw. `sourceType: RSS_FEED` mit
`sourceUrl: http://presse.stadt-rheinfurt.example/rss.xml`).

## Umfang außerhalb dieses Issues

Dieses Verzeichnis liefert Korpus und Bereitstellung. Nicht Teil von Issue #229:

- Erzeugung der Korpus-Inhalte selbst — #711
- Nutzer, Spaces, Bibliotheken, Rechte, Indizierung — #712
- Demo-Drehbuch und Installationsanleitung — #713
