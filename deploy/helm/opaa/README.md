# OPAA Helm-Chart

Betrieb von OPAA unter Kubernetes: genau eine Backend-Instanz ([ADR-0021](../../../docs/decisions/0021-single-instance-betrieb.md))
und das Frontend. Grundlage ist [ADR-0042](../../../docs/decisions/0042-kubernetes-lieferung-mit-helm.md).

PostgreSQL mit pgvector, Modell-Endpunkte und gegebenenfalls ein Identitätsanbieter werden angebunden,
nicht mitgeliefert.

## Voraussetzungen

- Kubernetes in einer der drei jüngsten gepflegten Minor-Versionen
- Helm 4 oder Helm 3 ab 3.8
- eine erreichbare PostgreSQL-Datenbank mit der Erweiterung `vector`
- ein OpenAI-kompatibler Endpunkt für Einbettung und Chat

## Installation aus dem Repository

```bash
helm install opaa deploy/helm/opaa -n opaa --create-namespace -f meine-werte.yaml \
  --set backend.image.tag=main --set frontend.image.tag=main
```

Zwischen zwei Releases trägt `Chart.yaml` die Platzhalterversion `0.0.0-dev`, zu der es kein Image
gibt. Deshalb nennt die Installation aus dem Repository die Image-Tags ausdrücklich.

`meine-werte.yaml` braucht mindestens die Werte aus [`ci/minimal-values.yaml`](ci/minimal-values.yaml),
mit eigenen Geheimnissen. Fehlt ein Pflichtwert, lehnt `values.schema.json` die Installation ab.

Die Schlüssel erzeugt der Betreiber selbst:

| Wert | Erzeugen mit |
|---|---|
| `secrets.jwtSecret` | `openssl rand -base64 48` |
| `secrets.credentialsEncryptionKey` | `openssl rand -base64 32` |
| `secrets.settingsEncryptionKey` | `openssl rand -base64 32` |

Für den Betrieb wird statt der Werte ein vorhandenes Secret empfohlen (`secrets.existingSecret`). Welche
Schlüssel es enthalten muss, steht in [`values.yaml`](values.yaml).

## Was der Chart festlegt

- **Eine Instanz, Strategie `Recreate`.** Ein Upgrade unterbricht den Betrieb kurz. Die
  Schemamigration läuft beim Start des Backends, die Startup-Probe gibt ihr bis zu zehn Minuten.
- **Pod Security Standard `restricted`.** Alle Pods laufen ohne Root, mit nur lesbarem
  Root-Dateisystem und ohne Token des ServiceAccounts.
- **Betriebsmodus `oidc`.** Ohne Identitätsanbieter melden sich Personen lokal an
  ([ADR-0033](../../../docs/decisions/0033-lokale-benutzerverwaltung.md)).
- **Startwerte wirken nur beim ersten Start.** `bootstrap.*` wird einmal in die Datenbank übernommen,
  danach führt die Oberfläche.

## Prüfen

```bash
helm lint deploy/helm/opaa -f deploy/helm/opaa/ci/minimal-values.yaml
helm test opaa -n opaa
```

`helm test` ruft `/api/health` über den Frontend-Service ab und prüft damit die Kette
Frontend-nginx → Backend.
