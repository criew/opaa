# Demo-Instanz „Stadt Rheinfurt"

Die eine Quelle für die Demo-Instanz „Stadt Rheinfurt" (Epic #708): installieren, anmelden,
vorführen — und, für alle, die die Demo selbst weiterentwickeln, Korpus-Generator und
Seed-Mechanismus. Konzept dahinter — Behördenlandschaft, Bibliotheken, Berechtigungsmatrix, Quellen
und Lizenzen — steht in [`docs/features/demo-instance.md`](../docs/features/demo-instance.md) und
wird hier **nicht wiederholt**. Das ausformulierte Vorführ-Drehbuch mit acht Fragen und acht
Vorführschritten steht in
[`docs/market/demo-drehbuch.md`](../docs/market/demo-drehbuch.md).

Dieses Verzeichnis liegt wie `eval/` bewusst außerhalb von Gradle-Build und CI — der Generator läuft
nur bei bewussten Korpus-Änderungen, nie automatisch.

```
demo/
├── generator/     Python-Generator, siehe generator/README.md für Reproduktion und Werkzeugwahl
├── seed/          Seed-Skript (Datenprofile "demo" und "e2e"), siehe "Seed-Mechanismus (#712)" unten
└── corpus/        generierter Korpus, committet
    ├── leistungen-meldewesen-ausweise/       .md, .csv
    ├── leistungen-kfz-zulassung/             .md, .txt
    ├── satzungen-gebuehrenordnungen/         .pdf, .xlsx
    ├── pressemitteilungen/                   rss.xml + .html
    ├── interne-dienstanweisungen-meldewesen/ .docx, .pdf, .pptx, .eml in Aktenplan-Ordnern (Upload mit folderPath, #2015)
    ├── ratsinformationen/<jahr>/<Gremium>/   .md, .txt, .eml mit PDF-Anlagen (S3-Bucket des Demo-Stacks, #1383, #2016)
    ├── formate/                              je ein Dokument pro unterstützter Endung (S3-Bucket des Demo-Stacks, #1519)
    ├── MANIFEST.sha256                       SHA-256 über alle Dokumente
    ├── SOURCE.md                             Quellen, Lizenzen, Hinweis auf synthetische Inhalte (vom Generator geschrieben)
    └── THIRD-PARTY-LICENSES/                 Volltexte der MIT-Lizenz des LHM-Dienstleistungen-Corpus und der Apache-2.0-Lizenz des POI-Testkorpus
```

---

## Demo nutzen

### Was die Demo zeigt

Das Bürgerbüro Rheinfurt mit mehreren Sachgebieten, sieben Wissensbibliotheken, vier
Konnektortypen und mehrere Dateiformate:

