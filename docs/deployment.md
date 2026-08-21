# Deployment

## Deployment aus vorgebauten Images (GHCR)

Für Zielsysteme, auf denen nicht aus dem Quellcode gebaut werden soll, veröffentlicht CI bei jedem Push auf `main` fertige Container-Images:

| Image | Tags |
|-------|------|
| `ghcr.io/criew/opaa-backend` | `main`, `sha-<commit>` |
| `ghcr.io/criew/opaa-frontend` | `main`, `sha-<commit>` |

Auf dem Zielsystem wird kein Repository-Checkout benötigt — es genügt eine `docker-compose.yml`, die `image:` statt `build:` verwendet:

```yaml
services:
  backend:
    image: ghcr.io/criew/opaa-backend:main
  frontend:
    image: ghcr.io/criew/opaa-frontend:main
```

Aktualisieren auf den neuesten `main`-Stand:

```bash
docker compose pull && docker compose up -d
```

`main` folgt dem jeweils letzten Stand; für reproduzierbare Deployments stattdessen einen `sha-<commit>`-Tag pinnen.

## Öffentliche Testinstanz

Unter **https://opaa.ewerlin.com** betreibt der Maintainer eine öffentliche **Test-/Demo-Instanz** von OPAA. Es handelt sich ausdrücklich nicht um einen Produktivbetrieb — es gelten keine Verfügbarkeits- oder Datenerhaltungsgarantien.

Seit dem 21.08.2026 ist die Instanz die Demo-Instanz **„Stadt Rheinfurt"** (#230, Epic #708): Der Stack wurde per Reset neu aufgesetzt (frische Datenbank, frischer Keycloak-Realm-Import mit den Demo-Konten) und mit dem Rheinfurt-Korpus samt Seed-Profil `demo` befüllt. Konzept, Nutzerkonten und Drehbuch stehen in [`demo-walkthrough.md`](demo-walkthrough.md) und [`features/demo-instance.md`](features/demo-instance.md); dieser Abschnitt beschreibt nur die instanzspezifischen Betriebsdetails, die dort nicht stehen.

