# ADR-0057: Teststufen für Konnektoren

## Status

Akzeptiert · zuletzt geändert 2026-10-24 · ergänzt ADR-0009

## Kontext

Epic #1293 fragte, wie Konnektoren getestet werden, deren Quellsystem sich nicht ohne Weiteres in
einer Testumgebung aufsetzen lässt (Confluence, S3, SharePoint, DMS, OIDC-Anbieter). Zur Wahl standen
gemockte HTTP-Clients, Ersatzsysteme in Testcontainers, aufgezeichnete Antworten (Record/Replay) und
periodische Läufe gegen echte Instanzen mit Secrets in der CI.

Bis zur Entscheidung waren elf Quellarten gebaut. Jede hatte ihren Testweg selbst gefunden, und es
hatten sich wenige, wiederkehrende Muster herausgebildet: ein programmiertes Testdoppel des
Quellsystems im Testprozess, ein leichtes Ersatzsystem im Container innerhalb von `test`, ein schweres
echtes System im Container als eigene nächtliche Suite, für Google Drive eine nur lokal laufende Suite
gegen ein echtes Workspace. Dazu waren gemeinsame Vertragstests entstanden, die jeder entfernte
Konnektor bestehen muss. Was fehlte, war die Festlegung, welche Stufe Pflicht ist und nach welcher
Regel ein neuer Konnektor sein Muster wählt.

## Entscheidung

### 1. Pflicht für jeden Konnektor: Testdoppel und gemeinsame Verträge

Jeder Konnektor hat ein Testdoppel seines Quellsystems, das mit jedem `./gradlew test` läuft, und
besteht darüber die gemeinsamen Verträge, die auf ihn zutreffen:

- `RunSecretContract` (`indexing.spi`) für jeden entfernten Konnektor; `RunSecretContractCoverageTest`
  schlägt fehl, bis ein neuer registrierter Konnektor ihn ausführt.
- `SourceConnectorProfileContractTest` prüft die Profilangabe jedes registrierten Konnektors ohne
  eigenes Zutun.
- Eine Dateiablage (`FileStore`, ADR-0045) erweitert `FileStoreContract` beziehungsweise die passende
  Ableitung (`FileStoreChangeFeedContract`, `FileStoreFolderContract`, `FileStoreResumptionContract`)
  einmal je Teststufe, die sie hat.
- Ein Konnektor mit eigenem Client für sein Quellsystem (heute alle außer RSS und
  Webverzeichnis) hat einen `<Typ>LogLeakTest`: kein Geheimnis gelangt ins Protokoll.

Damit ist die Dokumentstrecke einmal im Kern getestet, je Konnektor nur die Quelle. Weitere Stufen
kommen hinzu, ersetzen das Testdoppel aber nie.

### 2. Muster „Testdoppel“: programmiertes Ersatzsystem im Testprozess

Das Testdoppel heißt `Fake<System>Server` und ist ein `com.sun.net.httpserver.HttpServer` im
Testklassenpfad (`FakeConfluenceServer`, `FakeDropboxServer`, `FakeDriveServer`, `FakeNextcloudServer`,
`FakeGraphServer` für SharePoint), bei einfachen Protokollen ein Server direkt im Test (RSS,
Webverzeichnis). Es bildet das Verhalten nach, das der Konnektor braucht: Seiten, Änderungsmarken,
Fehlerantworten, Drosselung. Ein Zustand wird programmiert, nicht aufgezeichnet. Ohne entferntes
Quellsystem genügt ein temporäres Verzeichnis (Dateisystem) oder ein In-Memory-Doppel des Ports
(`FakeS3ObjectStore`).

Das Muster ist der Standard und für SaaS-Systeme ohne lauffähiges Image der einzige Weg in der CI.

### 3. Muster „Container in `test`“: leichtes echtes System

Gibt es ein Image des echten Systems oder ein protokolltreues Ersatzsystem, das ohne Lizenz und ohne
Internet in wenigen Sekunden startet und klein bleibt (Richtwert: Start unter 10 s, Image unter
500 MB), läuft es über eine `<System>Fixture` mit Testcontainers innerhalb von `./gradlew test`. Das
gilt heute für S3 (`S3TestFixture`, RustFS) und SMB (`SambaFixture`, Samba). Der Container prüft das
Protokoll, das ein Testdoppel nur behauptet; der Vertrag aus Entscheidung 1 läuft gegen beide.