| Wissensbibliothek | Formate | Quellentyp |
|---|---|---|
| Leistungen Meldewesen & Ausweise | `.md`, `.csv` | `HTTP_DIRECTORY` |
| Leistungen Kfz-Zulassung | `.md`, `.txt` | `HTTP_DIRECTORY` |
| Satzungen & Gebührenordnungen | `.pdf`, `.xlsx` | `HTTP_DIRECTORY` |
| Pressemitteilungen Stadt Rheinfurt | RSS-XML + HTML-Detailseiten | `RSS_FEED` (statisch, selbst gehostet) |
| Interne Dienstanweisungen Meldewesen | `.docx`, `.pdf`, `.pptx`, `.eml` mit PDF-Anhang (Ordner nach Aktenplan, bis zu zwei Ebenen) | `UPLOAD` (im Seed automatisiert) |
| Ratsinformationen Stadt Rheinfurt | `.md`, `.txt`, `.eml` mit PDF-Anhängen (Ordner Jahrgang › Gremium) | `S3` (Objektspeicher des Demo-Stacks, Bucket `rheinfurt-archiv`; #1383, #2016) |
| Formattest auf S3 | je ein Dokument pro unterstützter Endung | `S3` (Objektspeicher des Demo-Stacks, Bucket `formattest`; #1520) |

Die siebte Bibliothek ist keine Fachablage, sondern eine **technische Schaubibliothek**: Sie zeigt,
dass jedes vom Handbuch zugelassene Dateiformat wirklich verarbeitet wird, und gehört deshalb allein
dem Demo-Admin — kein Fachkonto bekommt ein Leserecht darauf, und zugeordnet ist sie allein dem
persönlichen Space des Demo-Admins.
Jedes erzeugte Dokument nennt im Text sein eigenes Format, damit im Chat erkennbar bleibt, aus
welcher Datei eine Antwort stammt.

**Ein Dokument fällt bewusst aus dem Rahmen:** Die Outlook-Nachricht (`.msg`) lässt sich mit keiner
Bibliothek erzeugen und stammt deshalb unverändert aus dem Testkorpus des Apache-POI-Projekts
(Apache License 2.0). Sie ist als einziges Dokument des gesamten Korpus englisch und ohne
Rheinfurt-Bezug — das ist eine Folge ihrer Herkunft, kein Versehen. Die Bibliotheksbeschreibung sagt
das auch in der Oberfläche; Herkunft und Lizenz stehen in
[`corpus/SOURCE.md`](corpus/SOURCE.md) und
[`corpus/THIRD-PARTY-LICENSES/`](corpus/THIRD-PARTY-LICENSES/).

Begründung der Auswahl, Quellen und Lizenzen des Korpus:
[`docs/features/demo-instance.md`](../docs/features/demo-instance.md).

Über den Korpus hinaus richtet der Seed das ein, was das
[Drehbuch](../docs/market/demo-drehbuch.md) vorführt:

| Funktion | In der Demo | Drehbuch |
|---|---|---|
| Belegte Antworten, abhängig von Konto und Space | vier Fachkonten, sechs Spaces mit zugeordneten Bibliotheken | Fragen 1–8 |
| Interne Gruppen | „Vertretung Meldewesen" (Maria verantwortet, Thomas liest darüber die internen Dienstanweisungen), „Presseverteiler Bürgerbüro", „Sachbearbeitung Bürgerbüro" | Schritt A |
| Gruppen aus Keycloak | „Bürgerbüro Rheinfurt", „Meldewesen", „Kfz-Zulassung" über den Verzeichnisabgleich, mit Rechten, Eigentum und Space-Mitgliedschaft | Schritt B |
| Prompt-Bibliotheken | „Textbausteine Bürgerbüro" für alle, „Vorlagen Amtsleitung" nur für Andrea, „Arbeitshilfen Kfz-Zulassung" nur für Thomas; Slash-Befehle mit Variablenformular | Schritt C |
| Ordner und Anhänge | Aktenplan in der Upload-Bibliothek, Jahrgang und Gremium in den Ratsinformationen, Ratsvorlagen mit PDF-Anlagen | Schritt D |
| Fachformate | Fachfragen aus XLSX, aus dem PDF-Anhang einer Mail und aus CSV | Schritt E |
| Viele Chats und ein langer Verlauf | 81 vorbereitete Chats im Space „Amtsleitung Bürgerbüro" (Andrea Vogt), über acht Wochen verteilt, fünf angeheftet, elf archiviert, einer mit 32 Runden | Schritt F |
| Space ohne Wissen | jeder neu angelegte Space: Hinweis mit Direktlink „Wissen zuordnen" statt einer Antwort ohne Belege | Schritt G |
| Eigentum, Sichtbarkeit, Favoriten, Zuordnung | Assets verschiedener Personen und einer Keycloak-Gruppe, öffentlich und geschlossen; je Person andere Favoriten, Selin ohne; Spaces nur mit öffentlichem Wissen und mit Wissen, das nicht jedes Mitglied liest | Schritt H |

Einzelheiten zu Gruppen, Prompts, Chats und den Kombinationen stehen unten in den Abschnitten
„Gruppen", „Prompt-Bibliotheken", „Vorbereitete Chats" und „Eigentum, Sichtbarkeit, Favoriten und
Zuordnung".

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
OPAA_INITIAL_ADMIN_PASSWORD=<eigener Wert, nur für die Dauer des Seeds; siehe unten>
OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST=demo-corpus,presse.stadt-rheinfurt.example,objectstore
OPAA_CREDENTIALS_ENCRYPTION_KEY=<Ausgabe von: openssl rand -base64 32>
OPAA_AUTH_JWT_SECRET=<Ausgabe von: openssl rand -base64 48>
OPAA_CSP_CONNECT_SRC_EXTRA=http://localhost:8180
OPAA_DEMO_MODE=true
OPAA_PGVECTOR_DIMENSIONS=768
OPAA_UPLOAD_THREAD_POOL_QUEUE_CAPACITY=30
OPAA_UPLOAD_STORE=s3
OPAA_UPLOAD_S3_ENDPOINT=http://objectstore:9000
OPAA_UPLOAD_S3_BUCKET=opaa-uploads
OPAA_UPLOAD_S3_ACCESS_KEY=opaa-uploads
OPAA_UPLOAD_S3_SECRET_KEY=OpaaUploads!2026
OPAA_DEMO_CHAT_IMPORT_ENABLED=true
```

Herkunft und Zwang jeder einzelnen Variable:

- `OPAA_INITIAL_ADMIN_EMAIL` und `OPAA_INITIAL_ADMIN_PASSWORD` sind Adresse und Passwort des
  **lokalen Notanker-Kontos der Systemverwaltung**, das OPAA beim allerersten Start im `oidc`-Modus
  anlegt ([ADR-0033](../docs/decisions/0033-lokale-benutzerverwaltung.md), #1534). Seit diesem Stand
  wird der Keycloak-Nutzer `demo-admin` **nicht mehr von selbst** `SYSTEM_ADMIN`; Schritt 1 des Seeds
  (siehe unten) meldet sich als Notanker-Konto an und vergibt ihm die Rolle über die reguläre
  Rollen-API. Dafür muss das Passwort beim allerersten Start gesetzt gewesen sein (ein erzeugtes
  Passwort stünde nur im Log und würde einen Wechsel erzwingen) und dem Seed als
  `--local-admin-email`/`--local-admin-password` bzw. als gleichnamige Umgebungsvariablen
  vorliegen — sonst bricht Schritt 1 mit einer klaren Fehlermeldung ab, statt eine falsche Rolle
  stillschweigend zu akzeptieren. Die Adresse darf dieselbe sein wie die des Keycloak-Nutzers
  (zwei Konten unter zwei Issuern); `admin@opaa.local` wird abgelehnt.
- `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST=demo-corpus,presse.stadt-rheinfurt.example,objectstore` ist
  zwingend: Die Zielprüfung ausgehender Abrufe (`opaa.indexing.target-validation`, #267,
  standardmäßig aktiv) lehnt Compose-interne Adressen in privaten Bereichen ab — ohne diesen Eintrag
  würde jede Indizierung der beiden Demo-Webserver und des Demo-Objektspeichers `objectstore` (#1383) mit
  „Zieladresse liegt in einem gesperrten Adressbereich" abgelehnt (siehe [`../docs/handbuch/deployment.md`, „Sicherheitshinweis"](../docs/handbuch/deployment.md#sicherheitshinweis-post-apiv1librarieslibraryidindexing-ist-von-außen-erreichbar)).
  Der Eintrag steht bewusst **nicht** in `docker-compose.yml`: Der Backend-Service dort läuft immer,
  mit oder ohne `demo`-Profil, und ein dort fest eingetragener Wert würde jede eigene Belegung dieser
  Variablen aus einer Betreiber-`.env.docker` überschreiben (Vorrang von `environment:` vor
  `env_file:` in Compose) — auch dort, wo das `demo`-Profil nie gestartet wird. `.env.docker.example`
  führt die Variable bereits auskommentiert mit diesem Demo-Wert als Beispiel.
- `OPAA_CREDENTIALS_ENCRYPTION_KEY` ist seit #1383 zwingend: Die `S3`-Bibliothek „Ratsinformationen
  Stadt Rheinfurt" ist die erste Demo-Bibliothek mit Zugangsdaten (dem Root-Schlüssel des
  `objectstore`-Containers), und Zugangsdaten werden nur verschlüsselt gespeichert (#483,
  [`../docs/handbuch/deployment.md`, „Zugangsdaten-Verschlüsselung"](../docs/handbuch/deployment.md#zugangsdaten-verschlüsselung)).
  Ohne Schlüssel bricht der Seed beim Anlegen dieser Bibliothek mit `503` ab. Der Demo-Stack läuft
  mit `docker,oidc`, nicht mit `dev` — der nur dort hinterlegte Entwicklungsschlüssel greift also
  nicht; einen eigenen Wert mit `openssl rand -base64 32` erzeugen (der Demo-Smoke-Lauf setzt in
  `e2e/demo-smoke.env` bewusst den öffentlichen Entwicklungsschlüssel, weil er nur Demo-Werte schützt).
- `OPAA_AUTH_JWT_SECRET` ist seit #1532 zwingend: Der Demo-Stack läuft mit `docker,oidc`, und in
  diesem Betriebsmodus bricht das Backend den Start ohne ein starkes Wurzelgeheimnis der lokalen
  Benutzerverwaltung ab (ADR-0033; mindestens 32 Zeichen, kein Platzhalter). Einen eigenen Wert mit
  `openssl rand -base64 48` erzeugen; der Demo-Smoke-Lauf setzt in `e2e/demo-smoke.env` bewusst
  einen öffentlichen Testwert, weil er nur Demo-Konten schützt.
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
  `uploadTaskExecutor` (Default 20, `opaa.upload.thread-pool`) an: Der Seed lädt die 27 Dokumente der
  Bibliothek „Interne Dienstanweisungen Meldewesen" sequentiell und ohne Pause hoch, und mit lokal
  betriebenen Ollama-Embeddings (langsamer als ein Cloud-Anbieter) füllt sich die Warteschlange eher
  als mit einem schnellen Anbieter — ohne die Anhebung kann der letzte Upload oder die letzten zwei
  mit „Die Verarbeitung ist derzeit ausgelastet - bitte später erneut versuchen." fehlschlagen (siehe
  „Seed-Mechanismus (#712)" unten für den Umgang, falls das trotzdem passiert).
- Der `OPAA_UPLOAD_S3_*`-Block stellt die **Originalablage der Demo auf den Objektspeicher** um
  ([ADR-0030](../docs/decisions/0030-originalablage-der-uploads.md), #1520): Hochgeladene Originale
  landen im Bucket `opaa-uploads` desselben `objectstore`-Containers, der auch die beiden S3-Quellen
  bereitstellt — nicht im Dienst `upload-store` des Compose-Profils `upload-s3`, der für reguläre
  Installationen gedacht ist. Ohne diesen Block bleibt die Demo auf der Dateisystem-Ablage
  (Anwendungs-Default) und zeigt einen gebauten Betriebsweg nicht. Zugangsdaten sind **nicht** der
  Root-Schlüssel: Der Init-Schritt `objectstore-seed` legt den Schlüssel `opaa-uploads` an und bindet ihn
  über `demo/objectstore/opaa-uploads-policy.json` auf genau diesen einen Bucket — der Root-Schlüssel
  steckt dagegen als Quellzugangsdaten in den beiden S3-Bibliotheken. Die Zieladressprüfung der
  Ablage hat einen eigenen Namensraum und lässt ihre eigene konfigurierte Adresse immer zu; der
  Eintrag `objectstore` in `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` oben betrifft ausschließlich die
  Konnektoren.
- `OPAA_DEMO_CHAT_IMPORT_ENABLED=true` schaltet den Einspielweg für die vorbereiteten Chats ein
  (#2071, siehe „Vorbereitete Chats" unten): Nur damit existieren die Routen
  `POST` und `GET /api/v1/spaces/{spaceId}/chat-imports`, über die der letzte Seed-Schritt Andreas
  Chats ohne Modellaufruf anlegt bzw. die schon eingespielten findet. Ohne die Zeile bricht der Seed an dieser Stelle mit einem Hinweis auf die
  Variable ab – alles davor ist dann schon eingerichtet, ein erneuter Lauf nach dem Neustart des
  Backends holt nur die Chats nach. **Der Schalter gilt nur für die Dauer des Seed-Laufs:** Danach
  wird die Zeile entfernt und das Backend neu gestartet, denn solange er gesetzt ist, kann jedes
  angemeldete Konto Antworten ohne Modell in eigene Chats schreiben (siehe „Vorbereitete Chats"
  unten). Für jede Nicht-Demo-Installation bleibt die Variable ungesetzt.

#### 2. Stack starten

```bash
docker compose --profile demo up
```

Ohne einen extern erreichbaren Ollama-Server (weder auf dem Host noch im eigenen Netz) zusätzlich
das Compose-Profil `ollama` aktivieren (#720, siehe
[`../docs/handbuch/deployment.md`, „Lokal betriebenes Ollama im Compose-Stack"](../docs/handbuch/deployment.md#lokal-betriebenes-ollama-im-compose-stack)):

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

  **Neustart:** Der Dienst hat bewusst kein Volume und importiert `keycloak/realm-export.json` bei
  jedem Start neu. Beide Realm-Exporte vergeben deshalb je Konto eine **feste Nutzer-ID** (`"id"`
  unter `users`, #1526); Keycloak übernimmt sie, das `sub` im Token bleibt damit über einen
  Neustart hinweg gleich, und OPAA findet zu jedem Konto (Schlüssel: `subject` + `issuer`) seinen
  vorhandenen Datensatz samt Spaces und Rechten wieder. Ein `docker compose --profile demo down`
  **ohne** `-v` erhält damit die ganze Demo: Datenbank, Objektspeicher und Konten. Wer den Realm
  ändert, startet einfach neu — die Datei führt, nicht ein Keycloak-Zustand. **Ein neuer Eintrag
  unter `users` braucht dabei seine eigene feste `"id"`**, sonst gilt für dieses eine Konto wieder
  das alte Verhalten: neues `sub` bei jedem Start, neuer rechteloser Datensatz bei jedem Login.
- **`demo-corpus`** (`httpd:2.4-alpine`) liefert die drei `HTTP_DIRECTORY`-Bibliotheken als getrennte
  Unterverzeichnisse aus: `leistungen-meldewesen-ausweise/`, `leistungen-kfz-zulassung/`,
  `satzungen-gebuehrenordnungen/`. `interne-dienstanweisungen-meldewesen/` (die `UPLOAD`-Bibliothek,
  #712) wird bewusst **nicht** gemountet — nichts davon darf über HTTP erreichbar sein.
- **`demo-presse`** (`httpd:2.4-alpine`) liefert `pressemitteilungen/` (RSS-Feed + HTML-Detailseiten)
  unter dem Compose-Netzwerk-Alias `presse.stadt-rheinfurt.example`, weil `rss.xml` seine Detailseiten
  absolut unter dieser Domain verlinkt (siehe `generator/presse.py`, `FEED_BASE_URL`) — bewusst eine
  realistische Domain statt `localhost`, damit die Demo das `RSS_FEED`-Konnektorverhalten so vorführt,
  wie es auch gegen eine echte Domain liefe.

- **`objectstore`** (`rustfs/rustfs`, gepinnt auf dieselbe Version wie die S3-Testfixture des
  Backends; seit #1949 an der Stelle von MinIO, dessen Images öffentlich nicht mehr beziehbar sind)
  ist der **eine** S3-kompatible Objektspeicher der Demo — mit drei Buckets (#1383, #1520,
  [ADR-0027](../docs/decisions/0027-s3-konnektor.md),
  [ADR-0030](../docs/decisions/0030-originalablage-der-uploads.md), Handbuch
  [`konnektor-s3.md`](../docs/handbuch/konnektor-s3.md)):

  | Bucket | Rolle | Inhalt |
  |---|---|---|
  | `rheinfurt-archiv` | Quelle der Bibliothek „Ratsinformationen Stadt Rheinfurt" | `demo/corpus/ratsinformationen/` unter dem Präfix `ratsinformationen/`, Jahrgangs- und Gremiumsordner inklusive |
  | `formattest` | Quelle der Bibliothek „Formattest auf S3" | `demo/corpus/formate/`, je ein Dokument pro unterstützter Endung |
  | `opaa-uploads` | **Ablage** der hochgeladenen Originale der Demo | was über die Oberfläche hochgeladen wird, einschließlich der 27 Dokumente der Upload-Bibliothek, die der Seed einspielt — je Original ein Objekt unter `<Organisations-ID>/<Bibliotheks-ID>/<Zufallsname><Endung>` ([ADR-0030](../docs/decisions/0030-originalablage-der-uploads.md), Entscheidung 4 mit Nachtrag) |

  Der Einmal-Schritt **`objectstore-seed`** legt alle drei Buckets an, spiegelt die beiden
  Korpus-Buckets (`aws s3 sync --delete`, idempotent; Fortschritt mit
  `docker compose logs objectstore-seed`) und richtet den auf `opaa-uploads` beschränkten
  Zugangsschlüssel der Ablage ein (`demo/objectstore/opaa-uploads-policy.json`, angelegt von
  `demo/objectstore/scoped-key.py` über die Admin-API des Speichers — einen mitgelieferten
  Kommandozeilenclient wie MinIOs `mc` gibt es dort nicht). Den Uploads-Bucket **befüllt er nie** —
  dessen Inhalt gehört der Anwendung allein.

  **Volume:** Der Dienst hat seit #1520 ein benanntes Volume (seit #1949
  `opaa-demo-objectstore-data`), weil die Datenbank der Demo einen Neustart überlebt und eine
  Dokumentzeile ohne ihr Objekt genau das kaputte Bild ist, das der Aufräumlauf (#1478)
  anschließend meldet. Die beiden Korpus-Buckets bleiben davon unberührt: `aws s3 sync --delete`
  erzwingt bei jedem Start wieder den committeten Korpusstand, egal was im Volume lag.

  > **Einmalig beim Wechsel auf #1949:** Das alte Volume trägt MinIOs Ablageformat, das der neue
  > Dienst nicht liest. Ein bestehender Demo-Stack braucht deshalb genau einmal
  > `docker compose --profile demo down -v` und einen neuen Seed-Lauf.

  > **Neustart:** Objekte, Datenbank, Dokumentzeilen **und Konten** überleben ein
  > `docker compose --profile demo down` (ohne `-v`). Die Konten tun das seit #1526, weil beide
  > Realm-Exporte je Eintrag eine feste Nutzer-ID vergeben (siehe „Stack starten", Punkt
  > `keycloak`): Das `sub` im Token bleibt gleich, OPAA findet seinen vorhandenen Datensatz samt
  > Rechten wieder, und ein erneuter Seed-Lauf bleibt auch über den Neustart hinweg idempotent.
  > Vollständig zurücksetzen (`down -v` plus neuer Seed) muss nur, wer den Bestand selbst loswerden
  > will.

  **Zugangsdaten:** Der Root-Schlüssel `rheinfurt-archiv` / `RheinfurtDemo!2026` ist ein
  dokumentierter Demo-Wert; ihn gibt der Seed den **beiden S3-Bibliotheken** als
  `accessKey:secretKey` mit, und mit ihm öffnet sich die Konsole des Speichers. Die **Ablage**
  benutzt stattdessen den eigenen, bucket-beschränkten Schlüssel `opaa-uploads` /
  `OpaaUploads!2026` (Schritt 1). Das Backend spricht `objectstore` Path-Style über das
  Compose-Netzwerk an, weshalb der Servicename für die Konnektoren in der Allowlist stehen muss
  (Schritt 1).

Alle Quellcontainer binden standardmäßig nur an `127.0.0.1` (Ports `OPAA_DEMO_CORPUS_PORT`, Default
8091, `OPAA_DEMO_PRESSE_PORT`, Default 8092, `OPAA_DEMO_OBJECTSTORE_PORT`, Default 8093, und
`OPAA_DEMO_OBJECTSTORE_CONSOLE_PORT`, Default 8094) — zum Prüfen im Browser, nicht als
öffentlicher Zugang;
das Backend erreicht alle drei ohnehin über das Compose-Netzwerk unter ihrem Servicenamen bzw. Alias,
ein Hafen nach außen ist dafür nicht nötig:

- <http://127.0.0.1:8091/leistungen-meldewesen-ausweise/> (ebenso für die anderen beiden Verzeichnisse)
- <http://127.0.0.1:8092/rss.xml>
- <http://127.0.0.1:8094/rustfs/console/> — **Konsole des Objektspeichers**, der Weg, sich die
  drei Buckets anzusehen (der Dienst serviert sie unter diesem Pfad, nicht unter `/`). Anmeldung
  mit dem Root-Schlüssel oben (`rheinfurt-archiv` / `RheinfurtDemo!2026`): `rheinfurt-archiv`
  zeigt die Jahrgangs- und Gremiumsordner unter `ratsinformationen/`, `formattest` die vierzehn Formatmuster,
  und `opaa-uploads` füllt sich mit je einem Objekt pro hochgeladenem Original — sichtbar
  unmittelbar nach einem Upload über die Oberfläche, zwei Schlüsselebenen tief: erst die
  Organisation, darin die Bibliothek.

  Dasselbe von der Kommandozeile, über die S3-API auf Port 8093:

  ```bash
  AWS_ACCESS_KEY_ID=rheinfurt-archiv AWS_SECRET_ACCESS_KEY='RheinfurtDemo!2026' \
    aws --endpoint-url http://127.0.0.1:8093 s3 ls --recursive s3://rheinfurt-archiv
  ```

Listing-Format: Apache `IndexOptions FancyIndexing HTMLTable`
(`webserver/httpd-demo-autoindex.conf`) — die erprobte Referenz, seit #550 aber keine Notwendigkeit
mehr (der `AutoindexCrawlerService` versteht seither auch `<pre>`-Listings und `<ul>`-Layouts).

Warten, bis `backend` und `keycloak` bereit sind (`docker compose logs -f backend`, Zeile
„Started OpaaApplication").

Wer nur den Korpus-Webserver ohne Anmeldung ausprobieren will (kein Login, keine Spaces/Rechte), lässt
`SPRING_PROFILES_ACTIVE` auf `docker,dev` — `demo-corpus`/`demo-presse`/`objectstore` starten trotzdem über
`--profile demo`, `keycloak` läuft dann einfach mit, bleibt aber ungenutzt. Für den vollständigen Seed
(unten) ist `docker,oidc` zwingend: Das `demo`-Datenprofil des Seeds meldet sich über Keycloak an.

#### 3. Seed ausführen

```bash
cd demo/seed
pip install -r requirements.txt
python seed.py --profile demo
```

Der Seed richtet über die öffentliche API alle vier Demo-Nutzer plus das Admin-Konto ein, schaltet
den Anbieter „Verzeichnisdienst" auf den **Verzeichnisabgleich** um und lässt ihn einmal laufen
(ADR-0036, siehe „Gruppen" unten), legt die sechs
Spaces und sieben Wissensbibliotheken an — jede über die Sitzung ihrer Eigentümerin bzw. ihres
Eigentümers —, vergibt die Freigaben an „Alle Konten" und die Leserechte, richtet drei interne
Gruppen mit benannter Verantwortung ein, vergibt den drei Keycloak-Gruppen ihre Rechte, ordnet jedem Space
seine Bibliotheken als Datenquellen zu — den sechs angelegten Spaces ebenso wie dem persönlichen
Space „Meine Dokumente" jedes Kontos, denn ein Space durchsucht nur, was ihm zugeordnet ist —, lädt die 27 Dokumente der internen Upload-Bibliothek hoch und stößt die
Indizierung der sechs konnektorgespeisten Bibliotheken an — darunter die beiden `S3`-Bibliotheken,
deren Läufe die Buckets `rheinfurt-archiv` und `formattest` des `objectstore`-Containers lesen (der
Einmal-Schritt `objectstore-seed` muss dafür durchgelaufen sein, siehe Schritt 2). Vollständiger Ablauf,
Idempotenz und Fehlerfälle: „Seed-Mechanismus (#712)" unten.

Außerdem legt der Seed im Namen von Andrea Vogt die beiden Prompt-Bibliotheken „Textbausteine
Bürgerbüro" und „Vorlagen Amtsleitung", im Namen von Thomas Klein die „Arbeitshilfen
Kfz-Zulassung" samt Prompts, Freigaben und Space-Zuordnung an (siehe „Prompt-Bibliotheken" unten),
ordnet die Textbausteine jedem Space zu, setzt die Favoriten von Maria, Thomas und Andrea (siehe
„Eigentum, Sichtbarkeit, Favoriten und Zuordnung" unten) und spielt zum Schluss 81 vorbereitete
Chats in Andreas Space „Amtsleitung Bürgerbüro" ein (siehe „Vorbereitete Chats" unten).

**Wie lange dauert die Erstindizierung, und wie erkennt man, dass sie fertig ist?** Der Seed selbst
wartet auf jede Indizierung und jeden Upload (Polling gegen `GET
/api/v1/libraries/{libraryId}/indexing/status` bzw. den Dokumentstatus) und bricht mit einer klaren
Fehlermeldung ab, wenn etwas schiefgeht — läuft `seed.py` bis zur Ausgabe „Seed-Profil 'demo'
abgeschlossen." durch, ist die Instanz vollständig gefüllt und durchsuchbar. Bei den 187 Dateien
des Korpus (47 + 37 + 20 + 27 + 15 + 14 in den sechs konnektorgespeisten Bibliotheken, 27 Uploads;
die PDF-Anhänge der Mails kommen als eigene Dokumente hinzu) und
lokal betriebenen Modellen ist mit einigen Minuten zu rechnen, je nach Ollama-Hardware; ein zweiter Lauf
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
| `demo-admin` (admin@stadt-rheinfurt.example) | Systemadministration | eigener Default-Space (mit „Formattest auf S3"), „Infotheke Bürgerbüro" (über Keycloak-Gruppe) | richtet ein; besitzt „Leistungen Kfz-Zulassung", „Ratsinformationen" und „Formattest auf S3" — letztere liest ausschließlich er; dazu die öffentlichen Satzungen | `RheinfurtDemo!2026` |
| `maria.weber` | Sachbearbeiterin Meldewesen | „Meldewesen & Ausweise" (mit Selin), „Maria Weber – persönlich" (allein), „Dienstbesprechung Bürgerbüro" und „Infotheke Bürgerbüro" (je über Gruppe) | Leistungen Meldewesen & Ausweise, Satzungen & Gebührenordnungen, Pressemitteilungen, Interne Dienstanweisungen Meldewesen, Ratsinformationen | `RheinfurtDemo!2026` |
| `selin.kaya` | Sachbearbeiterin Meldewesen | „Meldewesen & Ausweise" (über die Keycloak-Gruppe „Meldewesen"), „Dienstbesprechung Bürgerbüro" (über Gruppe), „Infotheke Bürgerbüro" (Eigentümerin) | dieselben fünf wie Maria, Pressemitteilungen nur über die Gruppe „Presseverteiler Bürgerbüro" | `RheinfurtDemo!2026` |
| `thomas.klein` | Sachbearbeiter Kfz-Zulassung | „Kfz-Zulassung" (allein), „Meldewesen & Ausweise", „Dienstbesprechung Bürgerbüro" und „Infotheke Bürgerbüro" (je über Gruppe) | Leistungen Kfz-Zulassung, Satzungen & Gebührenordnungen, Ratsinformationen; Pressemitteilungen nur über „Presseverteiler Bürgerbüro", Interne Dienstanweisungen Meldewesen nur über „Vertretung Meldewesen" | `RheinfurtDemo!2026` |
| `andrea.vogt` | Amtsleitung Bürgerbüro | „Amtsleitung Bürgerbüro" (allein), „Dienstbesprechung Bürgerbüro" (Eigentümerin), „Infotheke Bürgerbüro" (über Gruppe) | alle sechs fachlichen Bibliotheken (nicht „Formattest auf S3") | `RheinfurtDemo!2026` |

Der Objektspeicher `objectstore` des Demo-Stacks hat einen eigenen Root-Schlüssel (`rheinfurt-archiv` /
`RheinfurtDemo!2026`, `docker-compose.yml`) — derselbe offene Demo-Wert, mit dem der Seed die beiden
`S3`-Bibliotheken anlegt und mit dem sich die Konsole (Port 8094, Pfad `/rustfs/console/`) öffnen lässt. Die
Originalablage benutzt davon getrennt den bucket-beschränkten Schlüssel `opaa-uploads` /
`OpaaUploads!2026`, den der Init-Schritt `objectstore-seed` anlegt — ebenfalls ein offener Demo-Wert.

Zusätzlich existiert im zweiten Keycloak-Realm `partner` (`keycloak/realm-partner-export.json`,
ADR-0025) eine **zweite `maria.weber` mit derselben E-Mail** und demselben Passwort — ein
eigenes Konto ohne die Rechte der Demo-Maria, sobald die Systemverwaltung den Realm als weiteren
Anbieter angelegt hat (Administration → Identitätsanbieter; der Demo-Smoke-Lauf tut genau das).
Sie zeigt, dass Konten zweier Anbieter nie zusammengeführt werden.

Die Spalte „Lesbare Bibliotheken" zählt die wirksamen Leserechte: aus Eigentum, aus der Freigabe an
„Alle Konten" und aus `VIEWER`-Rechten, eigenen wie über eine Gruppe vermittelten (siehe „Gruppen"
und „Eigentum, Sichtbarkeit, Favoriten und Zuordnung" unten). Satzungen und Ratsinformationen sind
für „Alle Konten" freigegeben. Die Leistungen Meldewesen & Ausweise gehören der Keycloak-Gruppe
„Meldewesen", die Leistungen Kfz-Zulassung liest Thomas allein über „Kfz-Zulassung" — Andrea behält
ihre eigenen Grants auf beide Leistungsbibliotheken. Jeder Nutzer bekommt
beim ersten Login zusätzlich automatisch seinen eigenen Default-Space „Meine Dokumente", der oben
nicht eigens aufgeführt ist. **Ein Space durchsucht nur, was ihm zugeordnet ist** —
`@Space-Wissen` sucht in den zugeordneten Bibliotheken, geschnitten mit den Leserechten der
fragenden Person, und die `/`-Auswahl bietet nur zugeordnete Prompts an. Der Seed ordnet deshalb
jedem Space Wissen zu: den Sachgebiets- und Amtsleitungs-Spaces ihre lesbaren Bibliotheken, „Maria
Weber – persönlich" dieselben fünf wie „Meldewesen & Ausweise", „Dienstbesprechung Bürgerbüro" die
drei für alle Fachkonten lesbaren und die Leistungen beider Sachgebiete, „Infotheke Bürgerbüro"
nur die beiden öffentlichen und jedem Default-Space die Bibliotheken des Sachgebiets seines
Kontos (dem von Thomas zusätzlich die internen Dienstanweisungen, die er als Vertretung liest, dem
des Demo-Admins „Formattest auf S3"). Wie ein Space ohne Wissen aussieht — Hinweis mit
Direktlink „Wissen zuordnen" statt einer Antwort ohne Belege —, zeigt jeder neu angelegte Space
(Drehbuch, Schritt G). Begründung der Matrix:
[`../docs/features/demo-instance.md`, „Nutzer, Spaces und
Berechtigungen"](../docs/features/demo-instance.md#nutzer-spaces-und-berechtigungen).

**Der Vorführ-Kern:** Weil die Berechtigungsprüfung Teil der Vektorsuche ist und nicht ein
nachgeschalteter Filter, ist ein für einen Nutzer unzugänglicher Treffer nicht nur unterdrückt,
sondern nie geladen — Thomas' Anfrage nach einer internen Meldewesen-Dienstanweisung durchsucht diese
Bibliothek gar nicht erst, unabhängig davon, wie thematisch treffend ein Chunk daraus wäre. Weil
Thomas die Bibliothek über die Gruppe „Vertretung Meldewesen" lesen darf, stellt er die Frage dafür
in seinem Space „Kfz-Zulassung", dem sie nicht zugeordnet ist.

### Gruppen (ADR-0036, #1823, #2017)

`keycloak/realm-export.json` trägt drei Keycloak-Gruppen: „Bürgerbüro Rheinfurt" (alle fünf
Demo-Konten, `demo-admin` eingeschlossen — genau die Mindestgruppengröße der Suchdiagnose),
„Meldewesen" (Maria, Selin) und „Kfz-Zulassung" (Thomas). Seit #2017 kommen sie über den
**Verzeichnisabgleich** nach OPAA, nicht mehr über den Gruppen-Claim der Tokens: Der Realm-Export
führt dafür das Dienstkonto `opaa-directory` (vertraulicher Client ohne Anmeldefluss, Geheimnis
`RheinfurtVerzeichnis!2026` — ein offener Demo-Wert nur für lokale und CI-Stacks; eine erreichbare
Instanz bekommt ein eigenes, siehe „Realm-Änderungen in ein bestehendes Keycloak übertragen" —,
Rollen `view-users` und `query-groups` aus `realm-management`), und der Seed richtet am Anbieter „Verzeichnisdienst" ein
(Schritt 2 unten):

| Einstellung | Wert in der Demo |
|---|---|
| Gruppenmechanismus | Verzeichnisabgleich; der Gruppen-Claim ist leer (je Anbieter gibt es genau einen Mechanismus) |
| Verzeichniszugang | `KEYCLOAK`, Client `opaa-directory`, Adresse aus der JWK-Set-Adresse des Anbieters (im Compose-Stack `http://keycloak:8180`), Realm aus der Issuer-URI (`opaa`) |
| Intervall | 60 Minuten — eine Änderung in Keycloak ist spätestens nach einer Stunde in OPAA |

Die drei Gruppen erscheinen in der Gruppenverwaltung als Organisationseinheiten mit dem Anbieter
„Verzeichnisdienst" als Herkunft und ihrem Quellpfad (`/Meldewesen` usw.), unter **Administration →
Verzeichnisabgleich** steht die Karte des Anbieters mit Statuszeile, Trockenlauf und Lauf. Rechte,
Eigentum und Space-Mitgliedschaften tragen sie so (`provider_groups` und `owner_group` in
`demo/seed/profiles.py`). Nur Maria hält an „Leistungen Meldewesen & Ausweise" zusätzlich ein
eigenes Recht: Sie hat die Bibliothek für die Gruppe angelegt.

| Keycloak-Gruppe | Mitglieder | Recht an Bibliotheken | Space-Mitgliedschaft |
|---|---|---|---|
| Bürgerbüro Rheinfurt | alle fünf Demo-Konten | — | „Infotheke Bürgerbüro" (`MEMBER`) |
| Meldewesen | Maria, Selin | Eigentümerin von „Leistungen Meldewesen & Ausweise" (`MANAGER`) | „Meldewesen & Ausweise" (`MEMBER`) |
| Kfz-Zulassung | Thomas | `VIEWER` auf „Leistungen Kfz-Zulassung" | — |

Vorführen lässt sich damit der Weg einer Rechteänderung aus dem Verzeichnis: Thomas in Keycloak aus
„Kfz-Zulassung" nehmen, unter Administration → Verzeichnisabgleich „Trockenlauf" und „Lauf" anstoßen
— er liest die Kfz-Leistungen danach nicht mehr, ohne dass in OPAA ein Recht angefasst wurde.
Danach die Mitgliedschaft in Keycloak zurückgeben und erneut abgleichen, sonst fehlt Thomas die
Antwort auf Drehbuchfrage 4. Die
beiden kleineren Gruppen liegen unter der Mindestgruppengröße (Vorgabe 5) und zeigen in der
Suchdiagnose „kleine Gruppe" statt einer Zahl; „Bürgerbüro Rheinfurt" ist das eine wählbare
Rechteprofil oberhalb der Schwelle.

Die Gruppen-Mapper (Claim `groups`) auf `opaa-frontend` und `opaa-seed` bleiben im Export, werden für
diesen Anbieter aber nicht mehr ausgewertet. Eine Instanz, die vor #2017 im Token-Modus lief, behält
ihre drei Token-Gruppen mit eingefrorener Mitgliedschaft; sie stehen in der Gruppenverwaltung als
„Wird nicht mehr gepflegt" neben den gleichnamigen Organisationseinheiten und tragen keine Rechte.
Für die Umstellung einer bestehenden Instanz gilt deshalb: neu aufsetzen (siehe „Realm-Änderungen
in ein bestehendes Keycloak übertragen" unten).

Zusätzlich richtet der Seed (Schritt 6) die interne Gruppe **„Vertretung Meldewesen"** ein
(`demo/seed/profiles.py`, `GroupDef`): Maria ist ihre alleinige, benannte Verantwortliche (nicht das
Admin-Konto — `GroupService#createGroup` ernennt zunächst den Erstellenden, der Seed übergibt die
Verantwortung anschließend), Thomas ihr einziges Mitglied, und die Gruppe ist **zur Verwendung
freigegeben**. Über sie liest Thomas die Bibliothek „Interne Dienstanweisungen Meldewesen"
**ausschließlich** (kein eigenes `VIEWER`-Recht) und ist **ausschließlich** darüber Mitglied des
Space „Meldewesen & Ausweise" (`MEMBER`, keine eigene Mitgliedschaft) — beides zusammen die
Abnahmekriterien „ausschließlich über die Gruppe lesbar" bzw. „Space-Mitglied nur über die Gruppe".
Geschützte Gruppen liefert die Demo bewusst nicht (Umfang von #1823).

Seit #2013 kommen zwei weitere interne Gruppen hinzu, beide mit Andrea als alleiniger
Verantwortlicher (sie ist nicht zugleich Mitglied) und zur Verwendung freigegeben:

- **„Presseverteiler Bürgerbüro"** (Selin, Thomas) — ihre Mitglieder erhalten die
  Pressemitteilungen des Presseamts der Stadt. Die Gruppe trägt `VIEWER` auf „Pressemitteilungen Stadt Rheinfurt"; Selin und Thomas haben
  dort **kein eigenes Recht** mehr und lesen die Pressemitteilungen ausschließlich über die Gruppe.
  Maria und Andrea behalten ihren eigenen Grant. Die wirksame Rechtematrix bleibt damit unverändert.
- **„Sachbearbeitung Bürgerbüro"** (Maria, Selin, Thomas) — ist `MEMBER` des Space
  „Dienstbesprechung Bürgerbüro" (Eigentümerin Andrea) und bringt damit alle drei
  Sachbearbeitungskonten gleichzeitig in den Space, keines mit eigener Mitgliedschaft. Die Gruppe vermittelt kein
  Leserecht. Dem Space sind neben dem, was alle vier Fachkonten lesen, die Leistungen beider
  Sachgebiete zugeordnet; jedes Mitglied der Gruppe liest davon nur die eigenen.

Übersicht aller Gruppen und der mit „G" markierten Gruppenrechte:
[`../docs/features/demo-instance.md`, „Nutzer, Spaces und
Berechtigungen"](../docs/features/demo-instance.md#nutzer-spaces-und-berechtigungen). Der Seed
entzieht keine bestehenden Rechte: Auf einer vor #2013 eingespielten Instanz behalten Selin und Thomas
ihren eigenen Grant auf die Pressemitteilungen, bis die Demo neu aufgesetzt wird.

Die gleichnamige Gruppe „Meldewesen" im zweiten Realm `partner`
(`keycloak/realm-partner-export.json`, mit demselben Gruppen-Mapper auf `opaa-partner`) demonstriert
Herkunft statt Namen als Unterscheidungsmerkmal (ADR-0036, Entscheidung 2) und ist — wie die zweite
`maria.weber` oben — erst sichtbar, sobald die Systemverwaltung den Realm als weiteren Anbieter
angelegt hat und sich ein Konto darüber einmal anmeldet (der Demo-Smoke-Lauf tut beides): Ein neuer
Anbieter ist per Vorgabe **extern** gekennzeichnet, und `TokenGroupSynchronizer` legt eine
`IDENTITY_PROVIDER`-Gruppe erst beim ersten tatsächlichen Anmeldevorgang an — der Seed selbst kann
das nicht auslösen, weil `opaa-partner` (anders als `opaa-seed`) bewusst kein
`directAccessGrantsEnabled` trägt und nur den echten Authorization-Code-Ablauf im Browser zulässt.

Der Anbieter des Realms `partner` bleibt im Token-Modus: Seine „Meldewesen"-Gruppe hat deshalb
keinen Quellpfad, die gleichnamige Organisationseinheit des Anbieters „Verzeichnisdienst" den Pfad
`/Meldewesen`. Unterscheidbar sind beide in erster Linie über ihre Herkunft.

### Prompt-Bibliotheken (#2014)

Der Seed legt drei Prompt-Bibliotheken an (`demo/seed/profiles.py`, `PromptLibraryDef`). Zwei
gehören **Andrea Vogt**, eine **Thomas Klein** (seit #2103): Der Seed legt jede über die Sitzung
ihrer Eigentümerin bzw. ihres Eigentümers an, weil das Anlegerecht `CREATE_PROMPT_LIBRARY` an „Alle
Konten" ausgeliefert ist — die Systemverwaltung braucht es dafür nicht, und sie könnte die Prompts
ohne eigenes Recht auch nicht lesen.

| Prompt-Bibliothek | Eigentum | Reichweite | Zugeordnete Spaces |
|---|---|---|---|
| Textbausteine Bürgerbüro | Andrea | „Alle Konten" (`VIEWER`) | alle sechs angelegten Spaces und der Default-Space jedes Kontos |
| Vorlagen Amtsleitung | Andrea | nur Andrea | „Amtsleitung Bürgerbüro" und Andreas Default-Space |
| Arbeitshilfen Kfz-Zulassung | Thomas | nur Thomas | „Kfz-Zulassung" und Thomas' Default-Space |

| Slash-Befehl | Bibliothek | Variablen |
|---|---|---|
| `/antwort-buergeranfrage` | Textbausteine Bürgerbüro | Anliegen (mehrzeilig, vorbelegt), Frist (Auswahl), Tonfall (Auswahl) |
| `/gebuehrenauskunft-personalausweis` | Textbausteine Bürgerbüro | Altersgruppe (Auswahl), Anlass (Auswahl) |
| `/aktenvermerk` | Textbausteine Bürgerbüro | Aktenzeichen, Sachgebiet (Auswahl), Sachverhalt (mehrzeilig) |
| `/pressemitteilung-ratsbeschluss` | Textbausteine Bürgerbüro | Thema, Gremium (Auswahl), Sitzungsdatum (Datum, vorbelegt 21.04.2026) |
| `/wochenbericht-dezernentin` | Vorlagen Amtsleitung | Kalenderwoche, Schwerpunkt (mehrzeilig, optional) |
| `/stellungnahme-hauptausschuss` | Vorlagen Amtsleitung | Vorlage (vorbelegt: Bürgerkoffer, Vorlage 2024/019), Sitzungstermin (Datum, vorbelegt 14.05.2024), Grundhaltung (Auswahl) |
| `/auskunft-sonderkennzeichen` | Arbeitshilfen Kfz-Zulassung | Kennzeichenart (Auswahl, vorbelegt Saisonkennzeichen) |
| `/checkliste-umschreibung` | Arbeitshilfen Kfz-Zulassung | Umschreibung (Auswahl), Besonderheiten des Falls (mehrzeilig, optional, vorbelegt: geleastes Fahrzeug) |

Mehrere Prompts nutzen zusätzlich die Systemvariablen `{{CURRENT_DATE}}` und `{{USER_NAME}}`. Die
Vorbelegungen zielen auf Inhalte des Korpus — etwa die Niederschrift des Hauptausschusses vom
21.04.2026 zum mobilen Bürgerbüro oder die Hauptausschuss-Vorlage 2024/019 zum Bürgerkoffer —, sodass ein Prompt
ohne weiteres Tippen eine belegte Antwort liefert. Vorführen: als Maria im Space „Meldewesen &
Ausweise" `/` tippen, `/gebuehrenauskunft-personalausweis` wählen, Formular bestätigen und senden.
Als Thomas erscheinen die Textbausteine und im Space „Kfz-Zulassung" seine eigenen Arbeitshilfen,
die „Vorlagen Amtsleitung" aber weder dort noch im Katalog. Der vollständige Klickweg steht im
Drehbuch, Schritt C.

### Eigentum, Sichtbarkeit, Favoriten und Zuordnung (#2103)

Der Seed mischt, wem ein Asset gehört, ob es öffentlich oder geschlossen ist, wer es als Favorit
führt und welcher Space es zugeordnet hat — damit Katalog, Favoriten und die Zuordnung als harte
Grenze ([ADR-0039](../docs/decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md)) in der
Demo sichtbar werden. „Öffentlich" heißt: freigegeben an „Alle Konten". „Geschlossen" heißt: Es
liest nur, wer Eigentum oder eine Freigabe hat. Die Rechtematrix ändert sich dadurch nicht.
Begründung und Matrix: [`docs/features/demo-instance.md`, „Eigentum, Sichtbarkeit, Favoriten und
Zuordnung"](../docs/features/demo-instance.md#eigentum-sichtbarkeit-favoriten-und-zuordnung);
vorführen: Drehbuch, Schritt H.

**Wer besitzt was, was ist öffentlich bzw. geschlossen:**

| Asset | Art | Eigentum | Sichtbarkeit | Freigegeben an |
|---|---|---|---|---|
| Leistungen Meldewesen & Ausweise | Wissen | Keycloak-Gruppe „Meldewesen" (angelegt von Maria) | geschlossen | Andrea |
| Leistungen Kfz-Zulassung | Wissen | `demo-admin` | geschlossen | Andrea; Keycloak-Gruppe „Kfz-Zulassung" |
| Satzungen & Gebührenordnungen | Wissen | Andrea | öffentlich | Alle Konten |
| Pressemitteilungen Stadt Rheinfurt | Wissen | Andrea | geschlossen | Maria; Gruppe „Presseverteiler Bürgerbüro" |
| Interne Dienstanweisungen Meldewesen | Wissen | Maria | geschlossen | Selin, Andrea; Gruppe „Vertretung Meldewesen" |
| Ratsinformationen Stadt Rheinfurt | Wissen | `demo-admin` | öffentlich | Alle Konten |
| Formattest auf S3 | Wissen | `demo-admin` | geschlossen | niemand |
| Textbausteine Bürgerbüro | Prompts | Andrea | öffentlich | Alle Konten |
| Vorlagen Amtsleitung | Prompts | Andrea | geschlossen | niemand |
| Arbeitshilfen Kfz-Zulassung | Prompts | Thomas | geschlossen | niemand |

**Wer hat welche Favoriten:**

| Konto | Favoriten |
|---|---|
| `maria.weber` | Interne Dienstanweisungen Meldewesen, Leistungen Meldewesen & Ausweise, Satzungen & Gebührenordnungen, Textbausteine Bürgerbüro |
| `thomas.klein` | Leistungen Kfz-Zulassung, Ratsinformationen Stadt Rheinfurt, Arbeitshilfen Kfz-Zulassung |
| `andrea.vogt` | Pressemitteilungen Stadt Rheinfurt, Ratsinformationen Stadt Rheinfurt, Vorlagen Amtsleitung |
| `selin.kaya` | keine |
| `demo-admin` | keine |

**Welcher Space hat welche Zuordnung:**

| Space | Wissen | Prompts | Variante |
|---|---|---|---|
| Meldewesen & Ausweise | Leistungen Meldewesen & Ausweise, Satzungen, Pressemitteilungen, Interne Dienstanweisungen, Ratsinformationen | Textbausteine | nicht für alle Mitglieder lesbar (Thomas: Leistungen Meldewesen) |
| Maria Weber – persönlich | wie „Meldewesen & Ausweise" | Textbausteine | geschlossenes Wissen, vollständig lesbar |
| Kfz-Zulassung | Leistungen Kfz-Zulassung, Satzungen, Pressemitteilungen, Ratsinformationen | Textbausteine, Arbeitshilfen Kfz-Zulassung | geschlossenes Wissen und geschlossene Prompts, vollständig lesbar |
| Amtsleitung Bürgerbüro | alle sechs fachlichen | Textbausteine, Vorlagen Amtsleitung | geschlossenes Wissen und geschlossene Prompts, vollständig lesbar |
| Dienstbesprechung Bürgerbüro | Leistungen Meldewesen & Ausweise, Leistungen Kfz-Zulassung, Satzungen, Pressemitteilungen, Ratsinformationen | Textbausteine | nicht für alle Mitglieder lesbar (Maria, Selin: Kfz-Leistungen; Thomas: Leistungen Meldewesen) |
| Infotheke Bürgerbüro | Satzungen, Ratsinformationen | Textbausteine | nur öffentliches Wissen |

Einen bewusst leeren Space legt der Seed nicht an: Jeder Space der Demo soll passendes Wissen haben
(Epic #2070). Den Hinweis „kein Wissen zugeordnet" zeigt jeder neu angelegte Space (Drehbuch,
Schritt G).

**Seed-Weg:** Jede Bibliothek legt der Seed über die Sitzung ihrer Eigentümerin bzw. ihres
Eigentümers an und füllt, teilt und indiziert sie über dieselbe Sitzung. Die Gruppenbibliothek legt
Maria als Mitglied im Namen von „Meldewesen" an (`ownerType=GROUP`); die Gruppe hält damit `MANAGER`,
Maria als Anlegende `OWNER`. Die Freigabe an „Alle Konten" geht als `subjectType=ALL_ACCOUNTS` ohne
`subjectId`. Favoriten setzt jede Person über ihre eigene Sitzung
(`PUT /api/v1/assets/{assetType}/{assetId}/favorite`). Der Seed setzt nur und entfernt nie einen
Favoriten; ein zweiter Lauf setzt dieselben noch einmal, ohne etwas zu ändern.

**Bestehende Instanz:** Eine Instanz, deren Bibliotheken noch alle dem Admin-Konto gehören, lässt
sich nicht umstellen. Findet der Seed eine gleichnamige Bibliothek, die dem vorgesehenen Konto nicht
gehört, bricht er mit dem Hinweis ab, die Demo neu aufzusetzen.

### Vorbereitete Chats (#2071)

Der Space „Amtsleitung Bürgerbüro" von **Andrea Vogt** bekommt 81 Chats, wie sie nach einigen Wochen
Arbeit mit OPAA aussähen. Wofür: Chatliste, Chatsuche, Anheften, Archiv, Nachladen und lange
Verläufe lassen sich damit an einem realistischen Bestand vorführen und testen – ein leerer Space
zeigt davon nichts, und erst ein voller Space macht Layoutfehler der Chatliste sichtbar.

| Merkmal | In der Demo |
|---|---|
| Umfang | 80 Chats mit je 2–3 Runden, ein Chat mit 32 Runden („Jahresbericht Bürgerbüro 2026 vorbereiten") |
| Themen | Gebühren und Satzungen, Meldewesen und Ausweise, Kfz-Zulassung und Führerschein, interne Dienstanweisungen und Schulungen, Rats- und Pressethemen des Bürgerbüros |
| Zeitraum | verteilt über die acht Wochen vor dem Seed-Lauf, der jüngste vom Vortag |
| Angeheftet | 5 Chats, darunter der lange Verlauf |
| Archiviert | 11 Chats |
| Belege | jede Antwort mit Quellen zitiert echte Dokumente der sechs Bibliotheken, die dem Space zugeordnet sind; „Belege anzeigen" öffnet sie wie bei einer frisch erzeugten Antwort |

Die Verläufe sind **Daten, keine Live-Antworten**: Sie liegen unter
[`seed/chats/amtsleitung-buergerbuero/`](seed/chats/amtsleitung-buergerbuero/) – `set.json` nennt
Space, Eigentümerin und die zitierten Korpusdokumente, die übrigen Dateien die Chats nach Themen. Eine
Antwort zitiert mit `[[schluessel]]` (oder `[[schluessel#abschnitt]]`); der Seed setzt dafür beim
Einspielen die Dokument-ID der laufenden Instanz ein und schreibt die Fundstelle in der Form, in der
auch das Modell zitiert. Die Zeitpunkte sind relativ: `daysAgo` und `time` (Uhrzeit in Rheinfurt)
legen die erste Frage fest, die weiteren Runden folgen im Abstand weniger Minuten. Wer Chats ergänzt,
prüft sie mit `pytest demo/seed`: Die Tests verlangen unter anderem, dass jedes zitierte Dokument im
Korpus liegt und der Space die Bibliothek zugeordnet hat.

**Einspielweg:** Der Seed schreibt die Chats über `POST /api/v1/spaces/{spaceId}/chat-imports` mit
Andreas eigener Sitzung – sie wird Autorin, genau wie bei einem selbst begonnenen Chat. Die Route
existiert nur mit `OPAA_DEMO_CHAT_IMPORT_ENABLED=true` (Schritt 1 oben) und verlangt dieselbe
Space-Mitgliedschaft wie ein neuer Chat; jeder Beleg muss auf ein Dokument zeigen, das Andrea lesen
darf, und jede Fundstellenmarke im Antworttext auf einen Beleg derselben Runde mit dessen
Dateinamen – sonst lehnt das Backend den Chat mit `400` ab. Dateiname, Quellentyp und Metadaten
eines Belegs liest das Backend aus dem Dokument selbst, der Seed liefert nur die Dokument-ID; ein
eingespielter Beleg zeigt dieselben Felder wie ein frisch erzeugter, also neben Titel, Dokumentart
und Datum auch die Formatfelder (etwa Betreff einer Mail) und die Bibliotheksfelder mit
Zitierposition. Anheften und Archivieren laufen über die regulären Endpunkte.
Eine Gesprächsnotiz haben die eingespielten Chats nicht; sie entsteht erst mit der nächsten
gestellten Frage.

**Idempotenz:** Jeder Chat wird unter einem **Importschlüssel** eingespielt – seinem Titel, wie er in
der Datei steht. Den Schlüssel setzt nur der Import, eine Umbenennung lässt ihn stehen, und in der
Oberfläche erscheint er nicht. Vor dem Einspielen liest der Seed Andreas eingespielte Chats des Space
samt Schlüssel (`GET /api/v1/spaces/{spaceId}/chat-imports`, aktiv und archiviert). Gibt es zu einem
Schlüssel schon einen Chat, legt der Seed ihn nicht noch einmal an – auch wenn er inzwischen anders
heißt; das Backend weist einen zweiten Import unter demselben Schlüssel ohnehin mit `409` ab. Fehlt
einem solchen Chat eine Markierung, die die Datei verlangt (etwa weil er in einer Vorführung wieder
gelöst wurde), setzt der Seed sie erneut; Markierungen, die die Datei nicht verlangt, bleiben
unangetastet. Einen Chat, den eine besuchende Person selbst angelegt hat, hält der Seed nie für einen
vorbereiteten, auch nicht bei gleichem Titel, und markiert ihn nicht. Ein neuer Chat eines späteren
Laufs bekommt Zeitpunkte relativ zu diesem Lauf.

Chats, die vor #2082 eingespielt wurden, haben keinen Importschlüssel. Ein Seed-Lauf gegen eine
solche Instanz legt alle vorbereiteten Chats ein zweites Mal an; vorher die Demo neu aufsetzen oder
die alten Chats löschen.

---

## Demo weiterentwickeln

### Korpus neu erzeugen

```bash
cd demo/generator
pip install -r requirements.txt
python generate_corpus.py
```

Läuft erneut, wenn sich eine der sieben Bibliotheken inhaltlich ändern soll (z. B. weitere
Pressemitteilungen, andere Leistungsauswahl). Zwei Läufe erzeugen byte-identische Dateien; die
Prüfsumme steht in `corpus/MANIFEST.sha256`:

```bash
cd demo/corpus
sha256sum -c MANIFEST.sha256
```

Details zum Reproduktionsverfahren, den verwendeten Quellen und der Werkzeugwahl für PDF/DOCX/PPTX:
[`generator/README.md`](generator/README.md) und [`corpus/SOURCE.md`](corpus/SOURCE.md).

**Eine Abweichung vom „alles kommt aus dem Generator"-Bild**, dort ausführlich festgehalten: Die
Word-97-Datei der Bibliothek „Formattest auf S3" schreibt keine der gepinnten Bibliotheken, sie
entsteht einmalig über `generator/make_doc_fixture.py` mit LibreOffice und bleibt committet
(„Formate ohne Writer"). Alles andere — auch jeder Gebührenbetrag — kommt aus dem Lauf selbst und
braucht keine Nacharbeit von Hand (#1525).

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
   lässt. Trägt `demo-admin` danach noch nicht `SYSTEM_ADMIN`, meldet sich der Seed als lokales
   Notanker-Konto an (`POST /api/v1/auth/local/login`) und vergibt die Rolle über
   `POST /api/v1/admin/users/{id}/role`; gelingt das nicht, bricht der Lauf ab (siehe oben,
   `OPAA_INITIAL_ADMIN_EMAIL`/`OPAA_INITIAL_ADMIN_PASSWORD`).
2. **Identitätsanbieter: Verzeichnisabgleich** (nur im `demo`-Profil, ADR-0036 Entscheidungen 2
   und 3, siehe „Gruppen" oben) — am Anbieter „Verzeichnisdienst" leert der Seed einen gesetzten
   Gruppen-Claim (`PUT /api/v1/admin/oidc-providers/{id}`), hinterlegt den Verzeichniszugang
   (`PUT …/directory-connector` mit `opaa-directory` und dem Geheimnis aus
   `OPAA_DEMO_DIRECTORY_CLIENT_SECRET`/`--directory-client-secret`, ohne beides dem Demo-Wert; die
   Adresse leitet er aus der JWK-Set-Adresse des Anbieters ab), prüft ihn (`POST …/directory-connector/test`),
   schaltet den Abgleich mit 60 Minuten Intervall ein (`PUT …/directory-sync`) und stößt einen
   Lauf an (`POST …/directory-sync/run`). Jeder Lauf muss mit `APPLIED` enden, sonst bricht der
   Seed mit dem Ergebnis ab; ein `409` des gerade vom Zeitplan gestarteten Laufs wartet er ab.
   Gespeichert wird nur, was abweicht — ein hinterlegter Zugang, dessen Verbindungstest gelingt,
   bleibt stehen; scheitert sein Test, bricht der Seed ab und ersetzt ihn nur durch ein
   ausdrücklich übergebenes Geheimnis. Scheitert der Test eines neu hinterlegten Zugangs, fehlt dem
   Realm meist das Dienstkonto: siehe „Realm-Änderungen in ein bestehendes Keycloak übertragen"
   unten. Das `e2e`-Profil überspringt
   den Schritt — der dev-Betriebsmodus kennt keine Anbieterzeile (ADR-0036, Entscheidung 3).
3. **Spaces** gemäß `docs/features/demo-instance.md` — „Meldewesen & Ausweise" (Maria Weber; Selin
   Kaya kommt in Schritt 6 über die Keycloak-Gruppe „Meldewesen" hinzu), Marias eigener Space ohne
   weiteres Mitglied, „Kfz-Zulassung" (Thomas Klein), „Amtsleitung
   Bürgerbüro" (Andrea Vogt), „Dienstbesprechung Bürgerbüro" (Andrea Vogt; die Sachbearbeitung
   kommt erst in Schritt 6 über die Gruppe „Sachbearbeitung Bürgerbüro" hinzu), „Infotheke
   Bürgerbüro" (Selin Kaya; alle Konten kommen in Schritt 6 über die Keycloak-Gruppe „Bürgerbüro
   Rheinfurt" hinzu).
4. **Sieben Wissensbibliotheken**, jede über die Sitzung ihrer Eigentümerin bzw. ihres Eigentümers
   angelegt (`owner_key` in `profiles.py`, siehe „Eigentum, Sichtbarkeit, Favoriten und Zuordnung"
   oben); „Leistungen Meldewesen & Ausweise" legt Maria im Namen der Keycloak-Gruppe „Meldewesen" an
   (`ownerType=GROUP`, die Gruppe findet der Seed wie in Schritt 6). Gefunden wird eine Bibliothek
   per Name unter denen, deren `myRole` `OWNER` ist; eine gleichnamige fremde bricht den Lauf ab.
   Jede hat ihre eigene Quellkonfiguration
   (ADR-0018): drei `HTTP_DIRECTORY` gegen `demo-corpus`, ein `RSS_FEED` gegen
   `presse.stadt-rheinfurt.example`, ein `UPLOAD`, zwei `S3` gegen `objectstore` (Bucket
   `rheinfurt-archiv` mit Präfix `ratsinformationen/` sowie Bucket `formattest` ohne Präfix,
   Zugangsdaten und die S3-Einstellungen (`sourceSettings`) direkt aus `profiles.py`,
   [ADR-0027](../docs/decisions/0027-s3-konnektor.md)). „Formattest auf S3" ist die einzige
   Bibliothek ohne jede Freigabe und ohne Space-Zuordnung — sie bleibt beim anlegenden Admin-Konto.
5. **Freigaben und VIEWER-Rechte** exakt nach der Matrix aus `docs/features/demo-instance.md`,
   jeweils über die Sitzung der Eigentümerseite: die Freigabe an „Alle Konten" für Satzungen und
   Ratsinformationen, die eigenen Grants (die mit „G" und „K" markierten Gruppenrechte folgen in
   Schritt 6) sowie die 27
   Upload-Dokumente aus `demo/corpus/interne-dienstanweisungen-meldewesen/` — der Seed wartet nach
   dem Hochladen, bis kein Dokument mehr `PENDING` ist (Tika-Parsing und Embedding laufen asynchron,
   #434), und bricht bei `FAILED` mit der jeweiligen `errorMessage` ab. Das Upload-Verzeichnis wird
   rekursiv gelesen: Jedes Unterverzeichnis wird zum gleichnamigen Bibliotheksordner (`folderPath`
   relativ zur Bibliothekswurzel, die API legt fehlende Ordner selbst an). Die Dienstanweisungen
   liegen so in ihren Aktenplan-Ordnern (`01 Melderecht/02 Auskünfte und Übermittlungen/…`, Zuordnung
   in `generator/intern.py`, `AKTENPLAN`). Als „schon hochgeladen" gilt ein Dokument nur mit
   gleichem Ordnerpfad und gleichem Dateinamen; der Seed liest dafür den Ordnerbaum der Bibliothek
   von der Wurzel ab. Eine Instanz, die die Dokumente noch flach in der Wurzel führt, lässt sich
   nicht nachträglich umsortieren — die API lehnt denselben Inhalt in derselben Bibliothek mit 409
   ab, legt den Ordnerpfad aber vorher schon an. Der Seed erkennt diesen Fall deshalb vor dem
   ersten Upload und bricht mit dem Hinweis ab, die Demo neu aufzusetzen; es entsteht kein
   Ordner. Trifft ein Upload trotzdem auf 409 (derselbe Inhalt an anderer Stelle), bricht der Seed
   mit derselben Empfehlung ab; der eben angelegte Ordner bleibt dann leer zurück.
6. **Gruppen** (ADR-0036, `profiles.py`s `GroupDef`, siehe „Gruppen" oben) — je Gruppendefinition:
   anlegen oder per Namenssuche über `GET /api/v1/admin/groups` finden, die benannten
   Verantwortlichen ernennen und die automatische Erstverantwortung des Admin-Kontos wieder
   abgeben, Mitglieder aufnehmen, zur Verwendung freigeben, ihr `VIEWER`-Recht auf die
   konfigurierten Bibliotheken vergeben (`subjectType=GROUP`) und sie den konfigurierten Spaces als
   Mitglied hinzufügen (`subjectType=GROUP`, #1815). Danach dasselbe für die Keycloak-Gruppen
   (`ProviderGroupDef`): Der Seed legt sie nie an, sondern findet die Organisationseinheit, die der
   Abgleich aus Schritt 2 gebracht hat (Art `ORG_UNIT`, Anbieter „Verzeichnisdienst", nicht
   aufgelöst — eine gleichnamige, nicht mehr gepflegte Token-Gruppe ist es nicht), und vergibt ihr
   Leserecht und Space-Mitgliedschaft. Fehlt sie, bricht der Lauf mit dem Hinweis auf das
   Realm-Skript ab. Ein Gruppenrecht an einer Bibliothek vergibt die Sitzung ihrer Eigentümerseite.
7. **Space↔Bibliothek-Zuordnungen** gemäß den `library_names` der Space-Definitionen in
   `profiles.py` — ein Space durchsucht nur, was ihm zugeordnet ist: „Meldewesen & Ausweise" und
   „Maria Weber – persönlich" bekommen die fünf für das Sachgebiet lesbaren Bibliotheken zugeordnet,
   „Kfz-Zulassung" vier, „Amtsleitung Bürgerbüro" alle sechs fachlichen, „Dienstbesprechung
   Bürgerbüro" die drei für alle Fachkonten lesbaren und die Leistungen beider Sachgebiete,
   „Infotheke Bürgerbüro" die beiden öffentlichen („Formattest auf S3" steht in keinem dieser
   Spaces, siehe Schritt 7c). Die Zuordnung legt die Session des jeweiligen
   Space-Eigentümers an, denn `associateSpaceAsset` verlangt CURATOR oder höher im Space plus
   mindestens VIEWER auf der Bibliothek — beides hat der Eigentümer nach Schritt 6 (Thomas liest die
   Pressemitteilungen, die „Kfz-Zulassung" zugeordnet sind, erst über die Gruppe „Presseverteiler Bürgerbüro").

   **7b. Prompt-Bibliotheken** (`profiles.py`s `PromptLibraryDef`, siehe „Prompt-Bibliotheken" oben)
   — je Definition über die Sitzung der Eigentümerin anlegen (`POST /api/v1/prompt-libraries`) oder
   per Namens- und Eigentümersuche in `GET /api/v1/prompt-libraries` finden, die Freigaben vergeben
   (`POST /api/v1/assets/PROMPT_LIBRARY/{id}/grants`, für „Alle Konten" `subjectType=ALL_ACCOUNTS`
   ohne `subjectId`), fehlende Prompts per Namenssuche ergänzen
   (`POST /api/v1/prompt-libraries/{id}/prompts`; ein vorhandener Prompt bleibt unverändert) und die
   Bibliothek über die Sitzung des jeweiligen Space-Eigentümers zuordnen
   (`POST /api/v1/spaces/{id}/assets`, `assetType=PROMPT_LIBRARY`) — erst nach den Freigaben, weil
   die Zuordnung Leserecht des Space-Eigentümers verlangt. Das `e2e`-Profil hat keine
   Prompt-Bibliotheken.

   **7c. Persönliche Spaces** (`profiles.py`s `PersonalSpaceDef`) — den Default-Space „Meine
   Dokumente", den das Backend bei der ersten Anmeldung anlegt, findet der Seed über die Sitzung
   seines Kontos (`GET /api/v1/spaces`, `isDefault`) und ordnet ihm Wissen und Prompts zu:
   Maria und Selin die fünf Meldewesen-Bibliotheken, Thomas die vier der Kfz-Zulassung und als
   Vertretung die internen Dienstanweisungen Meldewesen samt seinen „Arbeitshilfen Kfz-Zulassung",
   Andrea alle
   sechs fachlichen samt „Vorlagen Amtsleitung", dem Demo-Admin „Formattest auf S3"; allen die
   Textbausteine. Erst nach 7b, weil die Zuordnung das Leserecht des Kontos verlangt.

   **7d. Favoriten** (`profiles.py`s `FavoritesDef`) — je Person über ihre eigene Sitzung
   (`PUT /api/v1/assets/{assetType}/{assetId}/favorite`, idempotent). Der Seed setzt nur, er
   entfernt keinen Favoriten.
8. **Indizierung je Bibliothek** über deren eigene Quellkonfiguration (nicht für die `UPLOAD`-Bibliothek
   — die hat keinen eigenen Lauf, ADR-0018, siehe Schritt 5) — der Seed wartet auf `COMPLETED` und
   bricht bei `documentsFailed > 0` ab. Für die beiden `S3`-Bibliotheken prüft er zusätzlich eine
   **Mindestzahl**: Ihr Bucket ist eine exakte Spiegelung eines Korpusverzeichnisses
   (`expected_documents_dir` in `profiles.py`), also muss der Lauf mindestens so viele Dokumente
   verarbeitet haben, wie dort Dateien liegen, Unterordner eingeschlossen. Die PDF-Anhänge der
   Versandmails in „Ratsinformationen" kommen beim ersten Lauf als eigene Dokumente hinzu, zählen
   aber nicht zur Mindestzahl: Ein erneuter Lauf überspringt die unveränderte Mail samt Anhängen
   und meldet nur die Mail. Ohne diese Prüfung meldete ein Lauf gegen einen noch
   nicht fertig befüllten Bucket „abgeschlossen" über eine leere Bibliothek — der Einmal-Schritt
   `objectstore-seed` muss vorher durch sein (Schritt 2 von „Demo nutzen" oben).
9. **Vorbereitete Chats** (nur im `demo`-Profil, siehe „Vorbereitete Chats" oben) — erst nach der
   Indizierung, weil die Belege auf die Dokumente der laufenden Instanz zeigen: Der Seed liest mit
   Andreas Sitzung die Dokumentlisten der zitierten Bibliotheken
   (`GET /api/v1/libraries/{id}/documents`, Ordnerbaum inklusive) und ordnet jede Korpusdatei genau
   einem Dokument zu – über den Dateinamen oder, bei
   den Pressemitteilungen, deren Adresse. Passt kein oder mehr als ein Dokument, bricht er ab, bevor
   ein Chat entsteht. Danach liest er Andreas eingespielte Chats des Space samt Importschlüssel
   (`GET /api/v1/spaces/{id}/chat-imports`, aktiv und archiviert), spielt die fehlenden über
   `POST /api/v1/spaces/{id}/chat-imports` ein und setzt fehlende Markierungen über
   `PUT /api/v1/chats/{id}/pin` bzw. `…/archive`. Antwortet eine der beiden Import-Routen mit `404`,
   ist `OPAA_DEMO_CHAT_IMPORT_ENABLED` im Backend nicht gesetzt; der Seed nennt die Variable. Das
   `e2e`-Profil hat keine vorbereiteten Chats.

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

**Idempotent:** Ein zweiter Lauf gegen dieselbe Instanz legt nichts doppelt an — Chats werden per
Importschlüssel erkannt (siehe „Vorbereitete Chats" oben), Spaces und
Bibliotheken werden vor dem Anlegen per Namenssuche geprüft (Spaces über die Session des jeweiligen
Eigentümers, da ein Space nur für seine eigenen Mitglieder sichtbar ist), Uploads werden anhand von
Dateiname und Status übersprungen (ein zuvor `FAILED`es Dokument wird dagegen erneut hochgeladen),
`upsertAssetGrant` ersetzt statt zu duplizieren, und `associateSpaceLibrary` liefert bei bereits
bestehender Zuordnung die vorhandene Assoziation unverändert zurück. Bricht der Seed beim `demo`-Profil
mit „Die Verarbeitung ist derzeit ausgelastet - bitte später erneut versuchen." ab (die 27
sequentiellen Uploads der internen Bibliothek können `uploadTaskExecutor`s Warteschlange füllen, siehe
„Installation mit einem Befehl", Schritt 1 oben), behebt genau diese Idempotenz das: Ein zweiter
`python seed.py --profile demo`-Lauf lädt die als `FAILED` markierten Dokumente erneut hoch, ohne
bereits erfolgreich indizierte Dokumente anzurühren.

**Ratenbegrenzung:** `RateLimitFilter` schlüsselt die Indizierungsauslösung nach Client-IP **und**
Bibliothek (`opaa.rate-limit.indexing`, Default 1 Anfrage/60s je IP+Bibliothek) sowie zusätzlich über
ein separates, IP-weites Gesamtkontingent (Default 5 Anfragen/60s). Die sechs Trigger des Seeds (einer
je Konnektor-Bibliothek, seit #1520 einer mehr) landen auf sechs verschiedenen Bibliotheken; das
je-Bibliothek-Kontingent greift dabei nie, das IP-weite kann es, sobald mehr als fünf Läufe innerhalb
einer Minute starten. Weil `seed.py` jeden Lauf bis `COMPLETED` abwartet, bevor er den nächsten
auslöst, ist das praktisch nur bei sehr schnellen Läufen (Testdoppel statt echtem Modell) der Fall —
und dann wartet `seed.py` bei HTTP 429 automatisch (`--rate-limit-wait-seconds`, Default 65s) und löst
erneut aus. Dasselbe gilt bei einem erneuten Lauf kurz nach einem vorherigen Versuch oder wenn mehrere
Seed-Läufe dieselbe IP teilen.

**Keycloak-Anmeldung des Seeds:** Das `demo`-Profil meldet sich über einen eigenen Client
`opaa-seed` (`keycloak/realm-export.json`, Resource Owner Password Grant, kein Client-Secret) an —
bewusst getrennt vom `opaa-frontend`-Client, dessen `directAccessGrantsEnabled` aus gutem Grund
`false` bleibt. **Dieser Client gehört vor jedem erreichbaren Deployment entfernt oder deaktiviert**
(`../docs/handbuch/deployment.md`, Härtungstabelle, Punkt 6) — er ist ein passwortbasierter Tokenweg
ohne Secret gegen jedes Realm-Konto und darf nicht dauerhaft scharf bleiben.

Der Client trägt dafür einen Audience-Mapper (`oidc-audience-mapper`,
`included.client.audience=opaa-frontend`): Das Backend nimmt ein Token nur an, wenn dessen `azp`
die `client_id` der Anbieterzeile nennt oder diese in `aud` steht
([ADR-0025](../docs/decisions/0025-mehrere-oidc-anbieter.md), Entscheidung 1). Keycloak setzt `azp`
immer auf den anfragenden Client, hier also `opaa-seed` — ohne den Mapper endet jeder API-Aufruf des
Seed-Laufs mit HTTP 401, obwohl der Tokenerwerb selbst erfolgreich war. Maßgeblich ist dabei die
`client_id` der Anbieterzeile in der Anbieterverwaltung, nicht `OPAA_OIDC_CLIENT_ID`: Die Variable
ist nur der Bootstrap-Wert des ersten Starts, danach führt die Datenbank. Lautet sie nicht
`opaa-frontend`, muss der Mapper auf denselben Wert zeigen. Ein
Keycloak, dessen Realm bereits importiert ist (eigenes Volume oder bestehende Datenbank), liest den
Export nicht erneut — dort wird der Mapper per `kcadm` nachgezogen
([„Härtung für erreichbare Deployments"](../docs/handbuch/deployment.md#härtung-für-erreichbare-deployments),
Absatz „Änderungen am Realm auf einer bereits laufenden Instanz").

Schlägt ein Seed-Lauf an dieser Stelle fehl, benennt `seed.py` die Ablehnung als solche und gibt den
`WWW-Authenticate`-Header des Backends aus — „nicht erreichbar" meldet es nur, wenn tatsächlich
nichts geantwortet hat.

### Realm-Änderungen in ein bestehendes Keycloak übertragen

Der lokale Compose-Stack importiert `keycloak/realm-export.json` bei jedem Start neu. Ein Keycloak mit
eigenem Volume oder eigener Datenbank — die öffentliche Instanz ist so eines — liest den Export nach
dem ersten Import **nie wieder**; Gruppen, Mitgliedschaften und Clients, die später in den Export
kamen, fehlen dort. `demo/keycloak/apply-realm-changes.sh` überträgt die Teile, von denen der Seed
abhängt, per `kcadm` in den bestehenden Realm:

- die Gruppen „Bürgerbüro Rheinfurt", „Meldewesen", „Kfz-Zulassung" und die Mitgliedschaften der
  fünf Demo-Konten darin,
- den Client `opaa-directory` (vertraulich, nur Dienstkonto) und die Rollen `view-users` und
  `query-groups` aus `realm-management`,
- den Audience-Mapper `opaa-frontend-audience` am Client `opaa-seed`.

Das Skript ist idempotent: Es prüft jeden Punkt und legt nur an, was fehlt; ein zweiter Lauf ändert
nichts. Es entfernt nichts und setzt kein Passwort — die Härtung der Konten auf einer erreichbaren
Instanz bleibt unberührt. **Das Geheimnis von `opaa-directory`** setzt es nur beim Anlegen des
Clients oder wenn `DIRECTORY_CLIENT_SECRET` ausdrücklich übergeben ist; ein vorhandener Client
behält sonst sein Geheimnis, ein rotiertes fällt also nie still auf den Demo-Wert zurück.

| Variable | Bedeutung | Vorgabe |
|---|---|---|
| `KC_CONTAINER` | Container, in dem `kcadm.sh` läuft (`docker exec`); ohne Angabe wird `kcadm.sh` aus dem `PATH` aufgerufen | — |
| `KC_SERVER` | Keycloak-Adresse aus Sicht von `kcadm`; auf der öffentlichen Instanz liegt Keycloak auch containerintern unter `/idp` | `http://localhost:8180` |
| `KC_ADMIN_USER`, `KC_ADMIN_PASSWORD` | Administrator des `master`-Realms | Pflicht |
| `KC_REALM` | Ziel-Realm | `opaa` |
| `DIRECTORY_CLIENT_SECRET` | Geheimnis von `opaa-directory`; muss zu dem passen, was der Seed in OPAA hinterlegt | ungesetzt: neuer Client bekommt den Demo-Wert `RheinfurtVerzeichnis!2026` (nur lokal/CI), vorhandener behält seins |

Der Seed nimmt dasselbe Geheimnis aus `OPAA_DEMO_DIRECTORY_CLIENT_SECRET` bzw.
`--directory-client-secret`. Ohne beides hinterlegt er den Demo-Wert — aber nur, solange in OPAA
noch kein Verzeichniszugang steht. Scheitert der Verbindungstest eines hinterlegten Zugangs (Keycloak
nicht erreichbar oder Geheimnis rotiert), bricht er ab, statt ihn zu überschreiben; ersetzt wird ein
hinterlegter Zugang nur durch ein ausdrücklich übergebenes Geheimnis.

**Lokaler Compose-Stack und CI:** Nichts zu tun — der Realm-Export bringt `opaa-directory` mit dem
Demo-Wert mit, und der Seed hinterlegt denselben. Das Skript ist dort nur nötig, um einen eigenen
Keycloak mit Volume nachzuziehen; der Container heißt dann `<Projektname>-keycloak-1`.

**Öffentlich erreichbare Instanz** (etwa opaa.ewerlin.com, deren Admin-API von außen erreichbar ist):
Der Demo-Wert steht im Repository und öffnet über `client_credentials` den ganzen Verzeichnisabzug.
Deshalb dort immer ein eigenes Geheimnis erzeugen, auf dem Server ablegen und an Skript **und** Seed
durchreichen:

```bash
# einmalig: Geheimnis erzeugen und nur für root lesbar ablegen
umask 077; openssl rand -base64 32 | tr -d '\n' > /srv/opaa/.directory-client-secret

# Realm nachziehen - setzt das Geheimnis auch an einem schon vorhandenen Client
KC_CONTAINER=opaa-keycloak \
KC_SERVER=http://localhost:8180/idp \
KC_ADMIN_USER=<Administrator des master-Realms> KC_ADMIN_PASSWORD=<sein Passwort> \
DIRECTORY_CLIENT_SECRET="$(cat /srv/opaa/.directory-client-secret)" \
  bash demo/keycloak/apply-realm-changes.sh

# Seed - hinterlegt dasselbe Geheimnis als Verzeichniszugang
OPAA_DEMO_DIRECTORY_CLIENT_SECRET="$(cat /srv/opaa/.directory-client-secret)" \
  python seed.py --profile demo --base-url https://opaa.ewerlin.com/api \
  --keycloak-url https://opaa.ewerlin.com/idp
```

Wer das Geheimnis später rotiert, erzeugt die Datei neu und lässt beide Aufrufe erneut laufen.

**Bestehende Instanz: neu aufsetzen, nicht nur nachziehen.** Der Seed entzieht nichts. Auf einer
Instanz, die vor der Umstellung gesät wurde, behalten Maria, Selin, Thomas und Andrea ihre eigenen
Grants auf Satzungen, Ratsinformationen und die Leistungsbibliotheken, und Selin bleibt einzeln
Mitglied von „Meldewesen & Ausweise"; die Spalte „K" der Matrix („ausschließlich über die
Keycloak-Gruppe") stimmt dort also nicht, und die Vorführung „Thomas aus ‚Kfz-Zulassung' nehmen"
ginge ins Leere. Außerdem blieben die alten Token-Gruppen als „Wird nicht mehr gepflegt" stehen.
Für die Umstellung deshalb: Skript ausführen, dann die OPAA-Datenbank zurücksetzen (Postgres-Volume
verwerfen; das Keycloak-Volume bleibt) und neu seeden.

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
- **Administration:** Zwei Konten tragen die Rolle `SYSTEM_ADMIN`: das lokale Notanker-Konto der
  Systemverwaltung (`OPAA_INITIAL_ADMIN_EMAIL`, seit ADR-0033 beim ersten Start angelegt; sein
  Passwort ist nach dem Seed versiegelt zu hinterlegen, nicht im Alltag zu benutzen) und das
  Administrationskonto `demo-admin`, dem der Seed die Rolle vergibt — nicht mehr ein persönliches
  Konto des Maintainers. Das Keycloak-Konto `demo-admin` samt Passwort stammt bereits aus
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
  ebenfalls ausschließlich auf `127.0.0.1`. Seit #1383 gehören `objectstore` und der Befüll-Schritt
  `objectstore-seed` ebenso dazu (zwei Bibliotheken sind `S3`-gespeist; ohne sie bricht der Seed beim
  Lauf dieser Bibliotheken ab) — samt `objectstore` in der Allowlist, und seit #1520 samt dem Volume
  `opaa-demo-objectstore-data` und dem `OPAA_UPLOAD_S3_*`-Block, ohne den die Originale der Instanz
  weiter auf dem Dateisystem liegen; ob die öffentliche Instanz das aufnimmt, ist eine
  Betreiber-Entscheidung nach dem Merge.
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
   Oberfläche** — für den Rheinfurt-Korpus die sechs konnektorgespeisten Bibliotheken vom Typ
   `HTTP_DIRECTORY`/`RSS_FEED`/`S3`, deren Quellkonfiguration bereits an der jeweiligen Bibliothek
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
OPAA_DEMO_DIRECTORY_CLIENT_SECRET="$(cat /srv/opaa/.directory-client-secret)" \
python seed.py --profile demo \
  --base-url https://opaa.ewerlin.com/api \
  --keycloak-url https://opaa.ewerlin.com/idp
```

Das Geheimnis des Verzeichnis-Dienstkontos `opaa-directory` ist dort ein eigener, auf dem Server
abgelegter Zufallswert, nie der dokumentierte Demo-Wert — Erzeugung und Weitergabe an Realm-Skript
und Seed: „Realm-Änderungen in ein bestehendes Keycloak übertragen" oben. Läuft der Seed von einer
Arbeitsstation, wird der Wert von dort übergeben.

Voraussetzung dafür ist der Keycloak-Client `opaa-seed` — siehe
[„Härtung für erreichbare Deployments"](../docs/handbuch/deployment.md#härtung-für-erreichbare-deployments),
Punkt 6. Auf der Instanz ist er im Normalbetrieb **deaktiviert**, sowohl in der Realm-Anpassung beim
Import als auch nachträglich per `kcadm`. Für einen Seed-Lauf wird er ausschließlich für dessen Dauer
aktiviert und unmittelbar danach wieder deaktiviert — kein dauerhaft scharfer, passwortbasierter
Tokenweg ohne Client-Secret auf einer erreichbaren Instanz.

**Vorbedingung für die vorbereiteten Chats (#2071):** Für den letzten Seed-Schritt läuft das
Backend der Instanz mit `OPAA_DEMO_CHAT_IMPORT_ENABLED=true` (siehe „Vorbereitete Chats" oben) –
**ausschließlich für die Dauer des Seed-Laufs**, genau wie beim Client `opaa-seed`: Variable vor dem
Lauf setzen und das Backend neu starten, unmittelbar danach die Variable entfernen und das Backend
erneut neu starten. Grund: Die Fach-Demokonten haben dokumentierte Passwörter und werden von allen
Besuchenden geteilt. Solange der Schalter an ist, kann jede angemeldete Person über die Route
beliebige, zurückdatierte „Antworten“ ohne Modell in die Chats dieser Konten schreiben, die die
nächsten Besuchenden in der vertrauten Oberfläche sehen. Die eingespielten Chats bleiben nach dem
Abschalten erhalten.

**Dritte Vorbedingung (seit ADR-0033, #1534):** Der Seed vergibt `SYSTEM_ADMIN` an `demo-admin` über
das lokale Notanker-Konto; dessen Passwort wird als `--local-admin-password` (oder
`OPAA_INITIAL_ADMIN_PASSWORD` in der Umgebung des Aufrufs) übergeben und ist auf der Instanz nach
dem Lauf über `OPAA_LOCAL_ADMIN_RESET=force` rotierbar. Trägt `demo-admin` die Rolle bereits, wird
das Konto nicht angefasst.

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

**Einmalig beim ersten Aufruf nach #1526:** Der Realm-Export vergibt seither feste Nutzer-IDs; die
bestehenden Konten der Instanz tragen noch die zufälligen aus ihrem letzten Import. Beim ersten
Deployment danach wechselt also jedes `sub` einmal, und jedes Demo-Konto bekäme beim nächsten Login
eine zweite, rechtelose Zeile. Dieser eine Lauf braucht deshalb den zurücksetzenden Schalter
(`down -v`) und anschließend einen vollständigen Seed; ab dann bleiben die Konten über jeden
Neustart hinweg dieselben.

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
  Vorführ-Drehbuch mit acht Fragen und acht Vorführschritten
- [`docs/handbuch/deployment.md`](../docs/handbuch/deployment.md), Abschnitt „Härtung für erreichbare
  Deployments" — zwingend vor jedem über `localhost` hinaus erreichbaren Rollout dieser Demo,
  einschließlich des dort separat behandelten `opaa-seed`-Clients
- Smoke-Test gegen das `demo`-Profil — #232 (`e2e/demo-smoke/`, `pnpm run test:demo-smoke` in `e2e/`,
  siehe [`e2e/README.md`, „Demo-Smoke (#232)"](../e2e/README.md#demo-smoke-232))
- [`docs/features/search-quality-evaluation.md`](../docs/features/search-quality-evaluation.md),
  Abschnitt „Öffentliche Demo" — der frühere Superhelden-Korpus, durch dieses Konzept abgelöst
- Rollout auf einen erreichbaren Host — #230
