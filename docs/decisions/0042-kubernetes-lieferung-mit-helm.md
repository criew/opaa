# ADR-0042: Kubernetes-Lieferung mit Helm — Single-Instance-Chart neben den Images

## Status

**Akzeptiert (Maintainer-Entscheidung vom 08.10.2026)**, Issue
[#2347](https://github.com/criew/opaa/issues/2347), Epic
[#2346](https://github.com/criew/opaa/issues/2346). Beantwortet die Frage nach der Helm-Lieferung aus
dem Konzept-Epic [#1439](https://github.com/criew/opaa/issues/1439) und die gleichlautende offene
Frage in [deployment-infrastructure.md](../features/deployment-infrastructure.md).

## Kontext

Der einzige beschriebene Betriebsweg ist heute der Compose-Stapel
([deployment.md](../handbuch/deployment.md)). Häuser mit einer vorhandenen Kubernetes-Plattform
müssen sich Manifeste selbst erarbeiten. Sie bilden dafür die Umgebungsvariablen aus dem Handbuch
nach und finden erst im Betrieb heraus, was das Backend voraussetzt:

- genau eine Instanz nach [ADR-0021](0021-single-instance-betrieb.md)
- eine Schemamigration beim Start
- beschreibbare Pfade
- eine Proxy-Kette, die das Rate-Limit nicht verfälschen darf

Jede dieser Eigenbauten veraltet mit dem nächsten Release, ohne dass das Projekt davon erfährt.

[deployment-infrastructure.md](../features/deployment-infrastructure.md) nennt Kubernetes als Betriebsform der Phase 1, ließ aber offen, ob das
Projekt Bereitstellungsbeschreibungen mitliefert und pflegt. Die Images liegen bereits in GHCR
(#196). Sie tragen aber nur die Tags `main` und `sha-…`. Ein mitgelieferter Chart hätte also heute
keine stabile Version, auf die er zeigen könnte.

Mehrere Instanzen, Hochverfügbarkeit und getrennte Speicher-Backends sind nicht Gegenstand dieser
Entscheidung. Sie bleiben bei #1439 und #1292.

## Entscheidung

### 1. Das Projekt liefert einen Helm-Chart als Bestandteil jedes Releases

Der Chart ist ein gepflegter Lieferbestandteil wie die Images, kein Beispiel und kein
Community-Beitrag. Er liegt im Repository unter `deploy/helm/opaa`. Er wird in der CI geprüft und
mit jedem Release veröffentlicht. Eine Änderung an Konfiguration oder Laufzeitverhalten des
Backends, die den Betrieb berührt, zieht den Chart im selben PR nach. Das gilt genauso für das
Compose-Setup und das Handbuch.

Helm wird gewählt, weil es in den Zielumgebungen verbreitet ist (Rechenzentren, Plattformteams,
GitOps mit Argo CD oder Flux), weil Werte und Schema eine prüfbare Konfigurationsoberfläche ergeben
und weil der Chart als OCI-Artefakt neben den Images in derselben Registry liegen kann. Unterstützt
werden Helm 3.8 und neuer (OCI-Unterstützung) sowie Helm 4.

### 2. Umfang: genau eine Backend-Instanz, Aktualisierung mit kurzer Unterbrechung

Der Chart setzt [ADR-0021](0021-single-instance-betrieb.md) technisch durch:

- **Eine Instanz:** Das Backend-Deployment hat genau ein Replikat. Ein Wert größer als 1 bricht das
  Rendern mit einer Meldung ab, die auf ADR-0021 verweist.
- **Kein Nebeneinander:** Die Strategie ist `Recreate`, damit nie zwei Fassungen gleichzeitig laufen.
- **Migration beim Start:** Liquibase migriert weiterhin beim Start des Backends, nicht in einem
  Helm-Hook oder einem eigenen Job. Eine Startup-Probe gibt der Migration Zeit. Liveness und
  Readiness setzen erst danach ein.
- **Rückweg:** Ein `helm rollback` über eine Schemaänderung hinweg ist kein Rückweg. Zurück geht es
  nur über die Datenbanksicherung, wie im Compose-Betrieb.

Die Werte sind so geschnitten, dass sie mit #1292 auf mehrere Replikate erweitert werden können,
ohne dass Betreiber neu installieren müssen.

### 3. Externe Dienste werden angebunden, nicht ausgeliefert

PostgreSQL mit pgvector, der Identitätsanbieter (OIDC, etwa Keycloak), SMTP, LLM-, Embedding- und
Rerank-Endpunkte und der Objektspeicher liefert der Betreiber. Der Chart bindet sie über Werte und
Secret-Referenzen an.

**Einzige Ausnahme ist eine Erprobungsdatenbank:**

- ein schlichtes StatefulSet mit dem Image `pgvector/pgvector` und einem PVC
- standardmäßig aus und in Werten, `NOTES.txt` und Handbuch als nicht produktiv gekennzeichnet
- ohne Sicherung, Replikation und Aktualisierungspfad über Hauptversionen

Ein fremder Sub-Chart wird dafür nicht eingebunden. Der Bitnami-Katalog ist kein verlässlicher,
freier Bezugsweg mehr. Ein Operator wie CloudNativePG wäre für eine Erprobung eine zu große
Voraussetzung. Für den Betrieb empfiehlt das Handbuch die Datenbank des Rechenzentrums oder einen
solchen Operator.

### 4. Eingang über Ingress oder Gateway API, TLS beim Cluster

Der Chart bietet ein `Ingress` und eine `HTTPRoute` (Gateway API) an. Beide sind optional, der
Betreiber wählt.

- **Gateway API** ist der Weg nach vorn, seit ingress-nginx eingestellt ist.
- **Ingress** bleibt für die verbreiteten Controller (Traefik, HAProxy, OpenShift Router) nötig.

Die Größenbegrenzung für Uploads und die Zeitgrenzen für lange Anfragen sind in den Werten
beschrieben, weil jeder Controller sie anders benennt.

TLS endet am Eingang des Clusters. Zertifikate verwaltet der Betreiber, etwa mit cert-manager. Der
Chart bringt keine Zertifikatslogik mit.

### 5. Geheimnisse nur aus Secrets, nie vom Chart erzeugt

Nicht geheime Einstellungen werden aus strukturierten Werten auf die `OPAA_*`-Variablen abgebildet.
`extraEnv` und `extraVolumes` bleiben als Ausweg für alles, was die Werte noch nicht abbilden.

Geheimnisse kommen ausschließlich aus Secrets. Dazu gehören JWT-Secret, die beiden
Verschlüsselungsschlüssel, das Datenbankpasswort, LLM- und Objektspeicher-Schlüssel und das Kennwort
des Erstadmins. Zwei Wege sind vorgesehen:

- **Vorhandenes Secret (`existingSecret`):** Das ist der empfohlene Weg, weil er External Secrets,
  Sealed Secrets und Vault offenhält.
- **Secret aus Werten:** Der Chart legt es aus ausdrücklich übergebenen Werten an.

Der Chart **erzeugt keine Zufallsschlüssel**. Ein bei `helm install` erzeugter Schlüssel geht bei
`helm template`, in GitOps-Werkzeugen oder bei einer Neuinstallation verloren. Mit dem
Verschlüsselungsschlüssel werden gespeicherte Zugangsdaten unlesbar. Fehlende Pflichtgeheimnisse
lehnt `values.schema.json` beim Rendern ab, nicht erst das Backend beim Start.

### 6. Pod Security Standard `restricted` ohne Ausnahmen

Alle Pods des Charts laufen unter dem Pod Security Standard `restricted`:

- ohne Root-Rechte
- `allowPrivilegeEscalation: false`
- `capabilities.drop: [ALL]`
- `seccompProfile: RuntimeDefault`
- `readOnlyRootFilesystem: true`, mit `emptyDir` für die wenigen beschreibbaren Pfade
- eigener ServiceAccount ohne eingehängtes Token

Was die Images dafür noch brauchen, regeln #2348 (Frontend) und #2349 (Backend). Feste UIDs setzt
der Chart nur über Werte, damit Plattformen mit zugewiesenen UIDs (OpenShift `restricted-v2`) ohne
Anpassung laufen. NetworkPolicies liefert der Chart mit, sie sind aber abschaltbar (#2353).

### 7. Versionierung: eine Nummer für Anwendung und Chart

Ein Release-Tag `vX.Y.Z` erzeugt die Images mit dem Tag `X.Y.Z` und den Chart mit Chart-Version
`X.Y.Z` und `appVersion: X.Y.Z` in einem Lauf (#2350, #2356). Die Vorgabewerte zeigen auf genau
diese Images. Wer `--version X.Y.Z` installiert, bekommt also einen geprüften Stand aus Anwendung
und Chart.

Ein reiner Chart-Fix erscheint als Patch-Release der Anwendung. Das kostet gelegentlich ein
Release ohne Anwendungsänderung. Dafür entfällt eine Zuordnungstabelle zwischen Chart- und
App-Versionen, die Betreiber sonst pflegen und lesen müssten. Veröffentlicht wird unter
`oci://ghcr.io/criew/charts/opaa`.

### 8. Unterstützte Kubernetes-Versionen

Unterstützt werden die drei jüngsten Minor-Versionen, die das Kubernetes-Projekt zum Zeitpunkt
eines Releases pflegt. Die CI prüft die Manifeste gegen genau diese Versionen (#2355). Ältere
Versionen können funktionieren, werden aber weder geprüft noch durch Rücksichten im Template
gestützt.

## Konsequenzen

**Einfacher:**

- Ein Haus mit Kubernetes installiert OPAA mit einem Befehl gegen einen geprüften Stand und muss
  die Laufzeitannahmen des Backends nicht aus dem Code erschließen.
- ADR-0021 ist im Betrieb nicht mehr nur Dokumentation. Der Chart verhindert die Fehlkonfiguration
  mit mehreren Replikaten.
- Versionierte Images und Releases nützen auch dem Compose-Betrieb, der heute nur `main` beziehen
  kann.
- GitOps-Werkzeuge können den Chart unverändert verwenden, weil er keine Zufallswerte erzeugt und
  keine Hooks braucht.

**Schwieriger:**

- Der Chart ist eine weitere Lieferform, die mit jeder betriebsrelevanten Änderung mitgepflegt
  werden muss. Ohne CI-Prüfung (#2355) veraltet er unbemerkt.
- Eine Aktualisierung bleibt eine kurze Unterbrechung. Wer unter Kubernetes rollierende
  Aktualisierungen erwartet, wird auf #1292 verwiesen.
- Das Projekt verspricht Unterstützung für Ingress und Gateway API sowie für drei
  Kubernetes-Versionen. Beides muss geprüft werden, nicht nur behauptet.
- Betreiber müssen PostgreSQL mit pgvector selbst bereitstellen. Für eine Erprobung gibt es die
  optionale Datenbank, für den Betrieb nicht.
- Ein reiner Chart-Fix erzeugt ein Anwendungsrelease.

**Nicht entschieden, bewusst offen:**

- Ein Operator, eine Kustomize-Basis oder plattformspezifische Pakete wie OpenShift-Templates oder
  Marketplace-Einträge.
- Ob Images zusätzlich für `linux/arm64` gebaut werden, entscheidet #2350.