### 4. Muster „nächtliche Container-Suite“: schweres echtes System

Ein System, das über dem Richtwert aus Entscheidung 3 liegt, eine Lizenz oder Internet braucht, läuft
als eigene Suite:

- Tests unter `io.opaa.integration.<system>`, aus `test` ausgeschlossen;
- eigene Gradle-Aufgabe `<system>IntegrationTest` in `backend/build.gradle.kts`, eingetragen in
  `docs/entwicklung/build-und-ci.md`;
- Workflow `.github/workflows/<system>-integration.yml`: nächtlich, per `workflow_dispatch` und an
  einem PR per Label `<system>-suite`, kein Required Check.

Das gilt heute für Nextcloud, Confluence Data Center und Keycloak (Verzeichnis-Konnektor).

### 5. Echte Instanzen nur lokal beim Maintainer

Ein Lauf gegen ein echtes Fremdsystem (Mandant, Workspace, Cloud-Sandbox) ist optional und läuft nur
lokal beim Maintainer: eigene Gradle-Aufgabe, Zugangsdaten außerhalb des Repositorys, Test
übersprungen ohne sie, kein CI-Job und kein Secret, nur lesend. Die Einrichtung beschreibt eine
`README.md` neben dem Test. Beispiel: `GoogleDriveWorkspaceIntegrationTest` (ADR-0040). Ein solcher
Lauf belegt Annahmen, auf denen das Testdoppel beruht; weicht er ab, wird das Testdoppel angepasst.

### 6. Playwright-Suite nur für Abläufe der Oberfläche

Ein Konnektor erscheint in der E2E-Suite (ADR-0009) nur, wenn ein Ablauf der Oberfläche vom
Quellsystem abhängt, etwa die Anmeldung beim Anbieter oder Indizierung und Suche über den vollen
Stack. Das Quellsystem ist dann ein Dienst im Overlay `e2e/docker-compose.e2e.yml`: ein Stub im
Standard-`node`-Image (`dropbox-stub`), ein statischer Webserver (`rss-feed`) oder das echte Image,
wenn es ohnehin gebraucht wird (`nextcloud`). Die Fachlogik des Konnektors prüft die Suite nicht.

### 7. Ein neuer Konnektor legt sein Muster im Epic fest

Das Epic oder die Spezifikation eines neuen Konnektors nennt, welche Stufen er bekommt:
Entscheidung 1 immer, Entscheidung 3 oder 4 nach den dort genannten Bedingungen, wenn ein Image
existiert, Entscheidung 5 und 6 nach Bedarf. Abnahmekriterium ist, dass die gewählten Stufen grün
laufen. Die Regel steht auch im Modul-`AGENTS.md` der Konnektoren.

## Konsequenzen

Einfacher: Ein neuer Konnektor folgt einem von vier bekannten Mustern statt eines eigenen Konzepts;
Kern und Dokumentstrecke sind über die Verträge einmal getestet; die reguläre CI braucht kein Secret
und kein Fremdsystem.

Schwieriger: Ein Testdoppel für SaaS ist so gut wie die Annahmen seines Autors. Ändert ein Anbieter
seine API, fällt das erst im Betrieb oder beim lokalen Lauf aus Entscheidung 5 auf.

Restrisiken, als Lücke akzeptiert: Ratenbegrenzungen, Bot-Schutz und Weiterleitungsketten realer
Server bildet kein Muster vollständig ab. Ein Befund aus dem Betrieb wird als Verhalten ins Testdoppel
übernommen, zusammen mit einem Test, der ihn absichert.

## Verworfene Alternativen

- Record/Replay (WireMock mit aufgezeichneten Antworten) — starrer als ein programmiertes Testdoppel,
  braucht Pflege für Aufzeichnung, Geheimnisentfernung und Veralterung.
- Nächtliche CI-Läufe gegen echte SaaS-Mandanten mit Secrets — Kosten und Pflege von Konten,
  Ausfälle des Fremdsystems in der CI, Secrets im Repository-Kontext.
- Ein einziger Vertragstest für alle Quellarten — RSS, Webverzeichnis und Confluence haben keine
  Dateiablage; ihre Verträge sind `RunSecretContract` und eigene Port-Verträge.

## Offen

Nichts.
