# ADR-0042: Kubernetes-Lieferung mit Helm — Chart neben den Images

## Status

Akzeptiert · zuletzt geändert 2026-10-20 · ergänzt durch [ADR-0053](0053-mehrinstanzbetrieb-aktiv-aktiv.md) · beantwortet die offene Frage nach der Helm-Lieferung aus
Epic #1439 und [deployment-infrastructure.md](../features/deployment-infrastructure.md)

## Kontext

Der einzige beschriebene Betriebsweg war der Compose-Stapel ([docker-compose.md](../handbuch/betrieb/docker-compose.md)).
Häuser mit Kubernetes mussten sich Manifeste selbst erarbeiten und fanden erst im Betrieb heraus, was
das Backend voraussetzt: genau eine Instanz ([ADR-0021](0021-single-instance-betrieb.md)), eine
Schemamigration beim Start, beschreibbare Pfade, eine Proxy-Kette, die das Rate-Limit nicht verfälscht.
Jeder Eigenbau veraltete mit dem nächsten Release. Die Images in GHCR trugen nur `main` und `sha-…`;
ein Chart hätte keine stabile Version gehabt, auf die er zeigen kann. Mehrere Instanzen,
Hochverfügbarkeit und getrennte Speicher-Backends bleiben bei #1439 und #1292.

## Entscheidung

### 1. Das Projekt liefert einen Helm-Chart als Bestandteil jedes Releases

Der Chart (`deploy/helm/opaa`) ist ein gepflegter Lieferbestandteil wie die Images. Er wird in der CI
geprüft (`.github/workflows/helm-chart.yml`, `deploy/helm/ci/`) und mit jedem Release veröffentlicht.
Eine betriebsrelevante Änderung an Konfiguration oder Laufzeitverhalten zieht Chart, Compose-Setup und
Handbuch im selben PR nach; das steht als Punkt in der PR-Checkliste. Helm, weil es in den
Zielumgebungen verbreitet ist (Plattformteams, GitOps), Werte und Schema eine prüfbare
Konfigurationsoberfläche ergeben und der Chart als OCI-Artefakt neben den Images liegt. Unterstützt
werden Helm 4 und Helm 3 ab 3.8 (OCI-Unterstützung), solange es Sicherheitskorrekturen erhält;
`values.schema.json` folgt deshalb JSON Schema draft-07. Die CI prüft die älteste zugesagte und die
jüngste Version.

### 2. Umfang: eine Backend-Instanz als Vorgabe, Aktualisierung mit kurzer Unterbrechung

In der Vorgabe läuft der Chart mit einem Replikat: Strategie `Recreate`, Liquibase migriert beim
Start des Backends (kein Hook, kein Job), eine Startup-Probe gibt der Migration Zeit. Ein
`helm rollback` über eine Schemaänderung hinweg ist dann kein Rückweg; zurück geht es über die
Datenbanksicherung. Das Spring-Profil ist fest `oidc`; `dev` wird nicht angeboten (ADR-0005).
`backend.replicas` größer 1 rendert nur unter den Bedingungen von ADR-0053 Entscheidung 15
(Migrations-Job, `RollingUpdate`, PDB, gemeinsame Originalablage), sonst bricht das Rendern ab
(`_helpers.tpl`); eine bestehende Installation wechselt dorthin mit `helm upgrade`, ohne
Neuinstallation.

### 3. Externe Dienste werden angebunden, nicht ausgeliefert

PostgreSQL mit pgvector, LLM-, Embedding-, Rerank- und Transkriptionsendpunkte, Objektspeicher,
Mailserver und gegebenenfalls ein Identitätsanbieter liefert der Betreiber; ohne Anbieter läuft die
lokale Benutzerverwaltung (ADR-0033). Drei Arten von Konfiguration:

| Art | Beispiele | Im Chart |
|---|---|---|
| Bereitstellungswerte, bei jedem Start gelesen | Datenbank, Originalablage, Embedding, Rerank, Proxy-Kette, Feature-Flags (ADR-0044), Zeitgrenzen | Werte, wirken bei jedem `helm upgrade` |
| Startwerte, einmal beim ersten Start in die Datenbank übernommen | erster Identitätsanbieter (`OPAA_OIDC_*`), erstes Chat-Modell, Erstadmin (`OPAA_INITIAL_ADMIN_*`) | Werte unter `bootstrap`, nur für die Erstinstallation; Werte und `NOTES.txt` sagen das ausdrücklich |
| Verwaltungseinstellungen, nur in der Datenbank | Mailserver, Verzeichnis-Konnektoren, Fremdzugänge | keine Werte |