- **Betreiber:** Der Maintainer (`criew`), auf privater VPS-Infrastruktur außerhalb dieses Repositorys.
- **Zweck:** Öffentlich erreichbare Vorführinstanz der Demo „Stadt Rheinfurt" auf dem aktuellen `main`-Stand.
- **Zugriff:** Die Instanz läuft im Auth-Modus `oidc` hinter Keycloak (siehe [Authentifizierung](#authentifizierung)). Der Zugang ist bewusst account-gebunden — ein anonymer Zugang oder Gastzugang ist **nicht** vorgesehen; jede Nutzung erfordert eine Anmeldung mit einem der Rheinfurt-Demo-Konten (siehe [„Nutzerkonten"](demo-walkthrough.md#nutzerkonten)). Eine Konsequenz dieser Festlegung: Inhalte auf der Instanz — der Rheinfurt-Korpus — sind nur für angemeldete Nutzer sichtbar, nicht öffentlich ohne Anmeldung einsehbar.
- **Administration:** Genau ein Konto trägt die Rolle `SYSTEM_ADMIN` — `OPAA_INITIAL_ADMIN_EMAIL` zeigt auf `admin@stadt-rheinfurt.example`, das vom Seed angelegte Administrationskonto `demo-admin`, nicht mehr auf ein persönliches Konto des Maintainers. Sein Passwort ist nach jedem Seed-Lauf bewusst rotiert (siehe „Seed-Verfahren" unten) und weicht deshalb vom in `demo-walkthrough.md` dokumentierten Demo-Passwort ab; die vier Fach-Demokonten behalten dieses dokumentierte Passwort unverändert. Alle administrativen Vorgänge auf der Instanz führt `demo-admin` über den Admin-Bereich der Oberfläche aus. Weitere Konten mit dieser Rolle gibt es derzeit nicht.
- **Netzwerk:** Alle Container-Ports binden ausschließlich auf `127.0.0.1`. Nach außen führt ausschließlich ein nginx auf dem Host, der TLS terminiert und weiterleitet. Keycloak ist unter dem Pfad **`/idp`** eingehängt — ausdrücklich **nicht** unter `/auth`, weil das Frontend `/auth/callback` selbst als OIDC-Redirect verwendet und die beiden sich sonst überlagern. Dieser Host-nginx braucht **zusätzlich** zum `client_max_body_size` im Frontend-Container-nginx (siehe [`OPAA_UPLOAD_MAX_FILE_SIZE`](#alle-umgebungsvariablen)) ein ausreichendes eigenes `client_max_body_size` — sein Default liegt ebenfalls bei nur 1 MB und würde Uploads sonst schon vor dem Frontend-Container abweisen. Zusätzlich zu `postgres`/`backend`/`frontend`/`keycloak` laufen in der Instanz-Compose zwei schlanke httpd-Container, die den Rheinfurt-Korpus ausliefern — einer für die drei `HTTP_DIRECTORY`-Bibliotheken, einer unter dem Netzwerk-Alias `presse.stadt-rheinfurt.example` für die `RSS_FEED`-Bibliothek der Pressestelle (siehe „Korpus einspielen und indizieren" unten). Beide binden ihre Ports ebenfalls ausschließlich auf `127.0.0.1`.
- **Betriebsart:** Die Instanz läuft ausschließlich aus vorgebauten GHCR-Images (`ghcr.io/criew/opaa-backend:main`, `ghcr.io/criew/opaa-frontend:main`). Auf dem Server gibt es **keinen Repository-Checkout** und keinen Build — nur eine `docker-compose.yml`, die `image:` statt `build:` verwendet.
- **Daten:** Es dürfen dort **keine personenbezogenen, vertraulichen oder produktiven Organisationsdaten** abgelegt werden. Die Instanz ist ausschließlich für Demo- und Testzwecke mit dem synthetischen Rheinfurt-Korpus vorgesehen.
- **Frontend-Modus:** `OPAA_DEMO_MODE=true` ist gesetzt — der Quellen- und Demo-Hinweis in der Fußzeile der Oberfläche ist damit sichtbar (siehe `OPAA_DEMO_MODE` in [„Alle Umgebungsvariablen"](#alle-umgebungsvariablen)).

#### Modellkonfiguration der Instanz

Hier ist eine Verwechslung angelegt, die bereits mehrfach zu falschen Aussagen geführt hat und deshalb ausdrücklich benannt wird:

| | Anbieter | Modell | Anmerkung |
|---|---|---|---|
| **Chat** | Anthropic | `claude-haiku-4-5` | Angebunden über Anthropics **OpenAI-kompatible Schicht**. `OPAA_AI_CHAT_PROVIDER` steht deshalb formal auf `openai`; nur `OPAA_OPENAI_BASE_URL`, der API-Schlüssel und `OPAA_OPENAI_CHAT_MODEL` zeigen auf Anthropic. |
| **Embedding** | Ollama, lokal | `nomic-embed-text` | 768 Dimensionen, entsprechend `OPAA_PGVECTOR_DIMENSIONS=768` statt des Stack-Defaults 1536. |

Drei Punkte dazu:

- **Der Wert `openai` in `OPAA_AI_CHAT_PROVIDER` bezeichnet hier das Protokoll, nicht den Anbieter.** Wer ihn als Anbieterangabe liest, kommt zu einem falschen Ergebnis — genau das ist in der Vergangenheit passiert.
- **Die Aufteilung Chat bei Anthropic, Embedding lokal ist dauerhaft, nicht provisorisch.** Anthropic bietet keine Embeddings-API an; ein einheitlicher Anbieter für beides ist mit dieser Wahl gar nicht möglich.
- Anthropic bezeichnet die OpenAI-kompatible Schicht ausdrücklich als Werkzeug zum Testen und Vergleichen, nicht als produktionsreifen Zugang. Für eine Testinstanz ist das angemessen; für einen Dauerbetrieb wäre die native Anbindung zu wählen.

**Kostenseite:** Token-Kosten entstehen ausschließlich beim Chat. Die Einbettung läuft lokal über Ollama und kostet nichts — eine Neuindizierung des Korpus ist deshalb kostenlos, unabhängig von seiner Größe. Eine grobe Messung im laufenden Betrieb der Rheinfurt-Demo: rund 5.000–7.000 Input- und rund 300 Output-Token je Chat-Frage, macht die Kosten pro Anfrage zu deutlich unter einem US-Cent. Das harte monatliche Ausgabenlimit für den verwendeten API-Schlüssel ist **in der Anthropic-Console hinterlegt** (Kontobereich für Nutzungslimits), nicht in OPAA selbst — OPAA kennt kein eigenes Budget-Limit für den Chat-Anbieter.

Die Rate-Limit-Werte der Instanz sind hier nicht festgehalten.

### Korpus einspielen und indizieren

Der Rheinfurt-Korpus liegt **nicht** über einen Repository-Checkout auf dem Server (siehe „Betriebsart" oben), sondern wird als fertiges Verzeichnispaar übertragen: `demo/corpus/` (die Dokumente selbst) und `demo/webserver/` (die beiden schlanken httpd-Container, die sie ausliefern, siehe „Netzwerk" oben).

1. Beide Verzeichnisse vom Arbeitsrechner auf den Server übertragen, per `rsync` oder `scp`:

   ```bash
   rsync -av --delete ./demo/corpus/ <benutzer>@<host>:<korpusverzeichnis>/
   rsync -av --delete ./demo/webserver/ <benutzer>@<host>:<webserververzeichnis>/
   ```

   > **Bewusst ohne konkrete Angaben:** `criew/opaa` ist ein öffentliches Repository. Host, Benutzername und die Pfade auf dem Server stehen deshalb nicht hier, sondern in der Betriebsdokumentation des Maintainers. Wer den Rollout ausführen soll, bekommt sie von ihm. Beschrieben ist hier das Verfahren, nicht die Belegung.

2. Die beiden httpd-Container binden das jeweilige Verzeichnis als Bind-Mount ein; ein Neustart des Backends ist für eine Korpus-Aktualisierung nicht nötig. Ob der jeweilige httpd-Container selbst neu erstellt werden muss, damit er neue Dateien ausliefert, hängt von seiner Bind-Mount-Konfiguration in der Instanz-Compose ab.
3. Damit das Backend `demo-corpus` und `presse.stadt-rheinfurt.example` als Indizierungsziel akzeptiert, steht in der Instanz-Konfiguration `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST=demo-corpus,presse.stadt-rheinfurt.example` — ohne diesen Eintrag lehnt die Zielprüfung (#267, siehe [„Sicherheitshinweis"](#sicherheitshinweis-post-apiv1librarieslibraryidindexing-ist-von-außen-erreichbar) oben) beide internen Hostnamen ab.
4. Die Indizierung löst aus, wer mindestens `EDITOR` auf der Zielbibliothek hält (ADR-0018; `SYSTEM_ADMIN` ist dafür seit #478 nicht mehr erforderlich), über den **Admin-Bereich der Oberfläche** — für den Rheinfurt-Korpus die vier konnektorgespeisten Bibliotheken vom Typ `HTTP_DIRECTORY`/`RSS_FEED`, deren Quellkonfiguration bereits an der jeweiligen Bibliothek gespeichert ist (seit #478 nicht mehr Teil des Anstoß-Requests).
5. Der Fortschritt ist im Admin-Bereich sichtbar (dahinter `GET /api/v1/libraries/{libraryId}/indexing/status`).

Unveränderte Dateien werden anhand ihrer SHA-256-Prüfsumme übersprungen und ihr `documents`-Datensatz bleibt bei Status `INDEXED`; ein erneuter Lauf über denselben Bestand — etwa nach einer Korpus-Aktualisierung, bei der nur ein Teil der Dateien sich geändert hat — verarbeitet deshalb ausschließlich die geänderten oder neuen Dateien und ist gefahrlos wiederholbar.

### Seed- und `opaa-seed`-Verfahren

Der (erneute) Seed der Instanz läuft im Profil `demo` von einer Arbeitsstation gegen die öffentliche API und Keycloak, nicht auf dem Server selbst:

```bash
python seed.py --profile demo \
  --base-url https://opaa.ewerlin.com/api \
  --keycloak-url https://opaa.ewerlin.com/idp
```

Voraussetzung dafür ist der Keycloak-Client `opaa-seed` — siehe [„Härtung für erreichbare
Deployments"](#härtung-für-erreichbare-deployments), Punkt 6 unten. Auf der Instanz ist er im
Normalbetrieb **deaktiviert**, sowohl in der Realm-Anpassung beim Import als auch nachträglich per
`kcadm`. Für einen Seed-Lauf wird er ausschließlich für dessen Dauer aktiviert und unmittelbar danach
wieder deaktiviert — kein dauerhaft scharfer, passwortbasierter Tokenweg ohne Client-Secret auf einer
erreichbaren Instanz.

Nach jedem Seed-Lauf wird das Passwort von `demo-admin` auf einen serverseitig verwahrten
Zufallswert rotiert (siehe „Administration" oben); die vier Fach-Demokonten (`maria.weber`,
`selin.kaya`, `thomas.klein`, `andrea.vogt`) behalten das in `demo-walkthrough.md` dokumentierte
Demo-Passwort unverändert — sie sind fachliche Vorführkonten ohne administrative Rechte.

### Aktualisierung auf einen neuen `main`-Stand

Der Workflow [`publish-images.yml`](../.github/workflows/publish-images.yml) baut bei jedem Push auf `main` neue `ghcr.io/criew/opaa-backend`- und `ghcr.io/criew/opaa-frontend`-Images und veröffentlicht sie mit den Tags `main` und `sha-<commit>` in der GHCR-Registry (siehe [Deployment aus vorgebauten Images](#deployment-aus-vorgebauten-images-ghcr) oben).

Auf dem Server liegt ein **Deployment-Skript**, das genau das tut: die aktuellen Images ziehen und den Stack auf den neuen Stand bringen. Es kennt zusätzlich einen Schalter, der auch die Volumes verwirft — damit ist die Datenbank und mit ihr der gesamte Index weg. Dieser Schalter ist deshalb kein Aktualisierungs-, sondern ein Neuaufsetzschritt; danach ist zwingend eine vollständige Neuindizierung nötig.

Ein **Cron-Job ruft dieses Skript täglich um 2 Uhr morgens auf** — ohne den zurücksetzenden Schalter, die Daten bleiben also erhalten. Die Ausgabe der Läufe wird protokolliert und wöchentlich rotiert. Die Instanz folgt dem `main`-Stand damit mit höchstens einem Tag Verzug; ein Push auf `main` erscheint nicht sofort, sondern beim nächsten nächtlichen Lauf. Wer schneller sein will, ruft das Skript von Hand auf.

Ohne das Skript entspricht der Ablauf diesen Schritten, ausgeführt im Verzeichnis mit der `docker-compose.yml` der Instanz:

```bash
docker compose pull          # neue Images aus GHCR holen
docker compose up -d         # Container mit den neuen Images neu erstellen
docker image prune -f        # optional: verdrängte Images aufräumen
```

`docker compose up -d` ersetzt nur die Container, deren Image sich geändert hat; die übrigen laufen weiter. Ein `docker compose restart` genügt **nicht** — es verwendet den vorhandenen Container samt altem Image erneut.

Zustand danach prüfen:

```bash
docker compose ps
docker compose logs -f backend
```

> **Service- statt Containernamen verwenden.** Alle Befehle oben sprechen den **Servicenamen** aus der Compose-Datei an (`backend`, `frontend`, `postgres`), nicht einen Containernamen. Das ist der robustere Weg: Wie die Container tatsächlich heißen, hängt davon ab, ob die jeweilige Compose-Datei `container_name` setzt und wie das Compose-Projekt heißt — beides unterscheidet sich zwischen der Testinstanz, lokalen Entwicklungsstacks und der E2E-Suite. `docker compose logs backend` funktioniert in allen dreien, `docker logs <name>` nur in einem.

#### Was ein Update mit dem Index macht

Kurz: Ein Update über `docker compose pull` + `docker compose up -d` **gefährdet den Index nicht** und macht **keine Neuindizierung erforderlich**. Im Einzelnen:

- **Der Korpus** liegt in einem Bind-Mount auf dem Host und ist von Container-Neustarts unberührt.
- **Der Index** liegt in PostgreSQL, dessen Daten im benannten Volume `opaa-postgres-data` liegen. Das Volume überlebt das Neuerstellen der Container; nur `docker compose down -v` löscht es.
- **Liquibase** wendet beim Backend-Start ausschließlich noch nicht angewendete Changesets vorwärts an. Mit einer Ausnahme (siehe unten) löscht keines der Changesets Dokument- oder Vektordaten — die `dropTable`-Anweisungen in `db/changelog/changes/` stehen ausnahmslos in `rollback`-Blöcken und laufen im Normalbetrieb nie.
- **Die Vektortabelle** wird nicht von Liquibase, sondern von Spring AI selbst angelegt (`spring.ai.vectorstore.pgvector.initialize-schema: true`). Sie wird nur erzeugt, wenn sie fehlt, und bei einem Update nicht verändert.

> **Der Vektorspeicher ist nicht wählbar.** OPAA speichert Vektoren in PostgreSQL mit pgvector; das ist der einzige unterstützte Vektorspeicher. Der Zugriff läuft zwar über eine portable Schnittstelle von Spring AI, ein Wechsel wird aber nicht unterstützt, nicht geprüft und nicht dokumentiert. Begründung: [Daten-Indizierung & RAG](./features/data-indexing-rag.md#der-vektorspeicher-postgresql-mit-pgvector-und-sonst-keiner).

> **Ausnahme: Migration `031-delete-system-library` (#521).** Diese Migration löscht bewusst Daten — die früher automatisch angelegte, nur für System-Admins lesbare System-Bibliothek samt ihrer Dokumente, Vektorspeicher-Chunks, Indizierungsaufträge und Grants. Es ist die erste und bislang einzige datenvernichtende Migration im Projekt; ihr Rollback ist bewusst ein No-op (die entfernten Zeilen ließen sich nicht von danach regulär geschriebenen unterscheiden). **Vor dem Update auf einen Stand mit dieser Migration einen Datenbank-Dump ziehen**, wer den Inhalt der System-Bibliothek noch braucht. Dateien, die ein Dokument der System-Bibliothek einst unter `opaa.upload.storage-path` abgelegt hatte, räumt die Migration nicht mit auf — nur die Datenbankzeilen verschwinden, verwaiste Dateien bleiben auf der Platte liegen und müssen bei Bedarf von Hand entfernt werden.

Eine Neuindizierung wird erst durch Änderungen nötig, die nichts mit dem Image-Update zu tun haben:

| Auslöser | Folge |
|---|---|
| Embedding-Modell oder -Anbieter gewechselt | Bestehende Vektoren stammen aus einem anderen Modell und sind nicht mehr vergleichbar — vollständige Neuindizierung nötig. **`OPAA_PGVECTOR_DIMENSIONS` muss mitgezogen werden**, sonst passt die Vektorbreite nicht zum neuen Modell |
| `OPAA_PGVECTOR_DIMENSIONS` geändert | Passt nicht mehr zur bestehenden Vektortabelle — die Datenbank muss zurückgesetzt und der Korpus neu indiziert werden |
| `docker compose down -v` bzw. das Deployment-Skript mit zurücksetzendem Schalter ausgeführt | Datenbank inklusive Index ist weg — vollständige Neuindizierung nötig |
| PostgreSQL-Hauptversion gewechselt (Image-Tag von `pg18` auf eine höhere Version) | Das Datenverzeichnis im Volume ist nicht aufwärtskompatibel; ein solcher Wechsel ist ein eigener Migrationsvorgang, kein `docker compose pull` |

Auf der Testinstanz sind `nomic-embed-text` und `OPAA_PGVECTOR_DIMENSIONS=768` fest aneinander gekoppelt: Wer das Embedding-Modell wechselt, muss beide Werte gemeinsam ändern und die Datenbank zurücksetzen. Ein Wechsel des **Chat**-Modells berührt den Index dagegen nicht — Chat und Einbettung sind auf der Instanz ohnehin getrennte Anbieter.

> **Falle bei einer Neuindizierung:** OPAA überspringt Dateien, deren SHA-256-Prüfsumme unverändert ist **und** deren Datensatz in der Tabelle `documents` den Status `INDEXED` trägt. Wird die Vektortabelle geleert, ohne auch `documents` zu bereinigen, meldet ein neuer Lauf lauter übersprungene Dateien und der Index bleibt leer. Beide Tabellen liegen in derselben Datenbank — wer den Index verwirft, muss `documents` mitverwerfen.

### Sicherheitshinweis: `POST /api/v1/libraries/{libraryId}/indexing` ist von außen erreichbar

Der Endpunkt ist auf der Testinstanz aus dem Internet erreichbar. Dass alle Container-Ports nur auf `127.0.0.1` binden, ändert daran nichts — der nach außen gerichtete nginx reicht die API-Pfade durch, und der Indizierungspfad ist davon nicht ausgenommen. Die Bindung auf `127.0.0.1` verhindert lediglich, dass jemand die Container unter Umgehung des nginx direkt anspricht.

Er ist durch Keycloak authentifiziert und verlangt mindestens die Rolle `EDITOR` auf der Zielbibliothek (`DocumentIndexingService#requireEditableLibrary`); die frühere, zusätzliche `SYSTEM_ADMIN`-Schranke des alten `/api/v1/indexing/trigger`-Endpunkts ist mit #478/ADR-0018 bewusst entfallen — ein Anstoß-Knopf, den nur die Systemverwaltung drücken darf, wäre für jeden anderen Bibliothekseigentümer tot. Zusätzlich greift das Rate Limiting mit einem eigenen, engen Kontingent für diesen Pfad (`OPAA_RATE_LIMIT_INDEXING_*`, standardmäßig eine Anfrage pro IP und Minute).

Wer eine Bibliothek vom Typ `HTTP_DIRECTORY` oder `RSS_FEED` anlegt, bestimmt bei der Anlage deren `sourceUrl` — eine beliebige Adresse, die der Server bei jedem Lauf abruft und crawlt. Das schließt Ziele ein, die nur aus dem Netz des VPS erreichbar sind und nicht aus dem Internet. Fachlich ist das serverseitige Anfragefälschung (Server-Side Request Forgery, SSRF) — hier allerdings als **Eigenschaft der Funktion**, nicht als Fehler: Der Endpunkt existiert genau dafür, entfernte Dokumentenquellen zu erschließen, und er verlangt Anmeldung plus mindestens Bearbeitungsrecht auf der Bibliothek.

**Ist-Zustand der Einschränkungen** (Stand dieser Datei, geprüft an `KnowledgeLibraryService`, `UrlIndexingExecutor`, `RssFeedIndexingExecutor`, `AutoindexCrawlerService` und `TargetAddressValidator`, #267):

- Das Schema wird von OPAA selbst geprüft (nicht mehr nur als Nebenwirkung des `java.net.http.HttpClient`, der ohnehin nur `http`/`https` akzeptiert): Ein anderes Schema wird mit einer deutschen Meldung abgelehnt, bevor überhaupt eine Verbindung versucht wird.
- Eine **Blockliste privater und lokaler Adressbereiche ist aktiv** (`TargetAddressValidator`, standardmäßig eingeschaltet): Loopback (`127.0.0.0/8`, `::1`), Link-Local einschließlich der Cloud-Metadaten-Adresse `169.254.169.254` (`169.254.0.0/16`, `fe80::/10`), die privaten IPv4-Bereiche (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), Carrier-Grade NAT (`100.64.0.0/10`), reserviert einschließlich Broadcast (`240.0.0.0/4`, `255.255.255.255`), IETF Protocol Assignments (`192.0.0.0/24`), Benchmarking (`198.18.0.0/15`), IPv6 Unique-Local (`fc00::/7`), NAT64 (`64:ff9b::/96`) sowie IPv4-in-IPv6-eingebettete Adressen — sowohl das aktuelle `::ffff:a.b.c.d` (IPv4-mapped) als auch das veraltete `::a.b.c.d` (IPv4-compatible) — werden abgelehnt, geprüft anhand des eingebetteten IPv4-Werts. Geprüft wird durchgehend die **aufgelöste** IP-Adresse, nicht der Hostname. Die Prüfung greift vor dem ersten Abruf **und** nach jeder Weiterleitung, damit ein Redirect eine erlaubte Startadresse nicht auf eine gesperrte Adresse umlenken kann. Abschaltbar (`OPAA_INDEXING_TARGET_VALIDATION_ENABLED=false`) oder um konkrete Hostnamen erweiterbar (`OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST`) für Betriebe mit legitimen internen Quellen — siehe die Tabelle unten.
- **DNS-Rebinding bleibt eine grundsätzliche, dokumentierte Grenze:** Die Auflösung, die geprüft wird, und die Auflösung, mit der `HttpClient` tatsächlich verbindet, sind zwei getrennte Vorgänge — ein Resolver, der zwischen beiden eine andere Antwort gibt, wird von dieser Prüfung nicht erkannt. Der JDK-`HttpClient` bietet keinen unterstützten Weg, eine Verbindung auf eine bereits geprüfte Adresse festzulegen. Das ist Härtung gegen naheliegende Fehlgriffe und Missbrauch, keine vollständige Garantie — genau der Umfang, den #267 selbst für sich beansprucht.
- Weiterleitungen werden gefolgt, aber nicht über `HttpClient.Redirect.NORMAL`: Seit #538 steht der `HttpClient` auf `Redirect.NEVER`, jede Weiterleitung wird manuell verfolgt und dabei gegen den ursprünglichen Origin geprüft (Schema, Host, normalisierter Port) — eine Weiterleitung auf einen fremden Host wird abgelehnt, statt ihr zu folgen, ebenso ein Downgrade von `https` auf `http`. Ein Upgrade von `http` auf `https` auf demselben Host (Standard-Ports oder identischer expliziter Port) gilt seit #693 dagegen als dieselbe Origin und wird gefolgt, Zugangsdaten eingeschlossen — vor #693 wurde auch dieser alltägliche Fall abgelehnt und machte jede mit `http://` konfigurierte Quelle faktisch unbenutzbar.
- Mit `sourceInsecureSsl: true` lässt sich die Zertifikatsprüfung abschalten. Bei `HTTP_DIRECTORY` gilt das für den gesamten Crawl (Verzeichnislisting und jede darüber gefundene Datei). Bei `RSS_FEED` dagegen nur für den Origin der konfigurierten `sourceUrl` selbst (#663): Eine Detailseite oder Anlage, auf die der Feed-Inhalt verweist und die auf einem fremden Origin liegt, wird weiterhin normal geprüft, unabhängig von `sourceInsecureSsl` — der Feed-Betreiber kontrolliert diesen Inhalt, nicht der Bibliothekseigentümer.
- **Ein konfigurierter `sourceProxy` bestimmt, wohin die TCP-Verbindung tatsächlich geht, und wird deshalb ebenfalls geprüft** — beim Verbindungstest (`POST /api/v1/libraries/source-test`) direkt gegen die vom Aufrufer im Request gesetzte Proxy-Adresse, bei einem echten Indizierungslauf über den bereits geprüften `sourceUrl`/Redirect-Pfad hinaus nicht zusätzlich: Ein Lauf liest `sourceProxy` ausschließlich aus der bereits gespeicherten, durch mindestens `MANAGER` autorisierten Bibliothekskonfiguration (kein per-Request-Feld, ADR-0018) — wer diesen Wert setzen darf, darf `sourceUrl` ohnehin frei wählen.
- Fehlermeldungen des Crawls landen im Jobstatus und sind über `GET /api/v1/libraries/{libraryId}/indexing/status` lesbar; sie unterscheiden erreichbare von nicht erreichbaren Zielen, eine abgelehnte Weiterleitung (Ziel als `Schema://Host` ohne Pfad/Query, nie die vollständige, potenziell sensible Ziel-URL) und ein per Zielprüfung gesperrtes Ziel.

**Risikoeinordnung.** ADR-0018, Entscheidung 6 hat die Anlage jedes Quellentyps bewusst und dauerhaft für jeden Berechtigten geöffnet (nicht mehr nur `SYSTEM_ADMIN`) — kein Rollenkonstrukt tritt an die Stelle dieser Öffnung. Für `FILESYSTEM` ist die daraus entstehende Angriffsfläche mit #484 geschlossen: Die Pfad-Allowlist (`opaa.indexing.filesystem-allowlist`) sichert, welche Serverpfade überhaupt konfigurierbar sind, unabhängig davon, wer die Bibliothek anlegt. Für `HTTP_DIRECTORY`/`RSS_FEED` schließt #267 die analoge Lücke: Die Zielprüfung (`opaa.indexing.target-validation`) sichert, welche Adressbereiche überhaupt erreichbar sind, ebenfalls unabhängig davon, wer die Bibliothek anlegt.

**Konsequenz für den Betrieb:** Für `FILESYSTEM` schützt die Allowlist, für `HTTP_DIRECTORY`/`RSS_FEED` die Zielprüfung — beide unabhängig davon, wem das Anlage-Recht zusteht. Ein Betrieb mit legitimen internen Dokumentenquellen (z. B. einem Intranet-Portal) trägt deren Hostnamen gezielt in `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` ein, statt die Prüfung insgesamt abzuschalten.

Weist ein `sourcePath` außerhalb der Allowlist zurück, nennt die 400-Antwort bewusst keine Serverpfade — das würde die konfigurierten Basisverzeichnisse gegenüber einem nicht berechtigten Aufrufer preisgeben. Damit der abgewiesene Aufrufer trotzdem handlungsfähig bleibt, verweist die Meldung stattdessen auf die Systemverwaltung als Anlaufstelle für die freigegebenen Basisverzeichnisse.

## Härtung für erreichbare Deployments

Der mitgelieferte Compose-Stack (`docker-compose.yml`, `keycloak/realm-export.json`) ist ausdrücklich für
die **lokale Entwicklung** gebaut, wie der Warnhinweis am Kopf von `docker-compose.yml` sagt. Seine
Vorgabewerte sind dafür richtig gewählt — bequem, ohne Ersteinrichtung nutzbar, mit einem funktionierenden
OIDC-Realm ab dem ersten Start. Für eine **öffentlich oder auch nur aus einem größeren Netz erreichbare
Instanz** ist jeder dieser Vorgabewerte ein Problem, und der Warnhinweis allein sagt nicht, was konkret zu
tun ist. Dieser Abschnitt schließt genau diese Lücke — die Doku, nicht der Betrieb: Für die reale
öffentliche Testinstanz siehe [„Öffentliche Testinstanz"](#öffentliche-testinstanz) oben, die vermutlich
nicht den hier mitgelieferten Realm unverändert nutzt.

Die folgende Liste geht die sechs tatsächlich im Repository liegenden Vorgabewerte durch. Wo eine
Gegenmaßnahme **zwingend** ist, steht das dabei; alles andere ist eine Empfehlung, deren Unterlassung
begründet werden sollte, kein hartes Muss.

| # | Fundstelle | Vorgabewert | Risiko bei erreichbarem Betrieb | Gegenmaßnahme |
|---|---|---|---|---|
| 1 | `keycloak/realm-export.json` | Realm-Benutzer `testuser`/`testpass` sowie, seit #712, die fünf Demo-Nutzer (`demo-admin`, `maria.weber`, `selin.kaya`, `thomas.klein`, `andrea.vogt`) mit dem gemeinsamen Passwort `RheinfurtDemo!2026`, alle `"temporary": false` | Bekannte, öffentlich im Repository stehende Zugangsdaten zu echten Konten — jeder, der das Repository kennt, kennt diese Logins | **Zwingend.** Diese Benutzer aus dem Realm-Export entfernen oder durch eigene, beim Import per Skript gesetzte Zufallspasswörter ersetzen, bevor der Realm importiert wird. Wer den mitgelieferten Realm unverändert importiert, importiert diese Konten mit. Für einen Rollout der Demo-Instanz selbst (opaa.ewerlin.com o. ä.) gilt dieselbe Pflicht — die Demo-Passwörter sind für lokales Vorführen gedacht, nicht für ein öffentlich erreichbares Deployment |
| 2 | `keycloak/realm-export.json:6` | `"sslRequired": "none"` | Keycloak nimmt Anmeldungen und Admin-Zugriffe auch unverschlüsselt an — in Kombination mit einer erreichbaren Instanz eine offene Tür für das Mitlesen von Zugangsdaten | **Zwingend**, sobald die Instanz nicht ausschließlich über eine als vertrauenswürdig geltende Loopback-/interne Verbindung erreicht wird: auf `external` (TLS für externe Zugriffe verpflichtend, intern optional) oder `all` (TLS immer verpflichtend) setzen. TLS selbst terminiert dabei **nicht** Keycloak, sondern der vorgelagerte Reverse-Proxy — siehe [„Netzwerkzugang"](#netzwerkzugang) unten und das reale Beispiel unter [„Öffentliche Testinstanz"](#öffentliche-testinstanz) (nginx terminiert TLS, Keycloak selbst bleibt intern auf HTTP). **Zur selben Maßnahme gehört zwingend `KC_PROXY_HEADERS=xforwarded`** (Keycloak-Umgebungsvariable, analog zum in [„Netzwerkzugang"](#netzwerkzugang) beschriebenen `server.forward-headers-strategy` des Backends) — ohne sie hält Keycloak eine über den Proxy per TLS ankommende Anfrage für unverschlüsselt und antwortet mit „HTTPS required", was einen Betreiber typischerweise dazu verleitet, `sslRequired` fälschlich wieder auf `none` zurückzustellen, statt die eigentliche Ursache zu beheben. Bei Einhängung unter einem Unterpfad (z. B. `/idp`, wie bei der öffentlichen Testinstanz) zusätzlich `KC_HTTP_RELATIVE_PATH` setzen. Wie beim Backend muss der vorgelagerte Proxy `X-Forwarded-Proto` dabei **autoritativ** setzen und den Wert nicht vom Client durchlassen |
| 3 | `docker-compose.yml:59-60` | Keycloak-Bootstrap-Admin `KC_BOOTSTRAP_ADMIN_USERNAME`/`_PASSWORD` fest auf `admin`/`admin` | Voller administrativer Zugriff auf den Identitätsanbieter mit einem der bekanntesten Vorgabepasswörter überhaupt | **Zwingend.** Über eigene Umgebungsvariablen setzen und **nach der Ersteinrichtung ersetzen** — der Bootstrap-Admin legt beim allerersten Start einen administrativen Benutzer an; sein Passwort danach unverändert zu lassen, macht die einmalige Bootstrap-Vereinfachung zu einer dauerhaften Schwachstelle. **Nicht** über `.env.docker`: Der `keycloak`-Service hat kein `env_file` und liest `${...}`-Platzhalter in `docker-compose.yml` stattdessen aus der Shell-Umgebung bzw. einer `.env`-Datei (siehe die Erklärung unter [„Konfiguration"](#konfiguration) unten) — die beiden Werte gehören also in `.env` oder werden per `docker compose --env-file <datei>` übergeben, mit derselben Vorsicht wie dort beschrieben, damit sie keine lokalen Entwicklungseinstellungen überschreiben |
| 4 | `docker-compose.yml:57` | `command: start-dev --import-realm` — kein persistentes Datenverzeichnis, nur der Realm-Export ist als Datei gemountet | Keycloaks eingebaute Entwicklungsdatenbank ist an den Container gebunden und geht bei jedem Neuerstellen verloren — ein rotiertes Bootstrap-Passwort (Punkt 3) und ein bereinigter Realm (Punkt 1) verschwinden damit beim nächsten `docker compose up -d`/Image-Update und `admin`/`admin` sowie `testuser` sind wieder da, ohne dass das auffällt | **Zwingend**, sonst wirken die Gegenmaßnahmen zu Punkt 1 und 3 nicht dauerhaft, sondern nur bis zum nächsten Container-Neustart. Für erreichbaren Betrieb `start` statt `start-dev` verwenden (das erzwingt ohnehin die übrigen Härtungspunkte dieser Zeile — Keycloak startet mit `start` ohne konfiguriertes TLS/Proxy-Setup gar nicht erst) und eine externe, persistente Datenbank anbinden (`KC_DB`, `KC_DB_URL` u. a.) statt der eingebauten Entwicklungsdatenbank. `--import-realm` importiert einen Realm dabei ohnehin nur, wenn er noch nicht existiert — nach der ersten, persistenten Einrichtung wirken spätere Realm-Änderungen über die Admin-Konsole oder einen erneuten, gezielten Import |
| 5 | `docker-compose.yml:19-20`, `35`, `51`, `63` | Nur `postgres` bindet auf `127.0.0.1`; `backend` (8081), `frontend` (3000) und `keycloak` (8180) veröffentlichen ihre Ports ohne Adressangabe und binden damit auf allen Schnittstellen | `postgres` auf `127.0.0.1` gebunden schützt vor Zugriff aus dem Netz, aber nicht vor jedem anderen Prozess und Nutzerkonto auf demselben Host. Die übrigen drei Ports sind dagegen aus dem Netz erreichbar, sobald keine Firewall davorsteht — bei `keycloak` ist das der konkrete Ausnutzungsweg zu Punkt 3: Die Admin-Konsole wäre netzweit ansprechbar, unabhängig davon, ob ein vorgelagerter Reverse-Proxy nur bestimmte Pfade durchreicht | Hinter einem Reverse-Proxy alle vier Ports auf `127.0.0.1:` binden, so wie es `postgres` bereits vormacht — für `keycloak` **zwingend** (sonst bleibt die Admin-Konsole trotz Proxy direkt aus dem Netz erreichbar), für `backend`/`frontend` **empfohlen** (der Reverse-Proxy ist dann der einzige Weg zu beiden). Für `postgres` **empfohlen**, die `ports:`-Zuordnung für den erreichbaren Betrieb ganz zu entfernen statt sie nur auf Loopback zu binden — Backend und `postgres` erreichen sich ohnehin über das interne Compose-Netz (Servicename `postgres`), ein Host-Port wird dafür nicht gebraucht. Für lokale Entwicklung (Anschluss mit einem Datenbank-Client vom Host aus, direkter Aufruf der Admin-Konsole) bleiben die bisherigen Bindungen dagegen sinnvoll — deshalb sind sie dort nicht als Fehler markiert |
| 6 | `keycloak/realm-export.json` (Client `opaa-seed`, seit #712) | Öffentlicher Client mit `directAccessGrantsEnabled: true` (Resource-Owner-Password-Grant) und ohne Client-Secret, ausschließlich für `demo/seed/seed.py` gedacht | Erlaubt einen passwortbasierten Tokenweg **ohne Secret** gegen jedes Realm-Konto — auf einer erreichbaren Instanz ein zusätzlicher, von der eigentlichen Anmeldung (`opaa-frontend`, `directAccessGrantsEnabled: false`, Authorization-Code + PKCE) unabhängiger Angriffsweg, unabhängig davon, wessen Passwort betroffen ist | **Zwingend.** Client `opaa-seed` aus dem Realm-Export entfernen oder auf `enabled: false` setzen, bevor der Realm auf einer erreichbaren Instanz importiert wird. Wer die Demo dort dennoch erneut seeden will, aktiviert den Client nur für die Dauer des Laufs wieder oder legt die Rechte direkt über die Keycloak-Admin-Konsole/API an, statt den Client dauerhaft scharf zu lassen. Siehe `demo/README.md`, Abschnitt „Seed ausführen". **Ist-Zustand auf opaa.ewerlin.com** (seit dem Rheinfurt-Rollout #230): umgesetzt — der Client ist im importierten Realm deaktiviert und wird für einen Seed-Lauf nur vorübergehend aktiviert, siehe [„Seed- und `opaa-seed`-Verfahren"](#öffentliche-testinstanz) oben |

**Zugangsdaten, die zusätzlich zu ersetzen sind (empfohlen, unabhängig von den fünf Fundstellen oben):**

- `OPAA_DB_USERNAME`/`OPAA_DB_PASSWORD` — die Vorgabewerte `opaa`/`opaa` sind für die Entwicklung gewählt,
  nicht für den Betrieb. Eine Änderung nach der Ersteinrichtung verlangt `docker compose down -v` (siehe
  [„Datenbank"](#datenbank) unten), weil PostgreSQL den initialen Benutzer nur beim ersten Start anlegt —
  das Zugangsdatenpaar also **vor** dem ersten Start setzen, nicht danach ändern wollen.
- `OPAA_CREDENTIALS_ENCRYPTION_KEY` — sobald eine Bibliothek vom Typ `HTTP_DIRECTORY`/`RSS_FEED`
  Zugangsdaten zu einer externen Quelle speichert (siehe [„Zugangsdaten-Verschlüsselung"](#zugangsdaten-verschlüsselung-483)
  unten), muss ein eigener Schlüssel gesetzt sein — ohne ihn schlägt nur dieser eine Schreibvorgang fehl,
  der Start selbst nicht. Der im `dev`-Profil hinterlegte Schlüssel ist ausdrücklich nicht
  produktionstauglich und darf nicht übernommen werden.

**Was es in diesem Repository nicht mehr gibt und deshalb hier auch nicht zu ersetzen ist:** Ein
anwendungsseitiges JWT-Secret (früher `OPAA_AUTH_BASIC_SECRET`) existiert seit dessen ersatzloser
Entfernung ([`fd04246`](https://github.com/criew/opaa/commit/fd0424621874270a2be78f05bfee5c550945fd3f))
nicht mehr — OPAA verifiziert Tokens ausschließlich gegen die Signaturschlüssel des konfigurierten
OIDC-Anbieters (`OPAA_OIDC_JWK_SET_URI`), es gibt kein eigenes Signier-Geheimnis, das rotiert werden
müsste. Diese Verantwortung liegt beim Identitätsanbieter (dessen Realm-Schlüssel) statt bei OPAA. Ebenso
gibt es keinen `OPAA_AUTH_MODE=mock` mehr: Der einzige ungeprüfte Modus ist heute das Spring-Profil `dev`
— **es gehört nie auf eine erreichbare Instanz**, siehe die Warnung unter
[„Entwicklungsmodus (dev)"](#entwicklungsmodus-dev) unten. Nur `SPRING_PROFILES_ACTIVE=...,oidc` ist für
den erreichbaren Betrieb zulässig.

**Woran erkennbar ist, was zwingend und was nur empfohlen ist:** Die Tabelle und die Liste oben markieren
jeden Punkt einzeln als „Zwingend" oder „Empfohlen". Als Faustregel: Ein bekanntes, öffentlich im
Repository stehendes Zugangsdatenpaar, ein Klartext-Transportweg zu Anmeldedaten oder ein netzweit
erreichbarer administrativer Zugang ist immer zwingend zu schließen; eine Einschränkung, die nur die
Angriffsfläche auf einem sonst schon vertrauenswürdigen Host verkleinert (etwa Teile von Punkt 5), ist
eine Empfehlung.

**Separate Compose-Datei oder Textliste?** Für dieses Issue fällt die Entscheidung bewusst auf die
Textliste oben statt auf eine eigene `docker-compose.prod.yml`/ein Override: Mehrere der Fundstellen
verlangen eine Änderung an `keycloak/realm-export.json` (kein Compose-Override kann Inhalte einer
importierten JSON-Datei patchen, ohne selbst wieder ein Geheimnis oder einen Generierungsschritt
einzuführen) und einen echten Bootstrap-Vorgang (Passwort **nach** der Ersteinrichtung ändern, was
keine Compose-Datei für sich allein leisten kann) bzw. eine externe, persistente Datenbank für Keycloak
(eine Betriebsentscheidung, keine Compose-Konstante). Eine `docker-compose.prod.yml` würde damit nur die
Port-Bindungen automatisieren und für den Rest weiterhin auf genau diese Anleitung verweisen — der
Mehrwert stünde in keinem Verhältnis zu einer zweiten, parallel zu pflegenden Compose-Datei. Sollte sich
das Bild ändern (etwa durch ein eigenes Setup-Skript, das Realm-Import und Bootstrap-Rotation ohnehin
automatisiert), ist eine separate Produktionsvariante ein guter Zeitpunkt, diese Punkte technisch statt nur
dokumentarisch durchzusetzen — dann als eigenes Issue.

## Vor der Inbetriebnahme: mandantenfähiger Betrieb

Eine Installation wird heute mit **genau einer** Organisation ausgeliefert. Solange das so bleibt, ist die Mandantengrenze unkritisch — es gibt nichts, was sie überschreiten könnte.

**Mandantenfähiger Betrieb — mehr als eine Organisation auf derselben Installation — setzt zwei Vorgänge voraus, die heute offen sind:** die symmetrische Absicherung der Organisationsgrenze auf Datenbankebene ([#289](https://github.com/criew/opaa/issues/289)) und ihre Durchsetzung im Verwaltungspfad ([#271](https://github.com/criew/opaa/issues/271)). Beide Lücken sind bei einer Organisation nicht ausnutzbar und werden mit dem Anlegen der zweiten gleichzeitig scharf. Wer eine zweite Organisation anlegt, bevor beide erledigt sind, betreibt die Installation ohne durchgesetzte Mandantentrennung.

Hintergrund und die drei Schichten, in denen die Grenze gehalten wird: [features/spaces-and-assets.md](./features/spaces-and-assets.md#wie-die-grenze-gehalten-wird).

## Schnellstart

```bash
# 1. Umgebung konfigurieren
cp .env.docker.example .env.docker
# .env.docker bearbeiten. Voreingestellt sind lokal betriebene Modelle über
# Ollama — dafür ist keine weitere Angabe in .env.docker selbst nötig. Der
# Compose-Stack enthält aber KEINEN ollama-Service: Ein erreichbarer Ollama-
# Server wird vorausgesetzt (OPAA_OLLAMA_BASE_URL=http://ollama:11434 ist
# nur ein Platzhalter-Hostname). Ohne einen solchen startet der Stack zwar,
# aber Indizierung und Fragen schlagen fehl. Läuft Ollama auf dem Host,
# OPAA_OLLAMA_BASE_URL auf http://host.docker.internal:11434 setzen. Wer
# stattdessen einen openai-kompatiblen Anbieter wählt, muss
# OPAA_OPENAI_BASE_URL setzen (siehe „LLM-Anbieter").

# 2. Alle Services starten
docker compose up --build

# 3. Anwendung öffnen
# Frontend: http://localhost:3000
# Backend-API: http://localhost:8081/api
```

## Services

| Service    | Host-Port | Container-Port | Beschreibung                          |
|------------|-----------|----------------|---------------------------------------|
| frontend   | 3000      | 80             | React-App über Nginx bereitgestellt   |
| backend    | 8081      | 8080           | Spring Boot API                       |
| postgres   | 5432      | 5432           | PostgreSQL 18 mit pgvector            |
| keycloak   | 8180      | 8180           | Keycloak (nur OIDC-Profil)            |

## Konfiguration

Alle Konfigurationen erfolgen über Umgebungsvariablen in `.env.docker`. Docker Compose lädt diese Datei über die `env_file`-Direktive. Alle verfügbaren Optionen mit Beschreibungen finden Sie in `.env.docker.example`.

> **Zwei getrennte Vorlagen, zwei Zwecke (#716).** `.env.example` ist auf `./gradlew bootRun` auf dem Host zugeschnitten (Datenbank auf `localhost`, CORS auf den Vite-Dev-Server `:5173`, kein `SPRING_PROFILES_ACTIVE`, weil `bootRun` es üblicherweise direkt in der Shell setzt). `.env.docker.example` ist die Vorlage für den Compose-Stack: `SPRING_PROFILES_ACTIVE=docker,dev` gesetzt, `OPAA_DB_URL` auskommentiert (der `docker`-Profil-Default liefert bereits den richtigen Hostnamen `postgres`), `OPAA_CORS_ALLOWED_ORIGINS` auf den Frontend-Host-Port `:3000` statt `:5173` und die leer gesetzten `OPAA_OPENAI_*`-Zeilen auskommentiert (ein leer gesetzter Wert überschreibt sonst den Anwendungs-Default, siehe Hinweis in der Variablen-Tabelle unten). Wer beide Kopien unverändert nebeneinander nutzt — `.env` für `bootRun`, `.env.docker` für Compose — bekommt in beiden Fällen einen startfähigen Stand, ohne die jeweils andere Vorlage von Hand nachzubessern.

> **Wichtig:** Docker Compose lädt automatisch eine `.env`-Datei (falls vorhanden) für die Variablen-Interpolation in `docker-compose.yml` selbst. Um Konflikte mit lokalen Entwicklungseinstellungen zu vermeiden, verwenden Docker-Compose-Services `.env.docker` als ihre `env_file`. Wenn Sie eine `.env`-Datei für die lokale Entwicklung haben, stellen Sie sicher, dass die Docker-relevanten Variablen (Ports, DB-Anmeldeinformationen) nicht kollidieren.

### Erforderliche Variablen

Im Standardfall — lokal betriebene Modelle über Ollama für Chat und Einbettung — ist **keine**
Modellvariable erforderlich. Der Stack startet ohne zusätzliche Angabe.

Wer für Chat oder Einbettung den openai-kompatiblen Anbieter wählt (`OPAA_AI_CHAT_PROVIDER=openai`
bzw. `OPAA_AI_EMBEDDING_PROVIDER=openai`), muss die **Zieladresse angeben**:

```env
OPAA_OPENAI_BASE_URL=https://modellserver.example.internal/v1
OPAA_OPENAI_API_KEY=sk-your-key-here
```

Es gibt für die Adresse **keine Voreinstellung**. Fehlt sie bei gewähltem openai-kompatiblen
Anbieter, bricht das Backend den Start mit einer Meldung ab, die die fehlende Variable benennt
(`io.opaa.config.OpenAiBaseUrlGuard`). Der Grund: `openai` bezeichnet das Protokoll, nicht das Ziel
— lokal betriebene Modellserver sprechen dasselbe Protokoll. Eine Voreinstellung würde eine
Installation, die im Haus bleiben soll, stillschweigend nach außen richten.

### Docker-spezifische Variablen

Diese Variablen sind wichtig, wenn mit Docker Compose ausgeführt wird, und sollten in `.env.docker` gesetzt werden:

| Variable | Erforderlicher Wert | Warum |
|----------|---------------------|-------|
| `SPRING_PROFILES_ACTIVE` | `docker,oidc` (Betrieb) oder `docker,dev` (Entwicklung) | Aktiviert Docker-spezifische Konfiguration (DB-URL, Ollama-URL) und den Auth-Modus; ohne `oidc` oder `dev` startet das Backend nicht |
| `OPAA_SERVER_ADDRESS` | `0.0.0.0` | Backend muss an alle Schnittstellen binden, um von anderen Containern erreichbar zu sein |
| `OPAA_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Muss mit dem Host-Port des Frontends übereinstimmen |
| `OPAA_DB_USERNAME` | `opaa` | Muss zwischen Backend- und postgres-Services übereinstimmen |
| `OPAA_DB_PASSWORD` | `opaa` | Muss zwischen Backend- und postgres-Services übereinstimmen |

### Minimale `.env.docker`

```env
SPRING_PROFILES_ACTIVE=docker,dev
OPAA_SERVER_ADDRESS=0.0.0.0
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:3000
OPAA_DB_USERNAME=opaa
OPAA_DB_PASSWORD=opaa
```

Chat und Einbettung laufen damit über Ollama (`OPAA_OLLAMA_BASE_URL` ist im Profil `docker` auf
`http://ollama:11434` vorbelegt). Für einen openai-kompatiblen Anbieter kommen
`OPAA_AI_CHAT_PROVIDER` bzw. `OPAA_AI_EMBEDDING_PROVIDER`, `OPAA_OPENAI_BASE_URL` und
`OPAA_OPENAI_API_KEY` hinzu.

### Alle Umgebungsvariablen

**„Standard" ist zweideutig — deshalb zwei Spalten.** Der **Anwendungs-Default** ist der Wert, den
`backend/src/main/resources/application.yml` (bzw. das Docker-Compose-Setup selbst) annimmt, wenn die
Variable **nirgends gesetzt** ist — etwa bei `./gradlew bootRun` ohne `.env`. Die **Compose-Belegung**
ist der Wert, den `.env.docker.example` tatsächlich vorgibt und den ein Nutzer erlebt, der es unverändert nach
`.env.docker` kopiert. Beide Angaben können auseinanderlaufen, ohne dass eine davon falsch ist — sie
beschreiben zwei verschiedene Ebenen. Eine leere Compose-Belegung („nicht gesetzt") bedeutet, dass
`.env.docker.example` die Variable auskommentiert lässt oder gar nicht enthält; dann gilt beim Kopieren nach
`.env.docker` der Anwendungs-Default.

**Vorrang der Konfigurationsquellen — zwei getrennte Mechanismen:**

1. **Werte, die im Backend-Container ankommen und von Spring gelesen werden** (also praktisch jede
   `opaa.*`/`spring.*`-Eigenschaft): Hier hat eine Umgebungsvariable, die explizit im
   `environment:`-Abschnitt einer `docker-compose.yml` gesetzt ist, Vorrang vor demselben Namen in der
   über `env_file:` eingebundenen `.env.docker`, und diese wiederum vor dem in `application.yml`
   hinterlegten Anwendungs-Default (`${VARIABLE:default}`). Bei `./gradlew bootRun` ohne Docker Compose
   tritt an die Stelle von `environment:`/`env_file` schlicht die Prozessumgebung der Shell, in der
   `bootRun` läuft. Eine Host-Shell-Variable erreicht den Backend-Container in Docker Compose **nicht**
   automatisch — nur wenn sie entweder in `environment:` referenziert wird (z. B. `OPAA_UPLOAD_STORAGE_PATH`
   in `docker-compose.yml`) oder über `env_file: .env.docker` geladen wird, landet sie im Container.
2. **Variablen, die `${...}` direkt in `docker-compose.yml` interpoliert** — Bind-Mounts, Host-Ports und
   die `env_file`-Auswahl selbst (`OPAA_ENV_FILE`, siehe unten). Docker Compose löst diese Platzhalter
   ausschließlich aus der **Prozessumgebung** und einer von Compose selbst automatisch geladenen
   `.env`-Datei im Projektwurzelverzeichnis auf — **niemals** aus der über `env_file:` eingebundenen
   `.env.docker` (`e2e/scripts/run-e2e.mjs` nutzt genau das, um Ports und die `env_file`-Auswahl der
   E2E-Suite per Prozessumgebung zu setzen, ohne die Datei eines Entwicklers anzufassen). Ein Wert, den
   `.env.docker.example` für eine solche Variable vorschlägt, bleibt deshalb wirkungslos, solange er nur in
   `.env.docker` steht — er muss als Shell-Variable exportiert oder in eine echte `.env`-Datei
   geschrieben werden. In der Tabelle unten mit „wirkt nur aus Prozessumgebung/`.env`, **nicht** aus
   `.env.docker`" gekennzeichnet.

Ist eine Variable nirgends gesetzt, gilt in beiden Fällen der jeweilige Default.

**Anbieterbedingte Variablen:** `OPAA_OPENAI_*` wirkt nur, solange der jeweilige Anbieter
(`OPAA_AI_CHAT_PROVIDER` bzw. `OPAA_AI_EMBEDDING_PROVIDER`) auf `openai` steht; `OPAA_OLLAMA_*`
entsprechend nur bei `ollama`. Ein gesetzter, aber wegen des falschen Anbieters wirkungsloser Wert wird
nicht gemeldet — er bleibt einfach ungenutzt liegen.

Manche Variablen dieser Tabelle sind kein Spring-Property, sondern werden ausschließlich von Docker
Compose selbst ausgewertet (Bind-Mounts, Host-Ports, die `env_file`-Auswahl) oder vom
`envsubst`-Template des Frontend-nginx — für sie gibt es keinen Anwendungs-Default im eigentlichen
Sinn; das ist jeweils vermerkt.

| Variable | Anwendungs-Default (`application.yml`) | Compose-Belegung (`.env.docker.example`) | Beschreibung |
|----------|------------------------------------------|-------------------------------------|-------------|
| **Allgemein** | | | |
| `OPAA_SERVER_ADDRESS` | `localhost` | `0.0.0.0` | Bind-Adresse (`0.0.0.0` für Netzwerkzugang). Docker Compose überschreibt den Anwendungs-Default bewusst — siehe Hinweis unter [Netzwerkzugang](#netzwerkzugang) |
| `OPAA_HTTP_FORCE_HTTP1` | `false` | `false` | HTTP/1.1 für vLLM-Kompatibilität erzwingen |
| `OPAA_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | `http://localhost:3000` | Erlaubte CORS-Origins (kommagetrennt). Der Anwendungs-Default passt nur außerhalb von Docker Compose (lokaler Vite-Dev-Server auf `:5173`) — die Compose-Belegung trägt deshalb bewusst den Frontend-Host-Port, standardmäßig `http://localhost:3000` (siehe [„Docker-spezifische Variablen"](#docker-spezifische-variablen) oben und [„POST-Anfragen geben 403 Forbidden zurück"](#post-anfragen-geben-403-forbidden-zurück) unten) — sonst schlägt jede POST-Anfrage aus dem Compose-Frontend am CORS-Preflight fehl |
| `OPAA_INDEXING_DOCUMENT_PATH_HOST` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `./documents`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — `.env.docker.example` lässt die Variable deshalb bewusst auskommentiert; ohne Shell-Export gilt der Compose-Default `./documents` | Host-Pfad für Dokumente (in Container gemountet) |
| `OPAA_UPLOAD_STORAGE_PATH_HOST` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `./uploads`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `./uploads` | Host-Pfad für hochgeladene Dokumente (in Container gemountet) |
| `OPAA_UPLOAD_STORAGE_PATH` | `./uploads` | — (`docker-compose.yml` setzt sie im Backend-Container fest auf `/app/uploads`, nicht über `.env.docker` änderbar) | Container-interner Speicherpfad für hochgeladene Dokumente (`opaa.upload.storage-path`) — bei Docker Compose nicht mit dem Bind-Mount `OPAA_UPLOAD_STORAGE_PATH_HOST` zu verwechseln |
| `OPAA_UPLOAD_MAX_FILE_SIZE` | `52428800` (50 MiB, Byte) | nicht gesetzt (Anwendungs-Default gilt) | Maximale Dateigröße beim Dokument-Upload (`spring.servlet.multipart.max-file-size`/`max-request-size` und `opaa.upload.max-file-size` in `application.yml`, dieselbe Variable für beide). **Bei Docker Compose zusätzlich zu beachten:** Der nginx-Reverse-Proxy im Frontend-Container (`frontend/nginx.conf`) setzt `client_max_body_size` unabhängig davon fest auf `52m` — etwas oberhalb dieses Limits, weil nginx die gesamte Multipart-Anfrage misst (inklusive Framing-Overhead), das Backend dagegen nur die Dateigröße. Diese Datei wird beim Image-Build fest eingebacken (kein `envsubst`), wird also **nicht** automatisch aus `OPAA_UPLOAD_MAX_FILE_SIZE` übernommen. Wer `OPAA_UPLOAD_MAX_FILE_SIZE` erhöht, muss `client_max_body_size` in `frontend/nginx.conf` entsprechend mit anheben, sonst weist nginx größere Uploads bereits mit einer eigenen HTML-413-Seite ab, bevor die Backend-Prüfung überhaupt greift — siehe [#519](https://github.com/criew/opaa/issues/519). |
| **Datenbank** | | | |
| `OPAA_DB_URL` | `jdbc:postgresql://localhost:5432/opaa?prepareThreshold=0`; im Spring-Profil `docker` (ohne gesetzte Variable) stattdessen `jdbc:postgresql://postgres:5432/opaa?prepareThreshold=0` | nicht gesetzt (auskommentiert) — der `docker`-Profil-Default mit dem Hostnamen `postgres` gilt | JDBC-Verbindungs-URL. Der `bootRun`-Beispielwert (`localhost`) passt nur außerhalb von Docker Compose, deshalb lässt `.env.docker.example` die Variable bewusst auskommentiert — ein gesetzter Wert würde den `docker`-Profil-Default mit dem korrekten Hostnamen `postgres` überschreiben |
| `OPAA_DB_USERNAME` | `opaa` | `opaa` | PostgreSQL-Benutzername |
| `OPAA_DB_PASSWORD` | `opaa` | `opaa` | PostgreSQL-Passwort |
| `OPAA_DB_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `5432`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `5432` | Host-Port, auf den `docker-compose.yml` den PostgreSQL-Container bindet (nur `127.0.0.1`) |
| **LLM / Embedding** | | | |
| `OPAA_AI_CHAT_PROVIDER` | `ollama` | `ollama` | Chat-Modell-Anbieter (`ollama` oder `openai`) |
| `OPAA_AI_EMBEDDING_PROVIDER` | `ollama` | `ollama` | Embedding-Modell-Anbieter (`ollama` oder `openai`) |
| `OPAA_OPENAI_API_KEY` | `sk-placeholder` (Platzhalter, kein gültiger Schlüssel — greift nur, falls ein Anbieter auf `openai` steht und kein spezifischerer Schlüssel gesetzt ist) | nicht gesetzt (auskommentiert) — der Anwendungs-Default (Platzhalter) gilt | Zugangsschlüssel der openai-kompatiblen Schnittstelle. Wirkt nur bei mindestens einem Anbieter `openai` |
| `OPAA_OPENAI_BASE_URL` | — (kein Default) | nicht gesetzt (auskommentiert) | Basis-Adresse der openai-kompatiblen Schnittstelle. **Ohne Voreinstellung; erforderlich, sobald ein Anbieter auf `openai` steht** — sonst bricht der Start ab (siehe [„Erforderliche Variablen"](#erforderliche-variablen) oben) |
| `OPAA_OPENAI_CHAT_API_KEY` | — (kein eigener Default; fällt auf `OPAA_OPENAI_API_KEY` zurück, verschachtelt: `${OPAA_OPENAI_CHAT_API_KEY:${OPAA_OPENAI_API_KEY:sk-placeholder}}`) | nicht gesetzt (auskommentiert) — der Fallback auf `OPAA_OPENAI_API_KEY` gilt | Eigener Zugangsschlüssel nur für den Chat-Aufruf, falls Chat- und Embedding-API unterschiedliche Schlüssel brauchen. Wirkt nur bei `OPAA_AI_CHAT_PROVIDER=openai` |
| `OPAA_OPENAI_CHAT_BASE_URL` | — (kein eigener Default; fällt auf `OPAA_OPENAI_BASE_URL` zurück, verschachtelt: `${OPAA_OPENAI_CHAT_BASE_URL:${OPAA_OPENAI_BASE_URL:}}`) | nicht gesetzt (auskommentiert) | Eigene Zieladresse nur für den Chat-Aufruf, überschreibt `OPAA_OPENAI_BASE_URL` für diese eine Funktion (siehe [„LLM-Anbieter"](#llm-anbieter) unten) |
| `OPAA_OPENAI_CHAT_MODEL` | `gpt-4o` | `gpt-4o` | OpenAI-Chat-Modellname. Wirkt nur bei `OPAA_AI_CHAT_PROVIDER=openai` |
| `OPAA_OPENAI_CHAT_TEMPERATURE` | `0.7` | `0.7` | Chat-Antwort-Temperatur (0,0–2,0). Wirkt nur bei `OPAA_AI_CHAT_PROVIDER=openai` |
| `OPAA_OPENAI_CHAT_MAX_TOKENS` | `2000` | `2000` | Maximale Tokens in Chat-Antwort. Wirkt nur bei `OPAA_AI_CHAT_PROVIDER=openai` |
| `OPAA_OPENAI_EMBEDDING_API_KEY` | — (kein eigener Default; fällt auf `OPAA_OPENAI_API_KEY` zurück, verschachtelt: `${OPAA_OPENAI_EMBEDDING_API_KEY:${OPAA_OPENAI_API_KEY:sk-placeholder}}`) | nicht gesetzt (auskommentiert) — der Fallback auf `OPAA_OPENAI_API_KEY` gilt | Eigener Zugangsschlüssel nur für den Embedding-Aufruf. Wirkt nur bei `OPAA_AI_EMBEDDING_PROVIDER=openai` |
| `OPAA_OPENAI_EMBEDDING_BASE_URL` | — (kein eigener Default; fällt auf `OPAA_OPENAI_BASE_URL` zurück, verschachtelt: `${OPAA_OPENAI_EMBEDDING_BASE_URL:${OPAA_OPENAI_BASE_URL:}}`) | nicht gesetzt (auskommentiert) | Eigene Zieladresse nur für den Embedding-Aufruf, überschreibt `OPAA_OPENAI_BASE_URL` für diese eine Funktion (siehe [„LLM-Anbieter"](#llm-anbieter) unten) |
| `OPAA_OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small` | `text-embedding-3-small` | OpenAI-Embedding-Modellname. Wirkt nur bei `OPAA_AI_EMBEDDING_PROVIDER=openai` |
| `OPAA_OLLAMA_BASE_URL` | Profil `local`: `http://localhost:11434`; Profil `docker`: `http://ollama:11434` — kein profilunabhängiger Default | `http://ollama:11434` | Ollama-API-Basis-URL. Wirkt nur, solange mindestens ein Anbieter auf `ollama` steht. **Der Compose-Stack enthält keinen `ollama`-Service** — der Hostname `ollama` ist nur ein Platzhalter; ein erreichbarer Ollama-Server wird vorausgesetzt, sonst schlagen Indizierung und Abfragen fehl (der Start selbst bricht nicht ab). Läuft Ollama auf dem Host, stattdessen `http://host.docker.internal:11434` setzen (`extra_hosts` im `backend`-Service ist dafür bereits konfiguriert) |
| `OPAA_OLLAMA_CHAT_MODEL` | `phi3:mini` | `phi3:mini` | Ollama-Chat-Modellname. Wirkt nur bei `OPAA_AI_CHAT_PROVIDER=ollama` |
| `OPAA_OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | `nomic-embed-text` | Ollama-Embedding-Modellname. Wirkt nur bei `OPAA_AI_EMBEDDING_PROVIDER=ollama` |
| **Abfrage (RAG-Retrieval)** | | | |
| `OPAA_QUERY_TOP_K` | `5` | `5` | Anzahl der pro Abfrage abgerufenen Dokument-Chunks (1–100) |
| `OPAA_QUERY_SIMILARITY_THRESHOLD` | `0.3` | `0.3` | Minimale Kosinus-Ähnlichkeit für Chunk-Aufnahme (0,0–1,0) |
| **Indizierung** | | | |
| `OPAA_INDEXING_DOCUMENT_PATH` | `./documents` | `./documents` | Dateisystempfad für Quelldokumente |
| `OPAA_INDEXING_CHUNK_SIZE` | `1000` | `1000` | Ziel-Tokens pro Chunk (1–10.000) |
| `OPAA_INDEXING_CHUNK_OVERLAP` | `100` | nicht gesetzt (Anwendungs-Default gilt) | Anzahl der Tokens, die jeder Chunk vom Ende seines Vorgängers wiederholt, damit eine Aussage an einer Chunk-Grenze in mindestens einem Chunk vollständig erhalten bleibt (#374). Muss kleiner als `OPAA_INDEXING_CHUNK_SIZE` sein; `0` deaktiviert die Überlappung, ein negativer Wert wird auf `0` normalisiert |
| `OPAA_INDEXING_BATCH_SIZE` | `50` | `50` | Chunks pro Embedding-API-Aufruf (1–1.000) |
| `OPAA_INDEXING_RETRY_ATTEMPTS` | `3` | `3` | Wiederholungsanzahl bei vorübergehenden Fehlern (0–10) |
| `OPAA_INDEXING_THREAD_POOL_CORE_SIZE` | `2` | `2` | Kern-Threads für asynchrone Indizierung |
| `OPAA_INDEXING_THREAD_POOL_MAX_SIZE` | `4` | `4` | Maximale Threads für asynchrone Indizierung |
| `OPAA_INDEXING_THREAD_POOL_QUEUE_CAPACITY` | `20` | `20` | Task-Queue-Kapazität für asynchrone Indizierung |
| `OPAA_INDEXING_RSS_MAX_ENTRIES` | `200` | `200` | Max. Anzahl verarbeiteter RSS-Feed-Einträge je Lauf |
| `OPAA_INDEXING_RSS_MAX_FEED_SIZE_BYTES` | `10485760` | `10485760` | Max. Größe des abgerufenen RSS-Feeds in Byte |
| `OPAA_INDEXING_RSS_MAX_PAGE_SIZE_BYTES` | `5242880` | `5242880` | Max. Größe einer abgerufenen Detailseite in Byte |
| `OPAA_INDEXING_RSS_REQUEST_DELAY_MS` | `1000` | `1000` | Mindestabstand zwischen zwei Detailseiten-Abrufen in ms |
| `OPAA_INDEXING_RSS_USER_AGENT` | `OPAA-Indexer/1.0` | `OPAA-Indexer/1.0` | User-Agent für RSS-Feed- und Detailseiten-Abrufe (kein Browser-Faking) |
| `OPAA_INDEXING_RSS_MAIN_CONTENT_SELECTOR` | `main, article, [role=main]` | `main, article, [role=main]` | CSS-Selektor (Jsoup-Syntax) für den Hauptinhalt einer Detailseite, Fallback `<body>` |
| `OPAA_INDEXING_RSS_ATTACHMENT_PROFILE` | `GENERIC` | `GENERIC` | Anlagenprofil für RSS-Detailseiten: `GENERIC` oder `GSB` (Government Site Builder) — gilt für jeden RSS-Lauf dieser Installation, nicht je Lauf wählbar (#468) |
| `OPAA_INDEXING_RSS_MAX_ATTACHMENTS_PER_ENTRY` | `10` | `10` | Max. Anzahl heruntergeladener Anlagen je RSS-Eintrag |
| `OPAA_INDEXING_RSS_MAX_ATTACHMENT_SIZE_BYTES` | `20971520` | `20971520` | Max. Größe einer einzelnen RSS-Anlage in Byte |
| `OPAA_INDEXING_FILESYSTEM_ALLOWLIST` | — (leer; Profil `dev`: `/data,/tmp`) | nicht gesetzt (auskommentiert; Beispielwert `/srv/opaa/documents`) | Absolute Basisverzeichnisse, unter denen der `sourcePath` einer FILESYSTEM-Bibliothek liegen muss (kommagetrennt, #484/[ADR-0018](decisions/0018-quellkonfiguration-in-der-bibliothek.md) Entscheidung 6). Eine leere Allowlist deaktiviert den Quellentyp FILESYSTEM vollständig — sie ist die eigentliche Sicherung, nicht die Anlage-Berechtigung. Wird bei Anlage, Änderung **und** jedem Lauf geprüft, da die Allowlist nachträglich verengt werden kann. URL-basierte Quellentypen (HTTP_DIRECTORY, RSS_FEED) sind hiervon nicht erfasst — dafür siehe `OPAA_INDEXING_TARGET_VALIDATION_*` unten. **Betriebsbedingung Symlinks:** Symlinks auf Dateien innerhalb eines freigegebenen Verzeichnisses werden mitindiziert (`Files::isRegularFile` folgt Links) — freigegebene Verzeichnisse dürfen deshalb nicht durch Endnutzer beschreibbar sein. |
| `OPAA_INDEXING_TARGET_VALIDATION_ENABLED` | `true` | `true` | Ob `HTTP_DIRECTORY`/`RSS_FEED`-Abrufe (Indizierungsläufe **und** der Verbindungstest) ein Ziel ablehnen, dessen aufgelöste Adresse Loopback, Link-Local, privat oder anderweitig nicht routbar ist (#267, SSRF-Härtung). Vor dem ersten Abruf **und** nach jeder Weiterleitung geprüft. Standardmäßig aktiv — ein Betrieb mit legitimer interner Dokumentenquelle schaltet bewusst ab, kein stillschweigender Permissiv-Modus. |
| `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` | — (leer) | nicht gesetzt (auskommentiert; Beispielwert `intranet.example.org`) | Hostnamen (kommagetrennt, exakter Vergleich ohne Groß-/Kleinschreibung), die von der Zielprüfung oben ausgenommen sind, auch während sie aktiv ist — erlaubt konkrete interne Quellen zu benennen, ohne die Prüfung für jedes andere Ziel abzuschalten. |
| `OPAA_INDEXING_STALE_JOB_TIMEOUT` | `PT4H` (ISO-8601-Dauer; als Java-Default in `IndexingProperties` hinterlegt, nicht als `${...:...}`-Platzhalter in `application.yml` — bindet über Spring Boots gelockerte Umgebungsvariablen-Zuordnung für `@ConfigurationProperties`) | nicht gesetzt (Anwendungs-Default gilt) | Wie lange ein Lauf `RUNNING` bleiben darf, ohne dass sein Fortschritts-Heartbeat sich bewegt, bevor er als verwaist gilt und automatisch auf `FAILED` gesetzt wird (#501) — schützt vor Läufen, die durch eine verworfene `@Async`-Aufgabe oder einen abgestürzten Prozess dauerhaft `RUNNING` bleiben und damit ihre Bibliothek auf Dauer sperren würden (jeder weitere Anstoß derselben Bibliothek antwortet 409, solange die Zeile `RUNNING` ist). Ein tatsächlich aktiver Lauf eines großen Bestands bleibt unangetastet, solange er weiter Fortschritt meldet, auch über diese Zeitspanne hinaus. Wird beim Anwendungsstart (alle `RUNNING`-Zeilen gelten dann als verwaist) und danach periodisch geprüft. **Setzt genau eine Backend-Instanz voraus:** Startup-Recovery und periodischer Sweep kennen nur die `indexing_jobs`-Zeilen der eigenen Datenbank, nicht welcher Prozess sie tatsächlich noch bearbeitet — bei einem Rolling-Deployment oder einer zweiten Replik würde eine Instanz die noch laufenden Jobs der anderen als verwaist erkennen und abbrechen. |
| **Dokument-Upload** | | | |
| `OPAA_UPLOAD_THREAD_POOL_CORE_SIZE` | `2` | `2` | Kern-Threads für die asynchrone Verarbeitung hochgeladener Dokumente (#434/#614) — eigener Pool, unabhängig von `OPAA_INDEXING_THREAD_POOL_*` |
| `OPAA_UPLOAD_THREAD_POOL_MAX_SIZE` | `4` | `4` | Maximale Threads für die asynchrone Verarbeitung hochgeladener Dokumente |
| `OPAA_UPLOAD_THREAD_POOL_QUEUE_CAPACITY` | `20` | `20` | Task-Queue-Kapazität für den Upload-Pool — bei voller Queue wird der Upload sofort mit Status `FAILED` beantwortet, statt die Aufgabe still zu verwerfen |
| `OPAA_UPLOAD_PENDING_RECOVERY_THRESHOLD_MINUTES` | `30` | `30` | Minuten, nach denen ein noch `PENDING` hängender Upload beim nächsten Anwendungsstart als durch einen Neustart abgebrochen auf `FAILED` gesetzt wird (#614) |
| `OPAA_UPLOAD_LIBRARY_QUOTA_BYTES` | `10737418240` (10 GiB, Byte) | `10737418240` | Speicherkontingent je Wissensbibliothek (#119) — Summe der `file_size`-Spalte aller Dokumente einer Bibliothek, durchgesetzt am Upload-Endpunkt (413) **und** an allen drei Konnektorpfaden (FILESYSTEM/HTTP_DIRECTORY/RSS_FEED, dort als übersprungenes Dokument mit `REJECTED`-Ereignis im Laufprotokoll). Zählt den *Bibliotheksinhalt* (die Größe der Quelldateien), nicht den von OPAA tatsächlich belegten Plattenplatz — bei HTTP_DIRECTORY/RSS_FEED liegen die Dateien nur temporär auf der Platte, OPAA behält dauerhaft nur die Chunks im Vektorspeicher; ein Betreiber sieht deshalb ggf. „10 GiB belegt", obwohl der eigene Plattenverbrauch deutlich kleiner ist. **`0` oder ein negativer Wert deaktiviert das Kontingent vollständig** (kein Rückfall auf den Default) — wichtig für Bestandsinstallationen mit Bibliotheken über 10 GiB: das Kontingent wirkt rückwirkend auf bereits gewachsene Bibliotheken, ein Update auf diese Version würde dort sonst jeden weiteren Upload und jedes weitere Konnektordokument ablehnen, bis die Bibliothek unter das Kontingent geschrumpft ist. |
| **pgvector** | | | |
| `OPAA_PGVECTOR_DIMENSIONS` | `1536` | `1536` | Vektor-Dimensionen (muss mit Embedding-Modell übereinstimmen) |
| `OPAA_PGVECTOR_DISTANCE_TYPE` | `cosine_distance` | `cosine_distance` | Distanzfunktion für Ähnlichkeitssuche |
| **Rate Limiting** | | | |
| `OPAA_RATE_LIMIT_ENABLED` | `true` | `true` | Rate Limiting aktivieren/deaktivieren |
| `OPAA_RATE_LIMIT_QUERY_MAX_REQUESTS` | `10` | `10` | Max. Abfrageanfragen pro IP pro Fenster |
| `OPAA_RATE_LIMIT_QUERY_WINDOW_SECONDS` | `60` | `60` | Abfrage-Rate-Limit-Fenster in Sekunden |
| `OPAA_RATE_LIMIT_QUERY_GLOBAL_MAX_REQUESTS` | `100` | `100` | Max. Abfrageanfragen über alle IPs pro Fenster |
| `OPAA_RATE_LIMIT_INDEXING_MAX_REQUESTS` | `1` | `1` | Max. Indizierungsanfragen pro IP pro Fenster |
| `OPAA_RATE_LIMIT_INDEXING_WINDOW_SECONDS` | `60` | `60` | Indizierungs-Rate-Limit-Fenster in Sekunden |
| `OPAA_RATE_LIMIT_INDEXING_GLOBAL_MAX_REQUESTS` | `5` | `5` | Max. Indizierungsanfragen über alle IPs pro Fenster |
| `OPAA_RATE_LIMIT_SOURCE_TEST_MAX_REQUESTS` | `10` | nicht gesetzt (Anwendungs-Default gilt) | Max. Verbindungstests (`POST /api/v1/libraries/source-test`) pro IP pro Fenster (#514) |
| `OPAA_RATE_LIMIT_SOURCE_TEST_WINDOW_SECONDS` | `60` | nicht gesetzt (Anwendungs-Default gilt) | Verbindungstest-Rate-Limit-Fenster in Sekunden |
| `OPAA_RATE_LIMIT_SOURCE_TEST_GLOBAL_MAX_REQUESTS` | `30` | nicht gesetzt (Anwendungs-Default gilt) | Max. Verbindungstests über alle IPs pro Fenster |
| **Verzeichnis-Synchronisation (Gruppen, #237)** | | | |
| `OPAA_DIRECTORY_SYNC_CHANGE_THRESHOLD_FRACTION` | `0.3` | nicht gesetzt (Anwendungs-Default gilt) | Plausibilitätsschwelle: Würde ein Synchronisationslauf mehr als diesen Anteil der bestehenden Gruppenmitgliedschaften entfernen, wird er verworfen und gemeldet statt angewendet — Schutz vor einer fehlkonfigurierten Verzeichnisquelle, die scheinbar fast alle Mitgliedschaften löscht. Gemessen ausschließlich an Entfernungen, nicht an Hinzufügungen. Muss echt größer als `0` und höchstens `1` sein — ein ungültiger Wert lässt den Start fehlschlagen, statt sich stillschweigend zu lockern |
| **Authentifizierung** | | | |
| `SPRING_PROFILES_ACTIVE` | ohne Angabe ist das Spring-Profil `local` aktiv (`spring.profiles.default: local` in `application.yml`) — das enthält aber weder `oidc` noch `dev`, sodass `io.opaa.auth.AuthProfileGuard` den Start trotzdem mit einer Fehlermeldung abbricht | `docker,dev` | Muss `oidc` (Betrieb) oder `dev` (Entwicklung/Tests) enthalten; ohne eines der beiden startet das Backend nicht — das gilt für den Auth-Modus, nicht für das Spring-Profil an sich, das auch ohne Angabe einen Wert (`local`) hat. Für Betrieb mit dem gebündelten Keycloak stattdessen `docker,oidc` setzen (siehe [„OIDC (Keycloak)"](#oidc-keycloak) unten) |
| `OPAA_INITIAL_ADMIN_EMAIL` | `admin@opaa.local` | `admin@opaa.local` | E-Mail für den automatisch erstellten initialen Admin-Benutzer |
| **Entwicklungs-Auth (`dev`)** | | | |
| `OPAA_AUTH_DEV_ISSUER` | `opaa-dev` | `opaa-dev` | Issuer-Claim der synthetischen Tokens |
| `OPAA_AUTH_DEV_DEFAULT_USER` | `dev-admin` | `dev-admin` | Nutzer, als der ohne `X-OPAA-Dev-User`-Header authentifiziert wird — `.env.docker.example` setzt diesen Block, weil `docker,dev` der Compose-Standardfall ist, und erklärt dort auch die vorkonfigurierten Nutzer |
| **Zugangsdaten-Verschlüsselung** | | | |
| `OPAA_CREDENTIALS_ENCRYPTION_KEY` | — (leer) außerhalb des Profils `dev`; im Profil `dev` fest hinterlegter, **ausdrücklich nicht produktionstauglicher** Schlüssel (siehe [„Zugangsdaten-Verschlüsselung"](#zugangsdaten-verschlüsselung-483) unten) | nicht gesetzt (auskommentiert — bewusst, siehe Kommentar in `.env.docker.example`) | Base64-kodierter AES-256-Schlüssel (32 rohe Byte) zur Verschlüsselung von `knowledge_libraries.source_credentials` ruhend in der Datenbank. **Ohne Voreinstellung außerhalb des Profils `dev`; erforderlich, sobald eine Bibliothek mit Zugangsdaten gespeichert wird** |
| **OIDC** | | | |
| `OPAA_OIDC_JWK_SET_URI` | `http://localhost:8180/realms/opaa/protocol/openid-connect/certs` | `http://keycloak:8180/realms/opaa/protocol/openid-connect/certs` | JWK-Set-URI für Token-Verifizierung. Die Compose-Belegung weicht bewusst vom Anwendungs-Default ab: Der Backend-Container muss den Docker-internen Hostnamen `keycloak` verwenden, siehe Hinweis unter [„OIDC (Keycloak)"](#oidc-keycloak) unten |
| `OPAA_OIDC_ISSUER_URI` | `http://localhost:8180/realms/opaa` | `http://localhost:8180/realms/opaa` | OIDC-Issuer-URI für Token-Validierung — bleibt `localhost`, weil der Browser diese URL verwendet |
| `OPAA_OIDC_AUTHORITY` | `http://localhost:8180/realms/opaa` | `http://localhost:8180/realms/opaa` | OIDC-Authority-URL (vom Frontend verwendet) |
| `OPAA_OIDC_CLIENT_ID` | `opaa-frontend` | `opaa-frontend` | OIDC-Client-ID |
| `OPAA_CSP_CONNECT_SRC_EXTRA` | — (kein Spring-Property; leer als Image-Default des Frontend-nginx-`envsubst`-Templates) | nicht gesetzt (auskommentiert) — der Compose-Standardfall `docker,dev` startet keinen Keycloak (Compose-Profil `oidc`); der Kommentar in `.env.docker.example` verweist auf den Wechsel zu `docker,oidc` | Zusätzliche Origin(s) in der `connect-src`-Richtlinie des Frontend-nginx, leerzeichengetrennt bei mehreren. Erforderlich, wenn die OIDC-Authority auf einem anderen Origin liegt als das Frontend selbst — sonst blockiert die Content-Security-Policy die OIDC-Anmeldung stillschweigend (#409/#670) |
| `OPAA_DEMO_MODE` | — (kein Spring-Property; `"false"` als Image-Default des Frontend-nginx-`envsubst`-Templates, siehe `frontend/Dockerfile`) | nicht gesetzt (auskommentiert) | Schaltet den Quellen- und Demo-Hinweis in der Fußzeile der Oberfläche ein (#230, `frontend/src/layouts/DemoNotice.tsx`) — **nur für Demo-Instanzen gedacht**, nicht für reguläre OPAA-Installationen. Nach demselben Muster wie `OPAA_CSP_CONNECT_SRC_EXTRA`: `frontend/nginx.conf` setzt den Wert beim Containerstart per `envsubst` in eine kleine, gleichen-Origin-JavaScript-Antwort unter `/runtime-config.js` (normalisiert über eine nginx-`map`-Direktive auf ein literales `true`/`false`, damit ein fehlerhafter Wert nie ungeprüft in die Antwort gelangt) — kein Rebuild des Images nötig, um den Hinweis umzuschalten |
| **Docker-Compose-Ports** | | | |
| `OPAA_BACKEND_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `8081`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — `.env.docker.example` lässt die Variable deshalb bewusst auskommentiert; ohne Shell-Export gilt der Compose-Default `8081` | Backend-Host-Port |
| `OPAA_FRONTEND_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `3000`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — `.env.docker.example` lässt die Variable deshalb bewusst auskommentiert; ohne Shell-Export gilt der Compose-Default `3000` | Frontend-Host-Port |
| `OPAA_KEYCLOAK_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `8180`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `8180` | Keycloak-Host-Port (Compose-Profile `oidc` und, seit #712, `demo` — `keycloak` gehört beiden an, siehe `demo/README.md`) |
| `OPAA_DEMO_CORPUS_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `8091`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `8091` | Host-Port des Rheinfurt-Demo-Korpus-Webservers `demo-corpus` (nur `demo`-Compose-Profil, `demo/README.md`), zum Prüfen der drei `HTTP_DIRECTORY`-Verzeichnislistings im Browser — an `127.0.0.1` gebunden, kein öffentlicher Zugang |
| `OPAA_DEMO_PRESSE_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `8092`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `8092` | Host-Port des Rheinfurt-Demo-Pressestelle-Webservers `demo-presse` (nur `demo`-Compose-Profil, `demo/README.md`), zum Prüfen von RSS-Feed und HTML-Detailseiten im Browser — an `127.0.0.1` gebunden, kein öffentlicher Zugang |
| `OPAA_ENV_FILE` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `.env.docker`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` selbst (zirkulär — siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt | Wählt die `env_file`, aus der `docker-compose.yml` die Container-Umgebung lädt (Standard `.env.docker`). Die E2E-Suite setzt sie per Prozessumgebung auf `e2e/e2e.env`, um denselben `docker-compose.yml` mit einem eigenen, von der Entwickler-`.env.docker` unabhängigen Umgebungssatz zu betreiben (siehe `e2e/scripts/run-e2e.mjs`) |

**Laufzeit und Speicher eines RSS-Laufs.** Die Politeness-Wartezeit (`OPAA_INDEXING_RSS_REQUEST_DELAY_MS`, Voreinstellung 1000 ms) gilt für jede Anfrage einzeln — Detailseite und jede einzelne Anlage. Mit den Voreinstellungen (200 Einträge, bis zu 10 Anlagen je Eintrag) dauert ein Lauf, der bei jedem Eintrag das Limit ausschöpft, im ungünstigsten Fall rund 200 × 11 × 1 s ≈ 37 Minuten. Jede Anlage wird vor dem Schreiben auf die temporäre Datei vollständig in den Heap gelesen (`UrlFileDownloader#downloadBounded`) — bis zu `OPAA_INDEXING_RSS_MAX_ATTACHMENT_SIZE_BYTES` (Voreinstellung 20 MiB) je Anlage. Bei knapp bemessenem Heap `OPAA_INDEXING_THREAD_POOL_MAX_SIZE` und die Anlagen-Obergrenzen entsprechend niedriger wählen.

### Netzwerkzugang

Standardmäßig bindet das Backend an `localhost`. Um OPAA von anderen Geräten im Netzwerk zugänglich zu machen, setzen Sie:

```env
OPAA_SERVER_ADDRESS=0.0.0.0
```

> **Hinweis:** In Docker Compose **muss** `OPAA_SERVER_ADDRESS` auf `0.0.0.0` gesetzt werden, damit das Backend vom Nginx-Reverse-Proxy des Frontend-Containers erreichbar ist.

> **TLS-terminierender Reverse-Proxy davor?** Das Backend wertet `X-Forwarded-*` aus (`server.forward-headers-strategy: framework`, seit [#553](https://github.com/criew/opaa/issues/553)), damit Browser-Anfragen desselben Origins hinter dem Proxy nicht fälschlich als cross-origin behandelt werden. Der äußere Proxy **muss** `X-Forwarded-Proto` dabei autoritativ setzen (`proxy_set_header X-Forwarded-Proto $scheme;`) und darf den Wert nicht vom Client durchlassen — ein gespooftes `https` würde sonst die CORS-Prüfung umgehen. Der nginx im Frontend-Container reicht ein eingehendes `X-Forwarded-Proto` unverändert weiter (Fallback: eigenes Schema).

#### Sicherheits-Header und `Strict-Transport-Security`

Der nginx im Frontend-Container (`frontend/nginx.conf`) setzt seit [#409](https://github.com/criew/opaa/issues/409) `Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` und `server_tokens off` auf jede Antwort (auch Fehlerantworten, über `add_header ... always;`). Die Content-Security-Policy setzt [ADR-0004](decisions/0004-self-hosted-frontend-resources.md) technisch durch: Skripte, Stile, Schriften und Verbindungen sind auf die eigene Herkunft begrenzt, keine externe Quelle ist erlaubt (bis auf die unten beschriebene, gezielte Ausnahme für den OIDC-Anbieter). `style-src` erlaubt zusätzlich `'unsafe-inline'`, weil MUIs Emotion-Engine Stile zur Laufzeit über eingebettete `<style>`-Tags einfügt — dafür gibt es ohne Nonce-Unterstützung in Emotion keinen strikteren Weg. `object-src 'none'` ist gesetzt, weil die Anwendung keine `<object>`/`<embed>`-Inhalte einbettet.

Auf `/api/`-Antworten setzt zusätzlich Spring Security eigene `X-Content-Type-Options`/`X-Frame-Options`-Header; `proxy_hide_header` in `location /api/` entfernt diese, damit der Client nur die eine, am nginx gesetzte Kopie sieht statt beide Werte doppelt.

**`Strict-Transport-Security` wird hier bewusst nicht gesetzt.** Der Frontend-Container terminiert im Compose-Betrieb kein TLS — er spricht selbst nur `http` (siehe Hinweis zu `X-Forwarded-Proto` oben). Diesen Header trotzdem hier zu setzen wäre wirkungslos für Installationen ohne vorgelagerten TLS-Weg und schädlich für solche mit einem: HSTS an zwei Stellen im selben Antwortpfad zu pflegen — hier und am vorgelagerten Proxy — schafft nur eine weitere Möglichkeit, dass beide auseinanderlaufen (z. B. unterschiedliches `max-age` oder `includeSubDomains`), ohne einen Sicherheitsgewinn gegenüber einer einzigen, korrekt gepflegten Stelle. Wer OPAA hinter einem TLS-terminierenden Proxy betreibt (siehe Hinweis oben und die öffentliche Testinstanz weiter unten), setzt `Strict-Transport-Security` an genau diesem äußeren Proxy — dort, wo TLS tatsächlich endet.

**`connect-src` und ein OIDC-Anbieter auf fremdem Origin.** `oidc-client-ts` holt die OIDC-Discovery-Metadaten und tauscht später den Auth-Code gegen Tokens jeweils per `fetch` direkt aus dem Browser gegen die Authority — liegt die Authority nicht auf demselben Origin wie das Frontend, blockiert eine reine `connect-src 'self'`-Richtlinie diese Aufrufe **stillschweigend** (kein Fehler in der Oberfläche, die Anmeldung tut einfach nichts). `frontend/nginx.conf` ist deshalb kein fest gebackenes Ergebnis mehr, sondern ein `envsubst`-Template (`/etc/nginx/templates/default.conf.template`, siehe `frontend/Dockerfile`): Die Umgebungsvariable `OPAA_CSP_CONNECT_SRC_EXTRA` wird beim Containerstart in `connect-src 'self' ${OPAA_CSP_CONNECT_SRC_EXTRA}` eingesetzt (leer per Voreinstellung im Image, in `.env.docker.example`/`.env.docker` mit dem Origin des mitgelieferten Keycloak vorbelegt). `NGINX_ENVSUBST_FILTER=^OPAA_(CSP|DEMO)_` im Dockerfile begrenzt die Ersetzung auf `OPAA_CSP_*`- und `OPAA_DEMO_*`-Variablen (Letztere für den Demo-Hinweis, siehe `OPAA_DEMO_MODE` oben), damit `envsubst` nicht versehentlich echte nginx-Variablen (`$scheme`, `$host`, `$remote_addr`, …) in derselben Datei anfasst. Details zum Betrieb mit einem eigenen Behörden-Identitätsanbieter: Abschnitt „OIDC (Keycloak)" unten.

Die genannten Header sind gegen den Produktions-Build (`npm run build`) und das gebaute Docker-Image verprobt: `dist/index.html` referenziert ausschließlich selbst gehostete, gehashte `<script>`- und `<link>`-Dateien (keine Inline-Skripte), und ein Abruf des laufenden Containers zeigt alle Header sowohl auf `/` als auch auf `/api/`-Antworten (Vererbung über den `server`-Block, siehe Kommentare in `frontend/nginx.conf`).

Für die lokale Entwicklung auch das Frontend mit `npm run dev -- --host` starten und den Zugriffsursprung zu CORS hinzufügen:

```env
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://your-hostname:5173
```

### LLM-Anbieter

**Voreingestellt sind lokal betriebene Modelle** über Ollama, für Chat und für Einbettung. Eine
Installation, an der niemand etwas konfiguriert, ruft kein Modell außerhalb des Hauses auf. Diese
Voreinstellung ist so gewollt und bleibt (siehe
[ADR-0014, Nachtrag vom 14.08.2026](decisions/0014-produktausrichtung-oeffentliche-verwaltung.md#nachträge-entschiedene-punkte)).

```env
OPAA_AI_CHAT_PROVIDER=ollama
OPAA_AI_EMBEDDING_PROVIDER=ollama
OPAA_OLLAMA_BASE_URL=http://localhost:11434
```

Um stattdessen einen openai-kompatiblen Anbieter zu verwenden, sind Anbieter **und Zieladresse**
anzugeben:

```env
OPAA_AI_CHAT_PROVIDER=openai
OPAA_OPENAI_BASE_URL=https://modellserver.example.internal/v1
OPAA_OPENAI_API_KEY=sk-your-key-here
```

`OPAA_OPENAI_CHAT_BASE_URL` und `OPAA_OPENAI_EMBEDDING_BASE_URL` überschreiben die Adresse je
Funktion; ohne sie gilt `OPAA_OPENAI_BASE_URL` für beide.

> **Es gibt keine technische Sperre**, die einen Aufruf außerhalb festgelegter Netzbereiche
> verhindert. Wer zusichern muss, dass keine Daten das Haus verlassen, weist die Konfiguration nach
> und sichert den Netzweg außerhalb von OPAA ab — siehe
> [Modelle und zentrale Steuerung](features/llm-integration.md#was-heute-gilt-und-was-nicht-gebaut).

### vLLM / OpenAI-kompatible Server

Bei Verwendung von vLLM oder anderen OpenAI-kompatiblen Servern, die HTTP/2 nicht unterstützen, HTTP/1.1-Modus aktivieren:

```env
OPAA_HTTP_FORCE_HTTP1=true
OPAA_OPENAI_BASE_URL=http://your-vllm-server:8000/v1
```

Dies ist erforderlich, weil Spring Boots Standard-HTTP-Client HTTP/2 bevorzugt, was bei Uvicorn-basierten Servern wie vLLM zu Verbindungsfehlern führt.

### Zugangsdaten-Verschlüsselung (#483)

Eine Bibliothek vom Quellentyp `HTTP_DIRECTORY` oder `RSS_FEED` kann Zugangsdaten
(`sourceCredentials`, Basic-Auth-Format `user:password`) tragen. Diese liegen **verschlüsselt** in
der Datenbank (AES-256-GCM, zufälliger Initialisierungsvektor je Wert) und erscheinen in keiner
API-Antwort und keinem Log (ADR-0018, Entscheidung 4) — die API behandelt das Feld als
Nur-Schreiben-Feld.

**Schlüssel erzeugen und setzen:**

```bash
openssl rand -base64 32
```

Das Ergebnis (ein Base64-kodierter 256-Bit-Schlüssel) als `OPAA_CREDENTIALS_ENCRYPTION_KEY` setzen
— **nicht** ins Repository committen, wie jede andere Zugangsinformation außerhalb von
Umgebungsvariablen behandeln (Passwort-Tresor, Secret-Manager der Zielumgebung o. Ä.).

**Kein Zwang, keinen zu haben:** Ohne gesetzten Schlüssel startet das Backend normal — die meisten
Bibliotheken (`UPLOAD`, `FILESYSTEM`, oder ein `HTTP_DIRECTORY`/`RSS_FEED` ohne Zugangsdaten)
brauchen keinen. Erst der Versuch, eine Bibliothek **mit** Zugangsdaten anzulegen oder zu ändern,
schlägt ohne gültigen Schlüssel mit `503` fehl (`io.opaa.security.CredentialsEncryptor`) — eine klare
Fehlermeldung statt eines unspezifischen `500`. Für lokale Entwicklung und Tests (nur Profil `dev`,
das jede Testsuite und `bootRun` aktivieren — nicht `local`) ist ein fest hinterlegter, **ausdrücklich
nicht produktionstauglicher** Schlüssel voreingestellt (`backend/src/main/resources/application.yml`),
damit beide ohne Betreiber-Eingriff laufen.

**Lesen scheitert weich, Schreiben hart:** Ein Wert, der mit dem aktuellen Schlüssel nicht mehr
entschlüsselt werden kann (Schlüssel verloren, rotiert, oder ein beschädigter Datenbankwert), lässt
das **Lesen** dieser einen Bibliothek nicht scheitern — `SourceCredentialsConverter` protokolliert
eine Warnung (ohne den Wert, ohne die Bibliotheks-ID) und behandelt `sourceCredentials` für diese
Bibliothek als nicht gesetzt; `GET /api/v1/libraries` liefert die übrige Liste normal, auch wenn eine
einzelne Bibliothek betroffen ist. Ein **Schreibvorgang** (Anlegen oder Ändern von Zugangsdaten)
scheitert dagegen weiterhin hart mit `503`, statt Zugangsdaten stillschweigend zu verlieren.

**Bei Schlüsselverlust:** Bereits verschlüsselte `sourceCredentials`-Werte sind ohne den
ursprünglichen Schlüssel nicht wiederherstellbar — es gibt keinen Wiederherstellungsweg außerhalb des
Schlüssels selbst. Betroffen sind ausschließlich die Zugangsdaten selbst, nicht die übrige
Bibliothekskonfiguration oder der bereits indizierte Bestand. Abhilfe: Zugangsdaten über die
bestehende Bibliotheks-API (`PATCH /api/v1/libraries/{id}`) neu setzen — derselbe Weg, über den
Zugangsdaten ohnehin rotiert werden. Dank des weichen Lesefehlers oben funktioniert dieser
Reparaturweg tatsächlich: das vorausgehende Laden der Bibliothek scheitert nicht mehr an genau dem
Wert, der repariert werden soll. Bis zur Reparatur läuft ein laufender oder künftiger
Indizierungslauf für diese Bibliothek ohne Anmeldung und kann entsprechend scheitern, wenn die
Quelle Zugangsdaten verlangt.

**Altbestand vor #483:** Werte, die vor Einführung dieser Verschlüsselung im Klartext geschrieben
wurden, erkennt `CredentialsEncryptor` beim Lesen an einem fehlenden `enc:`-Präfix und behandelt sie
weiterhin als Klartext — eine Liquibase-Migration kann sie nicht verschlüsseln, da der Schlüssel nur
der Anwendung bekannt ist. Der nächste Schreibvorgang auf dieselbe Bibliothek (z. B. eine
Zugangsdaten-Rotation über die bestehende Update-API) verschlüsselt den Wert. Ein Wert mit `enc:`-
Präfix, aber unbekannter Version (nicht `enc:v1:`), wird dagegen nie als Klartext behandelt — Lesen
scheitert weich (siehe oben), Schreiben hart.

## Authentifizierung

OPAA kennt genau zwei Auth-Modi ([ADR-0005](decisions/0005-authentication-strategy.md)). Der Modus
wird über das aktive Spring-Profil gewählt; ist weder `oidc` noch `dev` gesetzt, **bricht das
Backend den Start mit einer Fehlermeldung ab**.

| Modus | Profil | Zweck |
|-------|--------|-------|
| `oidc` | `oidc` | Der einzige für den Betrieb zulässige Modus |
| `dev` | `dev` | Lokale Entwicklung und automatisierte Tests — **keinerlei Prüfung von Anmeldedaten** |

### Entwicklungsmodus (`dev`)

```env
SPRING_PROFILES_ACTIVE=docker,dev
```

Es gibt keinen Anmeldevorgang und kein Token: Jede Anfrage wird als einer der konfigurierten
Entwicklungsnutzer authentifiziert. Vorkonfiguriert sind `dev-admin` (E-Mail `admin@opaa.local`,
wird durch `OPAA_INITIAL_ADMIN_EMAIL` zum `SYSTEM_ADMIN`) und `dev-user` (regulärer Nutzer).

Den Nutzer wechselt man im Browser über den Query-Parameter `?devUser=dev-user`, der für die Dauer
der Browser-Session gemerkt wird; direkte API-Aufrufe setzen stattdessen den Header
`X-OPAA-Dev-User`. Ein unbekannter Nutzername führt zu `401`.

> **Warnung:** Dieser Modus deaktiviert die Authentifizierung vollständig. Er gehört ausschließlich
> auf Arbeitsplätze und in Testumgebungen, nie auf eine erreichbare Instanz. Das Backend
> protokolliert beim Start eine entsprechende Warnung.

### OIDC (Keycloak)

Um OIDC-Authentifizierung mit dem gebündelten Keycloak zu aktivieren:

```bash
docker compose --profile oidc up --build
```

Erforderliche Variablen in `.env.docker`:

```env
SPRING_PROFILES_ACTIVE=docker,oidc
OPAA_OIDC_JWK_SET_URI=http://keycloak:8180/realms/opaa/protocol/openid-connect/certs
```

> **Wichtig:** `OPAA_OIDC_JWK_SET_URI` muss den Docker-internen Hostnamen `keycloak` (nicht `localhost`) verwenden, weil der Backend-Container JWT-Tokens verifiziert, indem er Schlüssel von Keycloak abruft. `OPAA_OIDC_ISSUER_URI` und `OPAA_OIDC_AUTHORITY` sollten `http://localhost:8180/...` bleiben, da der Browser diese URLs verwendet.

> **Ebenfalls erforderlich:** `OPAA_CSP_CONNECT_SRC_EXTRA=http://localhost:8180` (in `.env.docker.example` auskommentiert, da der Compose-Standardfall `docker,dev` keinen Keycloak startet — beim Wechsel zu `docker,oidc` die Zeile einkommentieren) — ohne diese Variable blockiert die Content-Security-Policy des Frontend-nginx den Aufruf gegen die Keycloak-Authority stillschweigend, siehe [„Sicherheits-Header und `Strict-Transport-Security`"](#sicherheits-header-und-strict-transport-security) oben. Bei einem produktiven Identitätsanbieter auf eigener Adresse (Entra ID, ein hausweiter Keycloak) tritt dessen Origin hier an die Stelle von `http://localhost:8180`; mehrere Origins werden durch Leerzeichen getrennt.

Ein Testbenutzer ist im Keycloak-Realm vorkonfiguriert:
- **Benutzername:** `testuser`
- **Passwort:** `testpass`

Die Keycloak-Admin-Konsole ist unter http://localhost:8180 verfügbar (admin/admin).

### Testkonten im Überblick

Im Repository existieren mehrere Testkonto-Muster nebeneinander. Sie sind **bewusst nicht
vereinheitlicht** — jedes gehört zu einem anderen Auth-Modus bzw. einer anderen Konfigurationsebene
und folgt dessen jeweils eigenem Mechanismus:

| Muster | Geltungsbereich | Nutzer |
|--------|------------------|--------|
| Entwicklungsnutzer des `dev`-Profils (`opaa.auth.dev.users`) | Lokale Entwicklung, `dev`-Auth-Modus (siehe [„Entwicklungsmodus (dev)"](#entwicklungsmodus-dev) oben) | `dev-admin` (`admin@opaa.local`, `SYSTEM_ADMIN`), `dev-user` (regulärer Nutzer) |
| Keycloak-Realm-Nutzer (`keycloak/realm-export.json`) | `oidc`-Auth-Modus mit dem gebündelten Keycloak (siehe [„OIDC (Keycloak)"](#oidc-keycloak) oben) | `testuser`/`testpass` (E-Mail `test@opaa.local`) — wird zum `SYSTEM_ADMIN`, sobald `OPAA_INITIAL_ADMIN_EMAIL` in der lokalen `.env.docker` auf dieselbe Adresse gesetzt ist, sonst ein regulärer Nutzer |
| Demo-Realm-Nutzer (`keycloak/realm-export.json`, Issue #712) | Demo-Instanz „Stadt Rheinfurt" (`--profile demo`, siehe [`demo/README.md`](../demo/README.md), Abschnitt „Seed ausführen") | `demo-admin` (`admin@stadt-rheinfurt.example`, `SYSTEM_ADMIN` bei entsprechend gesetztem `OPAA_INITIAL_ADMIN_EMAIL`), `maria.weber`, `selin.kaya`, `thomas.klein`, `andrea.vogt` — alle mit dem offenen Demo-Passwort `RheinfurtDemo!2026`, siehe `demo/README.md`, Abschnitt „Demo-Zugangsdaten". Zusätzlich der Client `opaa-seed` (Resource Owner Password Grant, `directAccessGrantsEnabled: true`) — ausschließlich für `demo/seed/seed.py`, nie für eine reguläre Anmeldung |
| E2E-Suite (`e2e/e2e.env`, `e2e/docker-compose.e2e.yml`) | Playwright-Suite (siehe [`e2e/README.md`](../e2e/README.md), Abschnitt „Drei Testnutzer") | Wiederverwendet `dev-admin` und `dev-user` aus dem `dev`-Profil, ergänzt um `dev-outsider` (nur für diese Suite, über `OPAA_AUTH_DEV_USERS_*` hinzugefügt) |
| Quellenzugangsdaten (`sourceCredentials`, siehe [„Zugangsdaten-Verschlüsselung"](#zugangsdaten-verschlüsselung-483) oben) | Kein Testkonto für OPAA selbst — Basic-Auth-Zugangsdaten (`user:password`), mit denen eine `HTTP_DIRECTORY`- oder `RSS_FEED`-Bibliothek eine *externe* Dokumentenquelle abruft | Kein fester Beispielwert; frei je Bibliothek |

Warum keine Vereinheitlichung:

- Der `dev`-Modus prüft grundsätzlich **keine** Anmeldedaten — er authentifiziert jede Anfrage
  ungeprüft als einen der konfigurierten Nutzer (siehe oben). Ein Passwort dafür zu vergeben wäre
  irreführend, weil keines abgefragt wird.
- Der Keycloak-`testuser` ist dagegen ein echtes, wenn auch bewusst schwaches Credential innerhalb
  eines vollständigen OIDC-Identitätsanbieters — ein anderer Mechanismus mit eigenem Lebenszyklus
  (Realm-Import), der sich nicht auf `dev`-Nutzer abbilden lässt.
- `sourceCredentials` ist kein Konto zum Anmelden bei OPAA, sondern die Zugangsdaten, mit denen das
  Backend selbst eine externe Quelle kontaktiert — fachlich und im Lebenszyklus unabhängig von den
  beiden Auth-Modi oben.
- Die E2E-Suite legt bewusst **kein** eigenes Kontoschema an, sondern läuft im `dev`-Auth-Modus und
  nutzt dessen Nutzer weiter (siehe [„Warum der `dev`-Auth-Modus?"](../e2e/README.md#warum-der-dev-auth-modus) in `e2e/README.md`).
- Ein früher skizziertes, separates `OPAA_AUTH_BASIC_USERNAME`/`OPAA_AUTH_BASIC_PASSWORD`/
  `OPAA_AUTH_MODE`-Paar für eine app-globale Basic-Auth (Modi `mock`/`basic`) wurde mit
  [`fd04246`](https://github.com/criew/opaa/commit/fd0424621874270a2be78f05bfee5c550945fd3f)
  ersatzlos entfernt — die zugehörigen Konfigurationsschlüssel liest das Backend nicht mehr,
  Authentifizierung läuft ausschließlich über einen der beiden Modi oben. Eine lokale,
  eingerichtete `.env.docker` (gitignored, nicht Teil dieses Repositories) kann diesen Block aus
  der Zeit vor `fd04246` noch enthalten; er ist dann wirkungsloser Altbestand, den ein
  `docker compose up` stillschweigend ignoriert — löschen oder gegen die aktuelle
  `.env.docker.example` abgleichen.

## Dokumente

Dokumente im `./documents`-Verzeichnis ablegen (oder `OPAA_INDEXING_DOCUMENT_PATH_HOST` in `.env.docker` ändern). Das Verzeichnis wird in den Backend-Container unter `/app/documents` gemountet.

## Datenbank

PostgreSQL-Daten werden in einem Docker-Volume (`opaa-postgres-data`) gespeichert. Daten überleben `docker compose down`- und `docker compose up`-Zyklen.

Datenbank zurücksetzen:

```bash
docker compose down -v
```

> **Hinweis:** `docker compose down -v` muss ausgeführt werden, wenn `OPAA_DB_USERNAME` oder `OPAA_DB_PASSWORD` geändert wird, weil PostgreSQL den initialen Benutzer nur beim ersten Start erstellt. Ohne Volume-Entfernung werden Anmeldeinformationsänderungen ignoriert.

## Fehlerbehebung

### Backend gibt leere Antworten oder "Connection refused" zurück

Das Backend bindet standardmäßig an `localhost`, was nur von innerhalb des Containers erreichbar ist. `OPAA_SERVER_ADDRESS=0.0.0.0` in `.env.docker` setzen.

### POST-Anfragen geben 403 Forbidden zurück

CORS ist wahrscheinlich falsch konfiguriert. Sicherstellen, dass `OPAA_CORS_ALLOWED_ORIGINS` mit der Frontend-URL übereinstimmt (z. B. `http://localhost:3000`). GET-Anfragen können funktionieren, weil sie keinen CORS-Preflight auslösen, während POST-Anfragen mit `Content-Type: application/json` dies tun.

### Passwort-Authentifizierung für Benutzer fehlgeschlagen

Das PostgreSQL-Volume enthält noch Daten von einer früheren Initialisierung mit anderen Anmeldeinformationen. `docker compose down -v` ausführen, um das Volume zu entfernen und neu zu starten.

### OIDC: "Completing sign in..." hängt

- Sicherstellen, dass Keycloak läuft (`docker compose --profile oidc ps`)
- Wenn Keycloak neu gestartet wurde, ist das Access-Token möglicherweise abgelaufen — Seite neu laden und erneut anmelden
- Prüfen, dass `OPAA_OIDC_JWK_SET_URI` `keycloak:8180` (nicht `localhost:8180`) verwendet

### Umgebungsvariablen-Änderungen treten nicht in Kraft

Nach dem Ändern von `.env.docker` `docker compose up -d <service>` (nicht `restart`) verwenden, um den Container mit der neuen Umgebung neu zu erstellen. `restart` verwendet den vorhandenen Container erneut und ignoriert `.env.docker`-Änderungen.

## Stoppen

```bash
docker compose down
```
