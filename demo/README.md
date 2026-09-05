# Demo-Instanz „Stadt Rheinfurt"

Die eine Quelle für die Demo-Instanz „Stadt Rheinfurt" (Epic #708): installieren, anmelden,
vorführen — und, für alle, die die Demo selbst weiterentwickeln, Korpus-Generator und
Seed-Mechanismus. Konzept dahinter — Behördenlandschaft, Bibliotheken, Berechtigungsmatrix, Quellen
und Lizenzen — steht in [`docs/features/demo-instance.md`](../docs/features/demo-instance.md) und
wird hier **nicht wiederholt**. Das ausformulierte Vorführ-Drehbuch mit acht Fragen steht in
[`docs/market/demo-drehbuch.md`](../docs/market/demo-drehbuch.md).

Dieses Verzeichnis liegt wie `eval/` bewusst außerhalb von Gradle-Build und CI — der Generator läuft
nur bei bewussten Korpus-Änderungen, nie automatisch.

```
demo/
├── generator/     Python-Generator, siehe generator/README.md für Reproduktion und Werkzeugwahl
├── seed/          Seed-Skript (Datenprofile "demo" und "e2e"), siehe "Seed-Mechanismus (#712)" unten
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

---

## Demo nutzen

### Was die Demo zeigt

Das Bürgerbüro Rheinfurt mit mehreren Sachgebieten, fünf Wissensbibliotheken, alle drei
Konnektortypen und mehrere Dateiformate:

| Wissensbibliothek | Formate | Quellentyp |
|---|---|---|
| Leistungen Meldewesen & Ausweise | `.md` | `HTTP_DIRECTORY` |
| Leistungen Kfz-Zulassung | `.md`, `.txt` | `HTTP_DIRECTORY` |
| Satzungen & Gebührenordnungen | `.pdf` | `HTTP_DIRECTORY` |
| Pressemitteilungen Stadt Rheinfurt | RSS-XML + HTML-Detailseiten | `RSS_FEED` (statisch, selbst gehostet) |
| Interne Dienstanweisungen Meldewesen | `.docx`, `.pdf`, `.pptx` | `UPLOAD` (im Seed automatisiert) |

Begründung der Auswahl, Quellen und Lizenzen des Korpus:
[`docs/features/demo-instance.md`](../docs/features/demo-instance.md).

### Installation mit einem Befehl

Voraussetzung: Docker und Docker Compose, ein Checkout dieses Repositorys, Python 3 mit `pip` für den
Seed-Lauf.

#### 1. Umgebung konfigurieren

```bash
cp .env.docker.example .env.docker
```

In der eigenen `.env.docker` zusätzlich setzen:

```env
SPRING_PROFILES_ACTIVE=docker,oidc
OPAA_INITIAL_ADMIN_EMAIL=admin@stadt-rheinfurt.example
OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST=demo-corpus,presse.stadt-rheinfurt.example
OPAA_CSP_CONNECT_SRC_EXTRA=http://localhost:8180
OPAA_DEMO_MODE=true
OPAA_PGVECTOR_DIMENSIONS=768
OPAA_UPLOAD_THREAD_POOL_QUEUE_CAPACITY=30
```

Herkunft und Zwang jeder einzelnen Variable:

- `OPAA_INITIAL_ADMIN_EMAIL` muss die E-Mail-Adresse des Keycloak-Nutzers `demo-admin` treffen
  (`keycloak/realm-export.json`) — sonst bekommt kein Konto der Demo `SYSTEM_ADMIN`, und Schritt 1 des
  Seeds (siehe unten) bricht mit einer klaren Fehlermeldung ab, statt eine falsche Rolle
  stillschweigend zu akzeptieren.
- `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST=demo-corpus,presse.stadt-rheinfurt.example` ist
  zwingend: Die Zielprüfung ausgehender Abrufe (`opaa.indexing.target-validation`, #267,
  standardmäßig aktiv) lehnt Compose-interne Adressen in privaten Bereichen ab — ohne diesen Eintrag
  würde jede Indizierung der beiden Demo-Webserver mit „Zieladresse liegt in einem gesperrten
  Adressbereich" abgelehnt (siehe [`../docs/handbuch/deployment.md`, „Sicherheitshinweis"](../docs/handbuch/deployment.md#sicherheitshinweis-post-apiv1librarieslibraryidindexing-ist-von-außen-erreichbar)).
  Der Eintrag steht bewusst **nicht** in `docker-compose.yml`: Der Backend-Service dort läuft immer,
  mit oder ohne `demo`-Profil, und ein dort fest eingetragener Wert würde jede eigene Belegung dieser
  Variablen aus einer Betreiber-`.env.docker` überschreiben (Vorrang von `environment:` vor
  `env_file:` in Compose) — auch dort, wo das `demo`-Profil nie gestartet wird. `.env.docker.example`
  führt die Variable bereits auskommentiert mit diesem Demo-Wert als Beispiel.
- `OPAA_CSP_CONNECT_SRC_EXTRA=http://localhost:8180` ist beim `oidc`-Compose-Profil **zwingend**
  (`.env.docker.example`, Kommentar bei derselben Variable) — ohne sie blockiert die
  Content-Security-Policy des Frontend-nginx die OIDC-Anmeldung im Browser still (#409/#670): kein
  Fehler im Seed selbst (der spricht Keycloak direkt an, nicht über den Browser), aber ein Login über
  die Oberfläche schlägt sonst fehl, ohne dass die Ursache offensichtlich wäre.
- `OPAA_DEMO_MODE=true` zeigt den Quellen- und Demo-Hinweis in der Fußzeile der Oberfläche (#230) —
  synthetischer Korpus einer fiktiven Stadt, Rohmaterial LHM-Dienstleistungen-Corpus (MIT). Der
  Frontend-Container liest die Variable beim Start (`frontend/nginx.conf`, `envsubst`-Template); ein
  Rebuild ist dafür nicht nötig, ein Neustart des `frontend`-Containers genügt. Ohne diese Zeile
  bleibt der Hinweis aus (Image-Default `false`) — das ist der richtige Zustand für jede
  Nicht-Demo-Installation, siehe [`../docs/handbuch/deployment.md`](../docs/handbuch/deployment.md),
  Variablentabelle.
- `OPAA_PGVECTOR_DIMENSIONS=768` steht seit #720 bereits so in `.env.docker.example` (nicht mehr auf
  dem Anwendungs-Default 1536) — die Zeile oben ist deshalb kein Abweichen mehr von der Vorlage,
  sondern nur zur Klarheit wiederholt: Voreingestellt bleiben lokal betriebene Modelle über die
  openai-kompatible Schicht, mit dem Embedding-Modell `nomic-embed-text`, das 768 Dimensionen liefert
  (siehe [`../docs/handbuch/deployment.md`, „Alle Umgebungsvariablen"](../docs/handbuch/deployment.md#alle-umgebungsvariablen)
  für dieselbe Kopplung). Der Wert muss zum jeweils verwendeten Embedding-Modell passen; eine
  nachträgliche Änderung an einer bereits laufenden Instanz erfordert `docker compose down -v` und
  eine vollständige Neuindizierung (siehe [`../docs/handbuch/deployment.md`, „Was ein Update mit dem
  Index macht"](../docs/handbuch/deployment.md#was-ein-update-mit-dem-index-macht)).
- `OPAA_UPLOAD_THREAD_POOL_QUEUE_CAPACITY=30` hebt die Standard-Warteschlange von
  `uploadTaskExecutor` (Default 20, `opaa.upload.thread-pool`) an: Der Seed lädt die 26 Dokumente der
  Bibliothek „Interne Dienstanweisungen Meldewesen" sequentiell und ohne Pause hoch, und mit lokal
  betriebenen Ollama-Embeddings (langsamer als ein Cloud-Anbieter) füllt sich die Warteschlange eher
  als mit einem schnellen Anbieter — ohne die Anhebung kann der letzte Upload oder die letzten zwei
  mit „Die Verarbeitung ist derzeit ausgelastet - bitte später erneut versuchen." fehlschlagen (siehe
  „Seed-Mechanismus (#712)" unten für den Umgang, falls das trotzdem passiert).

#### 2. Stack starten

```bash
docker compose --profile demo up
```

Ohne einen extern erreichbaren Ollama-Server (weder auf dem Host noch im eigenen Netz) zusätzlich
das Compose-Profil `ollama` aktivieren (#720, siehe
[`../docs/handbuch/deployment.md`, „Lokal betriebenes Ollama im Compose-Stack"](../docs/handbuch/deployment.md#lokal-betriebenes-ollama-im-compose-stack-720)):

```bash
docker compose --profile demo --profile ollama up
```

Das startet zusätzlich einen lokal betriebenen `ollama`-Service samt Init-Schritt, der
`nomic-embed-text` und `phi3:mini` zieht — die Demo läuft damit vollständig ohne externe Dienste, auf
Kosten eines mehrere Gigabyte großen Downloads beim allerersten Start.

Das startet zusätzlich zu `postgres`/`backend`/`frontend`:

- **`keycloak`** — Anmeldung. Der `keycloak`-Service ist seit #712 zusätzlich zu `oidc` auch dem
  Compose-Profil `demo` zugeordnet (`docker-compose.yml`, Kommentar am `keycloak`-Service), damit die
  Demo nie ohne Anmeldung erreichbar ist — `docker compose --profile demo up` genügt damit allein,
  kein zweiter, leicht vergessener `--profile oidc` auf jedem dokumentierten Befehl.
- **`demo-corpus`** (`httpd:2.4-alpine`) liefert die drei `HTTP_DIRECTORY`-Bibliotheken als getrennte
  Unterverzeichnisse aus: `leistungen-meldewesen-ausweise/`, `leistungen-kfz-zulassung/`,
  `satzungen-gebuehrenordnungen/`. `interne-dienstanweisungen-meldewesen/` (die `UPLOAD`-Bibliothek,
  #712) wird bewusst **nicht** gemountet — nichts davon darf über HTTP erreichbar sein.
- **`demo-presse`** (`httpd:2.4-alpine`) liefert `pressemitteilungen/` (RSS-Feed + HTML-Detailseiten)
  unter dem Compose-Netzwerk-Alias `presse.stadt-rheinfurt.example`, weil `rss.xml` seine Detailseiten
  absolut unter dieser Domain verlinkt (siehe `generator/presse.py`, `FEED_BASE_URL`) — bewusst eine
  realistische Domain statt `localhost`, damit die Demo das `RSS_FEED`-Konnektorverhalten so vorführt,
  wie es auch gegen eine echte Domain liefe.

Beide Webserver binden standardmäßig nur an `127.0.0.1` (Ports `OPAA_DEMO_CORPUS_PORT`, Default 8091,
und `OPAA_DEMO_PRESSE_PORT`, Default 8092) — zum Prüfen im Browser, nicht als öffentlicher Zugang; das
Backend erreicht beide ohnehin über das Compose-Netzwerk unter ihrem Servicenamen bzw. Alias, ein
Hafen nach außen ist dafür nicht nötig:

- <http://127.0.0.1:8091/leistungen-meldewesen-ausweise/> (ebenso für die anderen beiden Verzeichnisse)
- <http://127.0.0.1:8092/rss.xml>

Listing-Format: Apache `IndexOptions FancyIndexing HTMLTable`
(`webserver/httpd-demo-autoindex.conf`) — die erprobte Referenz, seit #550 aber keine Notwendigkeit
mehr (der `AutoindexCrawlerService` versteht seither auch `<pre>`-Listings und `<ul>`-Layouts).

Warten, bis `backend` und `keycloak` bereit sind (`docker compose logs -f backend`, Zeile
„Started OpaaApplication").

Wer nur den Korpus-Webserver ohne Anmeldung ausprobieren will (kein Login, keine Spaces/Rechte), lässt
`SPRING_PROFILES_ACTIVE` auf `docker,dev` — `demo-corpus`/`demo-presse` starten trotzdem über
`--profile demo`, `keycloak` läuft dann einfach mit, bleibt aber ungenutzt. Für den vollständigen Seed
(unten) ist `docker,oidc` zwingend: Das `demo`-Datenprofil des Seeds meldet sich über Keycloak an.

#### 3. Seed ausführen

```bash
cd demo/seed
pip install -r requirements.txt
python seed.py --profile demo
```

Der Seed richtet über die öffentliche API alle vier Demo-Nutzer plus das Admin-Konto ein, legt die
vier Spaces und fünf Wissensbibliotheken an, vergibt die Leserechte, ordnet den drei Sachgebiets- und
Amtsleitungs-Spaces ihre Bibliotheken als Datenquellen zu (Assoziation als reine Kuratierung, #706 —
Marias persönlicher Space bleibt bewusst ohne Zuordnung), lädt die 26 Dokumente der internen
Upload-Bibliothek hoch und stößt die Indizierung der vier konnektorgespeisten Bibliotheken an.
Vollständiger Ablauf, Idempotenz und Fehlerfälle: „Seed-Mechanismus (#712)" unten.

**Wie lange dauert die Erstindizierung, und wie erkennt man, dass sie fertig ist?** Der Seed selbst
wartet auf jede Indizierung und jeden Upload (Polling gegen `GET
/api/v1/libraries/{libraryId}/indexing/status` bzw. den Dokumentstatus) und bricht mit einer klaren
Fehlermeldung ab, wenn etwas schiefgeht — läuft `seed.py` bis zur Ausgabe „Seed-Profil 'demo'
abgeschlossen." durch, ist die Instanz vollständig gefüllt und durchsuchbar. Bei den 155 Dokumenten
des Korpus (46 + 37 + 19 + 27 in den vier konnektorgespeisten Bibliotheken, 26 Uploads) und lokal
betriebenen Modellen ist mit einigen Minuten zu rechnen, je nach Ollama-Hardware; ein zweiter Lauf
gegen dieselbe Instanz ist idempotent und legt nichts doppelt an.

#### 4. Anmelden und loslegen

Frontend: <http://localhost:3000>. Anmeldung über Keycloak mit einem der Konten aus der Tabelle
unten, dann das Vorführ-Drehbuch abspielen:
[`docs/market/demo-drehbuch.md`](../docs/market/demo-drehbuch.md).

### Nutzerkonten

Alle Passwörter sind offene **Demo-Werte, keine Secrets** — vor jedem erreichbaren Deployment gemäß
[`../docs/handbuch/deployment.md`, „Härtung für erreichbare Deployments"](../docs/handbuch/deployment.md#härtung-für-erreichbare-deployments)
zu ersetzen. Der Ist-Zustand auf der öffentlichen Instanz opaa.ewerlin.com weicht davon für
`demo-admin` bewusst ab — siehe „Öffentliche Instanz betreiben" unten.

| Konto | Rolle im Szenario | Spaces | Lesbare Bibliotheken | Passwort |
|---|---|---|---|---|
| `demo-admin` (admin@stadt-rheinfurt.example) | Systemadministration | eigener Default-Space | richtet ein, besitzt alle fünf Bibliotheken | `RheinfurtDemo!2026` |
| `maria.weber` | Sachbearbeiterin Meldewesen | „Meldewesen & Ausweise" (mit Selin), „Maria Weber – persönlich" (allein) | Leistungen Meldewesen & Ausweise, Satzungen & Gebührenordnungen, Pressemitteilungen, Interne Dienstanweisungen Meldewesen | `RheinfurtDemo!2026` |
| `selin.kaya` | Sachbearbeiterin Meldewesen | „Meldewesen & Ausweise" (mit Maria) | dieselben vier wie Maria | `RheinfurtDemo!2026` |
| `thomas.klein` | Sachbearbeiter Kfz-Zulassung | „Kfz-Zulassung" (allein) | Leistungen Kfz-Zulassung, Satzungen & Gebührenordnungen, Pressemitteilungen | `RheinfurtDemo!2026` |
| `andrea.vogt` | Amtsleitung Bürgerbüro | „Amtsleitung Bürgerbüro" (allein) | alle fünf Bibliotheken | `RheinfurtDemo!2026` |

Zusätzlich existiert im zweiten Keycloak-Realm `partner` (`keycloak/realm-partner-export.json`,
ADR-0025) eine **zweite `maria.weber` mit derselben E-Mail** und demselben Passwort — ein
eigenes Konto ohne die Rechte der Demo-Maria, sobald die Systemverwaltung den Realm als weiteren
Anbieter angelegt hat (Administration → Identitätsanbieter; der Demo-Smoke-Lauf tut genau das).
Sie zeigt, dass Konten zweier Anbieter nie zusammengeführt werden.

Die Spalte „Lesbare Bibliotheken" zählt nur explizit vergebene `VIEWER`-Rechte; jeder Nutzer bekommt
beim ersten Login zusätzlich automatisch seinen eigenen Default-Space, der oben nicht eigens
aufgeführt ist. Die drei Sachgebiets- und Amtsleitungs-Spaces tragen ihre lesbaren Bibliotheken
zusätzlich als zugeordnete Datenquellen (Space↔Bibliothek-Assoziation, #706): `@Alles-Wissen`
durchsucht in diesen Spaces genau die zugeordneten Bibliotheken, geschnitten mit den Leserechten der
fragenden Person. „Maria Weber – persönlich" bleibt bewusst ohne Zuordnung — dort greift
`@Alles-Wissen` weiterhin auf alle für Maria lesbaren Bibliotheken zurück. Begründung der Matrix:
[`../docs/features/demo-instance.md`, „Nutzer, Spaces und
Berechtigungen"](../docs/features/demo-instance.md#nutzer-spaces-und-berechtigungen).

**Der Vorführ-Kern:** Weil die Berechtigungsprüfung Teil der Vektorsuche ist und nicht ein
nachgeschalteter Filter, ist ein für einen Nutzer unzugänglicher Treffer nicht nur unterdrückt,
sondern nie geladen — Thomas' Anfrage nach einer internen Meldewesen-Dienstanweisung durchsucht diese
Bibliothek gar nicht erst, unabhängig davon, wie thematisch treffend ein Chunk daraus wäre.

---

## Demo weiterentwickeln

### Korpus neu erzeugen

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

Details zum Reproduktionsverfahren, den verwendeten Quellen und der Werkzeugwahl für PDF/DOCX/PPTX:
[`generator/README.md`](generator/README.md) und [`corpus/SOURCE.md`](corpus/SOURCE.md).

**Was danach neu indiziert werden muss:** Ein erneuter `python seed.py --profile demo`-Lauf gegen eine
bereits laufende Instanz legt Nutzer, Spaces, Bibliotheken und Rechte nicht doppelt an (idempotent),
löst aber für jede konnektorgespeiste Bibliothek erneut die Indizierung aus — neue oder geänderte
Dateien werden anhand ihrer SHA-256-Prüfsumme erkannt und neu verarbeitet, unveränderte übersprungen.
Für die Upload-Bibliothek gilt dasselbe für neu hinzugekommene Dateien; eine geänderte, bereits
hochgeladene Datei müsste vor einem erneuten Lauf gelöscht werden, weil `seed.py` ein vorhandenes,
nicht fehlgeschlagenes Dokument anhand des Dateinamens überspringt (siehe `demo/seed/seed.py`,
`upload_documents`). Bei größeren inhaltlichen Änderungen ist das zugehörige Drehbuch
([`docs/market/demo-drehbuch.md`](../docs/market/demo-drehbuch.md)) gegenzuprüfen — Antworten, die
auf konkreten Zahlen oder Formulierungen beruhen (Gebührenrahmen, Fristen), veralten sonst
stillschweigend.

### Seed-Mechanismus (#712)

`demo/seed/seed.py` ist der gemeinsame Seed-Mechanismus mit zwei **Datenprofilen**
(`docs/features/demo-instance.md`, „Installation und Seed"): `demo` (dieser Rheinfurt-Korpus, Anmeldung
über Keycloak) und `e2e` (minimal, eingefroren, Anmeldung über das dev-Auth-Profil). Beide Profile
sprechen ausschließlich die öffentliche API an — kein direkter Datenbankzugriff, keine Umgehung von
Validierung oder Audit-Protokollierung.

Voraussetzung: Der Stack läuft bereits mit `docker compose --profile demo up` (siehe „Demo nutzen"
oben) und ist erreichbar, per Voreinstellung unter `http://localhost:8081/api` (Backend) und
`http://localhost:8180` (Keycloak) — beides über `--base-url`/`--keycloak-url` änderbar.

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
   dem Hochladen, bis kein Dokument mehr `PENDING` ist (Tika-Parsing und Embedding laufen asynchron,
   #434), und bricht bei `FAILED` mit der jeweiligen `errorMessage` ab.
5. **Space↔Bibliothek-Zuordnungen** (Assoziation als reine Kuratierung, #706) gemäß den
   `library_names` der Space-Definitionen in `profiles.py`: „Meldewesen & Ausweise" bekommt die vier
   für das Sachgebiet lesbaren Bibliotheken zugeordnet, „Kfz-Zulassung" drei, „Amtsleitung Bürgerbüro"
   alle fünf; Marias persönlicher Space bleibt bewusst ohne Zuordnung (@Alles-Wissen greift dort
   weiter auf alle lesbaren Bibliotheken zurück). Die Zuordnung legt die Session des jeweiligen
   Space-Eigentümers an, denn `associateSpaceLibrary` verlangt CURATOR oder höher im Space plus
   mindestens VIEWER auf der Bibliothek — beides hat der Eigentümer nach Schritt 4.
6. **Indizierung je Bibliothek** über deren eigene Quellkonfiguration (nicht für die `UPLOAD`-Bibliothek
   — die hat keinen eigenen Lauf, ADR-0018, siehe Schritt 4) — der Seed wartet auf `COMPLETED` und
   bricht bei `documentsFailed > 0` ab.

Für das minimale, eingefrorene `e2e`-Profil (dev-Auth, keine Keycloak-Anmeldung nötig) braucht es den
separaten E2E-Stack (`e2e/docker-compose.e2e.yml`), nicht den `demo`-Stack — nur dieser provisioniert
`dev-outsider` und veröffentlicht das Backend auf Port `18081` statt `8081`
(`e2e/scripts/run-e2e.mjs`). Seine Uploads (`E2E_PROFILE`s einzige `UPLOAD`-Bibliothek) kommen aus
`demo/seed/e2e-data/test-documents/seed/` — nicht zu verwechseln mit
`demo/seed/e2e-data/test-documents/*.txt` (Dateien, die einzelne E2E-Spec-Dateien selbst über die
Oberfläche hochladen) oder `demo/seed/e2e-data/rss-feed/` (statisches Compose-Docroot für die E2E-Suite
eigene, UI-getriebene Konnektor-Tests, `e2e/docker-compose.e2e.yml`s `rss-feed`-Service — kein
Seed-Eingang).

**`e2e/scripts/run-e2e.mjs` führt diesen Seed-Lauf bereits automatisch aus** (nach dem Hochfahren des
Stacks, vor der Playwright-Suite, Issue #233) — die folgenden Befehle sind nur für einen manuellen Lauf
ohne die Playwright-Suite nötig, z. B. um den E2E-Stack zwischendurch von Hand zu inspizieren:

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
`upsertAssetGrant` ersetzt statt zu duplizieren, und `associateSpaceLibrary` liefert bei bereits
bestehender Zuordnung die vorhandene Assoziation unverändert zurück. Bricht der Seed beim `demo`-Profil
mit „Die Verarbeitung ist derzeit ausgelastet - bitte später erneut versuchen." ab (die 26
sequentiellen Uploads der internen Bibliothek können `uploadTaskExecutor`s Warteschlange füllen, siehe
„Installation mit einem Befehl", Schritt 1 oben), behebt genau diese Idempotenz das: Ein zweiter
`python seed.py --profile demo`-Lauf lädt die als `FAILED` markierten Dokumente erneut hoch, ohne
bereits erfolgreich indizierte Dokumente anzurühren.

**Ratenbegrenzung:** `RateLimitFilter` schlüsselt die Indizierungsauslösung nach Client-IP **und**
Bibliothek (`opaa.rate-limit.indexing`, Default 1 Anfrage/60s je IP+Bibliothek) sowie zusätzlich über
ein separates, IP-weites Gesamtkontingent (Default 5 Anfragen/60s). Die vier Trigger des Seeds (eine
je Konnektor-Bibliothek) landen auf vier verschiedenen Bibliotheken und lösen im Normalfall kein 429
aus; `seed.py` wartet bei HTTP 429 trotzdem automatisch (`--rate-limit-wait-seconds`, Default 65s) —
relevant vor allem bei einem erneuten Lauf kurz nach einem vorherigen Versuch oder wenn mehrere
Seed-Läufe dieselbe IP teilen.

**Keycloak-Anmeldung des Seeds:** Das `demo`-Profil meldet sich über einen eigenen Client
`opaa-seed` (`keycloak/realm-export.json`, Resource Owner Password Grant, kein Client-Secret) an —
bewusst getrennt vom `opaa-frontend`-Client, dessen `directAccessGrantsEnabled` aus gutem Grund
`false` bleibt. **Dieser Client gehört vor jedem erreichbaren Deployment entfernt oder deaktiviert**
(`../docs/handbuch/deployment.md`, Härtungstabelle, Punkt 6) — er ist ein passwortbasierter Tokenweg
ohne Secret gegen jedes Realm-Konto und darf nicht dauerhaft scharf bleiben.

### Öffentliche Instanz betreiben (opaa.ewerlin.com)

Unter **https://opaa.ewerlin.com** betreibt der Maintainer eine öffentliche **Test-/Demo-Instanz** von
OPAA mit genau diesem Rheinfurt-Korpus. Es handelt sich ausdrücklich nicht um einen Produktivbetrieb —
es gelten keine Verfügbarkeits- oder Datenerhaltungsgarantien. Seit dem 21.08.2026 (#230, Epic #708)
wurde der Stack per Reset neu aufgesetzt (frische Datenbank, frischer Keycloak-Realm-Import mit den
Demo-Konten) und mit dem Rheinfurt-Korpus samt Seed-Profil `demo` befüllt.

- **Betreiber:** Der Maintainer (`criew`), auf privater VPS-Infrastruktur außerhalb dieses
  Repositorys.
- **Zweck:** Öffentlich erreichbare Vorführinstanz der Demo „Stadt Rheinfurt" auf dem aktuellen
  `main`-Stand.
- **Zugriff:** Die Instanz läuft im Auth-Modus `oidc` hinter Keycloak. Der Zugang ist bewusst
  account-gebunden — ein anonymer Zugang oder Gastzugang ist **nicht** vorgesehen; jede Nutzung
  erfordert eine Anmeldung mit einem der Rheinfurt-Demo-Konten (siehe „Nutzerkonten" oben). Eine
  Konsequenz dieser Festlegung: Inhalte auf der Instanz — der Rheinfurt-Korpus — sind nur für
  angemeldete Nutzer sichtbar, nicht öffentlich ohne Anmeldung einsehbar.
- **Administration:** Genau ein Konto trägt die Rolle `SYSTEM_ADMIN` — `OPAA_INITIAL_ADMIN_EMAIL`
  zeigt auf `admin@stadt-rheinfurt.example`, das Administrationskonto `demo-admin`, nicht mehr auf ein
  persönliches Konto des Maintainers. Das Keycloak-Konto `demo-admin` samt Passwort stammt bereits aus
  dem Realm-Import; der Seed-Lauf legt keinen neuen Keycloak-Nutzer an, sondern löst nur dessen
  Erstanmeldung aus, die den zugehörigen OPAA-Datensatz anlegt. Sein Passwort ist nach jedem
  Seed-Lauf bewusst rotiert (siehe „Seed- und `opaa-seed`-Verfahren" unten) und weicht deshalb vom
  oben dokumentierten Demo-Passwort ab; die vier Fach-Demokonten behalten dieses dokumentierte
  Passwort unverändert — sie sind für das Drehbuch vorführnotwendige `USER`-Konten ohne
  Adminrechte, ihr offenes Demo-Passwort ist ein akzeptiertes Restrisiko. Begrenzt wird dieses
  Risiko durch das je Konto greifende Rate Limiting (siehe [„Sicherheitshinweis"](../docs/handbuch/deployment.md#sicherheitshinweis-post-apiv1librarieslibraryidindexing-ist-von-außen-erreichbar))
  und das monatliche Ausgabenlimit in der Anthropic-Console (siehe „Modellkonfiguration der
  Instanz" unten) — beide setzen dem, was ein Fachkonto anrichten kann, eine feste Obergrenze.
  Alle administrativen Vorgänge auf der Instanz führt `demo-admin` über den Admin-Bereich der
  Oberfläche aus. Weitere Konten mit dieser Rolle gibt es derzeit nicht.
- **Netzwerk:** Alle Container-Ports binden ausschließlich auf `127.0.0.1`. Nach außen führt
  ausschließlich ein nginx auf dem Host, der TLS terminiert und weiterleitet. Keycloak ist unter dem
  Pfad **`/idp`** eingehängt — ausdrücklich **nicht** unter `/auth`, weil das Frontend
  `/auth/callback` selbst als OIDC-Redirect verwendet und die beiden sich sonst überlagern. Dieser
  Host-nginx braucht **zusätzlich** zum `client_max_body_size` im Frontend-Container-nginx (siehe
  `OPAA_UPLOAD_MAX_FILE_SIZE` in [`../docs/handbuch/deployment.md`](../docs/handbuch/deployment.md))
  ein ausreichendes eigenes `client_max_body_size` — sein Default liegt ebenfalls bei nur 1 MB und
  würde Uploads sonst schon vor dem Frontend-Container abweisen. Zusätzlich zu
  `postgres`/`backend`/`frontend`/`keycloak` laufen in der Instanz-Compose die beiden schlanken
  httpd-Container `demo-corpus`/`demo-presse` aus „Demo nutzen" oben — beide binden ihre Ports
  ebenfalls ausschließlich auf `127.0.0.1`.
- **Betriebsart:** Die Instanz läuft ausschließlich aus vorgebauten GHCR-Images
  (`ghcr.io/criew/opaa-backend:main`, `ghcr.io/criew/opaa-frontend:main`, siehe
  [`../docs/handbuch/deployment.md`, „Deployment aus vorgebauten Images"](../docs/handbuch/deployment.md#deployment-aus-vorgebauten-images-ghcr)).
  Auf dem Server gibt es **keinen Repository-Checkout** und keinen Build — nur eine
  `docker-compose.yml`, die `image:` statt `build:` verwendet.
- **Daten:** Es dürfen dort **keine personenbezogenen, vertraulichen oder produktiven
  Organisationsdaten** abgelegt werden. Die Instanz ist ausschließlich für Demo- und Testzwecke mit
  dem synthetischen Rheinfurt-Korpus vorgesehen.
- **Frontend-Modus:** `OPAA_DEMO_MODE=true` ist gesetzt — der Quellen- und Demo-Hinweis in der
  Fußzeile der Oberfläche ist damit sichtbar (siehe oben, Schritt 1 der Installation).

#### Modellkonfiguration der Instanz

Hier ist eine Verwechslung angelegt, die bereits mehrfach zu falschen Aussagen geführt hat und deshalb
ausdrücklich benannt wird:

| | Anbieter | Modell | Anmerkung |
|---|---|---|---|
| **Chat** | Anthropic | `claude-haiku-4-5` | Angebunden über Anthropics **OpenAI-kompatible Schicht** — der einzige Anbindungsweg für beide Funktionen (siehe [„LLM-Anbieter"](../docs/handbuch/deployment.md#llm-anbieter)). `OPAA_OPENAI_CHAT_BASE_URL=https://api.anthropic.com/v1`, ein eigener `OPAA_OPENAI_CHAT_API_KEY` und `OPAA_OPENAI_CHAT_MODEL=claude-haiku-4-5` zeigen auf Anthropic; das gemeinsame `OPAA_OPENAI_BASE_URL` ist auf dieser Instanz **nicht** gesetzt. |
| **Embedding** | Ollama, auf dem Host der VPS (nicht im Compose-Netz) | `nomic-embed-text` | Läuft über denselben Anbindungsweg, aber **nicht** über den Anwendungs-Default: `OPAA_OPENAI_EMBEDDING_BASE_URL=http://host.docker.internal:11434/v1` (Ollama läuft auf dem Host, nicht als Compose-Service `ollama`) und `OPAA_OPENAI_EMBEDDING_MODEL=nomic-embed-text` sind explizit gesetzt. 768 Dimensionen, entsprechend `OPAA_PGVECTOR_DIMENSIONS=768`. |

Zwei Punkte dazu:

- **`openai` bezeichnet hier das Protokoll, nicht den Anbieter.** Wer die Basis-Adresse als
  Anbieterangabe liest, kommt zu einem falschen Ergebnis — genau das ist in der Vergangenheit
  passiert.
- **Die Aufteilung Chat bei Anthropic, Embedding lokal ist dauerhaft, nicht provisorisch.** Anthropic
  bietet keine Embeddings-API an; ein einheitlicher Anbieter für beides ist mit dieser Wahl gar nicht
  möglich. Anthropic bezeichnet die OpenAI-kompatible Schicht ausdrücklich als Werkzeug zum Testen und
  Vergleichen, nicht als produktionsreifen Zugang — für eine Testinstanz angemessen, für einen
  Dauerbetrieb wäre die native Anbindung zu wählen.

**Kostenseite:** Token-Kosten entstehen ausschließlich beim Chat. Die Einbettung läuft lokal über
Ollama und kostet nichts — eine Neuindizierung des Korpus ist deshalb kostenlos, unabhängig von seiner
Größe. Eine grobe Messung im laufenden Betrieb der Rheinfurt-Demo: rund 5.000–7.000 Input- und rund
300 Output-Token je Chat-Frage, macht die Kosten pro Anfrage zu deutlich unter einem US-Cent. Das harte
monatliche Ausgabenlimit für den verwendeten API-Schlüssel ist **in der Anthropic-Console hinterlegt**
(Kontobereich für Nutzungslimits), nicht in OPAA selbst — OPAA kennt kein eigenes Budget-Limit für den
Chat-Anbieter.

#### Korpus einspielen und indizieren

Der Rheinfurt-Korpus liegt **nicht** über einen Repository-Checkout auf dem Server (siehe
„Betriebsart" oben), sondern wird als fertiges Verzeichnispaar übertragen: `demo/corpus/` (die
Dokumente selbst, inklusive des Autoindex-Konfigurationsschnipsels, den der Korpus-httpd-Container
einbindet) und `demo/webserver/` (das Compose-Fragment für die beiden httpd-Container aus „Demo
nutzen" oben — der Korpus-Container mountet `demo/corpus/`, `demo-presse` mountet direkt
`demo/corpus/pressemitteilungen/`).

1. Beide Verzeichnisse vom Arbeitsrechner auf den Server übertragen, per `rsync` oder `scp`:

   ```bash
   rsync -av --delete ./demo/corpus/ <benutzer>@<host>:<korpusverzeichnis>/
   rsync -av --delete ./demo/webserver/ <benutzer>@<host>:<webserververzeichnis>/
   ```

   > **Bewusst ohne konkrete Angaben:** `criew/opaa` ist ein öffentliches Repository. Host,
   > Benutzername und die Pfade auf dem Server stehen deshalb nicht hier, sondern in der
   > Betriebsdokumentation des Maintainers. Wer den Rollout ausführen soll, bekommt sie von ihm.
   > Beschrieben ist hier das Verfahren, nicht die Belegung.

2. Die beiden httpd-Container binden das jeweilige Verzeichnis als Bind-Mount ein; ein Neustart des
   Backends ist für eine Korpus-Aktualisierung nicht nötig. Ob der jeweilige httpd-Container selbst
   neu erstellt werden muss, damit er neue Dateien ausliefert, hängt von seiner
   Bind-Mount-Konfiguration in der Instanz-Compose ab.
3. Damit das Backend `demo-corpus` und `presse.stadt-rheinfurt.example` als Indizierungsziel
   akzeptiert, steht in der Instanz-Konfiguration
   `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST=demo-corpus,presse.stadt-rheinfurt.example` — ohne
   diesen Eintrag lehnt die Zielprüfung (#267, siehe
   [„Sicherheitshinweis"](../docs/handbuch/deployment.md#sicherheitshinweis-post-apiv1librarieslibraryidindexing-ist-von-außen-erreichbar))
   beide internen Hostnamen ab.
4. Die Indizierung löst aus, wer mindestens `EDITOR` auf der Zielbibliothek hält (ADR-0018;
   `SYSTEM_ADMIN` ist dafür seit #478 nicht mehr erforderlich), über den **Admin-Bereich der
   Oberfläche** — für den Rheinfurt-Korpus die vier konnektorgespeisten Bibliotheken vom Typ
   `HTTP_DIRECTORY`/`RSS_FEED`, deren Quellkonfiguration bereits an der jeweiligen Bibliothek
   gespeichert ist (seit #478 nicht mehr Teil des Anstoß-Requests).
5. Der Fortschritt ist im Admin-Bereich sichtbar (dahinter `GET
   /api/v1/libraries/{libraryId}/indexing/status`).

**Deeplinks auf interne Quellen laufen über das Backend (#747).** `demo-corpus` und
`presse.stadt-rheinfurt.example` sind nur im Docker-Netz der Instanz auflösbar, nicht vom Browser
eines Nutzers aus — ein direkter Link auf die beim Indizieren gespeicherte Quell-URL lief deshalb vor
#747 ins Leere. `GET /api/v1/documents/{documentId}/content` streamt das Original für
`HTTP_DIRECTORY`/`RSS_FEED`-Dokumente seither serverseitig von dieser Quell-URL durch (mit derselben
Ziel-Allowlist-Prüfung wie beim Indizieren, siehe #267) statt den Client dorthin zu verweisen —
„Original öffnen" auf der Dokumentenübersicht und „Im Dokument öffnen" unter den Fundstellen
funktionieren für den Rheinfurt-Korpus dadurch unverändert, obwohl seine Webserver von außen nicht
erreichbar sind.

Unveränderte Dateien werden anhand ihrer SHA-256-Prüfsumme übersprungen und ihr `documents`-Datensatz
bleibt bei Status `INDEXED`; ein erneuter Lauf über denselben Bestand — etwa nach einer
Korpus-Aktualisierung, bei der nur ein Teil der Dateien sich geändert hat — verarbeitet deshalb
ausschließlich die geänderten oder neuen Dateien und ist gefahrlos wiederholbar.

#### Seed- und `opaa-seed`-Verfahren

Der (erneute) Seed der Instanz läuft im Profil `demo` von einer Arbeitsstation gegen die öffentliche
API und Keycloak, nicht auf dem Server selbst:

```bash
python seed.py --profile demo \
  --base-url https://opaa.ewerlin.com/api \
  --keycloak-url https://opaa.ewerlin.com/idp
```

Voraussetzung dafür ist der Keycloak-Client `opaa-seed` — siehe
[„Härtung für erreichbare Deployments"](../docs/handbuch/deployment.md#härtung-für-erreichbare-deployments),
Punkt 6. Auf der Instanz ist er im Normalbetrieb **deaktiviert**, sowohl in der Realm-Anpassung beim
Import als auch nachträglich per `kcadm`. Für einen Seed-Lauf wird er ausschließlich für dessen Dauer
aktiviert und unmittelbar danach wieder deaktiviert — kein dauerhaft scharfer, passwortbasierter
Tokenweg ohne Client-Secret auf einer erreichbaren Instanz.

**Zweite Vorbedingung, symmetrisch zur ersten:** `seed.py` erwartet für `demo-admin` fest das oben
dokumentierte Demo-Passwort und kennt kein Override-Flag dafür. Weil dieses Passwort nach jedem
Seed-Lauf rotiert wird (siehe unten), muss es vor einem erneuten Lauf für dessen Dauer per `kcadm
set-password` auf den dokumentierten Demo-Wert zurückgesetzt und unmittelbar danach wieder rotiert
werden — genau wie beim Client `opaa-seed` gilt: nur für die Dauer des Laufs scharf, sonst
deaktiviert/rotiert. Ein `--admin-password`-Override-Flag für `seed.py` wäre die naheliegende
Alternative, ist aber nicht Teil dieses Rollouts.

Nach jedem Seed-Lauf wird das Passwort von `demo-admin` auf einen serverseitig verwahrten Zufallswert
rotiert (siehe „Administration" oben); die vier Fach-Demokonten (`maria.weber`, `selin.kaya`,
`thomas.klein`, `andrea.vogt`) behalten das oben dokumentierte Demo-Passwort unverändert — sie sind
fachliche Vorführkonten ohne administrative Rechte.

**Entwicklungs-Nutzer `testuser`:** Der Realm-Import bringt neben den fünf Demo-Nutzern auch den in
`keycloak/realm-export.json` mitgelieferten Entwicklungs-Nutzer `testuser`/`testpass` auf die Instanz.
Er ist auf opaa.ewerlin.com per `kcadm` deaktiviert (`enabled: false`) — siehe
[„Härtung für erreichbare Deployments"](../docs/handbuch/deployment.md#härtung-für-erreichbare-deployments),
Punkt 1.

#### Aktualisierung der Instanz

Der Workflow [`publish-images.yml`](../.github/workflows/publish-images.yml) baut bei jedem Push auf
`main` neue Images (siehe
[„Deployment aus vorgebauten Images"](../docs/handbuch/deployment.md#deployment-aus-vorgebauten-images-ghcr)).
Auf dem Server liegt ein **Deployment-Skript**, das die aktuellen Images zieht und den Stack auf den
neuen Stand bringt (Mechanik: [„Aktualisierung auf einen neuen `main`-Stand" in
deployment.md](../docs/handbuch/deployment.md#aktualisierung-auf-einen-neuen-main-stand)). Es kennt
zusätzlich einen Schalter, der auch die Volumes verwirft — damit ist die Datenbank und mit ihr der
gesamte Index weg. Dieser Schalter ist deshalb kein Aktualisierungs-, sondern ein
Neuaufsetzschritt; danach ist zwingend eine vollständige Neuindizierung nötig.

Ein **Cron-Job ruft dieses Skript täglich um 2 Uhr morgens auf** — ohne den zurücksetzenden Schalter,
die Daten bleiben also erhalten. Die Ausgabe der Läufe wird protokolliert und wöchentlich rotiert. Die
Instanz folgt dem `main`-Stand damit mit höchstens einem Tag Verzug; ein Push auf `main` erscheint
nicht sofort, sondern beim nächsten nächtlichen Lauf. Wer schneller sein will, ruft das Skript von
Hand auf.

Auf der Testinstanz sind `nomic-embed-text` und `OPAA_PGVECTOR_DIMENSIONS=768` fest aneinander
gekoppelt: Wer das Embedding-Modell wechselt, muss beide Werte gemeinsam ändern und die Datenbank
zurücksetzen. Ein Wechsel des **Chat**-Modells berührt den Index dagegen nicht — Chat und Einbettung
sind auf der Instanz ohnehin getrennte Anbieter.

#### Sicherheitshinweis: Indizierungsendpunkt ist von außen erreichbar

Der Indizierungsendpunkt (`POST /api/v1/libraries/{libraryId}/indexing`) ist auf der Testinstanz aus
dem Internet erreichbar — die allgemeine Härtung dazu steht in
[`../docs/handbuch/deployment.md`, „Sicherheitshinweis"](../docs/handbuch/deployment.md#sicherheitshinweis-post-apiv1librarieslibraryidindexing-ist-von-außen-erreichbar)
und gilt hier unverändert. Zusätzlich zur dortigen Zielprüfung greift auf dieser Instanz das reguläre
Rate Limiting mit einem eigenen, engen Kontingent für diesen Pfad (`OPAA_RATE_LIMIT_INDEXING_*`,
standardmäßig eine Anfrage pro IP und Minute).

## Zugehörige Dokumentation

- [`docs/features/demo-instance.md`](../docs/features/demo-instance.md) — Konzept: Behördenlandschaft,
  Bibliotheken, Formate, Quellen und Lizenzen, Rechtemodell
- [`docs/market/demo-drehbuch.md`](../docs/market/demo-drehbuch.md) — das ausformulierte
  Vorführ-Drehbuch mit acht Fragen
- [`docs/handbuch/deployment.md`](../docs/handbuch/deployment.md), Abschnitt „Härtung für erreichbare
  Deployments" — zwingend vor jedem über `localhost` hinaus erreichbaren Rollout dieser Demo,
  einschließlich des dort separat behandelten `opaa-seed`-Clients
- Smoke-Test gegen das `demo`-Profil — #232 (`e2e/demo-smoke/`, `pnpm run test:demo-smoke` in `e2e/`,
  siehe [`e2e/README.md`, „Demo-Smoke (#232)"](../e2e/README.md#demo-smoke-232))
- [`docs/features/search-quality-evaluation.md`](../docs/features/search-quality-evaluation.md),
  Abschnitt „Öffentliche Demo" — der frühere Superhelden-Korpus, durch dieses Konzept abgelöst
- Rollout auf einen erreichbaren Host — #230