Für Startwerte gibt es keinen dauerhaften Schalter und keinen Rückgriff wie
`OPAA_OIDC_BOOTSTRAP=force`: Er bliebe in GitOps-Werkzeugen stehen und wirkte bei jedem Neustart.
Dasselbe gilt für `OPAA_LOCAL_ADMIN_RESET=force`, das einmalig per `kubectl set env` oder befristetem
`extraEnv` gesetzt und danach entfernt wird. **Einzige Ausnahme** ist eine Erprobungsdatenbank
(`evaluationDatabase`): ein StatefulSet mit `pgvector/pgvector` und PVC, standardmäßig aus, als nicht
produktiv gekennzeichnet, ohne Sicherung, Replikation und Aktualisierungspfad. Kein fremder Sub-Chart
(Bitnami ist kein verlässlicher freier Bezugsweg, ein Operator wie CloudNativePG eine zu große
Voraussetzung für eine Erprobung).

### 4. Eingang über Ingress oder Gateway API, TLS beim Cluster

`Ingress` und `HTTPRoute` sind beide optional und gleichrangig; Gateway API ist der Weg nach vorn,
Ingress in bestehenden Clustern verbreitet. Upload-Größe und Zeitgrenzen sind in den Werten
beschrieben, weil jeder Controller sie anders benennt. TLS endet am Eingang des Clusters; Zertifikate
verwaltet der Betreiber.

Die Proxy-Kette bleibt dieselbe wie im Compose-Betrieb. `trustedProxyCidrs`
(`OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS`) entscheidet über Rate-Limit, Fehlversuch-Grenzen der lokalen
Anmeldung und `localAdminAllowedCidrs`. Pods haben keine feste Adresse, die Liste muss das Pod-Netz
nennen — dann gilt jeder Pod als Proxy, auch ein fremder, der den Frontend-nginx direkt anspricht und
`X-Forwarded-For` mitbringt. Vertretbar nur, wenn beide Eingänge geschlossen sind:

- **Eingangs-Policy des Backends standardmäßig an:** nur Frontend-Pods (und auf Wunsch die
  Betriebsüberwachung) erreichen das Backend. Sie sperrt keinen Ausgang.
- **Liste ohne Vorgabe:** leer, bis der Betreiber sie setzt; leer heißt zu streng, aber nicht umgehbar.
- **Eingangs-Policy des Frontends auf Wunsch:** nur der Controller, dafür braucht der Chart dessen
  Namespace und Labels.
- **Pod-Netz nur mit beiden Policies:** Ist die Liste gesetzt, aber eine Policy aus, bricht das Rendern
  ab (`_helpers.tpl`). Ob die Netzwerkschicht Policies durchsetzt, kann der Chart nicht prüfen; Werte
  und Handbuch nennen die Voraussetzung.

### 5. Geheimnisse nur aus Secrets, nie vom Chart erzeugt

Nicht geheime Einstellungen werden aus strukturierten Werten auf `OPAA_*` abgebildet; `extraEnv` und
`extraVolumes` bleiben als Ausweg. Geheimnisse kommen aus einem vorhandenen Secret
(`secrets.existingSecret`, empfohlen: hält External Secrets, Sealed Secrets und Vault offen) oder aus
ausdrücklich übergebenen Werten. **Pflicht** sind `OPAA_AUTH_JWT_SECRET`, das Datenbankpasswort,
`OPAA_CREDENTIALS_ENCRYPTION_KEY` und `OPAA_SETTINGS_ENCRYPTION_KEY` — die Schlüssel strenger als im
Backend, das sie erst beim ersten Gebrauch prüft, weil eine Chart-Installation eine neue Installation
ist. Pflicht unter Bedingung: Zugangs- und Geheimschlüssel des Objektspeichers bei
`OPAA_UPLOAD_STORE=s3` (`if`/`then` im Schema). Optional: LLM-Schlüssel, Kennwort des Erstadmins (sonst
erzeugt und einmal protokolliert). Bei Werten lehnt `values.schema.json` fehlende Pflichtgeheimnisse
beim Rendern ab; bei `existingSecret` sind die Pflichtschlüssel ohne `optional` per `secretKeyRef`
eingebunden, ein fehlender Schlüssel hält den Pod mit `CreateContainerConfigError` an. Der Chart
erzeugt keine Zufallsschlüssel: Sie gingen bei `helm template`, in GitOps und bei Neuinstallation
verloren; geht einer der Verschlüsselungsschlüssel verloren, werden die damit gespeicherten Geheimnisse
unlesbar (Zugangsdaten von Quellen und Verbindungen, Modellschlüssel, Kennwort des Mailservers).

