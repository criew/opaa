# Releases

Ein Release ist ein unveränderlicher, benannter Stand von OPAA: zwei Container-Images und ein
Helm-Chart in GHCR mit derselben Versionsnummer und ein GitHub-Release mit der Änderungsübersicht.
Daneben gibt es weiter den beweglichen Stand `main`, der jedem Merge folgt. Wer reproduzierbar
betreiben will, pinnt eine Version.

## Was ein Release erzeugt

Ein Git-Tag `vX.Y.Z` auf einem Commit von `main` startet `.github/workflows/publish-images.yml`:

| Ergebnis | Inhalt |
|---|---|
| `ghcr.io/criew/opaa-backend:X.Y.Z`, `ghcr.io/criew/opaa-frontend:X.Y.Z` | der Release-Stand für `linux/amd64` und `linux/arm64` (ein Manifest-Index je Image), **nie überschrieben** |
| `…:X.Y` | wandert mit jedem Patch-Release dieser Linie mit |
| Helm-Chart `oci://ghcr.io/criew/charts/opaa`, Version `X.Y.Z` | Chart-Version und `appVersion` sind `X.Y.Z`, die Vorgabewerte zeigen auf die Images `X.Y.Z`; **nie überschrieben** |
| GitHub-Release `OPAA vX.Y.Z` | Änderungsübersicht aus den gemergten PRs, gruppiert nach Label (`.github/release.yml`), dazu die Image-Namen und der `helm install`-Befehl |

Wie bei `main` tragen die Images je Architektur eine SBOM- und eine Provenance-Attestierung
([sbom.md](sbom.md)). Der Chart trägt eine signierte Provenance-Attestierung, abgelegt bei GitHub
und neben dem Chart in GHCR. Prüfen lässt sie sich mit:

```bash
gh attestation verify oci://ghcr.io/criew/charts/opaa:X.Y.Z --owner criew
```

Installiert wird der Chart ohne Checkout des Repositories; was in die Wertedatei gehört, beschreibt
die [Chart-README](../deploy/helm/opaa/README.md):

```bash
helm install opaa oci://ghcr.io/criew/charts/opaa --version X.Y.Z \
  -n opaa --create-namespace -f meine-werte.yaml
```

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

## Schutzregeln im Workflow

Bevor gebaut wird, bricht der Lauf im Job `prepare` ab, wenn:

- das Tag nicht die Form `vX.Y.Z` bzw. `vX.Y.Z-<vorab>` hat oder keine gültige
  [SemVer](https://semver.org/lang/de/)-Version ist, etwa `v1.0.0-rc.01` mit führender Null,
- der getaggte Commit nicht auf `main` liegt,
- `X.Y.Z` in GHCR bereits existiert, als Image oder als Chart. Das gilt auch für einen erneut
  gepushten Tag und einen manuellen Lauf auf dem Tag. Eine fehlerhafte Version wird nicht ersetzt,
  sondern durch die nächste Patch-Version abgelöst,
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

Das Tag-Format steht an einer Stelle, `deploy/helm/ci/release-version.sh`; die Prüfung der Images
und das Packen des Charts rufen es beide auf. Ebenso gibt es eine einzige Existenzprüfung für
Images und Chart, `deploy/helm/ci/oci-published.sh`. Sie fragt die Registry-API direkt und wertet
den HTTP-Status aus. `docker buildx imagetools inspect` taugt dafür nicht, weil es eine
Verweigerung ebenfalls als „not found“ meldet.

Der Lauf ist in Jobs gestaffelt: erst die Prüfungen (`prepare`), dann die Images (`build` je Image
und Architektur, `publish` je Image), dann der Chart, dann seine Attestierung, zuletzt das
GitHub-Release. Jeder Job startet nur, wenn die vorigen gelungen sind, und ein Fehlschlag färbt
den Lauf rot. Der Chart zeigt also nie auf ein Image, das nicht veröffentlicht wurde, und das
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
*Re-run all jobs* scheitert dagegen absichtlich an der Prüfung oben.

Bevor der Job `chart` einen Digest an die Attestierung weitergibt, vergleicht er den Chart aus der
Registry inhaltlich mit dem Chart, den er selbst aus dem getaggten Commit gepackt hat
(`deploy/helm/ci/chart-matches.sh`: beide Pakete entpacken, `diff -r`). Ein Vergleich der
Archive selbst taugt nicht, weil `helm package` die Zeitstempel des Checkouts übernimmt. Weicht der
Inhalt ab, bricht der Job ab und attestiert nichts. Das betrifft vor allem eine Wiederholung:
Wurde das Tag zwischenzeitlich auf einen anderen Commit gesetzt und hat dessen Lauf die Version
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

Der wöchentliche Neubau (#1450) läuft nur auf `main`. Er berührt Release-Tags nie.

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

Lässt sich der Stand von `main` nicht abfragen, scheitert `publish`, ohne einen Tag zu setzen; der
Job lässt sich wiederholen. Release-Tags sind nicht betroffen: Ein Release-Lauf setzt `main` nie,
die Prüfung fragt dann gar nicht nach.

**Restfenster.** Zwischen der Abfrage und dem Setzen der Tags liegen Sekunden. Ein Commit, der in
diesem Fenster auf `main` landet, hat dann noch kein Image; sein eigener Lauf setzt `main` erst
nach dem Bau, also Minuten später und damit danach. `main` könnte nur zurückgehen, wenn der Lauf
des jüngeren Commits vollständig in diese Sekunden fiele. Eine zweite Prüfung nach dem Setzen
gibt es deshalb nicht: Sie fände fast immer nur einen jüngeren Lauf, der noch baut, und meldete
damit einen Zustand, der sich gleich selbst behebt.

**Folge für gescheiterte Läufe.** Scheitert der Lauf des jüngsten Commits, bleibt `main` auf dem
zuletzt gesetzten Stand, auch wenn danach noch ein Lauf eines älteren Commits fertig wird. Die
Wiederholung des gescheiterten Laufs setzt `main` – solange kein neuerer Commit auf `main` liegt.

## Wer ein Release anlegt

Releases legen Maintainer an, nie ein Agent ohne ausdrückliche Freigabe. Technisch kann heute
jeder mit Schreibrecht ein Tag `v*` pushen; die Prüfungen im Workflow schützen vor Versehen, nicht
vor Absicht. Ablauf:

1. Prüfen, dass `main` grün ist, einschließlich des letzten nächtlichen E2E-Laufs.
2. Version nach den Regeln unten bestimmen.
3. Tag setzen und pushen:

   ```bash
   git fetch origin
   git tag -a v0.1.0 origin/main -m "OPAA v0.1.0"
   git push origin v0.1.0
   ```

4. Den Lauf von „Publish Images“ abwarten. Danach stehen Images und Chart in GHCR und das
   GitHub-Release unter *Releases*.
   Das erste Release trägt nur einen festen Text: Ohne Vorgänger umfassten generierte Notizen die
   gesamte Historie.
5. Die generierten Notizen bei Bedarf ergänzen, vor allem um **Vorbereitungsschritte** für
   Bestandsinstallationen, wenn das Release welche verlangt.

Die erste Version ist `v0.1.0`.

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

Schemaänderungen laufen immer vorwärts. Zurück auf eine ältere Version geht es nur über die
Datenbanksicherung, unabhängig davon, ob die Version als Bruch gezählt wird.

Releases entstehen nur von Commits auf `main`. Eine ältere Linie wird nicht nachgepflegt. Daraus
folgen zwei Arten von Patch-Release:

- **Ohne Codeänderung:** Ein neues Tag `vX.Y.(Z+1)` auf **demselben Commit** wie das letzte Release.
  Der neue Bau zieht die aktuellen Pakete der Basis-Images.
- **Mit Codeänderung:** Nur, solange `main` seit dem letzten Release keine neue Funktion trägt.
  Sonst erscheint die Korrektur mit der nächsten Version von `main`, also als `0.(Y+1).0`.

## Sicherheitsupdates für Releases

Weil Release-Images nie neu gebaut werden, tragen sie den Sicherheitsstand ihres Bautags. Der
nächtliche CVE-Scan prüft deshalb neben `main` auch die Images des jüngsten Releases (ohne
Vorab-Releases, siehe [cve-scanning.md](cve-scanning.md)). Daraus folgt:

| Befund im jüngsten Release | Reaktion |
|---|---|
| `critical` mit verfügbarem Fix | Patch-Release, Frist wie in cve-scanning.md. Liegt der Fix im Basis-Image, genügt ein neues Tag auf dem Commit des Releases; liegt er in einer Abhängigkeit, kommt er mit der nächsten Version von `main` |
| `high` mit verfügbarem Fix | mit dem nächsten Release, spätestens als Patch-Release nach der wöchentlichen Triage |
| ohne Fix, `medium` und `low` | kein eigenes Release |

Solange das jüngste Release einen `critical`-Befund trägt, bleibt das Alarm-Issue des Scans offen,
auch wenn `main` schon behoben ist. Es schließt erst mit dem Patch-Release.

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
