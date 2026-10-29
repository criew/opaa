# Renovate — selbst betriebene Abhängigkeits-Updates

Renovate liefert Updates; ob eine Abhängigkeit dazwischen eine bekannte Schwachstelle hat, meldet
[`docs/cve-scanning.md`](./cve-scanning.md) (Dependabot-Alerts, Trivy-Image-Scan).

OPAA nutzt [Renovate](https://docs.renovatebot.com/) für automatisierte Update-PRs — **selbst
betrieben, ohne den Mend-Cloud-Service** (Issue #751): Es ist keine GitHub-App installiert,
kein externer Dienst hat Zugriff auf das Repository. Der Lauf erfolgt **täglich als
GitHub-Actions-Workflow** (`.github/workflows/renovate.yml`, 06:23 MESZ, zusätzlich manuell
über *Run workflow* auslösbar) im offiziellen Docker-Image; dasselbe Kommando lässt sich
jederzeit auch lokal ausführen (unten).

## Was Renovate hier aktualisiert

| Quelle | Manager | Bereichs-Label |
|---|---|---|
| `backend/gradle/libs.versions.toml` (einzige zulässige Versionsquelle, AGENTS.md) samt `backend/gradle/verification-metadata.xml` (siehe [Gradle-Prüfsummen](#gradle-prüfsummen)) | `gradle` | `backend` |
| Gradle-Wrapper samt `distributionSha256Sum`; ohne Auto-Merge (siehe [Gradle-Prüfsummen](#gradle-prüfsummen)) | `gradle-wrapper` | `backend` |
| `frontend/package.json` + `pnpm-lock.yaml` (inkl. `packageManager`-Pinning) | `npm` | `frontend` |
| `e2e/package.json` + `pnpm-lock.yaml` | `npm` | `frontend` |
| cdxgen für die Frontend-SBOM (`.github/tools/cdxgen/package.json` + `pnpm-lock.yaml`); eigene Gruppe ohne Auto-Merge, weil kein Required Check den Aufruf des Release-Laufs ausübt | `npm` | `frontend`, `ci` |
| Mermaid und playwright-core für die Diagramme der HTML-Fassung des Update-Pakets (`.github/tools/mermaid/package.json` + `pnpm-lock.yaml`); eigene Gruppe, die CI rendert jedes Diagramm damit | `npm` | `frontend`, `ci` |
| GitHub-Actions-Workflows (`.github/workflows/`), Commit-SHA und Versionskommentar gemeinsam (siehe [unten](#actions-per-commit-sha)) | `github-actions` | `ci` |
| Docker-Basisimages (`Dockerfile`s, `docker-compose*.yml`) | `dockerfile`, `docker-compose` | `ci` |
| Helm-Chart (`deploy/helm/opaa/`): Abhängigkeiten in `Chart.yaml` (derzeit keine) und Images mit festem Tag in `values.yaml` (die Evaluierungsdatenbank); Backend und Frontend folgen der Chart-Version und tragen dort keinen Tag | `helmv3`, `helm-values` | `ci` |
| Images der Hilfsdienste im Kubernetes-Erprobungsaufbau (`examples/kubernetes-trial/manifests/` und `jobs/`); ohne Auto-Merge, weil keine CI den Aufbau prüft — Beleg vor dem Merge mit `trial.sh up` (README dort) | `kubernetes` | `ci` |
| Demo-Seed-/Generator-Requirements (`demo/*/requirements.txt`) | `pip_requirements` | `demo` |
| pytest und PyYAML der CI-Jobs `report-script`, `seed-script` und Datenwirkung (`.github/requirements-ci.txt`), die Pakete des Update-Pakets, markdown-it-py mit mdurl (`.github/requirements-release.txt`), des Seeds im axe-Lauf (`.github/requirements-seed.txt`) und die Zeitzonendaten des Tagesreports (`.github/requirements-report.txt`); jede Datei listet alle Pakete samt Abhängigkeiten mit `--hash`, Renovate erneuert die Hashes beim Bump, jeder Job installiert mit `--require-hashes`. Ohne Auto-Merge, weil keiner dieser Jobs ein Required Check ist | `pip_requirements` | `ci` |
| Node-Version für die lokale Entwicklung (`frontend/.nvmrc`) | `nvm` | `frontend` |
| Pins, die kein regulärer Manager sieht: die Image-Konstanten in `S3TestFixture.java` und `KeycloakFixture.java`, die Werkzeugversionen der Chart-Prüfung in `helm-chart.yml`, die Helm-Version der Chart-Veröffentlichung in `publish-images.yml`, das Renovate-Image in `renovate.yml` (siehe [unten](#renovate-image-und-token-des-workflows)) und das alpine-Hilfsimage in `retrieval-regression.yml` (`ALPINE_IMAGE`, Version und Index-Digest; ein neuer Digest bei gleicher Version braucht die Freigabe in der Abhängigkeits-Übersicht) | `custom.regex` | `ci` |

**Rein transitive Sicherheits-Pins brauchen einen `[libraries]`-Eintrag.** Wird eine Bibliothek
angehoben, die kein Build-Skript direkt deklariert (eingebetteter Tomcat, Bouncy Castle —
siehe den `dependencyManagement`-Block in `backend/build.gradle.kts`), genügt ein bloßer
`[versions]`-Eintrag nicht: Der `gradle`-Manager leitet seine Koordinaten aus `[libraries]` ab und
sieht eine Version ohne `module` überhaupt nicht. Der Pin bekäme dann nie einen Update-PR und
bliebe auf dem Stand stehen, auf den ihn die damalige CVE gehoben hat. Deshalb steht zu jedem
solchen Pin ein `[libraries]`-Eintrag mit `version.ref`, den der Override über `libs.*` verwendet.

Achtung bei den Demo-Requirements: Ein Update von `demo/seed/requirements.txt` übt der Job
`seed-script` in `ci.yml` aus, für `demo/generator/requirements.txt` läuft **kein** CI-Job. Solche
Update-PRs vor dem Merge lokal gegen den Generator prüfen.

Regeln in [`renovate.json5`](../renovate.json5) (kommentiert): deutsche Commit-/PR-Texte im
Stil `chore(deps): <Paket> auf <Version> aktualisieren`, Labels (unten), höchstens fünf
gleichzeitig offene Update-PRs, Spring-Plattform als ein gebündelter PR, **kein**
Digest-Pinning für Docker-Images (gleitende Tags sind eine dokumentierte Projektentscheidung,
siehe `e2e/docker-compose.e2e.yml`; einzige Ausnahme ist das Renovate-Image selbst, siehe
[unten](#renovate-image-und-token-des-workflows)). Zusätzlich pflegt Renovate ein Übersichts-Issue
(„Abhängigkeits-Übersicht (Renovate)") mit allen anstehenden Updates.

**Labels:** Jeder Update-PR trägt `dependencies` und dazu das Bereichs-Label aus der Tabelle oben.
Über `dependencies` führen die Release-Notizen Renovate-Updates unter der eigenen Rubrik
„Abhängigkeiten“ statt unter „Neue Funktionen“ (#2458). Die Rubrik steht in `.github/release.yml`
hinter „Sicherheit“; trägt ein Update-PR zusätzlich `security`, erscheint er weiter dort, weil
die erste passende Rubrik gewinnt. Davor steht nur noch „Datenrelevante Änderungen“ (Label
`data-impact`, [releases.md](releases.md#datenwirkung-kennzeichnen)). Renovate vergibt `security` nicht selbst, das Label setzt
bei Bedarf ein Mensch. Bereichs-Labels ergänzen die `packageRules` per `addLabels`; eine Regel
mit `labels` würde `dependencies` ersetzen. Die Abhängigkeits-Übersicht trägt `ci`.

**Jedes Update wartet drei Tage** (`minimumReleaseAge: '3 days'` auf oberster Ebene, #3340):
Renovate legt einen Branch erst an, wenn die vorgeschlagene Version mindestens drei Tage alt ist,
gleich welcher Manager — Gradle, Gradle-Wrapper, npm, Docker, Helm, pip, Actions und die
Regex-Manager. Bis dahin steht das Update als „Pending“ in der Abhängigkeits-Übersicht; ohne
Branch gibt es weder einen PR-Lauf noch einen Auto-Merge. Ein kompromittiertes Release, das in
dieser Zeit auffällt und zurückgezogen wird, erreicht `main` und das Image `:main` damit nicht.
Liefert eine Datasource keinen Zeitstempel, bleibt das Update „Pending“
(`minimumReleaseAgeBehaviour: 'timestamp-required'`, Vorgabe). Für npm liegt die Frist über der
von pnpm, das Releases unter 24 h ohnehin ablehnt (#954); eine Regel, die die Frist für npm unter
einen Tag senkt, machte Update-PRs zwangsläufig rot. Ausgenommen von der Frist sind nur die
Sicherheits-PRs (nächster Abschnitt).

## Sicherheits-PRs und Lockfile-Pflege

- **Sicherheits-PRs** entstehen aus zwei Quellen: aus den offenen Dependabot-Alerts
  (`vulnerabilityAlerts`) und aus der OSV-Datenbank (`osvVulnerabilityAlerts: true`), die Renovate
  in jedem Lauf herunterlädt und offline abfragt. OSV deckt die **direkten** Abhängigkeiten der
  Datasources `maven`, `npm` und `pypi` ab, auch solche ohne GitHub-Advisory. Sicherheits-PRs
  kommen sofort, ohne Wartefrist und ohne `prConcurrentLimit`, und **mergen nie automatisch**
  (`vulnerabilityAlerts.automerge: false`): Gerade eine frische Version, die sich als
  Sicherheitskorrektur ausgibt, sieht vor dem Merge ein Mensch. Meldet OSV eine Version als
  bösartig, legt Renovate für das Paket keinen PR an und warnt im Log.
- **Lockfile-Pflege** (`lockFileMaintenance`, montags im Lauf um 06:23 bzw. 05:23 Uhr, nur auf
  `main`): Renovate löst jede `pnpm-lock.yaml` (`frontend/`, `e2e/`, `.github/tools/*`) neu auf und
  hebt damit auch **rein transitive** Pakete, die weder ein `package.json`-Bump noch ein
  Sicherheits-PR erreicht. Der PR mergt nicht automatisch: Für die transitiven Versionen gilt nur
  die 24-h-Frist von pnpm, nicht die drei Tage oben. Vor dem Merge den Lockfile-Diff auf
  unerwartete neue Pakete durchsehen. Auf den Wartungsbranches ist die Lockfile-Pflege aus
  (Sperrregel, [Wartungsbranches](#wartungsbranches)).

## Gradle-Prüfsummen

**Wrapper.** `backend/gradle/wrapper/gradle-wrapper.properties` trägt `distributionSha256Sum`;
der Wrapper prüft die heruntergeladene Distribution dagegen. Die Prüfsumme stammt von
[gradle.org/release-checksums](https://gradle.org/release-checksums/) („Complete (-all) ZIP“);
die Seite nennt auch die Prüfsumme der Wrapper-JAR, gegen die sich
`backend/gradle/wrapper/gradle-wrapper.jar` abgleichen lässt. Ein Wrapper-Update von Renovate
holt die neue Prüfsumme aus der `.sha256`-Datei neben der Distribution und schreibt sie mit
(dafür führt es `./gradlew wrapper` aus, `gradleWrapper` in `renovate.yml`), mergt aber nie
automatisch.

**Abhängigkeiten.** `backend/gradle/verification-metadata.xml` hält für jede Datei, die der Build
auflöst (Bibliotheken, Plugins samt Markern, POM- und Module-Dateien), die SHA-256-Prüfsumme.
Gradle bricht jeden Bau ab, dessen heruntergeladene Datei davon abweicht: lokal, in jedem
CI-Job und im Docker-Build des Backends (amd64 und arm64; das Dockerfile kopiert `backend/gradle/`
mit). Ausgenommen sind nur Quellen- und Javadoc-JARs, die allein die IDE lädt
(`trusted-artifacts`). Signaturen prüft die Datei nicht (`verify-signatures` ist `false`).

- **Zwei echte Fassungen.** Das Plugin Portal leitet die meisten Anfragen an Maven Central weiter,
  liefert Plugin-Marker und einzelne POM- und Module-Dateien aber in eigener Fassung mit anderer
  Prüfsumme. CI, Docker-Build und Renovate lösen den Plugin-Classpath über das Portal auf, eine
  Umgebung mit Central-Spiegel (Firmen-Proxy, Agenten-Umgebung) bekommt die Central-Fassung. Für
  jede Datei, die das Portal selbst ausliefert, stehen deshalb beide Prüfsummen in der Datei, die
  zweite als `also-trust`. Das ergänzt `.github/scripts/gradle_verification_variants.py`; es fragt
  nur Portal und Central, nie einen Spiegel.
- **Renovate pflegt die Datei mit.** Bei jedem Update des `gradle`-Managers führt Renovate selbst
  `./gradlew --write-verification-metadata sha256 dependencies` aus (dieselbe Erlaubnis
  `gradleWrapper` in `renovate.yml`) und committet die neuen Einträge im Update-Branch. Damit
  läuft bei Gradle-Updates Code der neuen Plugin-Versionen im Renovate-Container, der das Token
  hält; das gilt für Wrapper-Updates schon immer und ist durch `minimumReleaseAge` gedämpft.
  Veraltete Einträge entfernt Renovate nicht.
- **Was Renovate nicht erfasst.** Der Aufruf löst die Konfigurationen auf, führt aber keine Tasks
  aus. Dateien, die erst ein Task lädt, fehlen danach: der Formatierer von Spotless
  (`google-java-format`, seine Version hängt am Spotless-Plugin) und POM-Dateien, die das
  CycloneDX-Plugin für Lizenzangaben liest. Ein solcher PR scheitert in `backend-build` mit
  „Dependency verification failed“. Dann auf dem Update-Branch den Workflow
  „Gradle-Verifikationsdatei erzeugen“ (`gradle-verification-metadata.yml`) starten und die Datei
  aus seinem Artefakt `verification-metadata` committen.
- **Bekannte Koordinaten behalten ihre Prüfsumme.** `--write-verification-metadata` trägt eine
  abweichende Prüfsumme für schon bekannte Koordinaten stillschweigend als `also-trust` ein; ein
  manipuliertes Artefakt käme so durch einen Update-PR. Der Schritt „Bestehende Prüfsummen der
  Gradle-Verifikation prüfen“ im Pflicht-Check `changes` vergleicht deshalb die Datei mit dem
  Basis-Branch (`.github/scripts/check_gradle_verification_metadata.py`): Neue und entfallene
  Einträge sind erlaubt, eine geänderte Prüfsumme für dieselbe Datei bricht auf `renovate/`-Branches
  ab und erscheint sonst als Warnung. Eine solche Änderung nur übernehmen, nachdem die Datei
  gegen die `.sha1`/`.sha256` des Repositorys geprüft ist.
- **Neu erzeugen.** Der Workflow „Gradle-Verifikationsdatei erzeugen“ (nur manuell, auf einem
  wählbaren Branch) leert die Komponenten, lädt alles ohne Gradle-Cache direkt von Portal und
  Central, führt `help spotlessCheck cyclonedxBom` aus, ergänzt die Portal-Fassungen und listet
  im Job-Summary jede geänderte Prüfsumme. Danach sind auch veraltete Einträge weg. Lokal derselbe
  Ablauf aus `backend/`:

  ```bash
  ./gradlew --no-configuration-cache --no-build-cache --rerun-tasks \
    --write-verification-metadata sha256 help spotlessCheck cyclonedxBom
  python3 ../.github/scripts/gradle_verification_variants.py gradle/verification-metadata.xml
  ```

  Ohne vorheriges Leeren ergänzt der Aufruf nur. Prüfsummen aus einem Spiegel oder einem fremden
  Gradle-Cache nicht ungeprüft übernehmen, sondern gegen Central abgleichen.

**npm wird gepinnt** (`rangeStrategy: 'pin'` für `dependencies`/`devDependencies`):
`frontend/` und `e2e/` sind Anwendungen — exakte Versionen in der `package.json` machen jeden
Bump als PR sichtbar statt als stilles Lockfile-only-Update. Der allererste Lauf erzeugt dafür
einmalig einen „Pin dependencies"-PR, der alle Caret-Ranges auf exakte Versionen umschreibt;
`engines` bleibt bewusst eine Range, `packageManager` ist bereits exakt gepinnt.

## Wartungsbranches

Neben `main` pflegt Renovate den Wartungsbranch `release/X.Y` jeder unterstützten
[Linie](releases.md#supportlinien) ([ADR-0054](decisions/0054-release-zyklus-und-supportlinien.md),
Entscheidung 7), dort aber nur mit Updates, die eine Linie nach
[Versionsnummern](releases.md#versionsnummern) tragen darf:

| | `main` | `release/X.Y` |
|---|---|---|
| Update-Arten | alle, Majors ohne Auto-Merge | `patch`, `digest`, `pin`; `minor`, `major`, `replacement`, `rollback`, `bump` und `lockFileMaintenance` sind aus. Patch- und Minor-Versionen kommen getrennt (`separateMinorPatch`), damit eine neuere Minor-Version den Patch nicht mitnimmt |
| Spring Boot | jede neue Version | nur Patch-Versionen der Minor-Version der Linie |
| Basis-Images | neue Tags; der Stand des Tags kommt mit jedem Bau | Tag-Wechsel nur innerhalb der Patch-Versionen; aktuelle Pakete der Basis-Images bringt ein Patch-Release ohne Codeänderung ([Versionsnummern](releases.md#versionsnummern)) |
| Auto-Merge | wie in den Regeln unten | dieselben Regeln: Was auf `main` automatisch mergt, mergt auch dort, sobald die Required Checks des Rulesets `release-branches` grün sind; wie auf `main` verlangt es keine Freigabe ([releases.md](releases.md#wer-ein-release-anlegt)). Majors, Minors und Ausnahmen nie |
| Branch-Name, PR-Titel | `renovate/main-<Thema>`, Titel mit „(main)“ | `renovate/release/0.2-<Thema>`, Titel mit „(release/0.2)“ |

**Welche Linien.** Die einzige Liste der Linien mit ihrem Supportende ist
`.github/support-lines.json` (Pflege im Release-Ablauf, [releases.md](releases.md#supportlinien)).
`renovate.yml` liest sie vor jedem Lauf mit `.github/scripts/support_lines.py base-branches` und
übergibt `main` und `release/X.Y` jeder Linie, deren Supportende heute noch nicht überschritten
ist, als globales `force` (`RENOVATE_FORCE='{"baseBranchPatterns":[…]}'`). Das ersetzt
`baseBranchPatterns: ['main']` aus `renovate.json5`; ohne den Workflow, etwa im lokalen
[Probelauf](#probelauf-ohne-schreibzugriff-dry-run), bleibt es bei `main`. Eine Linie fällt am Tag
nach ihrem Supportende heraus, ohne dass jemand `renovate.json5` ändert; offene Update-PRs gegen
ihren Branch schließt Renovate im nächsten Lauf wie jeden verwaisten Update-Branch
(`pruneStaleBranches`, Vorgabe). Fehlt ein Branch noch, überspringt Renovate ihn mit einer
Warnung im Log.

**Namen.** Solange nur `main` gepflegt wird, heißen Branches und Titel wie bisher
(`renovate/<Thema>`, ohne Zusatz). Sobald die erste Linie dazukommt, hängt Renovate den
Basis-Branch an jeden Namen, auch an die von `main`: Offene PRs von `main` entstehen dann einmal
unter neuem Namen neu, die alten schließt Renovate. Die Prüfung der Datenwirkung erkennt
Renovate-PRs weiter am Präfix `renovate/`. **Abgelehnte PRs vergisst Renovate dabei:** Ob ein
Update schon einmal abgelehnt wurde, erkennt es am geschlossenen PR mit demselben Branch-Namen und
Titel, und beide ändern sich. Ein von Hand geschlossener PR, heute der Major-PR #1158, kommt beim
Wechsel deshalb einmal neu; er wird erneut geschlossen oder als Regel mit `enabled: false` in
`renovate.json5` festgehalten.

**Grenzen.** `prConcurrentLimit` (fünf offene Update-PRs) gilt für alle Basis-Branches zusammen;
Renovate kennt kein Limit je Basis-Branch. Ein Patch-Update für eine Linie kann deshalb hinter
offenen PRs von `main` warten, bis einer davon gemergt oder geschlossen ist. Steht ein dringendes
Update einer Linie so in der Abhängigkeits-Übersicht, legt der Maintainer es dort per Häkchen
sofort an oder bringt es als [Backport](releases.md#backport). Die Übersicht führt die anstehenden
Updates je Basis-Branch.

**Ausnahme für Sicherheitsupdates.** Braucht eine Linie eine Sicherheitskorrektur, die es nur mit
einer neuen Minor-Version einer Abhängigkeit gibt, und ist das kein
[Bruch](releases.md#versionsnummern), schaltet der Maintainer sie mit einer ausdrücklichen Regel
frei, die hinter der Sperrregel am Ende von `packageRules` steht (ADR-0054, Restrisiken):

```json5
{
  matchBaseBranches: ['release/0.2'],
  matchPackageNames: ['org.example:paket'],
  matchUpdateTypes: ['minor'],
  enabled: true,
  automerge: false,
}
```

**Woher der Anlass kommt.** Renovate selbst meldet einen solchen Fall nicht. Seine
Sicherheits-PRs (`vulnerabilityAlerts`) entstehen aus den offenen Dependabot-Alerts, und die
berechnet GitHub nur für `main`: Ist eine Lücke dort behoben, ist der Alert geschlossen, und für
eine Linie entsteht kein Sicherheits-PR mehr. Gibt es den Fix nur als neue Minor-Version, verwirft
ihn die Sperre ohne PR und ohne Eintrag in der Übersicht. Der Anlass kommt deshalb aus dem
nächtlichen [CVE-Scan](cve-scanning.md) des jüngsten Patch-Release jeder Linie; er sieht, was im
Image steckt, auch die Java-Bibliotheken des Backends, nicht aber Frontend-Bibliotheken, die im
gebündelten JavaScript aufgehen.
Die Regel wird mit dem Supportende der Linie wieder entfernt. `test_support_lines.py` liest `renovate.json5` mit Node und prüft, dass die
Sperrregel die Update-Arten oben abdeckt, dass `separateMinorPatch` nur für die Wartungsbranches und
vor der Sperre gilt, dass nach ihr nur solche Ausnahmen mit `matchPackageNames` und
`automerge: false` stehen,
dass `renovate.json5` nur `main` als Basis-Branch nennt und dass `renovate.yml` die Liste übergibt.

## Actions per Commit-SHA

Alle Workflows unter `.github/workflows/` binden jede Action über den vollen Commit-SHA ein, mit
der exakten Version als Kommentar (#2397, mit #2432 auf alle Workflows ausgeweitet):

```yaml
- uses: docker/login-action@dbcb813823bdd20940b903addbd779551569679f # v4.6.0
```

Ein Tag lässt sich auf anderen Code umhängen, ein SHA nicht. Wird ein Action-Repository
kompromittiert, läuft ein umgehängter Tag sonst beim nächsten Lauf mit den Rechten des Workflows,
in einem Release-Lauf bis hin zu manipulierten, gültig attestierten Images.

**Warum alle Workflows.** Bis #2432 galt der SHA nur für Workflows mit Schreibrechten auf
veröffentlichte Artefakte oder das Repository. Zwei Wege führten daran vorbei:

- **Der GitHub-Actions-Cache.** Jeder Job, der auf `main` läuft, darf in den Cache von `main`
  schreiben, und zwar unter jedem Schlüssel (siehe [unten](#sicherheitsmodell-des-gha-caches)).
  Eine kompromittierte Action in `e2e.yml` oder `demo-smoke.yml` hätte so Einträge für den
  Bau-Cache von `publish-images.yml` anlegen können.
- **Schreibrechte unterhalb der Artefakte.** `e2e.yml`, `retrieval-regression.yml` und
  `baseline-diff.yml` legen Issues oder PR-Kommentare an.

Eine Grenze nach Schreibrechten müsste beides einzeln nachhalten. Eine Regel für alle Dateien
ist einfacher zu prüfen und braucht keine Ausnahmeliste.

Der Preis sind mehr Renovate-PRs. Über einen gleitenden Major-Tag (`@v7`) kam jedes Minor- und
Patch-Release still an; mit SHA schlägt Renovate jedes einzeln vor. Neu betroffen sind die acht
Action-Repositories, die vorher in keinem Workflow gepinnt waren. Sie hatten in den sechs Monaten
bis zum 10.10.2026 zusammen 33 Releases, also etwa einen zusätzlichen PR pro Woche. Die übrigen
Actions waren schon gepinnt. Renovate bündelt ein Update über alle Dateien in einem Branch, ihr
PR ändert jetzt nur mehr Dateien.

### Sicherheitsmodell des gha-Caches

Zugriffsregeln laut GitHub-Dokumentation („Dependency caching reference“, Abschnitte
„Restrictions for accessing a cache“ und „Cache access for low-trust workflow triggers“, Stand
10.10.2026):

| Lauf | darf lesen | darf schreiben |
|---|---|---|
| `push`, `schedule`, `workflow_dispatch` auf `main` | Cache von `main` | Cache von `main` |
| `push` auf einen Wartungsbranch `release/X.Y` (`ci.yml`) und manueller E2E-Lauf dort (`gh workflow run e2e.yml --ref release/X.Y`, schreibt `type=gha`) | Cache des Branches, `main` | Cache des Branches; lesen dürfen ihn nur er selbst und PRs gegen ihn, kein Release-Tag |
| `pull_request`, auch aus einem Fork | eigener Merge-Ref, Basis-Branch, `main` | nur den eigenen Merge-Ref; sichtbar nur für erneute Läufe desselben PRs |
| `pull_request_target`, `issue_comment`, `workflow_run` auf `main` (hier `cla.yml`) | Cache von `main` | nichts, solange der Workflow kein schreibendes `cache-mode` setzt; keiner tut das |
| Release-Tag `vX.Y.Z` | eigener Tag, `main` | eigener Tag; kein anderer Tag sieht ihn |

Daraus folgt:

- **Ein Pull Request, auch aus einem Fork, kann weder den Cache von `main` noch den eines
  Releases vergiften.**
- **Ein Scope ist keine Grenze.** `scope=backend-amd64` ist nur ein Präfix der Cache-Schlüssel.
  Wer in den Cache von `main` schreiben darf, darf jeden Schlüssel anlegen, auch einen jüngeren
  Index-Eintrag für den Scope eines anderen Workflows. Dazu genügt jede Action in einem Job auf
  `main`. Öffentlich beschrieben ist außerdem, dass Code in einem `run`-Schritt das Cache-Token
  aus dem Runner-Prozess lesen kann (Adnan Khan, „The Monsters in Your Build Cache“, 2024; hier
  nicht nachgestellt). Dann reicht auch eine kompromittierte Abhängigkeit, die ein solcher Job
  installiert oder ausführt, und dagegen hilft kein SHA-Pin.
- **`cache-mode` hilft hier nicht.** Das Workflow-Schlüsselwort regelt Lesen und Schreiben je
  Job, nicht je Schlüssel, und die Jobs auf `main` brauchen ihre eigenen Caches.

Deshalb gilt zusätzlich zu den Pins:

- **Releases bauen ohne gha-Cache.** `publish-images.yml` setzt `cache-from` und `cache-to` nur
  bei Läufen, die nicht von einem Tag kommen; das gilt auch für Tags auf einem Wartungsbranch, und
  auf dem Branch selbst baut der Workflow nicht. Ein Release-Image entsteht damit nur aus Checkout,
  Basis-Images und Paketquellen, unabhängig davon, was ein Job auf `main` in den Cache gelegt
  hat. `test_publish_images_cache.py` hält die Bedingung fest. Gemessen an den ersten Läufen mit
  den Scopes je Architektur, die keinen Cache-Treffer hatten (#2411, Läufe 37980307632 und
  37980340271), braucht ein Bein ohne Cache 280 bis 354 s (Backend) bzw. 87 bis 105 s
  (Frontend), mit Cache 85 bis 108 s bzw. 51 bis 65 s (Lauf 38036312587). Die Beine laufen
  parallel, ein Release dauert also rund vier Minuten länger.
- **`:main`, `sha-<commit>` und der wöchentliche Neubau nutzen den Cache weiter.** Das ist ein
  bewusst getragenes Restrisiko: Code, der in einem Job auf `main` läuft, kann den Bau-Cache
  dieser Images vergiften. Nach den SHA-Pins bleiben dafür eine kompromittierte Abhängigkeit, ein
  Fehler in einem gemergten Workflow oder ein per Tag referenziertes Image, das Jobs auf `main`
  starten. Solche Images sind die Compose-Stacks von E2E-, Demo- und Anmelde-Läufen (etwa
  `node:24-alpine`, `pgvector/pgvector:pg18`, `httpd:2.4-alpine`), die Datenbank im Rauchtest
  von `image-smoke-arm64` und das kind-Node-Image im Helm-Test. Ein umgehängter Tag dort läuft im
  selben Job wie die Schritte, die den Cache schreiben dürfen. Die Stufe `runtime` baut jeder Lauf ohnehin neu
  (`no-cache-filters`). Wer geprüft und reproduzierbar betreiben will, nimmt ein Release
  ([releases.md](releases.md)).

### Renovate pflegt SHA und Kommentar gemeinsam

Zwei `packageRules` in `renovate.json5` gelten für den `github-actions`-Manager in allen
Workflows, ohne Dateiliste:

- **`pinDigests: true`.** Renovate liest `currentValue` aus dem Kommentar und `currentDigest` aus
  dem SHA und schreibt bei einem Update beides neu.
- **`minimumReleaseAge: '3 days'`.** Ein neues Release wird frühestens drei Tage nach seinem
  Zeitstempel vorgeschlagen; bis dahin steht es als „Pending“ in der Abhängigkeits-Übersicht, und
  Renovate legt keinen Branch an (`internalChecksFilter` steht auf der Vorgabe `strict`). Ohne
  Branch gibt es auch keinen PR-Lauf. Das ist wichtig, weil `cve-scan.yml` auch bei
  `pull_request` läuft, und zwar in der Fassung des PRs mit seinem Schreibrecht
  (`security-events: write`). Ein späteres `automerge: false` käme dafür zu spät.
  **Der Schutz ist nur teilweise:** Der Zeitstempel der Datasource `github-tags` ist das
  Commit-Datum (leichtgewichtiger Tag) bzw. das Tagger-Datum (annotierter Tag), angehoben auf das
  Datum eines GitHub-Releases, falls es eins gibt und es später liegt. Commit- und Tagger-Datum
  setzt der Autor selbst. Ohne GitHub-Release lässt sich ein Tag also rückdatieren.
- **Digest-Updates brauchen eine Freigabe** (`dependencyDashboardApproval: true`, dazu
  `automerge: false`). Ein neuer SHA bei gleicher Version heißt, dass der Tag umgehängt wurde,
  also genau der Angriff, gegen den die Pins schützen. Renovate legt den Branch erst an, wenn ein
  Maintainer das Update in der Abhängigkeits-Übersicht anhakt; vorher läuft nichts. Vor der
  Freigabe wird geklärt, warum der Tag umgehängt wurde.

Eine dritte Regel betrifft nur `publish-images.yml`: **Ihre Actions mergen nie automatisch**, auch
nicht bei Minor- und Patch-Updates. Kein Required Check übt sie aus, sie laufen aber bei jedem
Push auf `main` mit `packages: write`. Dasselbe gilt für alle Updates in `helm-chart.yml`
(bestehende Regel mit `automerge: false`, weil der Helm-Workflow kein Required Check ist). Ein
Update-Branch umfasst alle Dateien mit derselben Action. Enthält er ein Upgrade aus einer dieser
beiden Dateien, mergt der ganze Branch nicht automatisch, auch mit dem Teil in anderen Workflows.

Alle übrigen Minor- und Patch-Releases mergen nach Ablauf der Frist wie andere Updates
automatisch, Majors nie. Das entspricht dem Stand vor den Pins, als diese Releases über den
gleitenden Major-Tag ohne PR ankamen.

### Prüfung in der CI

**Der Guard `.github/scripts/check_action_pins.sh`** (Job `changes` in `ci.yml`, bei Änderungen
unter `.github/workflows/`) lehnt in jeder Datei `.github/workflows/*.yml` und `*.yaml` jedes
`uses:` ohne vollen SHA und `# vX.Y.Z`-Kommentar ab. Er erkennt Block-Stil, gequotete Schlüssel
und Flow-Mappings (`- { uses: … }`), nicht aber einen Wert auf einer eigenen Folgezeile. Lokale
Actions (`./…`) und `docker://…@sha256:…` sind ausgenommen. Ein neuer Workflow ist ohne weiteres
Zutun erfasst. Die Selbstprobe `test_check_action_pins.py` prüft außerdem, dass die beiden
Renovate-Regeln oben keine Dateiliste tragen, also ebenfalls für jeden Workflow gelten.

### SHA ermitteln und im Review prüfen

Eine neue Action bekommt ihren SHA über die GitHub-API, nicht von Hand aus der Weboberfläche. Bei
annotierten Tags (`"type": "tag"`) zeigt die Referenz auf das Tag-Objekt, nicht auf den Commit,
und muss einmal dereferenziert werden:

```bash
gh api repos/docker/login-action/git/ref/tags/v4.6.0 --jq '.object'
# bei "type": "tag" zusätzlich:
gh api repos/<owner>/<repo>/git/tags/<sha> --jq '.object.sha'
```

**Herkunft im Review prüfen.** Ein Commit aus einem Fork ist auch über das Upstream-Repository
erreichbar. `gh api repos/<owner>/<repo>/commits/<sha>` findet ihn, und der Tarball unter
`codeload.github.com/<owner>/<repo>/tar.gz/<sha>`, den der Runner lädt, wird ausgeliefert.
Geprüft am 09.10.2026 mit einem Commit, der nur im Fork `bhouston/checkout-git-dedup` liegt,
abgefragt über `actions/checkout`. Dass der SHA existiert, beweist also nichts. Das Review
gleicht jeden neuen oder geänderten Pin mit den beiden Befehlen oben ab: Der dereferenzierte
Commit des Tags aus dem Kommentar muss genau der gepinnte SHA sein.

## Voraussetzungen

- Docker
- Ein GitHub-Token als Umgebungsvariable `RENOVATE_TOKEN` — **nie committen**. Die minimalen
  Rechte und warum ein Fine-grained PAT hier nur vom Konto `criew` ausgestellt werden kann,
  stehen unter [Token: minimale Rechte](#token-minimale-rechte). Ein klassisches PAT braucht die
  Scopes `repo` **und** `workflow`. Für einen schreibenden Lauf ein Token mit genau diesen
  Rechten verwenden, **nicht** das CLI-Token (`gh auth token`): Es trägt meist weitere Scopes
  und reicht über alle Repositories seines Inhabers. Für die reinen Lese-Läufe unten
  (`RENOVATE_PLATFORM=local`) genügt das CLI-Token als `GITHUB_COM_TOKEN`; Renovate nutzt es dort
  nur für Abfragen.
- Dasselbe Image wie der Workflow. Die Befehle unten lesen es aus `renovate.yml`, statt
  `latest` zu ziehen; `RENOVATE_DOCKER_MAX_PAGES=10` hält wie im Workflow die Zeitstempel von
  Docker Hub (siehe [unten](#renovate-image-und-token-des-workflows)):

  ```bash
  IMAGE=$(sed -n 's/^ *IMAGE: //p' .github/workflows/renovate.yml)
  ```

## Probelauf ohne Schreibzugriff (Dry-Run)

Zeigt im Log, welche Updates ein echter Lauf anlegen würde — nichts wird geschrieben. Läuft
gegen den lokalen Arbeitsstand (nützlich auch, um Änderungen an `renovate.json5` vor dem
Merge zu prüfen):

```bash
GITHUB_COM_TOKEN=$(gh auth token) docker run --rm \
  -v "$(pwd)":/usr/src/app -w /usr/src/app \
  -e RENOVATE_PLATFORM=local \
  -e GITHUB_COM_TOKEN \
  -e RENOVATE_DOCKER_MAX_PAGES=10 \
  -e LOG_LEVEL=info \
  "$IMAGE"
```

Der Dry-Run funktioniert auch ganz ohne Token, endet dann aber mit `WARN: GitHub token is
required for some dependencies` — die Lookups der GitHub-Datasource (alle Actions, `node`,
`python`, …) bleiben dann aus bzw. rate-limitiert. Mit Token ist es weiterhin ein reiner
Lese-Lauf.

Am Log-Ende fasst `packageFiles with updates` je Manager zusammen, was erkannt wurde und
welche neuen Versionen anstehen.

## CustomManager belegt prüfen

Ein `customManagers`-Eintrag ist die einzige Konfiguration hier, die **still** wirkungslos sein
kann: `renovate-config-validator` prüft nur die Form, und ein Eintrag, der keine Datei trifft,
erzeugt weder Fehler noch Warnung — er fehlt einfach im Log. Ein Pin kann so jahrelang
einfrieren, ohne dass es auffällt (so geschehen mit dem MinIO-Image, #1861). Ein neuer oder
geänderter Eintrag wird deshalb **belegt**, nicht angenommen:

```bash
docker run --rm -v "$(pwd)":/usr/src/app -w /usr/src/app \
  -e RENOVATE_PLATFORM=local -e RENOVATE_DOCKER_MAX_PAGES=10 -e LOG_LEVEL=debug \
  "$IMAGE" > renovate-debug.log 2>&1

grep 'Matched .* file(s) for manager regex' renovate-debug.log
```

Die `regex`-Zeilen erscheinen in der Reihenfolge der `customManagers` (den ersten beiden geht
`config:recommended` mit seinen zwei tsconfig-Managern voraus). **Ein Eintrag, der keine Datei
trifft, hat gar keine Zeile** — das ist der Befund. Erwartet:

```
DEBUG: Matched 1 file(s) for manager regex: backend/src/test/java/io/opaa/s3/S3TestFixture.java
DEBUG: Matched 1 file(s) for manager regex: backend/src/test/java/io/opaa/integration/keycloak/KeycloakFixture.java
DEBUG: Matched 2 file(s) for manager regex: .github/workflows/helm-chart.yml, .github/workflows/publish-images.yml
```

Dass die Datei getroffen wurde, heißt noch nicht, dass der `matchStrings`-Ausdruck greift; dafür
gibt es zwei weitere Belege im selben Log:

- `No dependencies found in file for custom regex manager (packageFile=…)` — Datei getroffen,
  Regex daneben.
- Der Block `packageFiles with updates` am Log-Ende führt unter `"regex"` je Eintrag den
  `packageFile` mit `depName`, `currentValue` und `currentVersion`. Steht dort die erwartete
  Version, ist die Kette vollständig belegt.

Zwei Stolpersteine, die beide schon zugeschlagen haben:

- **`ignorePaths` wirkt vor jedem Manager.** `config:recommended` bringt über
  `:ignoreModulesAndTests` unter anderem `**/test/**`, `**/tests/**`, `**/examples/**` und
  `**/__fixtures__/**` mit. Eine Datei unter `backend/src/test/…` ist damit für **alle** Manager
  unsichtbar, egal wie genau `managerFilePatterns` sie benennt. Deshalb überschreibt
  `renovate.json5` die Liste gezielt (Kommentar dort), statt sie zu leeren; neben den beiden
  Testverzeichnissen fehlt dort auch `**/examples/**`, damit der `kubernetes`-Manager den
  Erprobungsaufbau sieht. Ein Negativ-Eintrag ist in `ignorePaths` nicht möglich, die Liste dort
  ist also eine **Kopie der Preset-Vorgabe** und
  friert deren heutigen Stand ein: Ergänzt Renovate `:ignoreModulesAndTests` später um einen
  Eintrag, greift der hier nicht mehr. Bei einem Renovate-Major deshalb abgleichen — der Workflow
  wechselt erst mit dem Merge des Major-PRs für das Renovate-Image, das ist der Zeitpunkt dafür.
- **`managerFilePatterns` sind Globs**, solange sie nicht in Schrägstriche gefasst sind
  (`/…regex…/`). Ein voller Pfad ohne Platzhalter ist ein gültiges Glob und trifft genau diese
  eine Datei — erkennbar an der `Using file pattern: … for manager regex`-Zeile kurz oberhalb.

Der lokale Lauf genügt als Beleg: Er liest dieselbe `renovate.json5` und durchläuft dieselbe
Extraktionsstufe wie der Lauf gegen GitHub. Wer den echten Lauf sehen will, stößt
`.github/workflows/renovate.yml` per *Run workflow* an — der schreibt allerdings (Branches, PRs)
und liefert nur `LOG_LEVEL=info`.

## Automatischer täglicher Lauf

`.github/workflows/renovate.yml` führt täglich das unten dokumentierte Docker-Kommando aus, mit
dem gepinnten Image statt `latest` (siehe
[Renovate-Image und Token des Workflows](#renovate-image-und-token-des-workflows)). Einzige
Voraussetzung ist das Repository-Secret **`RENOVATE_TOKEN`** — ein PAT mit den
[dort beschriebenen Berechtigungen](#token-minimale-rechte), hinterlegt von einem Maintainer:

```bash
gh secret set RENOVATE_TOKEN
```

Die Commits der Update-Branches tragen als Autor „Renovate Bot" mit der Noreply-Adresse des
PAT-Inhabers (`gitAuthor` in `renovate.json5`) — so besteht der CLA-Check über dessen
vorhandene Unterschrift (#924). Wechselt der Token-Inhaber, muss `gitAuthor` mitziehen.

Bewusst ein PAT und nicht der eingebaute `GITHUB_TOKEN` des Workflows: Mit dem
`GITHUB_TOKEN` erstellte PRs lösen **keine** CI-Workflows aus (GitHubs Schutz vor rekursiven
Triggern) — die Update-PRs stünden dauerhaft ohne Checks da — und Workflow-Dateien dürfte er
auch nicht ändern. Fehlt das Secret, bricht der Lauf mit einer klaren Fehlermeldung ab.
Token-Rotation: neues PAT erzeugen, `gh secret set RENOVATE_TOKEN` erneut ausführen, altes
Token widerrufen.

## Renovate-Image und Token des Workflows

### Image auf Version und Digest festgelegt

`renovate.yml` übergibt dem Image das Token `RENOVATE_TOKEN`, das Code und Workflows im
Repository ändern kann (#2433). Mit `renovate/renovate:latest` liefe jede neu veröffentlichte
Fassung beim nächsten Lauf ungeprüft mit diesem Token, also mit mehr Rechten als jede gepinnte
Action. Deshalb steht das Image in der Variable `IMAGE` des Schritts mit Version und Digest:

```yaml
# renovate: datasource=docker depName=renovate/renovate
IMAGE: renovate/renovate:44.142.0@sha256:e9c2dcbc5a6027e68755b98cccb10470da8f941d0fbfaa67c0d85b7e064025c7
```

Der Digest ist der des Multi-Arch-Index, nicht der eines einzelnen Plattform-Images. Docker
startet mit `Tag@Digest` immer den Digest; ein umgehängter Tag ändert also nichts. Docker Hub und
`ghcr.io/renovatebot/renovate` liefern denselben Index-Digest. Ermitteln und Herkunft prüfen:

```bash
docker buildx imagetools inspect renovate/renovate:<version> --format '{{json .Manifest.Digest}}'
docker buildx imagetools inspect ghcr.io/renovatebot/renovate:<version> --format '{{json .Manifest.Digest}}'
# Signatur des Renovate-Release-Workflows (keyless, Sigstore):
docker run --rm ghcr.io/sigstore/cosign/cosign:v3.0.2 verify \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/renovatebot/renovate/\.github/workflows/' \
  ghcr.io/renovatebot/renovate@<digest>
```

**Update-Weg.** Den `docker run`-Aufruf sieht kein regulärer Manager; ein eigener Eintrag unter
`customManagers` liest `currentValue` und `currentDigest` aus der Zeile unter dem Kommentar, ein
Update schreibt beide neu. Zwei `packageRules` gelten nur für dieses Image:

- **`pinDigests: true`**, als ausdrücklich festgehaltene Ausnahme von der Docker-Regel
  `pinDigests: false`. Einen vorhandenen Digest pflegt Renovate auch ohne die Option mit. Fehlt
  der Digest in der Zeile, greift der Ausdruck des Managers nicht mehr und das Image fiele aus
  der Pflege; das fängt die Selbstprobe `test_renovate_image_pin.py` in der CI ab.
- **`minimumReleaseAge: '3 days'`** wie bei den gepinnten Actions (Renovate rechnet seinen
  Vorgabepuffer `minimumReleaseAgeBuffer` von 30 Minuten dazu), und **`automerge: false`** für
  alle Updates, auch Minor und Patch. Renovate veröffentlicht mehrmals täglich; der Update-PR
  (Branch `renovate/renovate-renovate-44.x`) bleibt offen und wird bei jedem Lauf auf das neueste
  Release gehoben, das die Frist erfüllt. Ein Major kommt als eigener PR.
- **Ein neuer Digest bei gleicher Version** braucht die Freigabe in der Abhängigkeits-Übersicht
  (`dependencyDashboardApproval: true`). Ein Versionstag wie `44.142.0` wird nach der
  Veröffentlichung nicht neu belegt; ein solcher Vorschlag heißt, dass der Tag umgehängt wurde.

Der Update-PR selbst führt nichts mit dem Token aus: `renovate.yml` läuft nur nach Zeitplan und
per *Run workflow*, nie bei `pull_request`. Das neue Image startet erst mit dem nächsten Lauf nach
dem Merge. Vor dem Merge: Release Notes der übersprungenen Versionen überfliegen und den Digest
mit den Befehlen oben gegen beide Registries und die Signatur abgleichen.

**Zeitstempel kommen von Docker Hub.** Die Wartezeit rechnet ab `tag_last_pushed`, den die
Registry beim Push setzt, nicht der Autor; anders als bei Git-Tags lässt er sich nicht
rückdatieren. Anonym liefert Docker Hub aber höchstens 10 Seiten der Tag-Liste (1000 Tags), und
`renovate/renovate` hat deutlich mehr. Ohne Begrenzung bricht die Abfrage auf Seite 11 mit 403 ab
(`pagination offset too large for anonymous requests`), Renovate weicht auf die Tag-Liste der
Registry ohne Zeitstempel aus und hält mit `minimumReleaseAgeBehaviour=timestamp-required` jedes
neuere Release als „Pending“ zurück. Der Pin fröre still ein. Deshalb setzt `renovate.yml`
`RENOVATE_DOCKER_MAX_PAGES=10`: Renovate liest die 1000 zuletzt geschobenen Tags und behält deren
Zeitstempel. Die Einstellung gilt für alle Docker-Hub-Images; Images mit weniger als 1000 Tags
betrifft sie nicht, bei den übrigen fehlen nur Tags, die seit Langem niemand geschoben hat. Bei
anderen Registries begrenzt sie die Tag-Liste auf 10 statt der Vorgabe von 20 Seiten; ausgenommen
sind `ghcr.io`, `quay.io`, `cgr.dev` und die Red-Hat-Registries, die Renovate immer vollständig
liest.

**Beleg nach einer Änderung** am Eintrag oder an der Zeile (die Selbstprobe
`.github/scripts/test_renovate_image_pin.py` prüft Zeile, Ausdruck und Regeln, aber keine
Abfrage gegen die Registry):

```bash
docker run --rm -v "$(pwd)":/usr/src/app:ro -w /usr/src/app \
  -e RENOVATE_PLATFORM=local -e RENOVATE_ENABLED_MANAGERS=custom.regex \
  -e RENOVATE_DOCKER_MAX_PAGES=10 -e LOG_LEVEL=debug \
  "$IMAGE" > renovate-debug.log 2>&1
```

Im Block `packageFiles with updates` steht unter `.github/workflows/renovate.yml` der Eintrag
`renovate/renovate` mit `currentValue` und `currentDigest`. Ein neueres Release erscheint als
`newValue` mit `newDigest` und `releaseTimestamp`, jüngere als `pendingVersions`. Fehlt
`releaseTimestamp` und steht im Log `Marking … release(s) as pending, as they do not have a
releaseTimestamp`, greift die Seitenbegrenzung nicht.

**Warum nicht `renovatebot/github-action`.** Die offizielle Action startet ebenfalls ein
Docker-Image und empfiehlt selbst, die Renovate-Version per Regex-Manager zu pinnen. Sie bringt
also keinen eigenen Pin mit, sondern eine zweite Komponente, die das Token in der Hand hat: Ihr
JavaScript läuft mit dem Token im Runner und bräuchte ihrerseits einen SHA-Pin, den der Guard
`check_action_pins.sh` automatisch erfasst. Der direkte `docker run` hat eine vertrauenswürdige
Komponente weniger und bleibt deshalb.

### Token: minimale Rechte

Ein klassisches PAT mit `repo` und `workflow` gilt für **alle** Repositories, auf die sein
Inhaber Zugriff hat. Kleiner geht es mit einem Fine-grained PAT, das nur `criew/opaa` sieht.
Renovate braucht dort ([Renovate-Doku, GitHub-Plattform](https://docs.renovatebot.com/modules/platform/github/)):

| Recht | Zugriff | Wofür |
|---|---|---|
| *Contents* | Read and write | `renovate.json5` lesen, Update-Branches pushen, Renovate-seitiger Merge der npm-PRs |
| *Pull requests* | Read and write | PRs eröffnen, aktualisieren, GitHub-Auto-Merge setzen |
| *Issues* | Read and write | Abhängigkeits-Übersicht, Labels |
| *Workflows* | Read and write | Pushes, die Dateien unter `.github/workflows/` ändern (Actions, dieses Image) |
| *Commit statuses* | Read and write | Status `renovate/stability-days` und `renovate/artifacts`, Prüfstatus vor dem Merge |
| *Dependabot alerts* | Read-only | Schwachstellen-Hinweise in Update-PRs; ohne das Recht nur eine Warnung im Log |
| *Metadata* | Read-only | wird automatisch gesetzt |

*Members* aus der Renovate-Liste entfällt, weil `criew` ein persönliches Konto ist und keine
Organisation.

**Einschränkung: Ein Fine-grained PAT kann hier nur das Konto `criew` ausstellen.** Fine-grained
PATs gelten für Repositories ihres Inhabers oder seiner Organisationen. Wer nur Collaborator
eines fremden persönlichen Repositorys ist, kann damit nicht schreiben
([GitHub-Doku, Limitations](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#fine-grained-personal-access-tokens-limitations)).
Das heutige Token gehört laut `gitAuthor` dem Collaborator-Konto `bigpuritz`; mit diesem Konto
ist ein Fine-grained PAT für `criew/opaa` also nicht möglich. Wege:

1. **Fine-grained PAT des Kontos `criew`** (empfohlen, kleinster Umbau). Als `criew` anmelden,
   *Settings → Developer settings → Fine-grained tokens → Generate new token*; *Resource owner*
   `criew`, *Only select repositories* `criew/opaa`, die Rechte der Tabelle, Ablauf höchstens ein
   Jahr. Hinterlegen mit `gh secret set RENOVATE_TOKEN`, *Run workflow* einmal anstoßen und im Log
   auf 401/403 prüfen, danach das alte klassische PAT widerrufen. Im selben PR `gitAuthor` in
   `renovate.json5` auf die Noreply-Adresse von `criew` umstellen
   (`<id>+criew@users.noreply.github.com`, `<id>` aus `gh api users/criew --jq .id`). Der
   CLA-Check (#924) bleibt dabei grün: `criew` steht in `cla.yml` in der `allowlist`
   (`criew,*[bot]`). PRs und Commits zeigen dann `criew` als Urheber.
2. **Eigene GitHub App**, nur in `criew/opaa` installiert, mit den Rechten aus der Renovate-Doku.
   Die Installations-Tokens gelten eine Stunde, kein Personenkonto steht dahinter. Der Workflow
   müsste das Token mit einer Action erzeugen (`actions/create-github-app-token`, SHA-gepinnt,
   der Guard `check_action_pins.sh` erfasst sie automatisch). Das ist ein eigener Umbau mit
   eigenem Issue.
3. **Klassisches PAT eines eigenen Maschinenkontos**, das nur Collaborator von `criew/opaa` ist.
   `repo` reicht dann nicht weiter als dieses eine Repository, die Scopes bleiben aber grob.

Solange keiner der Wege umgesetzt ist, bleibt das klassische PAT mit `repo` und `workflow`. Das
Pinning des Images verkleinert das Risiko unabhängig davon: Ohne gemergten Update-PR startet mit
dem Token kein anderes Image.

## Manueller Lauf (erzeugt Branches und PRs)

Renovate liest die `renovate.json5` aus dem Default-Branch des Zielrepositories:

```bash
read -rs RENOVATE_TOKEN && export RENOVATE_TOKEN   # Token mit den minimalen Rechten, nicht gh auth token
docker run --rm \
  -e RENOVATE_TOKEN \
  -e RENOVATE_PLATFORM=github \
  -e RENOVATE_REPOSITORIES=criew/opaa \
  -e RENOVATE_ALLOWED_UNSAFE_EXECUTIONS=gradleWrapper \
  -e RENOVATE_DOCKER_MAX_PAGES=10 \
  -e LOG_LEVEL=info \
  "$IMAGE"
```

`RENOVATE_ALLOWED_UNSAFE_EXECUTIONS=gradleWrapper` erlaubt Renovate, bei einem Gradle-Update den
Wrapper-Befehl auszuführen (#997) — ohne die Freigabe aktualisiert es nur
`gradle-wrapper.properties`, nicht die Wrapper-Skripte/JAR, und der Lauf meldet eine WARN-Zeile im
Dependency-Dashboard. Bewusst nur dieser eine Befehl, keine weiteren unsicheren Ausführungen. Der
Wrapper läuft dabei im Renovate-Container, in dem auch `RENOVATE_TOKEN` liegt; deshalb mergen
Wrapper-Updates nie automatisch, und Renovate schlägt erst Versionen vor, die drei Tage alt sind.

Der Lauf ist idempotent: erneutes Ausführen aktualisiert bestehende Update-Branches (Rebase
bei Bedarf), schließt Überholtes und legt nur Neues an.

**Auto-Merge (#951, eingeschränkt durch #1002):** Renovate eröffnet seine PRs mit aktiviertem
GitHub-Auto-Merge (Squash) — gemergt wird automatisch, sobald die **Required Checks** grün
sind; der Branch entsteht erst nach Ablauf der Wartefrist von drei Tagen. Das gilt für
minor/patch/pin/digest; **Major-Updates sind ausgenommen**, ebenso Wrapper-Updates,
Sicherheits-PRs und die Lockfile-Pflege und bleiben als
normale PRs zur menschlichen Entscheidung offen (Hintergrund: das auto-gemergte
`eclipse-temurin`-v25-Major brach den Backend-Image-Build, siehe #1002 und „Typische
Fehlerbilder"). Ein unerwünschtes Update lehnt man durch Schließen des PRs ab (Renovate legt
es dann nicht erneut vor). Zu beachten: `e2e` ist kein Required Check und hält den Auto-Merge
nicht auf — die E2E-Suite läuft seit #1226 gar nicht mehr bei PRs; der Lauf bei jedem Push auf
`main` und der nächtliche Lauf bleiben das Sicherheitsnetz und legen bei Fehlschlag ein
Alarm-Issue an (bewusste Repo-Entscheidung, vgl. #792).

**Lizenzen:** Die Required Checks `backend` und `frontend` prüfen auch die Lizenzen der
Laufzeit-Abhängigkeiten. Bringt ein Update eine nicht freigegebene oder geänderte Lizenz mit,
bleibt der PR offen; das Vorgehen steht in [`docs/sbom.md`](./sbom.md#lizenzprüfung).

## Konfiguration validieren

Nach Änderungen an `renovate.json5`:

```bash
docker run --rm -v "$(pwd)":/usr/src/app -w /usr/src/app \
  -e RENOVATE_CONFIG_FILE=/usr/src/app/renovate.json5 \
  --entrypoint renovate-config-validator "$IMAGE"
```

## Typische Fehlerbilder

- **`Repository is disabled` / Onboarding-PR statt Updates:** `renovate.json5` liegt nicht im
  Default-Branch — erst mergen, dann laufen lassen.
- **403/401 beim echten Lauf:** Token abgelaufen oder Zuschnitt zu eng (siehe
  [Token: minimale Rechte](#token-minimale-rechte)).
- **Gradle-Updates fehlen im Log:** Der `gradle`-Manager braucht die
  `libs.versions.toml`-Einträge in Standardform (`[versions]`/`[libraries]`-Referenzen) —
  direkt in `build.gradle.kts` eingetragene Versionen sind ohnehin verboten (AGENTS.md). Ein
  `[versions]`-Eintrag **ohne** zugehörigen `[libraries]`-Eintrag hat keine Koordinate und wird
  still übergangen; das trifft besonders transitive Pins (siehe oben).
- **`pnpm install --frozen-lockfile` bricht nach einem Renovate-Tag mit
  `ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY` (#996):** Mehrere Lockfile-ändernde npm-PRs mergten
  nacheinander, ohne dass die späteren gegen den neuen Stand rebased waren — die textuell
  konfliktfreie Git-Vereinigung der `pnpm-lock.yaml` ist dann semantisch inkonsistent.
  Vorbeugung seit #1000: Non-Major-npm-Updates laufen als **ein Sammel-PR** je Lauf
  (`groupName: 'npm (non-major)'`), und npm-Branches mergen **Renovate-seitig** statt über
  GitHubs nativen Auto-Merge (`rebaseWhen: 'behind-base-branch'` + `platformAutomerge: false`)
  — gemergt wird nur ein Branch, der aktuell hinter `main` steht und grün ist; das passiert
  folglich nur während eines Renovate-Laufs. Heilung, falls es doch passiert: `pnpm install`
  auf `main`-Stand, Lockfile-Diff committen.
- **`packageManager` verliert den Corepack-Hash (#1660):** Aktualisiert ein Sammel-PR pnpm
  zusammen mit mindestens einem weiteren npm-Paket, ruft Renovate `corepack use pnpm@<version>`
  auf, bevor die Lockfile neu erzeugt ist; der Aufruf bricht mit `ERR_PNPM_OUTDATED_LOCKFILE` ab
  und der zuvor eingetragene, hashlose Wert bleibt stehen. Sichtbar wird das am roten Status
  `renovate/artifacts`. Den `+sha512.`-Anteil prüft **corepack** beim Laden der pnpm-Binary —
  in `frontend/Dockerfile` (`corepack enable`, dann `pnpm install --frozen-lockfile`, also im
  Produktions-Image-Build) und in jeder lokalen Arbeitskopie. `pnpm/action-setup` schneidet den
  Hash dagegen ab und liest nur die Version; ein Verlust fiele in der CI also von selbst nie
  auf. Deshalb prüft ihn der Guard `.github/scripts/check_package_manager_hash.sh` (Job
  `changes` in `ci.yml`, Selbstprobe in `test_check_package_manager_hash.py`). Heilung:
  `corepack use pnpm@<version>` im betroffenen Verzeichnis ausführen, sobald die Lockfile
  aktuell ist, und `package.json` committen.
- **Ein `customManagers`-Eintrag erzeugt nie einen PR, obwohl die Konfiguration validiert:** Die
  Datei liegt unter einem Pfad, den `ignorePaths` ausschließt (Vorgabe aus `config:recommended`,
  darunter `**/test/**`), oder `managerFilePatterns`/`matchStrings` treffen nicht. Der Nachweis
  steht oben unter „CustomManager belegt prüfen"; ohne Debug-Lauf ist dieser Fall unsichtbar, weil
  ein leerer Manager keine Warnung erzeugt (#1861).
- **`Dependency verification failed` in einem Gradle-Update-PR:** Der Update-Branch braucht eine
  Datei, die Renovates Aufruf nicht auflöst (Spotless-Formatierer, CycloneDX-POMs) — Heilung unter
  [Gradle-Prüfsummen](#gradle-prüfsummen). Bricht stattdessen der Schritt „Bestehende Prüfsummen
  der Gradle-Verifikation prüfen“ ab, hat sich eine Prüfsumme bekannter Koordinaten geändert: nicht
  mergen, bevor die Datei gegen das Repository geprüft ist.
- **Docker-Hub-Rate-Limit im Dry-Run:** kurz warten und wiederholen; der Lauf cached nichts
  zwischen Containern.
- **Major-Update eines Basisimages bricht einen Build, obwohl der Update-PR grün war:** Der
  brechende Job (z. B. `e2e`, das den Backend-Image-Build enthält) ist kein Required Check und
  hielt den Auto-Merge nicht auf. Seit #1002 mergen Majors deshalb nicht mehr automatisch;
  passiert es doch (z. B. manuell gemergt), Basisimage-Tag zurücksetzen und den erneut
  aufschlagenden Renovate-PR bewusst entscheiden (Vorfall: `eclipse-temurin` 21 → 25 bei
  Gradle-Toolchain `languageVersion = 21`).
- **`pnpm install --frozen-lockfile` bricht nach einem Renovate-Tag mit
  `ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY`:** Mehrere Lockfile-ändernde Update-PRs sind
  nacheinander per Auto-Merge gemergt, ohne dass die späteren gegen den neuen Stand rebased
  waren — die textuell konfliktfreie Git-Vereinigung der `pnpm-lock.yaml` ist dann semantisch
  inkonsistent (#996). Heilung: `pnpm install` auf `main`-Stand, Lockfile-Diff committen
  (Vorbild: PR #1003); Vorbeugung wird in #1000 verfolgt.