### 6. Pod Security Standard `restricted` ohne Ausnahmen

Alle Pods laufen ohne Root, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`,
`seccompProfile: RuntimeDefault`, `readOnlyRootFilesystem: true` mit `emptyDir` für beschreibbare Pfade,
eigener ServiceAccount ohne eingehängtes Token. `runAsUser`, `runAsGroup` und `fsGroup` bleiben leer,
damit Plattformen mit zugewiesenen UIDs (OpenShift `restricted-v2`) ohne Anpassung laufen; wer die
Originalablage auf einem PVC hält, setzt `fsGroup`. NetworkPolicies: Eingang zum Backend an (der
`helm test`-Pod fragt über das Frontend), Eingang zum Frontend bei gesetzten Controller-Werten, alle
übrigen (vor allem Ausgang) aus, weil ein gesperrter Ausgang eine frische Installation bräche; das
Handbuch empfiehlt sie nach der Einrichtung.

### 7. Versionierung: eine Nummer für Anwendung und Chart

Ein Release-Tag `vX.Y.Z` erzeugt Images `X.Y.Z` und den Chart mit `version` und `appVersion` `X.Y.Z` in
einem Lauf (`publish-images.yml`, `deploy/helm/ci/package-chart.sh`); die Vorgabewerte zeigen auf genau
diese Images. Im Repository steht `0.0.0-dev`; die CI prüft keine Versionssteigerung
(`ct lint --check-version-increment=false`). Ein reiner Chart-Fix ist ein Patch-Release der Anwendung —
dafür entfällt eine Zuordnungstabelle. Veröffentlicht unter `oci://ghcr.io/criew/charts/opaa`. Images
erscheinen für `linux/amd64` und `linux/arm64` (#2401), je Architektur auf einem eigenen Runner gebaut,
per Digest gepusht, erst der Manifest-Index bekommt die Tags; E2E-Suite und Installationstest bleiben
auf amd64, ein Rauchtest prüft arm64 ([releases.md](../releases.md#mehrere-architekturen)).

### 8. Unterstützte Kubernetes-Versionen

Die drei jüngsten Minor-Versionen, die das Kubernetes-Projekt zum Zeitpunkt eines Releases pflegt
(`KUBERNETES_VERSIONS` in `deploy/helm/ci/static-check.sh`, geprüft mit kubeconform). Ältere Versionen
werden weder geprüft noch durch Rücksichten im Template gestützt.

## Konsequenzen

**Einfacher:** Installation mit einem Befehl gegen einen geprüften Stand; die Voraussetzungen von
ADR-0053 werden beim Rendern geprüft; versionierte Images nützen auch Compose; GitOps-Werkzeuge nutzen den Chart unverändert
(keine Zufallswerte, nur `helm test` als Hook).

**Schwieriger:** Der Chart muss mit jeder betriebsrelevanten Änderung mitgepflegt werden. Eine
Aktualisierung mit einer Instanz bleibt eine Unterbrechung. Ingress, Gateway API und drei Kubernetes-Versionen
müssen geprüft werden, nicht nur behauptet. PostgreSQL mit pgvector stellt der Betreiber. Ein
Chart-Fix erzeugt ein Anwendungsrelease. Versions-Tags werden nie überschrieben; Sicherheitsupdates
des Basis-Images erreichen ein Release nur als Patch-Release, und der CVE-Scan deckt neben `main` das
jüngste Release ab. Was die Oberfläche verwaltet, ist nicht deklarativ.

## Verworfene Alternativen

- Fremder PostgreSQL-Sub-Chart — kein verlässlicher freier Bezugsweg mehr.
- Zufallsschlüssel bei `helm install` — gehen bei `helm template`, GitOps und Neuinstallation verloren.
- Migration in Helm-Hook oder Job bei einer Instanz — siehe Entscheidung 2; bei mehreren ADR-0053 Entscheidung 14.
- Getrennte Chart-Versionierung — Zuordnungstabelle, die Betreiber pflegen müssten.

## Offen

- Ein Operator, eine Kustomize-Basis oder plattformspezifische Pakete (OpenShift-Templates,
  Marketplace-Einträge).
