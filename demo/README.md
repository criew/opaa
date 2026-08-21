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
Ingestion-Codes.

Voraussetzung wie für jeden Compose-Start (siehe [`docs/deployment.md`](../docs/deployment.md),
Abschnitt „Schnellstart"): eine eigene `.env.docker` aus der Vorlage:

```bash
cp .env.docker.example .env.docker
```

Für die vollständige Demo-Instanz (Keycloak-Anmeldung, siehe „Seed ausführen (#712)" unten) in dieser
`.env.docker` zusätzlich setzen:

```bash
SPRING_PROFILES_ACTIVE=docker,oidc
OPAA_INITIAL_ADMIN_EMAIL=admin@stadt-rheinfurt.example
OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST=demo-corpus,presse.stadt-rheinfurt.example
OPAA_CSP_CONNECT_SRC_EXTRA=http://localhost:8180
OPAA_DEMO_MODE=true
```

`OPAA_DEMO_MODE=true` zeigt den Quellen- und Demo-Hinweis in der Fußzeile der Oberfläche (#230) —
synthetischer Korpus einer fiktiven Stadt, Rohmaterial LHM-Dienstleistungen-Corpus (MIT). Der
Frontend-Container liest die Variable beim Start (`frontend/nginx.conf`, `envsubst`-Template); ein
Rebuild ist dafür nicht nötig, ein Neustart des `frontend`-Containers genügt. Ohne diese Zeile
bleibt der Hinweis aus (Image-Default `false`) — das ist der richtige Zustand für jede
Nicht-Demo-Installation, siehe `docs/deployment.md`, Variablentabelle.

`OPAA_INITIAL_ADMIN_EMAIL` muss die E-Mail-Adresse des Keycloak-Nutzers `demo-admin` treffen
(`keycloak/realm-export.json`) — sonst bekommt kein Konto der Demo `SYSTEM_ADMIN`, und Schritt 1 des
Seeds (siehe unten) bricht mit einer klaren Fehlermeldung ab, statt eine falsche Rolle stillschweigend
zu akzeptieren.

`OPAA_CSP_CONNECT_SRC_EXTRA` ist beim `oidc`-Compose-Profil **zwingend** (`.env.docker.example`,
Kommentar bei derselben Variable) — ohne sie blockiert die Content-Security-Policy des
Frontend-nginx die OIDC-Anmeldung im Browser still (#409/#670): kein Fehler im Seed selbst (der
spricht Keycloak direkt an, nicht über den Browser), aber ein Login über die Oberfläche schlägt
sonst fehl, ohne dass die Ursache offensichtlich wäre.

Dann genügt ein einziger Befehl — `keycloak` gehört seit #712 zusätzlich zum Profil `demo` (siehe
`docker-compose.yml`, Kommentar am `keycloak`-Service), damit die Demo nie ohne Anmeldung erreichbar
ist:

```bash
docker compose --profile demo up
```

Wer nur den Korpus-Webserver ohne Anmeldung ausprobieren will (kein Login, keine Spaces/Rechte), lässt
`SPRING_PROFILES_ACTIVE` auf `docker,dev` — `demo-corpus`/`demo-presse` starten trotzdem über
`--profile demo`, `keycloak` läuft dann einfach mit, bleibt aber ungenutzt. Für den vollständigen Seed
(unten) ist `docker,oidc` zwingend: Das `demo`-Datenprofil des Seeds meldet sich über Keycloak an.

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

Bibliotheken, Berechtigungen und das Auslösen der Indizierung selbst richtet der Seed ein (siehe
„Seed ausführen (#712)" unten).

## Seed ausführen (#712)

`demo/seed/seed.py` ist der gemeinsame Seed-Mechanismus mit zwei **Datenprofilen**
(`docs/features/demo-instance.md`, „Installation und Seed"): `demo` (dieser Rheinfurt-Korpus, Anmeldung
über Keycloak) und `e2e` (minimal, eingefroren, Anmeldung über das dev-Auth-Profil). Beide Profile
sprechen ausschließlich die öffentliche API an — kein direkter Datenbankzugriff, keine Umgehung von
Validierung oder Audit-Protokollierung.

```bash
cd demo/seed
pip install -r requirements.txt
python seed.py --profile demo
```

Voraussetzung: Der Stack läuft bereits mit `docker compose --profile demo up` (siehe oben) und ist
erreichbar, per Voreinstellung unter `http://localhost:8081/api` (Backend) und `http://localhost:8180`
(Keycloak) — beides über `--base-url`/`--keycloak-url` änderbar.

Der Lauf richtet über die API ein:

1. **Nutzer bereitstellen** — jeder der vier Demo-Nutzer plus das Admin-Konto meldet sich einmal an
   (`GET /api/v1/auth/me`), was `UserProvisioningFilter` zum ersten Mal einen Datenbanksatz anlegen
   lässt. Das Admin-Konto muss danach `SYSTEM_ADMIN` tragen — sonst bricht der Lauf ab (siehe oben,
   `OPAA_INITIAL_ADMIN_EMAIL`).
2. **Spaces** gemäß `docs/features/demo-instance.md` — „Meldewesen & Ausweise" (Maria Weber, Selin
   Kaya), Marias eigener Space ohne weiteres Mitglied, „Kfz-Zulassung" (Thomas Klein), „Amtsleitung
   Bürgerbüro" (Andrea Vogt).
3. **Fünf Wissensbibliotheken** im Besitz des Admin-Kontos, je mit eigener Quellkonfiguration
   (ADR-0018): drei `HTTP_DIRECTORY` gegen `demo-corpus`, ein `RSS_FEED` gegen
   `presse.stadt-rheinfurt.example`, ein `UPLOAD`.
4. **VIEWER-Rechte** exakt nach der Matrix aus `docs/features/demo-instance.md` sowie die 26
   Upload-Dokumente aus `demo/corpus/interne-dienstanweisungen-meldewesen/` — der Seed wartet nach
   dem Hochladen, bis kein Dokument mehr `PENDING` ist (Tika-Parsing und Embedding laufen
   asynchron, #434), und bricht bei `FAILED` mit der jeweiligen `errorMessage` ab.
5. **Indizierung je Bibliothek** über deren eigene Quellkonfiguration (nicht für die `UPLOAD`-Bibliothek
   — die hat keinen eigenen Lauf, ADR-0018, siehe Schritt 4) — der Seed wartet auf `COMPLETED` und
   bricht bei `documentsFailed > 0` ab.

Für das minimale, eingefrorene `e2e`-Profil (dev-Auth, keine Keycloak-Anmeldung nötig) braucht es
den separaten E2E-Stack (`e2e/docker-compose.e2e.yml`), nicht den `demo`-Stack — nur dieser
provisioniert `dev-outsider` und veröffentlicht das Backend auf Port `18081` statt `8081`
(`e2e/scripts/run-e2e.mjs`). Seine Uploads (`E2E_PROFILE`s einzige `UPLOAD`-Bibliothek) kommen aus
`demo/seed/e2e-data/test-documents/seed/` — nicht zu verwechseln mit
`demo/seed/e2e-data/test-documents/*.txt` (Dateien, die einzelne E2E-Spec-Dateien selbst über die
Oberfläche hochladen) oder `demo/seed/e2e-data/rss-feed/` (statisches Compose-Docroot für die
E2E-Suite eigene, UI-getriebene Konnektor-Tests, `e2e/docker-compose.e2e.yml`s `rss-feed`-Service —
kein Seed-Eingang).

**`e2e/scripts/run-e2e.mjs` führt diesen Seed-Lauf bereits automatisch aus** (nach dem Hochfahren
des Stacks, vor der Playwright-Suite, Issue #233) — die folgenden Befehle sind nur für einen
manuellen Lauf ohne die Playwright-Suite nötig, z. B. um den E2E-Stack zwischendurch von Hand zu
inspizieren:

```bash
COMPOSE_PROJECT_NAME=opaa-e2e OPAA_ENV_FILE=e2e/e2e.env \
  docker compose -f docker-compose.yml -f e2e/docker-compose.e2e.yml \
  up -d ai-stub rss-feed postgres backend frontend
```

Dann:

```bash
python seed.py --profile e2e --base-url http://localhost:18081/api
```

**Idempotent:** Ein zweiter Lauf gegen dieselbe Instanz legt nichts doppelt an — Spaces und
Bibliotheken werden vor dem Anlegen per Namenssuche geprüft (Spaces über die Session des jeweiligen
Eigentümers, da ein Space nur für seine eigenen Mitglieder sichtbar ist), Uploads werden anhand von
Dateiname und Status übersprungen (ein zuvor `FAILED`es Dokument wird dagegen erneut hochgeladen),
und `upsertAssetGrant` ersetzt statt zu duplizieren.

**Ratenbegrenzung:** `RateLimitFilter` schlüsselt die Indizierungsauslösung nach Client-IP **und**
Bibliothek (`opaa.rate-limit.indexing`, Default 1 Anfrage/60s je IP+Bibliothek) sowie zusätzlich
über ein separates, IP-weites Gesamtkontingent (Default 5 Anfragen/60s). Die vier Trigger des Seeds
(eine je Konnektor-Bibliothek) landen auf vier verschiedenen Bibliotheken und lösen im Normalfall
kein 429 aus; `seed.py` wartet bei HTTP 429 trotzdem automatisch (`--rate-limit-wait-seconds`,
Default 65s) — relevant vor allem bei einem erneuten Lauf kurz nach einem vorherigen Versuch oder
wenn mehrere Seed-Läufe dieselbe IP teilen.

**Keycloak-Anmeldung des Seeds:** Das `demo`-Profil meldet sich über einen eigenen Client
`opaa-seed` (`keycloak/realm-export.json`, Resource Owner Password Grant, kein Client-Secret) an —
bewusst getrennt vom `opaa-frontend`-Client, dessen `directAccessGrantsEnabled` aus gutem Grund
`false` bleibt. **Dieser Client gehört vor jedem erreichbaren Deployment entfernt oder deaktiviert**
(`docs/deployment.md`, Härtungstabelle, Punkt 6) — er ist ein passwortbasierter Tokenweg ohne
Secret gegen jedes Realm-Konto und darf nicht dauerhaft scharf bleiben.

## Demo-Zugangsdaten

Alle Konten des Realms `opaa` sind offene **Demo-Werte**, keine Secrets — vor jedem erreichbaren
Deployment gemäß `docs/deployment.md`, Abschnitt „Härtung für erreichbare Deployments" zu ersetzen.
Die vollständige Konto-Tabelle mit Rollen, Spaces, lesbaren Bibliotheken und Passwörtern sowie das
ausformulierte Vorführ-Drehbuch mit acht Fragen stehen an einer Stelle, nicht dupliziert:
[`docs/demo-walkthrough.md`](../docs/demo-walkthrough.md).

## Umfang außerhalb dieses Verzeichnisses

- Demo-Drehbuch und Installationsanleitung — [`docs/demo-walkthrough.md`](../docs/demo-walkthrough.md) (#713)
- Smoke-Test gegen das `demo`-Profil — #232
- Rollout auf einen erreichbaren Host — #230
