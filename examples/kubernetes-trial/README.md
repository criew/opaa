# Erprobungsaufbau für Kubernetes

> **Nur zur Erprobung und Vorführung, nicht für den Betrieb.** Alle Dienste laufen mit einer
> Instanz, ohne TLS, ohne Sicherung und mit Testkonten, deren Passwörter in diesem Verzeichnis
> stehen. Eine echte Installation beschreibt das Handbuch-Kapitel
> [Kubernetes](../../docs/handbuch/kubernetes.md).

Ein Befehl bringt OPAA mit allem, was es zum Ausprobieren braucht, in einen leeren lokalen Cluster:

| Dienst | Image | Namespace | Adresse im Browser |
|---|---|---|---|
| OPAA (Helm-Chart aus [`deploy/helm/opaa`](../../deploy/helm/opaa)) mit Erprobungsdatenbank | `ghcr.io/criew/opaa-backend`, `-frontend`, `pgvector/pgvector` | `opaa-trial` | http://opaa.localhost:8088 |
| Keycloak mit Realm `opaa` und Testkonten | `quay.io/keycloak/keycloak` | `opaa-trial-services` | http://keycloak.localhost:8088 |
| Ollama mit Chat- und Einbettungsmodell | `ollama/ollama` | `opaa-trial-services` | nur im Cluster |
| RustFS als Objektspeicher der Originale, Bucket `opaa-uploads` | `rustfs/rustfs` | `opaa-trial-services` | nur im Cluster |
| Mailpit als Mailserver | `axllent/mailpit` | `opaa-trial-services` | http://mailpit.localhost:8088 |

Der Chart bindet diese Dienste nur an und liefert sie nicht mit
([ADR-0042](../../docs/decisions/0042-kubernetes-lieferung-mit-helm.md), Entscheidung 3). Das
Verzeichnis zeigt, wie die Anbindung aussieht, und ändert am Chart nichts.

## Voraussetzungen

