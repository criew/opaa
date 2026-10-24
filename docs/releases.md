# Releases

Ein Release ist ein unveränderlicher, benannter Stand von OPAA: zwei Container-Images und ein
Helm-Chart in GHCR mit derselben Versionsnummer und ein GitHub-Release mit der Änderungsübersicht.
Daneben gibt es weiter den beweglichen Stand `main`, der jedem Merge folgt. Wer reproduzierbar
betreiben will, pinnt eine Version.

Für Betreiber und Behörden zusammengefasst: [Handbuch, Releases und Nachweise](handbuch/betrieb/releases-und-nachweise.md).

## Was ein Release erzeugt

Ein Git-Tag `vX.Y.Z` auf einem Commit von `main` oder des Wartungsbranches `release/X.Y` seiner
[Linie](#supportlinien) startet `.github/workflows/publish-images.yml`:

| Ergebnis | Inhalt |
|---|---|
| `ghcr.io/criew/opaa-backend:X.Y.Z`, `ghcr.io/criew/opaa-frontend:X.Y.Z` | der Release-Stand für `linux/amd64` und `linux/arm64` (ein Manifest-Index je Image), **nie überschrieben** |
| `…:X.Y` | wandert mit jedem Patch-Release dieser Linie mit |
| Helm-Chart `oci://ghcr.io/criew/charts/opaa`, Version `X.Y.Z` | Chart-Version und `appVersion` sind `X.Y.Z`, die Vorgabewerte zeigen auf die Images `X.Y.Z`; **nie überschrieben** |
| GitHub-Release `OPAA vX.Y.Z` | Änderungsübersicht aus den gemergten PRs seit dem vorigen finalen Release, das der Commit enthält (bei einem Patch-Release das vorige Release seiner Linie), gruppiert nach Label (`.github/release.yml`), [datenrelevante Änderungen](#datenwirkung-kennzeichnen) zuerst, dazu die Image-Namen, die Installationsbefehle, die [Vorbereitung für Bestandsinstallationen](#vorbereitung-für-bestandsinstallationen) und der Hinweis auf das Update-Paket; als „Latest“ markiert nur, wenn es das höchste finale Release ist (siehe [Latest](#latest-und-vergleichsstand-der-notizen)) |
| `opaa-update-paket-X.Y.Z.tar.gz` mit `….tar.gz.sha256`, am GitHub-Release | das [Update-Paket](#update-paket) für Behörden: Faktenblätter der Version, Delta zum vorigen finalen Release, Betriebsfolgen und Antwortqualität, Lizenzübersicht, SBOMs, Vorlage Update-Vermerk; **attestiert** |
| `opaa-offline-X.Y.Z.tar` mit `….tar.sha256` und `….tar.sigstore.json`, am GitHub-Release | das [Offline-Bündel](#offline-bündel) für Netze ohne Internetzugang: Images beider Architekturen als OCI-Archive, Chart, Update-Paket, SBOMs, ihre Attestierungen und `SHA256SUMS`; **attestiert** |
| `opaa-backend-X.Y.Z.cdx.json`, `opaa-frontend-X.Y.Z.cdx.json`, am GitHub-Release | die CycloneDX-Stücklisten (SBOM) der ausgelieferten Abhängigkeiten von Backend und Frontend aus dem Stand des Tags ([sbom.md](sbom.md#ebene-2--ökosystem-sboms-cyclonedx)); dieselben Dateien liegen im Update-Paket; **attestiert** |

Wie bei `main` tragen die Images je Architektur eine SBOM- und eine Provenance-Attestierung
([sbom.md](sbom.md)). Der Chart trägt eine signierte Provenance-Attestierung, abgelegt bei GitHub
und neben dem Chart in GHCR. Prüfen lässt sie sich mit:

```bash
gh attestation verify oci://ghcr.io/criew/charts/opaa:X.Y.Z --owner criew
```

Das Update-Paket und die beiden SBOMs tragen ebenso je eine signierte Provenance-Attestierung,
abgelegt bei GitHub. Geprüft wird die heruntergeladene Datei:

```bash
gh release download vX.Y.Z --repo criew/opaa --pattern 'opaa-update-paket-*'
gh attestation verify opaa-update-paket-X.Y.Z.tar.gz --repo criew/opaa
gh attestation verify opaa-backend-X.Y.Z.cdx.json --repo criew/opaa   # ebenso opaa-frontend-…
```

Installiert wird der Chart ohne Checkout des Repositories, in der Reihenfolge des Handbuchs
([Kubernetes, Installation](handbuch/betrieb/kubernetes.md#installation)): zuerst der Namespace mit den
Labels für Pod Security `restricted`, dann Datenbank, Geheimnisse und Werte-Datei, zuletzt der
Chart. Was in die Werte-Datei gehört, beschreibt die [Chart-README](../deploy/helm/opaa/README.md):

```bash
kubectl create namespace opaa
kubectl label namespace opaa \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/warn=restricted
helm install opaa oci://ghcr.io/criew/charts/opaa --version X.Y.Z -n opaa -f opaa-werte.yaml
```

`--create-namespace` legt den Namespace ohne diese Labels an und gehört deshalb nicht in die
Anleitung. Die Release-Notizen zeigen dieselben Befehle; ihren festen Teil erzeugt
`deploy/helm/ci/release-notes.sh`, und die statische Prüfung des Charts stellt sicher, dass er den
Namespace vor `helm install` mit beiden Labels anlegt.

Das Backend eines Release-Images meldet dieselbe Version in seiner Startzeile
(`Starting OpaaApplication vX.Y.Z`) und unter `/actuator/info`, das Frontend im Dialog „Info zu
OPAA“ (`OPAA vX.Y.Z`); der Release-Lauf übergibt sie beiden Image-Builds als Build-Argument
`OPAA_VERSION`. Ein Image von `main` meldet `0.0.0-dev+<Commit>` mit dem kurzen Hash des Commits,
aus dem es gebaut ist (`0.0.0-dev+3f2a9c1`), jeder lokale Build `0.0.0-dev`.

Im Repository trägt der Chart zwischen zwei Releases die Platzhalterversion `0.0.0-dev`; erst der
Release-Lauf setzt die Version ([ADR-0042](decisions/0042-kubernetes-lieferung-mit-helm.md),
Entscheidung 7). Ein reiner Chart-Fix erscheint deshalb als Patch-Release.

Ein Tag `latest` gibt es bewusst nicht. Wer keine Version pinnt, folgt `main`.

Ein Vorab-Stand behält seinen Suffix vollständig im Image-Tag, damit am Tag erkennbar bleibt, ob es
ein Alpha-, Beta- oder Release-Kandidat ist. Er bekommt kein `X.Y` und wird als Vorab-Release
markiert. Nur das führende `v` des Git-Tags entfällt:

| Git-Tag | Image-Tags | Chart-Version | GitHub-Release |
|---|---|---|---|
| `v1.2.3-alpha.1` | `1.2.3-alpha.1` | `1.2.3-alpha.1` | Vorab-Release |
| `v1.2.3-beta.2` | `1.2.3-beta.2` | `1.2.3-beta.2` | Vorab-Release |
| `v1.2.3-rc.1` | `1.2.3-rc.1` | `1.2.3-rc.1` | Vorab-Release |
| `v1.2.3` | `1.2.3`, `1.2` | `1.2.3` | Release |

Der Workflow bricht ab, wenn aus dem Git-Tag nicht genau dieses Image-Tag bzw. diese
Chart-Version entsteht. Ein abgeschnittener Suffix würde also nie veröffentlicht. Eine
Vorab-Version des Charts installiert Helm nur, wenn `--version` sie ausdrücklich nennt oder
`--devel` gesetzt ist.

### Datenwirkung kennzeichnen

Jede Änderung mit Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen
soll in den Release-Notizen auffallen, damit eine Behörde ein Update gegenüber Datenschutz,
Personalrat und Informationssicherheit verantworten kann. Neue Funktionen bleiben dafür nicht
standardmäßig ausgeschaltet; sie werden sichtbar gemacht.

- **Im PR:** Der Abschnitt „Wirkung auf Daten und Behördenunterlagen“ des PR-Templates fragt ab,
  ob personenbezogene Daten oder Protokollereignisse, Sichtbarkeit, Auswertungen oder Exporte,
  Fristen, externe Datenflüsse, Voreinstellungen, das Standardmodell, System-Prompts oder die
  KI-Einstufung (Zustimmungsklasse Z6 in
  [behoerdenunterlagen.md](features/behoerdenunterlagen.md)) neu sind oder sich ändern. Trifft etwas zu, beschreibt ein Satz in Alltagssprache die Wirkung, und der PR
  bekommt das Label `data-impact`. Wer den PR anlegt, setzt es.
- **In der CI:** Der Workflow `.github/workflows/data-impact.yml` läuft bei jedem PR und erneut,
  wenn sich Labels oder Beschreibung ändern. Er schlägt fehl, wenn die Beschreibung eine Wirkung
  ankreuzt, das Label aber fehlt, oder wenn der PR eine Datei aus
  `.github/data-impact-paths.txt` ändert (Liquibase-Changelogs, Protokollereignisse und Rollen,
  OpenAPI-Spezifikation, Konfigurationsklassen und Vorgabewerte, Konnektoren und Modellanbieter,
  Prompts, Fristen) und weder das Label trägt noch „Keine Wirkung“ ankreuzt. Bei angekreuztem
  „Keine Wirkung“ listet er die Dateien als Warnung; **das Review sieht sich diese Warnung an** und
  prüft, ob die Aussage stimmt.
- **Pflege der Pfadliste:** Neue Pfade kommen in `.github/data-impact-paths.txt`. Derselbe
  Workflow führt `.github/scripts/test_check_data_impact.py` aus: Jedes Muster muss eine vorhandene
  Datei treffen, und jede Backend-Datei mit ausgehendem Client (HTTP, Mail, S3, MCP), Modellaufruf,
  System-Prompt oder Aufbewahrungsfrist muss von einem Muster getroffen werden. Fehlt eine, ist der
  Check rot und nennt die Datei; eine neue Datenfluss-Datei kann so nicht unbemerkt hinzukommen.
  PRs von Renovate (Branch `renovate/`, Konto aus `gitAuthor` in `renovate.json5`) prüft der
  Workflow nicht, in einer Merge-Queue läuft er ohne Prüfung durch.
- **In den Release-Notizen:** `.github/release.yml` führt PRs mit `data-impact` in der Rubrik
  „Datenrelevante Änderungen“, und zwar als erste Rubrik. Weil die erste passende Rubrik gewinnt
  und ein PR nur einmal erscheint, steht ein PR mit `data-impact` dort, auch wenn er zusätzlich
  `enhancement`, `bug`, `dependencies` oder `security` trägt. Die Datenwirkung geht vor, weil sie
  bei der Behörde eigene Schritte auslöst (Beteiligung der Personalvertretung, Verzeichnis der
  Verarbeitungstätigkeiten, Folgenabschätzung) und nicht unter einer anderen Rubrik verschwinden
  darf. Ist ein solcher PR zugleich ein Sicherheitsfix, sagt das sein Satz zur Datenwirkung.
- **Der Satz aus dem PR** steht in der PR-Beschreibung, die Notizen verlinken jeden PR. Wer ihn
  zusätzlich im Release zeigen will, ergänzt ihn nach dem Lauf mit `gh release edit`.

Die Pfadliste fängt nur, was an bekannten Stellen geschieht; eine neue Auswertung im Frontend etwa
trifft sie nicht. Dafür bleibt das Ankreuzen im PR maßgeblich. Die Kennzeichnung steht neben dem
Delta des [Update-Pakets](#update-paket), weil nicht jede Wirkung im Manifest steht, etwa eine neue
Auswertung bestehender Daten in der Oberfläche; das Delta führt die gekennzeichneten PRs deshalb
in einem eigenen Abschnitt.

## Schutzregeln im Workflow

Bevor gebaut wird, bricht der Lauf im Job `prepare` ab, wenn:

- das Tag nicht die Form `vX.Y.Z` bzw. `vX.Y.Z-<vorab>` hat oder keine gültige
  [SemVer](https://semver.org/lang/de/)-Version ist, etwa `v1.0.0-rc.01` mit führender Null,
- das Tag nicht dort liegt, wo es hingehört (Prüfung in `.github/scripts/release_origin.sh`):
  Ein Funktionsrelease `vX.Y.0` und seine Vorab-Tags liegen auf `main`. **Jedes Patch-Release**
  `vX.Y.Z` mit `Z` ≥ 1, auch als Vorab-Tag und auch für die jüngste Linie, liegt auf dem
  Wartungsbranch `release/X.Y` genau seiner Linie, und sein Commit enthält `vX.Y.0`. Ein Patch
  von `main` brächte die Funktionen mit, die dort schon für die nächste Linie hinzugekommen sind.
  `v0.2.1` auf `release/0.2` geht also, `v0.3.1` auf `release/0.2` oder auf `main` nicht,
- der getaggte Commit schon ein höheres finales Release enthält, bei einem Vorab-Release auch das
  finale Release derselben Version,
- `X.Y.Z` in GHCR bereits existiert, als Image oder als Chart. Das gilt auch für einen erneut
  gepushten Tag und einen manuellen Lauf auf dem Tag. Eine fehlerhafte Version wird nicht ersetzt,
  sondern durch die nächste Patch-Version abgelöst,
- sich die Release-Notizen nicht erzeugen lassen, etwa weil die Datei mit den
  [Vorbereitungsschritten](#vorbereitung-für-bestandsinstallationen) bei einem finalen `vX.Y.0`
  fehlt oder leer ist. Der Job `release` erzeugt sie erst, wenn Images und Chart schon
  veröffentlicht sind,
- GHCR die Abfrage nach einem Image oder dem Chart nicht eindeutig beantwortet. Frei ist eine
  Version nur, wenn die Registry mit HTTP 404 und dem Registry-Fehlercode `MANIFEST_UNKNOWN` oder
  `NAME_UNKNOWN` „nicht vorhanden“ meldet. Eine 404 ohne diesen Code (etwa die Klartext-Antwort
  eines Routers), eine Verweigerung (401 oder 403, auch schon bei der Token-Anfrage), ein
  Serverfehler (5xx) oder eine Registry ohne Antwort stoppt den Lauf, bevor ein Image
  veröffentlicht ist. Ein Repository-Name mit Großbuchstaben, etwa aus einem Fork, ist kein
  gültiger OCI-Name und wird gar nicht erst abgefragt; der Image-Name wird dafür wie beim Push
  kleingeschrieben. Abhilfe bei einer Verweigerung:
  Unter *Packages → (Paket aus der Meldung) → Package settings → Manage Actions access* dem
  Repository Schreibzugriff geben bzw. die Organisationseinstellung für Pakete prüfen und dann
  *Re-run all jobs*. Tritt das schon beim allerersten Release auf, weil das Paket noch nicht
  existiert, muss die Prüfung im Workflow angepasst werden; das zeigt der Vorab-Release (siehe
  unten).

Bevor ein Tag gesetzt wird, bricht der Lauf außerdem ab, wenn das **Fakten-Manifest unvollständig**
ist oder sich das [Update-Paket](#update-paket) nicht erzeugen lässt. Der Job `update-package` führt
dafür den Vollständigkeitswächter aus (`ComplianceManifestTest`, `ConfigurationSettingsTest`,
`FactSheetReferenceTest` und `ModularArchitectureTest`, der jeden ausgehenden Client an einen
Datenfluss bindet), parallel zu den
Image-Builds; `publish` setzt erst Tags, wenn er gelungen ist. Gepusht ist dann höchstens ein Digest
ohne Tag, also nichts veröffentlicht.

Das Tag-Format steht an einer Stelle, `deploy/helm/ci/release-version.sh`; die Prüfung der Images
und das Packen des Charts rufen es beide auf. Ebenso gibt es eine einzige Existenzprüfung für
Images und Chart, `deploy/helm/ci/oci-published.sh`. Sie fragt die Registry-API direkt und wertet
den HTTP-Status aus. `docker buildx imagetools inspect` taugt dafür nicht, weil es eine
Verweigerung ebenfalls als „not found“ meldet.

**Ein Release baut ohne Build-Cache.** Bei einem Tag lesen und schreiben die `build`-Jobs den
GitHub-Actions-Cache nicht. In den Cache von `main` darf jeder Job schreiben, der auf `main` läuft,
und ein Tag-Lauf liest aus ihm. Ein Release-Image entsteht deshalb nur aus dem getaggten Commit,
den Basis-Images und den Paketquellen. Die Abwägung und das verbleibende Risiko für `main` stehen
in [renovate.md](renovate.md#sicherheitsmodell-des-gha-caches).

Der Lauf ist in Jobs gestaffelt: erst die Prüfungen (`prepare`), dann die Images (`build` je Image
und Architektur, `publish` je Image) und daneben das Update-Paket (`update-package`, seine
Attestierung in `update-package-attestation`), dann der Chart, dann seine Attestierung, dann das
[Offline-Bündel](#offline-bündel) (`offline-bundle`), zuletzt das GitHub-Release mit Update-Paket und
Bündel als Anhang. Jeder Job startet nur, wenn die vorigen gelungen sind, und ein Fehlschlag färbt
den Lauf rot. Ein gescheitertes Bündel hält deshalb das GitHub-Release auf, obwohl Images und Chart
schon veröffentlicht sind. Der Chart zeigt also nie auf ein Image, das nicht veröffentlicht wurde, und das
GitHub-Release kündigt nichts an, was fehlt. Wie die Images für zwei Architekturen entstehen,
beschreibt [Mehrere Architekturen](#mehrere-architekturen).

Scheitert ein späterer Job an einer vorübergehenden Störung, etwa von GHCR oder Sigstore, wird er
mit *Re-run failed jobs* wiederholt; bereits veröffentlichte Teile bleiben unberührt. Das gilt
auch für `build` und `publish`: Ein wiederholter `build`-Job pusht nur einen Digest ohne Tag, und
`publish` fragt vor dem Setzen der Tags erneut, ob `X.Y.Z` noch frei ist. Die Digests liegen sieben
Tage als Artefakt des Laufs bereit; so lange lässt sich `publish` allein wiederholen. Die beiden
`publish`-Jobs (Backend, Frontend) laufen unabhängig: Scheitert einer, läuft der andere zu Ende,
und nur der gescheiterte wird wiederholt. Nach dem Setzen der Tags färbt `publish` den Lauf nicht
mehr rot; die abschließende Anzeige des Index ist nur eine Ausgabe. Hat `publish` die Tags doch
schon teilweise gesetzt und ist erst danach gescheitert, ist die Version vergeben; das Release
erscheint mit der nächsten Patch-Version. Hat der Job
`chart` den Chart schon gepusht und ist erst danach gescheitert, überspringt die Wiederholung den
Push und liest den Digest aus der Registry, damit Attestierung und GitHub-Release folgen können.
Scheitert `offline-bundle` an einer Störung, hilft ebenfalls *Re-run failed jobs*. Bricht `pack` an
der Größengrenze ab, hilft keine Wiederholung, weil der Workflow am Tag fixiert ist: Die Version ist
vergeben, das Release erscheint nach einer Korrektur auf `main` mit der nächsten Patch-Version.
*Re-run all jobs* scheitert dagegen absichtlich an der Prüfung oben.

Bevor der Job `chart` einen Digest an die Attestierung weitergibt, vergleicht er den Chart aus der
Registry inhaltlich mit dem Chart, den er selbst aus dem getaggten Commit gepackt hat
(`deploy/helm/ci/chart-matches.sh`: beide Pakete entpacken, `diff -r`). Ein Vergleich der
Archive selbst taugt nicht, weil `helm package` die Zeitstempel des Checkouts übernimmt. Weicht der
Inhalt ab, bricht der Job ab und attestiert nichts. Das betrifft vor allem eine Wiederholung:
Wurde das Tag zwischenzeitlich auf einen anderen Commit gesetzt (seit den [Rulesets](#wer-ein-release-anlegt)
nur noch möglich, wenn ein Admin sie abschaltet) und hat dessen Lauf die Version
schon veröffentlicht, trüge der fremde Chart sonst die Provenance dieses Commits. Die Meldung, dass
der Push entfällt, erscheint nur bei Übereinstimmung. Ein Abbruch wegen abweichenden Inhalts lässt
sich nicht durch Wiederholen beheben; die Version ist vergeben, das Release erscheint mit der
nächsten Patch-Version. Fehlt dagegen eines der Pakete oder ist es nicht lesbar, meldet der Job
das getrennt und attestiert ebenfalls nichts; dieser Fall lässt sich wiederholen.

**Grenze:** Eine Wiederholung nutzt die Workflow-Datei des getaggten Commits. Ein dauerhafter
Fehler im Workflow selbst, etwa eine Attestierung, die nie gelingt, lässt sich für dieses Tag
nicht mehr beheben. Er wird auf `main` korrigiert und erscheint mit der nächsten Patch-Version.

Die Chart-CI (`.github/workflows/helm-chart.yml`) packt den Chart bei jeder Chart-Änderung mit
Beispiel-Tags auf demselben Weg (`deploy/helm/ci/package-chart.sh`), ohne zu veröffentlichen. Sie
prüft dabei auch, dass der Inhaltsvergleich einen zweimal gepackten Chart annimmt und einen
geänderten ablehnt und dass die Existenzprüfung ohne erreichbare Registry scheitert.

Der wöchentliche Neubau (#1450) läuft nur auf `main`. Er berührt Release-Tags nie. Ein
Wartungsbranch `release/X.Y` veröffentlicht nur über ein Tag: Ein Push dorthin startet den Workflow
nicht, ein manueller Lauf auf dem Branch bricht in `prepare` ab. Er setzt also nie `main` oder
`sha-<commit>`. Der Tag `X.Y` entsteht aus dem Git-Tag selbst und wandert deshalb nur innerhalb
seiner Linie: `vX.Y.0` setzt ihn von `main` aus, jedes Patch-Release von `release/X.Y` aus.

`.github/scripts/test_release_lines.py` prüft beide Skripte dieses Abschnitts und des folgenden an
einer Historie mit zwei Linien und hält ihren Aufruf im Workflow fest; kein PR führt die Jobs aus,
die sie aufrufen.

### Latest und Vergleichsstand der Notizen

Der Job `release` bestimmt mit `.github/scripts/release_line_info.sh` zweierlei:

- **„Latest“** bekommt ein Release nur, wenn kein anderes finales Tag `v*` höher ist; sonst setzt
  der Job `--latest=false`, ein Vorab-Release ist es nie. Ein Patch-Release einer älteren Linie
  nimmt der jüngsten Linie die Markierung also nicht weg.
- **Vergleichsstand der generierten Notizen** (`--notes-start-tag`) ist das höchste finale Tag
  unter der eigenen Version, das der getaggte Commit enthält, nach derselben Regel wie der
  [Vorgänger des Update-Pakets](#update-paket). Ein Patch-Release auf `release/0.2` vergleicht so
  mit dem vorigen Release der Linie 0.2, `v0.3.0` auf `main` mit `v0.2.0`, nicht mit einem jüngeren
  `v0.2.1` vom Wartungsbranch. Gibt es kein solches Tag, wählt GitHub den Vergleichsstand selbst;
  das erste Release überhaupt trägt nur den festen Text.

## Der Tag `main` geht nie zurück

Läufe für verschiedene Commits von `main` laufen parallel und brechen sich nicht gegenseitig ab,
damit jeder Commit sein `sha-<commit>` bekommt. Ohne weitere Prüfung setzte der Lauf, der zuletzt
fertig wird, den Tag `main` – auch wenn sein Commit älter ist (#2418). Deshalb fragt `publish`
unmittelbar vor `docker buildx imagetools create` mit `git ls-remote origin refs/heads/main` ab,
ob der Commit des Laufs noch der Stand von `main` ist (`.github/scripts/drop_stale_main_tag.sh`).
Ist er es nicht, entfällt nur `main`; `sha-<commit>` wird gesetzt, und der Lauf meldet das als
Hinweis (`notice`). Das gilt gleichermaßen für

- einen älteren Lauf, der langsamer ist als der jüngere,
- die Wiederholung (*Re-run*) eines alten Laufs,
- den wöchentlichen Neubau: Er baut den Stand von `main` zum Startzeitpunkt. Kommt während des
  Baus ein Push, setzt er `main` nicht mehr, der Lauf des Pushs übernimmt.

Lässt sich der Stand von `main` auch nach drei Versuchen nicht abfragen, scheitert `publish`, ohne
einen Tag zu setzen; der Job lässt sich wiederholen. Release-Tags sind nicht betroffen: Ein
Release-Lauf setzt `main` nie, die Prüfung fragt dann gar nicht nach.

**Restfenster.** Zwischen der Abfrage und dem Setzen der Tags liegen Sekunden. Ein Commit, der in
diesem Fenster auf `main` landet, hat dann noch kein Image; sein eigener Lauf setzt `main` erst
nach dem Bau, also Minuten später und damit danach. `main` könnte nur zurückgehen, wenn der Lauf
des jüngeren Commits vollständig in diese Sekunden fiele. Eine zweite Prüfung nach dem Setzen
gibt es deshalb nicht: Sie fände fast immer nur einen jüngeren Lauf, der noch baut, und meldete
damit einen Zustand, der sich gleich selbst behebt. Backend und Frontend fragen getrennt ab: Landet
ein Push genau zwischen beiden Abfragen, zeigt `main` kurz auf gemischte Stände (etwa das Backend
des jüngeren, das Frontend des älteren Commits), bis der Lauf des neuen Commits beide setzt.

Ein einzelner Netzfehler bei der Abfrage kostet den Lauf keine Tags: Das Skript versucht es dreimal
im Abstand von fünf Sekunden, erst danach scheitert `publish`.

**Folge für gescheiterte Läufe.** Scheitert der Lauf des jüngsten Commits, bleibt `main` auf dem
zuletzt gesetzten Stand, auch wenn danach noch ein Lauf eines älteren Commits fertig wird. Die
Wiederholung des gescheiterten Laufs setzt `main` – solange kein neuerer Commit auf `main` liegt.

## Wer ein Release anlegt

Releases legen Maintainer an, nie ein Agent ohne ausdrückliche Freigabe. Die Prüfungen im
Workflow schützen vor Versehen, nicht vor Absicht. Wer ein Tag `v*` setzen darf, regeln deshalb
zwei Tag-Rulesets des Repositorys (*Settings → Rules → Rulesets*, nur Admins können sie ändern):

| Ruleset | Regel | Ausnahme |
|---|---|---|
| `release-tags-create` | Anlegen von `refs/tags/v*` gesperrt | Repository-Rollen *Admin* und *Maintain* |
| `release-tags-immutable` | Verschieben (auch per Force-Push) und Löschen von `refs/tags/v*` gesperrt | keine |

Ein Push von `v*` durch einen Account ohne diese Rollen wird abgelehnt. Damit läuft der
Release-Workflow auch nie aus der Workflow-Datei eines Commits außerhalb von `main` und den
Wartungsbranches, den jemand ohne Maintainer-Rolle getaggt hat. Ein vorhandenes Release-Tag bleibt, wo es ist; eine fehlerhafte
Version wird durch die nächste Patch-Version abgelöst. Wer die Rulesets abschwächt, ändert diese
Grenze und braucht dafür Admin-Rechte.

Die Wartungsbranches `release/X.Y` schützen zwei Branch-Rulesets genauso wie `main`, dessen
Schutzregel (*Settings → Branches*) dieselben Prüfungen verlangt. Beide legt ein Admin unter
*Settings → Rules → Rulesets → New branch ruleset* an, mit *Enforcement status: Active* und als
*Target branches* dem Muster `release/*` (*Include by pattern*):

| Ruleset | Regeln | Ausnahme (*Bypass list*) |
|---|---|---|
| `release-branches-create` | *Restrict creations* | Repository-Rollen *Admin* und *Maintain* |
| `release-branches` | *Restrict deletions*; *Block force pushes*; *Require a pull request before merging* mit *Required approvals* 0 wie bei `main` und *Require conversation resolution before merging*; *Require status checks to pass* mit `backend`, `backend-integration`, `frontend`, `changes` (Workflow CI) und `check` (Workflow Datenwirkung), Quelle *GitHub Actions*, ohne *Require branches to be up to date*, mit *Do not require status checks on creation* (der Commit von `vX.Y.0` trägt keinen Check `check`, der nur bei PRs läuft) | Repository-Rolle *Admin*, wie bei `main` (dort *Do not allow bypassing the above settings* aus) |

Ein Wartungsbranch entsteht damit nur durch einen Maintainer, und jede Änderung darauf kommt per PR
mit denselben Required Checks wie auf `main`; `ci.yml` und `data-impact.yml` laufen dafür auch bei
PRs gegen `release/**` und bei jedem Push dorthin. Ein neuer Required Check auf `main` kommt in
dasselbe Ruleset. Ein Branch, dessen Linie ausgelaufen ist, bleibt bestehen und geschützt.

Ablauf:

1. Prüfen, dass `main` grün ist, einschließlich des letzten nächtlichen E2E-Laufs. Für ein
   Patch-Release auf `release/X.Y` gilt dasselbe für den Branch; die E2E-Suite läuft dort nur von
   Hand (`gh workflow run e2e.yml --ref release/X.Y`), ihr Alarm-Issue meldet nur `main`.
2. Version nach den Regeln unten bestimmen.
3. Die Vorbereitung für Bestandsinstallationen festlegen. Bei einem finalen `vX.Y.0` und bei
   jedem Vorab-Release, das Vorbereitung verlangt, die Datei `docs/upgrade-notes/<version>.md` per
   PR auf `main` bringen, **bevor** das Tag gesetzt wird (siehe
   [Vorbereitung für Bestandsinstallationen](#vorbereitung-für-bestandsinstallationen)). Für
   `vX.Y.0` ist sie Pflicht, notfalls mit dem Text „Keine Vorbereitung nötig.“; ohne sie bricht der
   Lauf ab, bevor etwas veröffentlicht ist. Ein Patch-Release verlangt nie Vorbereitung und braucht
   keine Datei.
4. Den Stand aus Behördensicht lesen lassen. Das Update-Paket entsteht erst im Lauf des Tags;
   vorher werden deshalb sein Delta und die Faktenblätter lokal erzeugt, auf dem Commit, der das
   Tag bekommt, und gegen denselben Vorgänger wie das Paket. Welcher Commit das ist, hängt von der
   Art des Release ab; das Beispiel zeigt ein Funktionsrelease `v0.3.0`:

   | Release | `commit` |
   |---|---|
   | Funktionsrelease `vX.Y.0` und seine Kandidaten | `origin/main` |
   | Patch-Release mit Codeänderung (Backport) | `origin/release/X.Y`, nie `origin/main` |
   | Patch-Release ohne Codeänderung | der Commit des vorigen Release der Linie, also `origin/release/X.Y` |

   Ein Patch-Release, auch der jüngsten Linie, entsteht nie von `main`; ein Tag dort lehnt der
   Workflow ab (siehe [Schutzregeln](#schutzregeln-im-workflow)).

   ```bash
   git fetch origin --tags
   if [ "$(git rev-parse --is-shallow-repository)" = true ]; then git fetch --unshallow origin; fi
   commit=$(git rev-parse origin/main)   # nur vX.Y.0 und Kandidaten; Patch: origin/release/X.Y
   git switch --detach "$commit"
   prev=$(python3 .github/scripts/update_package.py previous --tag v0.3.0 --commit "$commit") \
     || { echo "Vorgänger nicht bestimmbar" >&2; exit 1; }
   [ -n "$prev" ] || prev=$(git rev-list --max-parents=0 "$commit" | tail -n 1)   # erstes finales Release
   gh pr list --state merged --label data-impact --limit 1000 --json number,title,url \
     >/tmp/data-impact-prs.json
   if [ "$(jq length /tmp/data-impact-prs.json)" -ge 1000 ]; then
     echo "Limit 1000 erreicht, die Liste kann abgeschnitten sein" >&2; exit 1
   fi
   (cd backend && ./gradlew complianceDocs complianceDelta -PopaaVersion=0.3.0 \
     -PfromRef="$prev" -PtoRef="$commit" -PlabelledPrs=/tmp/data-impact-prs.json)
   ```

   Der Block beendet bei einem Fehler die Shell (`exit 1`). Wer ihn in die eigene Terminal-Shell
   einfügt, startet vorher `bash` und behält so die Variablen `commit` und `prev` für Schritt 5.
   Wie im Workflow bricht die Abfrage am Limit ab, statt gekennzeichnete PRs zu verlieren. Wo
   `gh pr list` (GraphQL) nicht erreichbar ist, etwa in Cloud-Sessions, liefert die REST-API
   dieselbe Liste im selben Format:

   ```bash
   gh api --paginate 'repos/criew/opaa/issues?state=closed&labels=data-impact&per_page=100' \
     --jq '.[] | select(.pull_request.merged_at) | {number, title, url: .html_url}' \
     | jq -s . >/tmp/data-impact-prs.json
   ```

   Das Delta liegt unter `backend/build/compliance/delta/`, die Faktenblätter unter
   `backend/build/compliance/faktenblaetter/`. Wer das ganze Paket vorab sehen will, erzeugt es im
   [Probelauf](#update-paket) mit `update_package.py build --tag v0.3.0 …`. Dann startet der
   Koordinator die Rolle `behoerdenkontakt` ([Rollenvertrag](../agents/roles/behoerdenkontakt.md),
   Adapter für Claude Code, Codex und OpenCode) mit Version, `$commit`, Vergleichsstand `$prev`
   und den Pfaden von Delta und Faktenblättern. Ihre Befunde werden
   vor dem Tag geklärt oder nach dem Lauf mit `gh release edit` als bekannt in die Release-Notizen
   aufgenommen ([Behördenkontakt vor dem Release](AGENT-ORGANIZATION.md#behördenkontakt-vor-dem-release)).

   Verglichen wird nach der Regel unter [Update-Paket](#update-paket) mit dem vorigen finalen
   Release, das der Commit enthält; bei einem Patch-Release ist das das vorige Release seiner
   [Linie](#supportlinien). Ein LTS-Release und seine Kandidaten bekommen zusätzlich das Delta zum
   jüngsten Release der vorigen LTS-Linie; dessen Tag nennt
   `python3 .github/scripts/update_package.py lts-previous --tag v0.5.0 --commit "$commit"`, und
   Delta und Betriebsfolgen dafür entstehen mit demselben Block, `prev` durch dieses Tag ersetzt.
   Damit ein Kandidat als LTS erkannt wird, steht seine Linie vor dem Tag in der
   [Liste der Linien](#liste-der-linien). **Prüfpunkt:** Bei einem Juni-Release und seinen
   Kandidaten muss `lts-previous` ein Tag ausgeben (ab dem zweiten LTS); gibt es nichts aus, fehlt
   der Eintrag in der Liste, und es wird nicht getaggt. Der Lauf nennt seine Entscheidung zusätzlich
   als Hinweis im Job `update-package`.
5. Den in Schritt 4 begutachteten Commit taggen und das Tag pushen:

   ```bash
   git tag -a v0.3.0 "$commit" -m "OPAA v0.3.0"
   git push origin v0.3.0
   ```

   Ist `main` inzwischen weiter und soll das mit ins Release, Schritt 4 wiederholen.

6. Den Lauf von „Publish Images“ abwarten. Danach stehen Images und Chart in GHCR und das
   GitHub-Release unter *Releases*.
   Das erste Release trägt nur einen festen Text: Ohne Vorgänger umfassten generierte Notizen die
   gesamte Historie.
7. Bei einem finalen `vX.Y.0` den Wartungsbranch `release/X.Y` der neuen Linie anlegen
   ([Backport](#backport), Schritt 1; beim ersten Mal vorher die beiden Rulesets oben anlegen) und
   per PR auf `main` die [Liste der Linien](#liste-der-linien)
   nachziehen: die neue Linie eintragen und beim Vorgänger, einer normalen Linie, das Supportende
   setzen; dazu die Zeile in der Roadmap. Eine LTS-Linie steht schon seit ihrem ersten
   Release-Kandidaten in der Liste (unten). Den [Meilenstein](#roadmap) des Release schließen,
   offene Issues darin in den nächsten verschieben und für jede neue Zeile der Roadmap einen
   Meilenstein anlegen:

   ```bash
   gh api repos/criew/opaa/milestones -f title='Juni-Release 2029' -f due_on=2029-06-30T12:00:00Z \
     -f description='LTS-Funktionsrelease. Plan und Supportende: https://github.com/criew/opaa/blob/main/docs/releases.md#roadmap'
   ```

Von Hand nachbearbeiten muss man die Notizen nur für bekannte Befunde aus Schritt 4: Was ein
Release von Bestandsinstallationen verlangt, kommt über Schritt 3 aus dem Repository,
datenrelevante Änderungen ordnet das Label `data-impact` ein. Die Sätze zur Datenwirkung aus den
PRs lassen sich auf Wunsch ergänzen ([Datenwirkung kennzeichnen](#datenwirkung-kennzeichnen)).

Die erste Version ist `v0.1.0`. Auch sie ist ein finales `X.Y.0` und braucht deshalb
`docs/upgrade-notes/0.1.0.md`, bevor das Tag gesetzt wird.

**Erstes Release mit Chart.** Der erste Lauf legt das Paket `charts/opaa` in GHCR an. Ob das mit
dem `GITHUB_TOKEN` des Workflows gelingt, wie GHCR die Abfrage nach dem noch fehlenden Paket
beantwortet und ob die Attestierung landet, lässt sich nur dort prüfen. Deshalb:

1. **Vorher** in den Organisationseinstellungen erlauben, dass Workflows neue Pakete anlegen.
   Sonst scheitert der Job `chart`, nachdem die Images schon veröffentlicht sind.
2. Das erste Release als **Vorab-Tag** fahren, etwa `v0.1.0-rc.1`. Ein Fehlschlag kostet dann nur
   eine Vorab-Version, nicht `v0.1.0`.
3. **Danach** das Paket unter *Packages → charts/opaa → Package settings* auf **Public** stellen,
   wie die beiden Images. GHCR legt es privat an; bis dahin scheitert `helm install` ohne
   Anmeldung.

### Backport

Eine Linie wird auf `release/X.Y` gepflegt ([ADR-0054](decisions/0054-release-zyklus-und-supportlinien.md),
Entscheidung 7). Zurückportiert werden nur Sicherheitskorrekturen und Behebungen
[kritischer Fehler](#kritische-fehler); ob ein Fehler kritisch ist, hält der Maintainer vorher am
Issue fest. Ablauf, Beispiel Linie 0.2:

1. **Branch anlegen**, einmal je Linie, gleich nach dem Lauf des finalen `vX.Y.0` (Schritt 6
   oben), vom Commit des Tags; nur Admin oder Maintain (Ruleset `release-branches-create`). So
   steht der Branch für Backports bereit, bevor einer ansteht:

   ```bash
   git fetch origin --tags
   git push origin v0.2.0^{commit}:refs/heads/release/0.2
   ```

2. **Zuerst auf `main`.** Die Korrektur landet wie jede Änderung per PR auf `main`. Nur wo `main`
   den betroffenen Code nicht mehr hat, entsteht sie direkt auf dem Wartungsbranch, dann per PR
   gegen `release/X.Y` nach dem üblichen Ablauf.
3. **Je betroffenem Branch ein Backport-PR** gegen `release/X.Y`, Branch nach der üblichen Regel
   `feature/<issue-id>_backport-0.2`. Der Cherry-Pick nimmt den Squash-Commit des PRs von `main`;
   `-x` hält dessen Commit in der Nachricht fest:

   ```bash
   git switch -c feature/1234_backport-0.2 origin/release/0.2
   git cherry-pick -x <commit auf main>
   git push -u origin HEAD
   gh pr create --base release/0.2 --title "fix(...): ... (Backport 0.2)" --label bug
   ```

   Die PR-Beschreibung nennt den PR auf `main`, den Commit und die Einordnung des Maintainers am
   Issue (Sicherheitskorrektur oder kritischer Fehler), übernimmt `data-impact` und den Abschnitt
   zur Datenwirkung des Original-PRs und schließt das Issue nicht (`Refs #N`); es ist mit dem PR
   auf `main` schon geschlossen. Der Squash-Merge übernimmt die Zeile
   `(cherry picked from commit …)` nur, wenn sie im Text des Merges steht; maßgeblich ist der
   Verweis in der Beschreibung.
4. **Konflikte** löst der Backport selbst, so knapp wie möglich: kein Mitziehen weiterer Änderungen
   von `main`. Braucht die Korrektur eine neue Funktion, eine neue Minor-Version einer
   Abhängigkeit oder einen [Bruch](#versionsnummern), entscheidet der Maintainer nach
   ADR-0054, Restrisiken; das steht dann in den Release-Notizen.
5. **Tests** laufen über die Required Checks des PRs wie auf `main`. Erst der Merge bringt die
   Korrektur auf den Branch.
6. **Patch-Tag** auf den Commit von `release/X.Y`, nach dem Ablauf oben ab Schritt 1 mit
   `commit=$(git rev-parse origin/release/0.2)`. Eine Datei unter `docs/upgrade-notes/` braucht ein
   Patch-Release nicht. Das Update-Paket vergleicht mit dem vorigen Release der Linie, weil der
   Branch nur deren Tags enthält ([Vorgänger](#update-paket)); dasselbe gilt für die
   [Notizen](#latest-und-vergleichsstand-der-notizen). Das GitHub-Release einer älteren Linie wird
   nicht „Latest“.

Ein Sicherheitsupdate nur des Basis-Images braucht keinen Backport: Ein neues Tag auf dem Commit des
letzten Release der Linie genügt ([Versionsnummern](#versionsnummern)). Patch-Updates der
Abhängigkeiten schlägt Renovate auf jedem Wartungsbranch einer unterstützten Linie selbst vor
([renovate.md](renovate.md#wartungsbranches)); sie brauchen keinen Backport.

## Vorbereitung für Bestandsinstallationen

Die Notizen jedes Release enthalten den Abschnitt **Vorbereitung für Bestandsinstallationen**.
`deploy/helm/ci/release-notes.sh` füllt ihn aus dem getaggten Commit:

| Im getaggten Commit | Abschnitt in den Notizen |
|---|---|
| `docs/upgrade-notes/X.Y.Z.md` (bei einem Vorab-Release mit Suffix, etwa `1.2.3-rc.1.md`) | der Inhalt der Datei, unverändert als Markdown |
| keine solche Datei, Patch-Release `X.Y.Z` mit `Z` > 0 oder Vorab-Release | „Keine Vorbereitung nötig.“ |
| keine solche Datei, finales Release `X.Y.0` | kein Release: Der Job `prepare` bricht ab, bevor etwas veröffentlicht ist |
| die Datei, aber leer, als symbolischer Link oder mit einem nicht geschlossenen Codeblock (ungerade Zahl von Zeilen mit ```` ``` ````) | ebenfalls kein Release |

Darunter verlinken die Notizen in beiden Fällen den Ablauf der Aktualisierung im Handbuch im Stand
des Tags: für Kubernetes [Aktualisierung und Rückweg](handbuch/betrieb/kubernetes.md#aktualisierung-und-rückweg),
für Docker Compose [Vorbereitungsschritte für Bestandsinstallationen](handbuch/betrieb/docker-compose.md#vorbereitungsschritte-für-bestandsinstallationen).

Die Datei nennt, was eine Bestandsinstallation **vor** dem Update erledigen muss, getrennt nach
Kubernetes und Docker Compose, und verweist für Einzelheiten auf das Kapitel des Handbuchs. Was
als Vorbereitung zählt, entspricht der Liste der Brüche unter [Versionsnummern](#versionsnummern):
eine neue Pflicht-Variable oder ein neuer Pflichtwert im Chart, ein geänderter Port, geänderte
Eigentumsrechte, eine Neuindizierung. Ein Beispiel:

```markdown
- **Kubernetes:** Keine.
- **Docker Compose:** `OPAA_BEISPIEL` in der `.env.docker` setzen, sonst startet das Backend nicht
  (Handbuch, Deployment, Vorbereitungsschritte für Bestandsinstallationen).

Neuindizierung: nein
```

Eine Datei mit Vorbereitung trägt eine eigene Zeile `Neuindizierung: ja` oder `Neuindizierung: nein`
(auch fett, `**Neuindizierung:** ja`). Hinter `ja` folgt die Dauerschätzung, etwa
`Neuindizierung: ja, rund eine Stunde je 10.000 Dokumente bei GPU-Einbettung`. Das
[Update-Paket](#betriebsfolgen-und-antwortqualität) liest die Zeile; fehlt sie, steht dort „nicht
gekennzeichnet“. Eine Datei „Keine Vorbereitung nötig.“ braucht sie nicht.

Regeln:

- Die Datei beschreibt den Sprung vom **vorigen Release** aus. Ein finales Release nach
  Vorab-Releases nennt alle Schritte seit dem vorigen finalen Release, auch wenn sie schon in den
  Notizen eines Vorab-Release standen: Betreiber eines finalen Release lesen die Vorab-Releases nicht.
- Wer Versionen überspringt, prüft die Vorbereitung jedes übersprungenen Release; das sagen auch
  die Notizen.
- **Ein finales `X.Y.0` braucht die Datei immer.** Vorbereitung verlangt nur ein Bruch, und ein
  Bruch erhöht nach den [Versionsnummern](#versionsnummern) `Y` (unter `0.x`) bzw. `X` (ab
  `1.0.0`). Ein Patch-Release verlangt deshalb nie Vorbereitung. Für ein `X.Y.0` erzwingt die
  Pflichtdatei die Entscheidung; „Keine Vorbereitung nötig.“ ist ein gültiger Inhalt.
- **Finales Tag auf dem Commit eines Release-Kandidaten:** Wird `vX.Y.0` auf denselben Commit
  gesetzt wie zuvor `vX.Y.0-rc.N`, muss `docs/upgrade-notes/X.Y.0.md` schon in diesem Commit
  liegen. Die Datei gehört also vor das Tag des letzten Release-Kandidaten, zusammen mit einer
  eventuellen `X.Y.0-rc.N.md`.
- Ein Patch-Release ohne Codeänderung liegt auf dem Commit des vorigen Release, in dem es keine
  Datei für die neue Version geben kann. Es verlangt auch keine Vorbereitung; die Notizen sagen
  das ausdrücklich.
- Die statische Prüfung des Charts (`deploy/helm/ci/static-check.sh`, in der Chart-CI auch bei
  Änderungen unter `docs/upgrade-notes/`) prüft, dass jede Datei nach einer Release-Version benannt,
  nicht leer und kein symbolischer Link ist, ihre Codeblöcke schließt und dass die verlinkten
  Überschriften im Handbuch existieren.

## Update-Paket

Zu jedem Release, auch zu einem Vorab-Release, erzeugt der Job `update-package` das Update-Paket,
mit dem eine Behörde ein Update gegenüber Datenschutz, Personalvertretung und
Informationssicherheit verantwortet ([Spezifikation](features/behoerdenunterlagen.md#das-update-paket)).
Die Logik steht in `.github/scripts/update_package.py`, der Workflow ruft nur auf. Das Archiv
`opaa-update-paket-X.Y.Z.tar.gz` enthält unter `opaa-update-paket-X.Y.Z/`:

| Datei | Inhalt |
|---|---|
| `LIESMICH.md` | Version, Commit, Vergleichsstände und für wen sie gelten, Prüfbefehle, was noch fehlt |
| `faktenblaetter/` | die Faktenblätter der Version mit `faktenblaetter.json` (`./gradlew complianceDocs -PopaaVersion=X.Y.Z`) |
| `delta/manifest-delta.md`, `delta/manifest-delta.json` | das [kumulierte Delta](features/behoerdenunterlagen.md#das-kumulierte-delta) zum vorigen finalen Release mit Stufenvorschlägen und den PRs mit `data-impact` dazwischen |
| `betrieb/betriebsfolgen.md`, `betrieb/antwortqualitaet.md` | [Betriebsfolgen und Antwortqualität](#betriebsfolgen-und-antwortqualität) für dasselbe Intervall |
| `delta-lts/manifest-delta.md`, `delta-lts/manifest-delta.json`, `betrieb-lts/` | nur bei einem LTS-Release und seinen Kandidaten: Delta, Betriebsfolgen und Antwortqualität gegenüber dem jüngsten Release der vorigen LTS-Linie ([zweiter Vergleich](#update-paket)) |
| `lizenzen/` | Lizenzübersicht von Backend und Frontend wie in der [Lizenzprüfung](sbom.md#lizenzprüfung) |
| `sbom/opaa-backend-X.Y.Z.cdx.json`, `sbom/opaa-frontend-X.Y.Z.cdx.json` | die [CycloneDX-SBOMs](sbom.md#ebene-2--ökosystem-sboms-cyclonedx) der ausgelieferten Abhängigkeiten, dieselben Dateien wie am GitHub-Release |
| `vorlagen/` | alle Markdown-Dateien aus `docs/behoerden/` in derselben Ordnerstruktur, Vorlagen und Ordnerübersichten; ohne `AGENTS.md` und `CLAUDE.md` auf oberster Ebene, die Regeln für Autoren sind |
| `handbuch/` | alle Markdown-Dateien aus `docs/handbuch/` in derselben Ordnerstruktur, ohne `AGENTS.md` und `CLAUDE.md` auf oberster Ebene |
| `update-vermerk.md` | die Vorlage aus `docs/behoerden/09-betrieb-update/` noch einmal, zum direkten Ausfüllen |
| `index.html`, `style.css`, `<datei>.html` | die HTML-Fassung: neben jeder Markdown-Datei dieselbe Seite als HTML, `index.html` als Startseite |
| `SHA256SUMS` | Prüfsummen aller Dateien |

Daneben liegen `opaa-update-paket-X.Y.Z.tar.gz.sha256` und die beiden SBOMs. Die Backend-SBOM
entsteht im selben Gradle-Lauf wie die Faktenblätter (`cyclonedxBom`), die Frontend-SBOM
gleichzeitig mit cdxgen; scheitert eines davon, scheitert der Job und mit ihm das Release. Der Job
`update-package-attestation` attestiert das Archiv und die SBOMs (`actions/attest`, Prüfbefehl
unter [Was ein Release erzeugt](#was-ein-release-erzeugt)), der Job `release` hängt alle Dateien
an das GitHub-Release.

**Vorgänger.** Verglichen wird mit dem höchsten finalen Tag `vA.B.C` unter der eigenen Version, das
der getaggte Commit enthält. Vorab-Tags zählen nie, auch nicht für ein Vorab-Release; ein
Patch-Release ohne Codeänderung vergleicht mit dem Release auf demselben Commit, sein Delta ist
leer. Gibt es kein finales Tag davor (das erste finale Release), vergleicht das Delta mit dem
ältesten Wurzel-Commit, der kein Manifest hat: Jeder Eintrag erscheint als neu, und
`LIESMICH.md` und der Kopf des Deltas sagen das. Überschriften und Text nennen ihn „Stand ohne
Fakten-Manifest (Commit `abc1234`)“, nicht mit der rohen Commit-ID; das gilt für jeden Commit ohne
Manifest, der als Vergleich angegeben ist. Die Regel trägt auch mehrere
[Supportlinien](#supportlinien): Ein Patch-Release auf `release/X.Y` enthält die Tags seiner Linie,
nicht die neuerer Linien, und vergleicht so mit dem vorigen Release seiner Linie.

**Zweiter Vergleich eines LTS-Release.** Ein LTS-Release `vX.Y.0` und seine Kandidaten
`vX.Y.0-rc.N` vergleichen zusätzlich mit dem jüngsten finalen Release der vorigen LTS-Linie
([ADR-0054](decisions/0054-release-zyklus-und-supportlinien.md), Entscheidung 5): Wer nur LTS-Linien
betreibt, springt von dort direkt auf das neue LTS. Ob ein Tag ein LTS ist, sagt die
[Liste der Linien](#liste-der-linien) im getaggten Commit (`kind: "lts"` für `X.Y`); die vorige
LTS-Linie ist die höchste LTS-Linie darunter, ihr jüngstes finales Tag wird unter allen Tags
gesucht, denn ihre Patch-Releases liegen auf `release/A.B` und nicht im Verlauf von `main`. Das Paket
legt diesen Vergleich getrennt ab, in `delta-lts/` und `betrieb-lts/`, und `LIESMICH.md` sagt im
Abschnitt „Welcher Vergleich für wen“, welcher für wen gilt. Der zweite Lauf von `complianceDelta`
vergleicht die Manifeste beider Stände unabhängig von ihrer Ahnenreihe; die Liste der als
datenrelevant gekennzeichneten PRs dazwischen nimmt die Commits, die das neue Release enthält und die
vorige LTS-Linie nicht, ohne die, deren Patch dort schon unverändert als Backport steckt
(`git log --cherry-pick --right-only`). Ein Backport mit aufgelöstem Konflikt hat einen anderen
Patch und erscheint deshalb ein zweites Mal. Ohne vorige LTS-Linie mit finalem Release, bei einem
normalen Release und bei einem Patch-Release entfällt der zweite Vergleich, ebenso wenn er auf
dasselbe Tag fiele wie der erste; hat die höchste vorige LTS-Linie kein finales Release, eine ältere
aber schon, warnt der Lauf, statt auf die ältere auszuweichen. Das Vergleichs-Tag wird bei jedem
Lauf neu bestimmt: Ist zwischen erstem Lauf und „Re-run failed jobs“ ein Patch der vorigen LTS-Linie
erschienen, vergleicht `delta-lts/` mit diesem.
`update_package.py lts-previous --tag <tag> --commit <commit>` nennt das Tag.

**Verweise im Paket.** Das Paket ist in sich verlinkt und lässt sich offline durchklicken. In
jeder Vorlage unter `vorlagen/`, in `update-vermerk.md` und in jedem Kapitel unter `handbuch/` wird
beim Zusammenstellen

- jeder Faktenblatt-Platzhalter `{{faktenblatt:<name>[#<anker>]}}` ein relativer Link auf
  `faktenblaetter/<name>.md`, passend zur Tiefe der Datei; ein Blatt oder Anker, das es im Paket
  nicht gibt, bricht den Lauf ab (nur in Vorlagen),
- ein relativer Link auf eine Datei oder einen Ordner im Paket ein relativer Link dorthin, auch
  zwischen Vorlagen und Handbuch; Links, die im Paket schon treffen, bleiben so, wie sie
  geschrieben sind,
- ein relativer Link auf alles andere im Repository, etwa `SECURITY.md`, ein ADR oder diese
  Datei, ein absoluter Link auf GitHub im Stand des Release-Tags
  (`https://github.com/<repository>/blob/vX.Y.Z/<pfad>`, bei einem Probelauf auf den Commit);
  ein Ziel, das es im Checkout nicht gibt, bricht den Lauf ab.

Anker innerhalb einer Datei und externe Links bleiben unverändert. Platzhalter und Links in
Code-Spans und Codeblöcken bleiben stehen, ebenso Text, der nicht aus einer Vorlage oder dem
Handbuch stammt, etwa Titel von Pull Requests im Delta oder Upgrade-Hinweise in den
Betriebsfolgen. Neue Vorlagen, Kapitel und Ordner unter `docs/behoerden/` und `docs/handbuch/`
kommen ohne Änderung am Skript ins Paket. Der Link
des Deltas auf die Stufenregeln zeigt auf den Commit des Release im Repository des Laufs
(`--repository`, im Workflow `GITHUB_REPOSITORY`; an `complianceDelta` als `-Prepository`), nicht
auf `main`.

**HTML-Fassung.** Danach erzeugt `.github/scripts/package_html.py` mit markdown-it-py (mit
allen Abhängigkeiten per Hash gepinnt in `.github/requirements-release.txt`, nur als Wheel installiert) zu jeder Markdown-Datei des Pakets eine HTML-Seite daneben, dazu
`index.html` mit allen Teilen und `style.css` (helles und dunkles Farbschema). Überschriften tragen
Anker in der Schreibweise von GitHub, `<a id>` bleiben erhalten, ein Front-Matter-Kopf erscheint als
kleiner Kasten. Links auf Markdown-Dateien führen auf deren HTML-Seite, Links auf einen Ordner auf
seine `README`. Die Seiten laden nichts aus dem Internet; Mermaid-Diagramme stehen als Code da. Ein
relativer Link der HTML-Fassung, dessen Datei oder Anker fehlt, bricht den Lauf ab. Prüfsummen und
Attestierung decken die HTML-Dateien wie alle anderen ab. Jeder PR prüft
dieselben Verweise vorab: Der Job `changes` der CI ruft `update_package.py links` auf, ohne
Faktenblätter und ohne Pfadfilter, sodass ein umbenannter Anker im Handbuch nicht erst das Release
anhält.

**Wiederholbar.** Erzeugungsdatum der Faktenblätter (`-PgeneratedOn`), Zeitstempel der SBOMs und
im Archiv sind die Commit-Zeit, die Seriennummern der SBOMs aus Repository, Commit und Dateiname
abgeleitet ([sbom.md](sbom.md#ebene-2--ökosystem-sboms-cyclonedx)), Besitzer und Rechte fest, die Reihenfolge sortiert. Derselbe Commit ergibt
dieselben Dateien und dieselben Zeilen in `SHA256SUMS`, mit einer Ausnahme: Der Abschnitt
„Weitere gekennzeichnete Änderungen“ im Delta folgt dem Stand des Labels `data-impact` zum
Zeitpunkt des Laufs; wird ein Label zwischen zwei Läufen gesetzt oder entfernt, unterscheidet sich
das Delta. Die Bytes des Archivs hängen zusätzlich von der zlib-Version des Runners ab. Ein
wiederholter Job `update-package` erzeugt das Archiv neu, eine wiederholte Attestierung attestiert
das dann hochgeladene. Die PR-Liste fragt der Job mit einem Limit von 1000 ab; erreicht sie es,
bricht er ab, statt eine abgeschnittene Liste zu verwenden.

**Anhängen ist nicht atomar.** `gh release create` mit Anhängen legt das Release zuerst als
Entwurf an, lädt dann die Dateien hoch und veröffentlicht erst danach. Scheitert der Upload, bleibt
ein Entwurf ohne Anhänge, und eine Wiederholung des Jobs `release` scheitert daran, dass es das
Release schon gibt. Vor *Re-run failed jobs* deshalb den Entwurf löschen; das Tag bleibt stehen:

```bash
gh release delete vX.Y.Z --repo criew/opaa --yes   # ohne --cleanup-tag
```

**Ohne Cache.** Wie die Image-Builds eines Release liest und schreibt `update-package` keinen
Cache: kein `setup-gradle`, kein Cache von pnpm, `setup-node` oder `setup-python`. Gradle, pnpm
und pip laden ihre Abhängigkeiten frisch, der Job braucht deshalb einige Minuten. cdxgen für die Frontend-SBOM
kommt samt aller transitiven Abhängigkeiten aus der Lockfile unter `.github/tools/cdxgen/`, ohne
Installationsskripte ([sbom.md](sbom.md#ebene-2--ökosystem-sboms-cyclonedx)). Rechte: nur Lesen des Repositorys und
der PRs; das OIDC-Token für die Attestierung hält nur der eigene Job, in dem weder Gradle noch pnpm
läuft. `.github/scripts/test_update_package.py` prüft beides am Workflow.

**Probelauf ohne Release.** Dasselbe Paket entsteht lokal oder in einer eigenen CI für den
ausgecheckten Commit; gibt es das Tag nicht, kennzeichnet `LIESMICH.md` den Lauf als Probelauf.
Gebraucht werden Java, `pnpm install` in `frontend/`,
`pnpm install --frozen-lockfile --ignore-scripts --no-optional` in `.github/tools/cdxgen/`,
`pip install --require-hashes --only-binary=:all: --no-deps -r .github/requirements-release.txt`
und die Historie mit Tags:

```bash
gh pr list --state merged --label data-impact --limit 1000 --json number,title,url >/tmp/prs.json
python3 .github/scripts/update_package.py build --tag v0.3.0 --out /tmp/paket \
  --labelled-prs /tmp/prs.json [--previous <tag oder commit>]
python3 .github/scripts/update_package.py previous --tag v0.3.0 --commit HEAD   # nur den Vorgänger
```

Ohne `--labelled-prs` sagt das Delta, dass die gekennzeichneten PRs nicht abgefragt wurden.

**Erweiterungen.** Das Offline-Bündel (#2557) nimmt das Archiv aus dem Artefakt `update-package` des
Laufs, statt es neu zu erzeugen.

### Betriebsfolgen und Antwortqualität

`betrieb/betriebsfolgen.md` sagt Betrieb und ISB, was das Update für Wartungsfenster, Sicherung und
Freigaben bedeutet, `betrieb/antwortqualitaet.md` der Fachseite, ob die Suche schlechter wird. Beide
entstehen in `update_package.py`, nicht im Manifest-Delta: Ihre Quellen sind keine Fakten des
Manifests, und `ModularArchitectureTest` lässt im Backend nur `ManifestSnapshot` Git starten.
Gelesen wird jede Angabe aus Git an den beiden Commits des Intervalls, nie aus dem Arbeitsstand; jeder
Abschnitt nennt seine Quelle mit Pfad und Commit.

| Abschnitt | Quelle | Regel |
|---|---|---|
| Bruch und Vorbereitung | `docs/upgrade-notes/<Version>.md` im neuen Stand | jede Version über dem Vergleichsstand bis zur eigenen, kumuliert; ist der Vergleichsstand kein Release-Tag, die Dateien, die er nicht enthält. Ein Hinweis zu `X.Y.Z` ersetzt die seiner Vorab-Releases. Bruch ist „ja“, sobald ein Hinweis mehr sagt als „Keine Vorbereitung nötig.“ |
| Neuindizierung | die Zeile `Neuindizierung:` der Hinweise ([Vorbereitung](#vorbereitung-für-bestandsinstallationen)) | „nötig“ mit der Dauerschätzung des Hinweises; „nicht gekennzeichnet“, wenn ein Hinweis mit Vorbereitung die Zeile nicht trägt; „nicht nötig“, wenn kein Hinweis Vorbereitung verlangt oder jeder Hinweis mit Vorbereitung `Neuindizierung: nein` sagt. Die Zeile allein ist keine Vorbereitung: „Keine Vorbereitung nötig.“ mit `Neuindizierung: nein` ist kein Bruch |
| Nachzug | `ingestionPipelineFingerprint` der Baselines unter `eval/baseline/` beider Stände | jedes Format mit neuer Pipeline-Version; `PipelinePathIsolationTest` hält den Abdruck gleich dem Code |
| Neueinbettung | Eintrag `spring.ai.openai.embedding.model` im Manifest-Delta | geänderte Vorgabe des Embedding-Modells |
| Schemamigration | Kennungen der `changeSet`-Einträge unter `backend/src/main/resources/db/changelog/` beider Stände | neu ist jede Kennung, die nur der neue Stand hat; Anzahl je Modulverzeichnis, Hinweis „nur vorwärts, vorher sichern“ |
| Ports | Schlüssel auf `port` mit Zahlenwert in `deploy/helm/opaa/values.yaml` und `containerPort` in den Templates | neu, geändert, entfallen; nur Blockschreibweise |
| Egress-Ziele | Abschnitt `dataFlows` im Manifest-Delta (`delta/manifest-delta.json`) | neue und entfallene Datenflüsse, geändertes Ziel, geänderter Weg über den Ausgangsproxy |
| Antwortqualität | `eval/baseline/*.json` beider Stände, Gruppe `overall` | Kennzahlen, Messdatum, Messlauf und Messvertrag je Messpfad und Domäne; fehlt eine Baseline oder Kennzahl, steht „nicht gemessen“; abweichende Festpunkte heißen „Messgrundlage geändert“ |

Annahmen und Grenzen:

- **Antwortqualität sind Referenzwerte.** Je Stand sind es die committeten Baselines der
  [Retrieval-Regression](features/search-quality-evaluation.md), also der zuletzt angenommene Messlauf
  mit seinem Datum, nicht ein Lauf auf dem Release-Commit. Die Ergebnisse der einzelnen Läufe liegen
  nur 90 Tage als Artefakt am Workflow-Lauf; die Datei sagt deshalb ausdrücklich „Messlauf auf dem
  Commit dieser Version: nicht gemessen“. Einen Wert je Release gäbe erst ein Messbericht im
  Repository oder am Release. Die Kennzahlen messen die Suche, nicht die formulierte Antwort.
- **Keine Dauerschätzung aus der Evaluierung.** Die Evaluierung hält ihre Laufzeit nur als Freitext
  fest, mit einem kleinen Modell auf CPU; auf eine Installation übertragbar ist das nicht. Die
  Schätzung kommt allein aus der Zeile `Neuindizierung:` des Hinweises.
- **Ports nur aus dem Chart**, Docker Compose bleibt außen vor. Gelesen werden nur die Port-Schlüssel
  in `values.yaml` und die `containerPort`-Zeilen der Templates; gleiche `containerPort`-Zeilen in
  einer Datei erscheinen einmal. Feste Ports an anderer Stelle der Templates, etwa in einer
  NetworkPolicy oder einem Service, erfasst das Paket nicht. Ein Egress-Ziel ist eine Rolle (etwa
  „der Einbettungs-Endpunkt“), keine Adresse; die Adresse legt die Konfiguration der Installation fest.
- **Das Manifest-Delta muss zum Intervall passen.** Nennt `manifest-delta.json` andere Commits als
  das Intervall, oder fehlt eine ausdrücklich angegebene Datei, bricht der Lauf ab, statt fremde
  Datenflüsse zu zeigen.
- **Neue Changesets sind eine Obergrenze**, wenn die Historie zu einer neuen Baseline zusammengefasst
  wurde; dann fehlen Kennungen des Vergleichsstands, und die Datei sagt das.

Für ein anderes Versionspaar erzeugt `operations` die Dateien in einem Checkout mit Tags, nach
`./gradlew complianceDelta` mit denselben Ständen. Es liest immer ab der Wurzel des Repositorys,
auch aus einem Unterverzeichnis gestartet; relative Pfade gelten ab dem Startverzeichnis. Ohne
`--delta-json` stehen Datenflüsse und Embedding-Vorgabe als „nicht ermittelt“ da. Aus der Wurzel:

```bash
python3 .github/scripts/update_package.py operations --from v0.12.0 --to v0.17.0 --out /tmp/betrieb \
  --delta-json backend/build/compliance/delta/manifest-delta.json
```

## Offline-Bündel

Für Netze ohne Internetzugang hängt an jedem Release, auch an einem Vorab-Release,
`opaa-offline-X.Y.Z.tar`. Das Bündel enthält alles von OPAA selbst, was hinter einer Schleuse
gebraucht wird, und lässt sich dort ohne Netz prüfen. Der Job `offline-bundle` ruft dafür den
wiederverwendbaren Workflow `.github/workflows/offline-bundle.yml` auf; die Logik steht in
`.github/scripts/offline_bundle.sh`. Das Archiv enthält unter `opaa-offline-X.Y.Z/`:

| Datei | Inhalt |
|---|---|
| `images/opaa-backend-X.Y.Z.oci.tar`, `images/opaa-frontend-X.Y.Z.oci.tar` | die veröffentlichten Images als OCI-Archiv, je mit `linux/amd64` und `linux/arm64` und ihren SBOM- und Provenance-Manifesten; der Digest des Index ist derselbe wie in GHCR |
| `DIGESTS` | Image-Referenz und Digest des Index je Image |
| `chart/opaa-X.Y.Z.tgz` | das Helm-Chart aus GHCR |
| `update-paket/` | das [Update-Paket](#update-paket) samt Handbuch der Version |
| `sbom/` | die beiden CycloneDX-SBOMs |
| `attestations/` | die Sigstore-Bundles der Attestierungen von Update-Paket, SBOMs und Chart, wie sie der Release-Lauf erzeugt hat (`gh attestation download`) |
| `LIESMICH.md` | Inhalt und Kurzfassung der Prüfung |
| `SHA256SUMS`, `SHA256SUMS.sigstore.json` | Prüfsummen aller Dateien und die Attestierung dieser Datei |

Daneben liegen `opaa-offline-X.Y.Z.tar.sha256` und `opaa-offline-X.Y.Z.tar.sigstore.json`, die
Attestierung des Archivs.

**Ablauf.** Der Job `collect` holt Images und Chart nach ihrer Veröffentlichung aus GHCR zurück
(`skopeo copy --all --preserve-digests`) und bricht ab, wenn der Digest eines Index im Archiv von
dem in GHCR abweicht. Das Bündel enthält also genau das Veröffentlichte. Update-Paket und SBOMs
kommen aus dem Artefakt desselben Laufs; fehlt eine der beiden SBOMs, bricht `collect` ab. Der Job `attest` attestiert `SHA256SUMS`, legt das
Sigstore-Bundle dazu, packt das Archiv und attestiert es ebenfalls. Nur dieser Job hält das
OIDC-Token; er führt außer `tar` und `sha256sum` nichts aus. Der Job `release` hängt Archiv,
Prüfsumme und Sigstore-Bundle an das GitHub-Release.

**Ablage als Release-Asset.** Das Bündel hängt am GitHub-Release, nicht als OCI-Artefakt in
GHCR:

- Wer es herunterlädt, braucht nur `gh release download` oder den Browser, kein `oras` und keinen
  Registry-Zugang.
- Es liegt neben Update-Paket und SBOMs, die Behörden dort schon holen.
- Ein Asset darf höchstens 2 GiB groß sein. Das Bündel bleibt mit rund 600 MB deutlich darunter
  (gemessen an `v0.1.0-rc.4`). `pack` bricht oberhalb von 1,9 GiB ab; dann wäre die Ablage neu
  zu entscheiden, etwa ein Archiv je Architektur.

**Prüfung ohne Netz.** Sie steht für Betreiber im Handbuch
([Images und Laufzeit](handbuch/betrieb/images-und-laufzeit.md#installation-ohne-internetzugang)):

1. Vertrauensanker je Release vorab holen (`gh attestation trusted-root`).
2. Das Archiv vor dem Entpacken gegen `opaa-offline-X.Y.Z.tar.sigstore.json` prüfen, mit
   `--custom-trusted-root`.
3. `SHA256SUMS` gegen ihr Sigstore-Bundle prüfen, danach `sha256sum -c SHA256SUMS`.

Die Schritte 2 und 3 binden das Bündel an `offline-bundle.yml` und an das Tag `vX.Y.Z` (`--signer-workflow`,
`--source-ref`). Diese Identität entsteht nur im Release-Lauf: Auf einem Tag-Ref bricht
`offline-bundle.yml` ab, wenn das Tag nicht `v` plus die verlangte Version ist oder der Aufrufer nicht
`publish-images.yml` (`GITHUB_WORKFLOW_REF`). Der Probelauf startet auf einem Tag gar nicht. Seine
Attestierungen nennen den Ref eines Branches oder PRs und bestehen die Prüfung deshalb nicht.

**Probelauf.** `.github/workflows/offline-bundle-probe.yml` baut das Bündel des jüngsten Release
zwischen zwei Releases, auf Zuruf und an einem PR über das Label `offline-bundle-suite`. Danach
prüft der Lauf das Bündel nach dem Handbuch in einem Netz-Namespace ohne Schnittstelle außer
Loopback (`unshare --net`), mit `offline_bundle.sh check`:

- Prüfsumme und Attestierung von Archiv, `SHA256SUMS` und Update-Paket
- Digests der Image-Archive
- Lesbarkeit des Charts
- zwei Manipulationen: ein geändertes Byte eines Image-Archivs scheitert an `SHA256SUMS`, eine
  daran angepasste `SHA256SUMS` an ihrer Attestierung

`.github/scripts/test_offline_bundle.py` prüft Prüfsummen, Packen, Größengrenze und die
Verdrahtung im Release-Lauf in jedem PR.

Nicht im Bündel sind Drittimages (Datenbank, Modellserver, Identitätsanbieter) und Modellgewichte.

## Versionsnummern

OPAA zählt nach [Semantic Versioning](https://semver.org/lang/de/). Solange die Hauptversion `0`
ist, gilt:

| Teil | Erhöht bei |
|---|---|
| `Y` in `0.Y.Z` | neuen Funktionen **und** jedem Bruch |
| `Z` in `0.Y.Z` | Fehlerbehebungen und Sicherheitsupdates ohne neue Funktion, auch nur des Basis-Images |

Ab `1.0.0` steigt bei einem Bruch die Hauptversion.

**Als Bruch gilt**, was eine Bestandsinstallation zu Handarbeit zwingt:

- eine neue Pflicht-Umgebungsvariable oder ein geänderter Wert, ohne den der Start scheitert,
- ein Vorbereitungsschritt im Handbuch, z. B. Eigentumsrechte, Ports, Volumes,
- eine inkompatible Änderung an `/api/v1` oder am MCP-Endpunkt,
- eine Migration, die eine Neuindizierung oder einen Neuaufbau verlangt.

Schemaänderungen laufen immer vorwärts. Zurück auf eine ältere Version geht es über die
Datenbanksicherung, unabhängig davon, ob die Version als Bruch gezählt wird. Ohne Sicherung geht es
nur zurück, wenn die neue Version die vorige in ihrem Versionsfenster zulässt (unten): Ihr Schema hat
die vorige nur erweitert.

**Versionsfenster für Rolling Updates.** `backend/src/main/resources/instance/rolling-update.properties`
nennt in `oldest-compatible-version` die älteste Version, neben der der Bau laufen darf
([ADR-0053](decisions/0053-mehrinstanzbetrieb-aktiv-aktiv.md), Entscheidung 4). Leer heißt: nur neben
sich selbst. Das Release trägt dort das unmittelbar vorige Release ein; an einer Versionsgrenze ohne
Rolling Update bleibt es leer. Bis zum ersten Release, das den Wert setzt, laufen nur Stände derselben
Version nebeneinander ([Handbuch, Update ohne Ausfall](handbuch/betrieb/mehrere-instanzen.md#update-ohne-ausfall)).

Ein finales `X.Y.0` entsteht auf `main` und eröffnet eine Linie, die Patch-Releases `X.Y.Z` bis
zu ihrem Supportende bekommt ([Supportlinien](#supportlinien)). Eine ältere Linie wird also
nachgepflegt, solange ihr Supportzeitraum läuft, und danach nicht mehr. Es gibt zwei Arten von
Patch-Release:

- **Ohne Codeänderung:** Ein neues Tag `vX.Y.(Z+1)` auf **demselben Commit** wie das letzte Release
  der Linie, dem Stand von `release/X.Y`. Der neue Bau zieht die aktuellen Pakete der Basis-Images.
- **Mit Codeänderung:** Die Korrektur landet zuerst auf `main` und kommt per Backport auf den
  Wartungsbranch `release/X.Y` der Linie, das Tag auf dessen Commit ([Backport](#backport)).

## Supportlinien

Festgelegt in [ADR-0054](decisions/0054-release-zyklus-und-supportlinien.md), orientiert an
Spring Boot, der Basis des Backends: Es erscheint als Minor-Version im Mai und im November und
unterstützt jede Minor-Version mindestens 12 Monate kostenlos
([Spring Boot Wiki, Supported Versions](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions);
das Enddatum je Version nennt die [Projektseite](https://spring.io/projects/spring-boot#support)).

| | Regel |
|---|---|
| **Takt** | zwei Funktionsreleases `X.Y.0` im Jahr, etwa Juni und Dezember, jeweils 4–6 Wochen nach Spring Boot und auf dessen neuer Minor-Version, das Juni-Release spätestens zum Supportende der laufenden LTS-Linie (im Regelfall 30.06.); dazwischen nur Patch- und Vorab-Releases |
| **Linie** | jedes finale `X.Y.0` eröffnet die Linie `X.Y`; ihre Patch-Releases bringen Sicherheitskorrekturen und Behebungen [kritischer Fehler](#kritische-fehler), sonst nichts: keine neue Funktion, keinen [Bruch](#versionsnummern) und keine neue Minor-Version von Spring Boot |
| **Normales Release** | Patch-Releases bis einen Monat nach dem nächsten Funktionsrelease |
| **LTS** | das Juni-Release; Patch-Releases bis zum Ende des kostenlosen Supports seiner Spring-Boot-Minor-Version, im Regelfall der 30.06. des Folgejahres, bei einem Release im Takt also rund 12 Monate; weil das nächste LTS spätestens zu diesem Supportende erscheint, überlappen die LTS-Linien immer, aber höchstens wenige Wochen |
| **Wechsel** | wer nur LTS betreibt, aktualisiert einmal im Jahr von LTS zu LTS. Weil die Überlappung knapp ist, erscheint das nächste LTS so früh wie möglich als Release-Kandidat (`vX.Y.0-rc.N`), sobald OPAA auf der neuen Spring-Boot-Version läuft, spätestens zwei Wochen nach deren Erscheinen; danach kommen bis zum finalen Release nur Korrekturen hinzu, und das finale Release erscheint spätestens zum Supportende der laufenden LTS-Linie. Der Kandidat trägt das vollständige Update-Paket mit dem kumulierten Delta zur laufenden LTS-Linie ([#3020](https://github.com/criew/opaa/issues/3020)); auf seiner Grundlage bereitet die Behörde den Wechsel vor und beginnt die Beteiligung, damit sie das finale Release vor dem Supportende der laufenden Linie einspielen kann |
| **Supportende** | steht bei einem LTS mit Erscheinen fest, bei einem normalen Release mit Erscheinen seines Nachfolgers, also mindestens einen Monat vorher; es steht in der Roadmap unten und in den Release-Notizen und wird nie vorgezogen |

Meist laufen zwei Linien nebeneinander, im Monat nach einem Juni-Release drei. Ein Patch-Release
einer Linie ist damit immer ein Update ohne neue Funktion, das eine Behörde sofort einspielen kann.
Wer neue Funktionen vor dem nächsten Funktionsrelease braucht, folgt `main`.

**Was heute gilt.** Takt und Supportzeiträume gelten ab dem ersten
finalen Release. Jede unterstützte Linie bekommt Patch-Releases: ein Sicherheitsupdate des
Basis-Images als neues Tag auf dem Commit ihres letzten Release, eine Korrektur im Code per
[Backport](#backport) auf ihren Wartungsbranch. Ein Patch-Release einer älteren Linie wird nicht
„Latest“, die jüngste Linie behält die Markierung. Der nächtliche CVE-Scan prüft jede
unterstützte Linie ([Sicherheitsupdates für Releases](#sicherheitsupdates-für-releases)), und das
Update-Paket eines LTS-Release vergleicht zusätzlich mit der vorigen LTS-Linie
([Update-Paket](#update-paket)).

### Kritische Fehler

Kritisch ist ein Fehler, für den es keinen zumutbaren Workaround gibt und der

- Daten verliert oder verfälscht (Dokumente, Indizes, Einstellungen, Protokoll),
- OPAA oder eine Kernfunktion ausfallen lässt (Start, Anmeldung, Suche, Chat, Indizierung) oder
- Rechte falsch auswertet, also Inhalte Personen zeigt, die sie nicht sehen dürfen, oder
  Berechtigten den Zugriff verweigert.

Ob ein Fehler kritisch ist, entscheidet der Maintainer und hält es am Issue fest. Die Behebung
kommt wie eine Sicherheitskorrektur zuerst auf `main` und dann per Backport auf jede unterstützte
Linie, die der Fehler betrifft. Alles andere erscheint mit dem nächsten Funktionsrelease.

### Roadmap

Geplant ist nach dem Takt von Spring Boot, nicht nach Funktionen; Versionsnummern vergibt erst das
Release.

Jede Zeile, deren Release noch nicht erschienen ist, hat einen
[GitHub-Meilenstein](https://github.com/criew/opaa/milestones) mit dem Namen der Zeile
([ADR-0054](decisions/0054-release-zyklus-und-supportlinien.md), Entscheidung 6). Fällig ist er am
letzten Tag des geplanten Monats; das erste finale Release liegt außerhalb des Takts und hat kein
Fälligkeitsdatum. Die Beschreibung nennt nur die Art (LTS oder normal) und verweist auf diese
Tabelle. Supportenden und Funktionen stehen nicht im Meilenstein; ein Issue in einem Meilenstein ist
für dieses Release geplant, nicht zugesagt. Gepflegt werden die Meilensteine im
[Release-Ablauf](#wer-ein-release-anlegt), Schritt 7.

| Linie | Erscheint | Spring Boot | Art | Patch-Releases bis |
|---|---|---|---|---|
| erstes finales Release (aus den Kandidaten `v0.1.0-rc.N`) | vor dem ersten Release im Takt, außerhalb des Takts | 4.1 | normal | einen Monat nach dem Dezember-Release 2026 |
| Dezember-Release 2026 | 4–6 Wochen nach Spring Boot 4.2 (November 2026) | 4.2 | normal | einen Monat nach dem Juni-Release 2027 |
| Juni-Release 2027 | 4–6 Wochen nach der Mai-Version 2027 von Spring Boot (noch ohne vorige LTS-Linie) | Mai-Version 2027 | erstes LTS | Ende des kostenlosen Supports dieser Spring-Boot-Version, im Regelfall 30.06.2028; Datum steht mit Erscheinen fest |
| Dezember-Release 2027 | 4–6 Wochen nach der November-Version 2027 | November-Version 2027 | normal | einen Monat nach dem Juni-Release 2028 |
| Juni-Release 2028 | 4–6 Wochen nach der Mai-Version 2028, spätestens zum Supportende des LTS 2027 (im Regelfall 30.06.2028); Release-Kandidat spätestens zwei Wochen nach Spring Boot | Mai-Version 2028 | LTS | Ende des kostenlosen Supports dieser Spring-Boot-Version |

Der Release-Kandidat jedes LTS erscheint spätestens zwei Wochen nach der Mai-Version von Spring
Boot, also etwa Anfang Juni und damit einige Wochen vor dem Supportende der laufenden LTS-Linie
(im Regelfall 30.06.); das finale LTS-Release folgt 4–6 Wochen nach Spring Boot, spätestens zu
diesem Supportende.

Erscheint ein Release, ersetzt es seine Zeile durch die Versionsnummer und das feste Supportende;
eine ausgelaufene Linie bleibt mit ihrem Ende stehen.

### Liste der Linien

Maschinenlesbar stehen die Linien in `.github/support-lines.json`, veröffentlichte und eine
LTS-Linie schon vor ihrem ersten Kandidaten; Renovate und
der nächtliche CVE-Scan lesen sie. Darin stehen je Linie `line`
(`X.Y`), `kind` (`lts` oder `normal`) und `supportEnd`, der letzte Tag mit Patch-Releases als
`JJJJ-MM-TT`. Bei einer normalen Linie bleibt `supportEnd` `null`, bis ihr Nachfolger erscheint;
dann ist es der Tag einen Monat nach dessen Erscheinen. Eine LTS-Linie trägt ihr Ende von Anfang
an. Ausgelaufene Linien bleiben stehen. Eine Linie gilt bis einschließlich `supportEnd` als
unterstützt; danach pflegt Renovate ihren Branch nicht mehr, ohne dass jemand `renovate.json5`
ändert ([renovate.md](renovate.md#wartungsbranches)). Geprüft wird die Datei von
`.github/scripts/test_support_lines.py`; gepflegt im Release-Ablauf, Schritt 7.

```json
{
  "lines": [
    { "line": "0.1", "kind": "normal", "supportEnd": "2027-01-31" },
    { "line": "0.2", "kind": "normal", "supportEnd": null }
  ]
}
```

**Eine LTS-Linie kommt früher in die Liste:** per PR auf `main` vor dem Tag ihres ersten
Release-Kandidaten, mit `kind: "lts"` und dem Supportende, das mit der Spring-Boot-Version schon
feststeht, dazu ihre Zeile in der Roadmap. Nur so erkennt das Update-Paket den Kandidaten als LTS
und legt den [zweiten Vergleich](#update-paket) mit der vorigen LTS-Linie bei, auf dem die Behörde die
Beteiligung beginnt. Bis zum finalen Release hat die Linie noch keinen Wartungsbranch, den Renovate
überspringt, und kein finales Release, das der Scan prüfen könnte.

## Sicherheitsupdates für Releases

Weil Release-Images nie neu gebaut werden, tragen sie den Sicherheitsstand ihres Bautags. Der
nächtliche CVE-Scan prüft deshalb neben `main` auch Release-Images (ohne Vorab-Releases, siehe
[cve-scanning.md](cve-scanning.md)): das jüngste Patch-Release jeder unterstützten
[Linie](#supportlinien) nach der [Liste der Linien](#liste-der-linien) und immer die Linie des
höchsten finalen Release. Für jede unterstützte Linie gilt:

| Befund in einer unterstützten Linie | Reaktion |
|---|---|
| `critical` mit verfügbarem Fix | Patch-Release der Linie, Frist wie in cve-scanning.md. Liegt der Fix im Basis-Image, genügt ein neues Tag auf dem Commit des letzten Release der Linie; liegt er in einer Abhängigkeit oder im Code, kommt er per [Backport](#backport) |
| `high` mit verfügbarem Fix | spätestens als Patch-Release nach der wöchentlichen Triage |
| `medium` und `low` mit verfügbarem Fix | kein eigenes Release; mit dem nächsten Patch-Release der Linie |
| ohne verfügbaren Fix | kein Release; Triage nach [cve-scanning.md](cve-scanning.md#triage-von-image-alerts) |

Solange ein gescanntes Release einen `critical`-Befund trägt, bleibt das Alarm-Issue des Scans
offen, auch wenn `main` schon behoben ist. Es schließt erst mit dem Patch-Release.

## Mehrere Architekturen

Beide Images erscheinen für `linux/amd64` und `linux/arm64`, bei `main`, beim wöchentlichen Neubau
und bei Release-Tags gleichermaßen (#2401). Jeder Tag, den ein Lauf seither setzt (`main`,
`sha-<commit>`, `X.Y.Z`, `X.Y`), zeigt auf einen Manifest-Index mit beiden Plattformen; Docker und
Kubernetes ziehen die passende. Ältere `sha-<commit>`-Tags gibt es nur für `linux/amd64`.

**Ablauf in `publish-images.yml`** (das Muster „Distribute build across multiple runners“ aus der
Docker-Dokumentation):

1. `prepare` prüft das Release-Tag einmal für den ganzen Lauf und bestimmt Tags, Labels und
   Index-Annotationen mit `docker/metadata-action`.
2. `build` läuft je Image und Architektur auf einem Runner dieser Architektur (`ubuntu-latest`
   bzw. `ubuntu-24.04-arm`). Er pusht das Image samt SBOM- und Provenance-Attestierung **nur per
   Digest**, ohne Tag, und reicht den Digest als Artefakt weiter. Die Labels stehen im Image jeder
   Plattform.
3. `publish` führt je Image die Digests mit `docker buildx imagetools create` zu einem Index
   zusammen und setzt erst dabei die Tags und die Index-Annotationen. Vorher prüft er im
   Probelauf, dass der Index genau `linux/amd64` und `linux/arm64` enthält; ein Index mit nur einer
   Plattform bekommt nie einen Tag.

Ein Digest ohne Tag gilt für die Existenzprüfung nicht als veröffentlicht; ein abgebrochener Lauf
belegt also keine Version.

**Zeitgrenzen.** Ein Bein von `build` braucht mit Cache üblicherweise ein bis vier Minuten. Ohne
Cache, also bei einem Release oder nachdem GitHub einen sieben Tage nicht abgerufenen Cache
verdrängt hat, sind es bis etwa sechs Minuten. Nach 30 Minuten bricht GitHub es ab, statt den
Lauf stundenlang zu blockieren; die übrigen Jobs haben Grenzen von 10 bis 15 Minuten. Ein abgebrochenes Bein holt
*Re-run failed jobs* nach: Die fertigen Beine behalten ihre Digests, `publish` setzt die Tags erst
danach (#2427).

**Nativ statt emuliert.** Für ein öffentliches Repository stehen arm64-Runner ohne Aufpreis zur
Verfügung. Ein emulierter Bau (QEMU) auf einem amd64-Runner wäre einfacher zu verdrahten, aber der
Gradle-Bau und `jlink` laufen dort um ein Vielfaches langsamer: Schon unter Rosetta, das deutlich
schneller emuliert als QEMU, brauchte `bootJar` lokal etwa das Dreifache der nativen Zeit
(103 s statt 35 s, das ganze Backend-Image 282 s statt 162 s, gemessen am 09.10.2026 auf einem
Apple-Silicon-Rechner ohne Cache).

**Native Bibliotheken.** Das `app.jar` ist für beide Architekturen dasselbe. Nativen Code enthalten
darin nur Netty-Module: QUIC liegt für `linux-x86_64` und `linux-aarch_64` bei, der
Epoll-Transport nur für `linux-x86_64`. Ohne passende Epoll-Bibliothek nimmt Reactor Netty den
Java-NIO-Transport; OPAA konfiguriert keinen Transport selbst. Tesseract, ONNX oder andere
JNI-Bibliotheken gibt es im Image nicht. Die übrigen nativen Teile bringt die `jlink`-Laufzeit mit,
die in der Bau-Stufe der jeweiligen Architektur entsteht.

**Was arm64 prüft:**

- Der Job `image-smoke-arm64` in `.github/workflows/ci.yml` baut beide Images auf einem
  arm64-Runner und startet sie gehärtet (nur lesbares Wurzeldateisystem, keine Capabilities) mit
  `.github/scripts/image_smoke_test.sh`: das Backend gegen PostgreSQL mit pgvector bis zur
  Bereitschaft, das Frontend davor, bis `/api/health` durch dessen nginx antwortet. Er läuft bei
  jedem Push auf `main` und auf Pull Requests, die Dockerfiles, Frontend-Laufzeitdateien, den
  Publish-Workflow oder die Abhängigkeitsdeklarationen ändern. Kein Required Check.
- Der CVE-Scan prüft jede veröffentlichte Plattform ([cve-scanning.md](cve-scanning.md)).
- E2E-Suite und Installationstest des Charts laufen weiter nur auf amd64. Fachliche Fehler
  hängen nicht an der Architektur; was an ihr hängt, sind Start, Laufzeit und native Bibliotheken,
  und die deckt der Rauchtest ab.
