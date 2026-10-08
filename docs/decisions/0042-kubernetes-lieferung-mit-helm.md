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

Jeder dieser Eigenbauten veraltet mit dem nächsten Release, ohne dass das Projekt davon erfährt.

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
werden Helm 4 und, solange es Sicherheitskorrekturen erhält, Helm 3 ab 3.8 (OCI-Unterstützung).
`values.schema.json` folgt deshalb JSON Schema draft-07, das auch ältere Helm-3-Versionen
verstehen. Die CI prüft die älteste zugesagte und die jüngste Version (#2355).

Die Pflege im selben PR wird als Punkt der PR-Checkliste festgehalten, sobald der Chart existiert
(#2351). Ohne diesen Punkt setzt die Regel niemand durch.

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
- **Betriebsprofil:** Der Chart setzt das Spring-Profil fest auf `oidc` und bietet `dev` nicht an.
  `dev` ist nach [ADR-0005](0005-authentication-strategy.md) Entwicklung und Tests vorbehalten.

Die Werte sind so geschnitten, dass sie mit #1292 auf mehrere Replikate erweitert werden können,
ohne dass Betreiber neu installieren müssen.

### 3. Externe Dienste werden angebunden, nicht ausgeliefert

PostgreSQL mit pgvector, LLM-, Embedding- und Rerank-Endpunkte, der Objektspeicher, ein Mailserver
und gegebenenfalls ein Identitätsanbieter liefert der Betreiber. Ein externer Identitätsanbieter ist
keine Voraussetzung: Ohne ihn melden sich Personen über die lokale Benutzerverwaltung an
([ADR-0033](0033-lokale-benutzerverwaltung.md)).

Nicht jede dieser Anbindungen ist eine Einstellung des Charts. OPAA kennt drei Arten von
Konfiguration, und der Chart behandelt sie unterschiedlich:

| Art | Beispiele | Im Chart |
|---|---|---|
| **Bereitstellungswerte**, bei jedem Start gelesen | Datenbank, Originalablage und Objektspeicher, Embedding und Rerank, Proxy-Kette, CSP-Ergänzungen, Zeitgrenzen | Werte, wirken bei jedem `helm upgrade` |
| **Startwerte**, einmal beim ersten Start in die Datenbank übernommen | erster Identitätsanbieter (`OPAA_OIDC_*`), erstes Chat-Modell, Erstadmin (`OPAA_INITIAL_ADMIN_*`) | Werte für die Erstinstallation. Spätere Änderungen erfolgen in der Oberfläche. Werte und `NOTES.txt` sagen das ausdrücklich, damit eine geänderte `values.yaml` nicht für wirksam gehalten wird |
| **Verwaltungseinstellungen**, nur in der Datenbank | Mailserver, Verzeichnis-Konnektoren, Fremdzugänge | keine Werte; Einrichtung in der Oberfläche |

Für eine Startwert-Variable, die die Datenbank danach übergeht, bietet der Chart keinen
dauerhaften Schalter an. Auch einen Rückgriff wie `OPAA_OIDC_BOOTSTRAP=force` gibt es dort nicht:
Ein solcher Wert bliebe in GitOps-Werkzeugen stehen und würde bei jedem Neustart erneut wirken.

Dasselbe gilt für die Notfallwiederherstellung des Erstadmins (`OPAA_LOCAL_ADMIN_RESET=force`). Sie
wird einmalig gesetzt, etwa mit `kubectl set env` oder einem befristeten `extraEnv`, und nach dem
Neustart wieder entfernt. Die Einzelheiten beschreibt das Handbuch (#2357).

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
- **Ingress** ist in bestehenden Clustern weiter verbreitet und bleibt deshalb gleichrangig
  unterstützt.

Die Größenbegrenzung für Uploads und die Zeitgrenzen für lange Anfragen sind in den Werten
beschrieben, weil jeder Controller sie anders benennt.

TLS endet am Eingang des Clusters. Zertifikate verwaltet der Betreiber, etwa mit cert-manager. Der
Chart bringt keine Zertifikatslogik mit.

Zwischen Eingang, Frontend und Backend bleibt die Proxy-Kette dieselbe wie im Compose-Betrieb. Die
Liste der vertrauenswürdigen Proxys (`OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS`) ist ein Wert des Charts.
Sie entscheidet über mehr als das Rate-Limit: Auch die Fehlversuch-Grenzen der lokalen Anmeldung und
die Netzbeschränkung lokaler Systemverwalter (`OPAA_LOCAL_ADMIN_ALLOWED_CIDRS`) lesen die
Client-Adresse aus der Kette, die diese Liste freigibt.

Im Compose-Betrieb steht dort nur die feste Adresse des Frontend-Containers. Pods haben keine feste
Adresse, die Liste muss also das Pod-Netz nennen. Das ist nur vertretbar, wenn außer dem Frontend
kein Pod das Backend erreicht. Daraus folgt:

- **Eingangs-Policy des Backends standardmäßig an.** Eine NetworkPolicy lässt Verbindungen zum
  Backend nur von den Frontend-Pods zu, dazu auf Wunsch von der Betriebsüberwachung. Sie sperrt
  keinen Ausgang und bricht deshalb keine frische Installation.
- **Liste ohne Vorgabe.** Der Chart kennt das Pod-Netz nicht; die Liste bleibt leer, bis der
  Betreiber sie setzt. Leer heißt: Das Backend sieht die Adresse des Frontend-Pods, die Grenzen
  wirken zu streng, aber nicht umgehbar.
- **Pod-Netz nur mit wirksamer Policy.** Ist die Eingangs-Policy abgeschaltet oder setzt die
  Netzwerkschicht des Clusters keine NetworkPolicies durch, darf das Pod-Netz nicht eingetragen
  werden. Werte und Handbuch sagen das an der Stelle, an der die Liste gesetzt wird (#2353).

### 5. Geheimnisse nur aus Secrets, nie vom Chart erzeugt

Nicht geheime Einstellungen werden aus strukturierten Werten auf die `OPAA_*`-Variablen abgebildet.
`extraEnv` und `extraVolumes` bleiben als Ausweg für alles, was die Werte noch nicht abbilden.

Geheimnisse kommen ausschließlich aus Secrets. Zwei Wege sind vorgesehen:

- **Vorhandenes Secret (`existingSecret`):** Das ist der empfohlene Weg, weil er External Secrets,
  Sealed Secrets und Vault offenhält.
- **Secret aus Werten:** Der Chart legt es aus ausdrücklich übergebenen Werten an.

**Pflicht** sind im Chart vier Geheimnisse:

- `OPAA_AUTH_JWT_SECRET`
- das Datenbankpasswort
- `OPAA_CREDENTIALS_ENCRYPTION_KEY`
- `OPAA_SETTINGS_ENCRYPTION_KEY`

Die beiden Verschlüsselungsschlüssel verlangt der Chart bewusst strenger als das Backend. Das Backend
prüft sie erst beim ersten Gebrauch, damit eine bestehende Installation ohne gespeicherte
Zugangsdaten weiter startet. Eine Installation mit dem Chart ist eine neue Installation, und ein
fehlender Schlüssel fiele dort erst auf, wenn die erste Bibliothek ihre Zugangsdaten speichern will.

Pflicht unter einer Bedingung sind Zugangs- und Geheimschlüssel des Objektspeichers: sobald die
Originalablage dort liegt (`OPAA_UPLOAD_STORE=s3`). Das Schema prüft das als Bedingung
(`if`/`then`).

Optional sind die Schlüssel für LLM-Endpunkte und das Kennwort des Erstadmins. Fehlt das Kennwort,
erzeugt das Backend beim ersten Start eines und gibt es einmal im Protokoll aus.

Wo die Prüfung greift, hängt vom Weg ab:

- **Secret aus Werten:** `values.schema.json` lehnt fehlende Pflichtgeheimnisse schon beim Rendern
  ab.
- **`existingSecret`:** Das Schema sieht nur den Namen, nicht den Inhalt. Die Pflichtschlüssel werden
  deshalb ohne `optional` per `secretKeyRef` eingebunden. Fehlt einer, startet der Pod nicht
  (`CreateContainerConfigError`), und `kubectl describe` nennt den fehlenden Schlüssel.

Der Chart **erzeugt keine Zufallsschlüssel**. Ein bei `helm install` erzeugter Schlüssel geht bei
`helm template`, in GitOps-Werkzeugen oder bei einer Neuinstallation verloren. Geht einer der
Verschlüsselungsschlüssel verloren, werden die damit gespeicherten Geheimnisse unlesbar, etwa
Zugangsdaten von Quellen und Verbindungen, Modellschlüssel und das Kennwort des Mailservers.

### 6. Pod Security Standard `restricted` ohne Ausnahmen

Alle Pods des Charts laufen unter dem Pod Security Standard `restricted`:

- ohne Root-Rechte
- `allowPrivilegeEscalation: false`
- `capabilities.drop: [ALL]`
- `seccompProfile: RuntimeDefault`
- `readOnlyRootFilesystem: true`, mit `emptyDir` für die wenigen beschreibbaren Pfade
- eigener ServiceAccount ohne eingehängtes Token

Was die Images dafür noch brauchen, regeln #2348 (Frontend) und #2349 (Backend).

`runAsUser`, `runAsGroup` und `fsGroup` bleiben in den Vorgabewerten leer. So laufen Plattformen
mit zugewiesenen UIDs (OpenShift `restricted-v2`) ohne Anpassung. Wer die Originalablage auf einem
PVC hält, setzt `fsGroup` über einen Wert (#2352).

NetworkPolicies liefert der Chart in zwei Teilen:

- **Eingang zum Backend:** standardmäßig an (siehe Proxy-Kette in Entscheidung 4).
- **Übrige Policies:** standardmäßig aus, vor allem die Begrenzung des Ausgangs. Ein gesperrter
  Ausgang bräche eine frische Installation, solange die Ziele für Identitätsanbieter, Modelle und
  Quellen nicht eingetragen sind. Das Handbuch empfiehlt, sie nach der Einrichtung einzuschalten
  (#2353).

### 7. Versionierung: eine Nummer für Anwendung und Chart

Ein Release-Tag `vX.Y.Z` erzeugt die Images mit dem Tag `X.Y.Z` und den Chart mit Chart-Version
`X.Y.Z` und `appVersion: X.Y.Z` in einem Lauf (#2350, #2356). Die Vorgabewerte zeigen auf genau
diese Images. Wer `--version X.Y.Z` installiert, bekommt also einen geprüften Stand aus Anwendung
und Chart.

Im Repository trägt `Chart.yaml` zwischen zwei Releases den Platzhalter `0.0.0-dev` für `version`
und `appVersion`. Erst die Release-Pipeline setzt beide beim Packen
(`helm package --version X.Y.Z --app-version X.Y.Z`). Eine Chart-Änderung zwischen zwei Releases
braucht deshalb keine eigene Versionsnummer, und die CI prüft nicht, ob die Version steigt
(`ct lint --check-version-increment=false`).

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
  keine Install- oder Upgrade-Hooks braucht. Nur `helm test` ist ein Hook.

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
- Versions-Tags werden nie überschrieben. Der wöchentliche Neubau mit den Sicherheitsupdates des
  Basis-Images (#1450) kommt deshalb nur bei `main` an. Ein Release erhält solche Updates wie ein
  Chart-Fix als Patch-Release, und der CVE-Scan muss neben `main` auch das jüngste Release
  abdecken (#2350).
- Was die Oberfläche verwaltet, ist nicht deklarativ: Ein GitOps-Werkzeug kann Mailserver, weitere
  Identitätsanbieter oder Modelle nicht über den Chart nachführen.

**Nicht entschieden, bewusst offen:**

- Ein Operator, eine Kustomize-Basis oder plattformspezifische Pakete wie OpenShift-Templates oder
  Marketplace-Einträge.
- Ob Images zusätzlich für `linux/arm64` gebaut werden, entscheidet #2350.