- Ein lokaler Cluster mit Traefik als Ingress-Controller, am einfachsten
  [k3d](https://k3d.io) (k3s in Docker), auf `linux/amd64` oder `linux/arm64`
- `kubectl`, `helm` (Helm 4 oder Helm 3 ab 3.8) und `openssl`
- Rund 6 GiB freier Arbeitsspeicher und 10 GiB Plattenplatz für Images, Modelle und Volumes
- Ein Browser, der `*.localhost` selbst auf den eigenen Rechner auflöst (Chrome, Edge, Firefox).
  Sonst gehören `opaa.localhost`, `keycloak.localhost` und `mailpit.localhost` mit `127.0.0.1` in
  `/etc/hosts`

## Starten

```bash
k3d cluster create opaa-trial -p "127.0.0.1:8088:80@loadbalancer"
examples/kubernetes-trial/trial.sh up
```

`127.0.0.1` bindet den Port nur an den eigenen Rechner, wie die Ports des Compose-Stapels. Ohne
diese Angabe wären OPAA mit den bekannten Testkonten, die Keycloak-Verwaltung und Mailpit mit seinen
Einladungs- und Rücksetzlinks aus dem ganzen Netz erreichbar.

`k3d cluster create` stellt den kubectl-Kontext auf den neuen Cluster um. `trial.sh` arbeitet immer
im aktuellen Kontext und nennt ihn zuerst; mit `KUBE_CONTEXT=<Name>` wählt es einen anderen. Zwei
Sperren schützen vor einem falschen Cluster:

- Nur Kontexte, die nach einem lokalen Cluster aussehen (`k3d-*`, `kind-*`, `docker-desktop`,
  `rancher-desktop`, `orbstack`, `minikube`), werden ohne Weiteres angenommen. Jeden anderen nimmt
  das Skript nur, wenn `TRIAL_ALLOW_CONTEXT` genau seinen Namen nennt.
- `up` und `down` ändern einen Namespace `opaa-trial` oder `opaa-trial-services` nur, wenn er das
  Label `app.kubernetes.io/part-of=opaa-trial` trägt, also von diesem Aufbau stammt.

Der erste Start lädt die Images und die Modelle und dauert je nach Leitung wenige Minuten; jeder
weitere Aufruf von `up` ist schnell und ändert nichts an Daten und Geheimnissen. Am Ende stehen die
Adressen und die Befehle für die beiden Verwaltungspasswörter in der Ausgabe.

## Ausprobieren

1. http://opaa.localhost:8088 öffnen, **Anmelden bei Verzeichnisdienst** wählen und bei Keycloak
   mit `testuser` / `testpass` anmelden. Ein zweites Konto ist `maria.weber` /
   `RheinfurtDemo!2026`, Mitglied der Gruppen „Bürgerbüro Rheinfurt“ und „Meldewesen“.
2. Im Katalog eine Wissensbibliothek für Uploads anlegen und ein Dokument hochladen. Die
   Indexierung ist nach wenigen Sekunden abgeschlossen.
3. Im Katalog über das Menü „⋯“ der Bibliothek **In Space verwenden** wählen und den Space
   „Privater Bereich“ nehmen, dann im Chat eine Frage zum Dokument stellen. Unter der Antwort zeigt
   **Belege anzeigen** die Fundstellen.
4. http://mailpit.localhost:8088 zeigt die Testnachricht an das erste Systemverwalter-Konto und
   jede weitere Mail, die OPAA verschickt.
5. Die Systemverwaltung erreicht man über http://opaa.localhost:8088/login/system mit
   `it-postfach@opaa-trial.example`. Das Passwort liest der Befehl, den `up` am Ende nennt, aus dem
   Secret.

## Abbauen

```bash
examples/kubernetes-trial/trial.sh down
k3d cluster delete opaa-trial
```

`down` löscht die beiden Namespaces und damit alles, was `up` angelegt hat, einschließlich
Datenbank, Modellen, Originalen und Geheimnissen. Der Cluster selbst bleibt; ihn entfernt
`k3d cluster delete`.

## Einstellungen

| Umgebungsvariable | Vorgabe | Wirkung |
|---|---|---|
| `TRIAL_PORT` | `8088` | Port, unter dem der Ingress-Controller auf diesem Rechner erreichbar ist; muss zum Port aus `k3d cluster create -p` passen. Bei `80` entfällt der Port in allen Adressen. Nach dem ersten `up` nicht mehr ändern (siehe unten) |
| `TRIAL_CHAT_MODEL` | `qwen2.5:3b` | Chat-Modell, das Ollama zieht und OPAA beim ersten Start als Chat-Modell übernimmt |
| `OPAA_IMAGE_TAG` | `main` | Tag der OPAA-Images; nach dem ersten Release mit Chart eine Versionsnummer. Bei `main` zieht jeder neu gestartete Pod den aktuellen Stand (`pullPolicy: Always`); `kubectl -n opaa-trial rollout restart deploy` holt ihn |
| `KUBE_CONTEXT` | aktueller Kontext | kubectl-Kontext des Zielclusters |
| `TRIAL_ALLOW_CONTEXT` | leer | Name eines Kontexts, der nicht nach einem lokalen Cluster aussieht und trotzdem gemeint ist |

Adresse und Chat-Modell sind Startwerte von OPAA: Der erste Start übernimmt den Identitätsanbieter
samt Issuer und das Chat-Modell in die Datenbank. Ein späteres `up` mit anderem `TRIAL_PORT` oder
`TRIAL_CHAT_MODEL` ändert daran nichts. Für einen anderen Port oder ein anderes erstes Modell wird der
Aufbau mit `down` und `up` neu angelegt; ein weiteres Chat-Modell lässt sich auch in der
Modellverwaltung eintragen. Das Einbettungsmodell ist fest `nomic-embed-text` mit 768 Dimensionen.

**Kleine Modelle setzen keine Zitatmarken.** Mit `qwen2.5:3b` und `qwen2.5:1.5b` stand in keiner der
gemessenen Antworten eine Zitatmarke; die Fundstellen erscheinen unter **Belege anzeigen** als
„geprüft, nicht zitiert“. Ein größeres Modell braucht mehr Arbeitsspeicher, als die Speichergrenze von
Ollama in `manifests/ollama.yaml` zulässt; wer eines ausprobiert, hebt die Grenze an.

## Wie die Teile zusammenhängen

| Datei | Inhalt |
|---|---|
| `trial.sh` | erzeugt Geheimnisse und Einstellungen, wendet die Manifeste an, installiert den Chart und wartet, bis alles bereit ist |
| `opaa-values.yaml` | Werte des Charts; was von Port und Modellen abhängt, setzt `trial.sh` |
| `manifests/` | Namespaces, Keycloak, Ollama, RustFS und Mailpit; mit `kubectl apply` angewendet |
| `jobs/` | Bucket anlegen, Modelle ziehen, Mailserver eintragen. Ein Job lässt sich nicht ändern, nur ersetzen: `trial.sh` löscht die ersten beiden bei jedem `up` und legt sie neu an, den Mail-Job nur, solange er nicht erfolgreich war |
| `keycloak/realm-opaa.json` | Realm `opaa` mit dem öffentlichen Client `opaa` und den Testkonten |

**Geheimnisse.** Beim ersten `up` erzeugt `trial.sh` mit `openssl` die Pflichtgeheimnisse des Charts,
das Passwort des ersten Systemverwalters, die Schlüssel des Objektspeichers und das Passwort der
Keycloak-Verwaltung und legt sie als Secrets an (`opaa-secrets` in `opaa-trial`, `rustfs-credentials`
und `keycloak-admin` in `opaa-trial-services`). Der Chart liest sie über `secrets.existingSecret`.
Ein weiteres `up` lässt vorhandene Secrets unverändert, sonst wären die mit den alten Schlüsseln
verschlüsselten Daten unlesbar. Fest im Repository stehen nur die Passwörter der beiden Testkonten.

**Ein Issuer für Browser und Backend.** Keycloak prägt in jedes Token die Adresse, unter der der
Browser es erreicht (`KC_HOSTNAME`, http://keycloak.localhost:8088). Im Cluster löst
`keycloak.localhost` nicht auf, und Port 8088 gibt es dort nicht. Das Backend braucht diese Adresse
aber nicht: Es vergleicht den Issuer im Token nur als Zeichenkette und holt die Signaturschlüssel
über `bootstrap.oidc.jwkSetUri` vom Keycloak-Service im Cluster. Das ist derselbe Weg wie im
Compose-Stapel und kommt ohne Eingriff in das DNS des Clusters aus. Wer stattdessen will, dass auch das
Backend den Issuer unter derselben Adresse erreicht, braucht einen Port, der innerhalb und außerhalb
gleich ist (`TRIAL_PORT=80`), und eine Umschreibung von `keycloak.localhost` auf den Traefik-Service
im CoreDNS des Clusters.

**Content-Security-Policy.** Der Browser ruft das Discovery-Dokument und den `token_endpoint` von
Keycloak per `fetch` auf, also auf einem anderen Origin als OPAA. `trial.sh` setzt deshalb
`frontend.cspConnectSrcExtra` auf den Origin von Keycloak; die Ausgabe von `helm install` bestätigt
das, statt zu warnen.

**Mailserver.** Der Mailserver ist eine Verwaltungseinstellung in der Datenbank, kein Wert des
Charts. Der Job `opaa-mail-setup` meldet sich deshalb nach dem Start als erster Systemverwalter an,
trägt Mailpit ein und schickt die Testnachricht. Er läuft nur, bis er einmal erfolgreich war.

**Netz.** Alle Dienste sind über ihre Services erreichbar. Die NetworkPolicy des Charts lässt am
Backend nur das Frontend zu; der Mail-Job geht deshalb über den Frontend-Service. Der Ausgang ist
nicht begrenzt, `trustedProxyCidrs` bleibt leer.

## Abgrenzung zum Betrieb

| Thema | Erprobungsaufbau | Betrieb |
|---|---|---|
| Datenbank | Erprobungsdatenbank des Charts ohne Sicherung | Datenbank des Rechenzentrums oder ein Operator |
| Identitätsanbieter | Keycloak im Entwicklungsmodus, Realm bei jedem Start neu importiert, Testkonten mit bekannten Passwörtern | eigener Anbieter mit eigener Datenhaltung |
| Modelle | Ollama auf der CPU mit kleinen Modellen | Modellserver mit GPU oder ein Anbieter, Modelle nach Bedarf |
| Objektspeicher | RustFS mit einer Instanz und dem Root-Schlüssel | Speicher mit Sicherung und einem auf den Bucket begrenzten Schlüssel |
| Geheimnisse | von `trial.sh` erzeugt, nur im Cluster | aus einem Geheimnisspeicher, mit Kopie außerhalb des Clusters |
| Eingang | HTTP auf `*.localhost` | HTTPS mit Zertifikat, Proxy-Kette und NetworkPolicies wie im Handbuch |
| Mail | Mailpit, nichts verlässt den Cluster | Mailserver des Hauses |

## Gemessen

Durchläufe auf einem MacBook mit Apple Silicon (18 Kerne), Docker Desktop mit 16 GB und k3d 5.9
(k3s 1.36, ein Knoten), am 09.10.2026, mit dem vorgegebenen Chat-Modell:

| Schritt | Dauer |
|---|---|
| `trial.sh up` auf einem frischen Cluster, mit Download aller Images und Modelle | 229 s |
| `trial.sh up` erneut auf dem laufenden Aufbau | 5 s |
| Upload eines kleinen Textdokuments bis zum Status „indexiert“ | rund 1 s |
| Chat-Antwort mit Fundstelle, Ollama auf der CPU | 10 bis 17 s |
| `trial.sh down` | 40 s |

| Pod | Arbeitsspeicher nach einer Chat-Antwort |
|---|---|
| Ollama mit beiden Modellen geladen | bis 2,9 GiB |
| OPAA-Backend | 0,9 GiB |
| Keycloak | 0,6 GiB |
| RustFS, Erprobungsdatenbank, Frontend, Mailpit | zusammen 0,35 GiB |
| k3d-Knoten insgesamt | 5,4 GiB |

Images und Volumes belegen nach dem ersten Start rund 6 GiB auf dem Knoten, davon 2,8 GiB das
Ollama-Image und 2,2 GiB Modelle, Datenbank und Objektspeicher.

## Pflege

Die Images der Hilfsdienste sind in `manifests/` und `jobs/` gepinnt. Renovate liest sie mit dem
`kubernetes`-Manager ([docs/renovate.md](../../docs/renovate.md)) und schlägt Updates als PR vor,
zusammen mit demselben Image im Compose-Stapel. Diese PRs mergen nie automatisch, weil keine CI den
Aufbau prüft: Vor dem Merge einmal `trial.sh up` auf einem frischen Cluster laufen lassen und die
Schritte unter „Ausprobieren“ durchgehen.

Ändert sich am Chart ein Wert, den `opaa-values.yaml` oder `trial.sh` setzt, prüft
`helm template` die Kombination ohne Cluster:

```bash
helm template opaa deploy/helm/opaa -f examples/kubernetes-trial/opaa-values.yaml \
  --set-string publicBaseUrl=http://opaa.localhost:8088 --set-string ingress.host=opaa.localhost \
  --set-string bootstrap.oidc.issuerUri=http://keycloak.localhost:8088/realms/opaa \
  --set-string bootstrap.initialAdmin.email=it-postfach@opaa-trial.example \
  --set-string embedding.model=nomic-embed-text --set-string bootstrap.chatModel.model=qwen2.5:3b \
  --set-string uploads.s3.bucket=opaa-uploads > /dev/null
```

## Fehlersuche

| Bild | Ursache und Abhilfe |
|---|---|
| `up` wartet lange auf `job/ollama-pull-models` | Die Modelle werden geladen; Fortschritt mit `kubectl -n opaa-trial-services logs -f job/ollama-pull-models` |
| Browser findet `opaa.localhost` nicht | Der Browser löst `*.localhost` nicht selbst auf; Einträge in `/etc/hosts` ergänzen |
| Seite nicht erreichbar, obwohl `up` fertig ist | `TRIAL_PORT` passt nicht zum Port aus `k3d cluster create -p` |
| `up` oder `down` bricht mit „ist kein lokaler Cluster“ ab | Der Kontext heißt nicht wie ein lokaler Cluster; ist er gemeint, `TRIAL_ALLOW_CONTEXT=<Name>` setzen |
| `up` oder `down` bricht mit „gehört nicht zum Erprobungsaufbau“ ab | Ein gleichnamiger Namespace stammt nicht von `trial.sh`; das Skript ändert ihn nicht |
| `up` bricht mit „job/… ist fehlgeschlagen“ ab | Der genannte Job hat aufgegeben; die Ursache zeigt der dort genannte `kubectl logs`-Befehl |
| Anmeldung bei Keycloak endet mit „Invalid parameter: redirect_uri“, oder OPAA lehnt das Token ab | `TRIAL_PORT` wurde nach dem ersten `up` geändert; mit `down` und `up` neu anlegen |
| Ollama startet mit `OOMKilled` neu, der Chat antwortet „KI-Dienst vorübergehend nicht verfügbar“ | Ein größeres oder ein zweites Chat-Modell passt nicht in die Speichergrenze von Ollama; Grenze in `manifests/ollama.yaml` anheben oder beim kleinen Modell bleiben |
| Chat antwortet „Fehler im KI-Dienst“ | Das Chat-Modell fehlt in Ollama; `kubectl -n opaa-trial-services exec deploy/ollama -- ollama list` |
| `up` bricht bei `job/opaa-mail-setup` ab | Das Passwort des ersten Systemverwalters wurde vor dem ersten erfolgreichen Lauf geändert; Mailpit in der Oberfläche eintragen (Host `mailpit.opaa-trial-services.svc.cluster.local`, Port 1025, ohne Verschlüsselung) |
