# Überwachung und Fehlerbehebung

> **Entwurf.** Gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

Dieses Kapitel beschreibt, was eine Installation über sich ausgibt — Log und Metriken —, wie der
Betrieb sie ohne Shell im Container untersucht und wo er bei einer Störung weiterliest. Es gilt für
jeden Betriebsweg und gehört zum Teil Betrieb des [Handbuchs](README.md); die Bausteine einer
Installation beschreibt die [Betriebsübersicht](betrieb.md).

## Logs und Metriken

- **Log** auf der Standardausgabe, als Text oder mit `LOGGING_STRUCTURED_FORMAT_CONSOLE` (`ecs`,
  `logstash`, `gelf`) als JSON. Beim Start stehen dort Version, Warnungen zur Konfiguration, das
  Einmalpasswort des Erststarts
  ([Authentifizierung](authentifizierung.md#erststart-und-systemverwalter-konto)) und jeder
  Startabbruch mit dem Namen der Variable.
- **Inhalt:** Geheimnisse, Zugangsdaten und Request-Bodies erscheinen nicht, Namen aus privaten
  Bibliotheken auch auf `DEBUG` nicht. Ausgaben eingebundener Bibliotheken auf `DEBUG`/`TRACE` und
  Datenbankfehler können Inhalte nennen: Ein Log über `INFO` wie Inhalte behandeln.
- **Handlungen von Personen** stehen nicht im Log, sondern im Nachweis- und Revisionsprotokoll der
  Anwendung ([Berechtigungen](berechtigungen.md)).
- **Metriken** im Prometheus-Format unter `/actuator/prometheus`:

| Metrik | Bedeutung |
|---|---|
| `http_server_requests_seconds_*` | Anfragen nach Pfad, Methode, Status |
| `opaa_query_duration_seconds_*`, `opaa_query_count_total` | Dauer und Zahl der Chat-Fragen |
| `opaa_query_tokens_total` | Token der Antwortaufrufe |
| `opaa_indexing_documents_total` | verarbeitete Dokumente nach Ergebnis |
| `opaa_indexing_embedding_inputs_total` | Chunks an der Eingabegrenze des Embedding-Modells, nach Ergebnis (`split`, `cut`, `unverified`; [Modelle](modelle.md)) |
| `opaa_rate_limit_rejected_total` | abgewiesene Anfragen; die globale Anmeldegrenze warnt vor verteiltem Ausprobieren von Zugangsdaten |
| `opaa_auth_local_account_lockout_total` | Sperren lokaler Konten |
| `hikaricp_connections_*` | Connection Pool der Datenbank |
| `jvm_memory_used_bytes`, `jvm_memory_max_bytes` | Speicher der JVM |

Weitere Metriken: [Suche](suche.md#102-metriken-und-log); Einsammeln und Alarme unter Kubernetes:
[Betriebsüberwachung](kubernetes.md#betriebsüberwachung).

## Diagnose ohne Shell

Actuator-Pfade sind nur über den Backend- bzw. Management-Port erreichbar, nicht über das Frontend:

```bash
curl -s http://localhost:8081/actuator/info              # Version
curl -s http://localhost:8081/actuator/health/readiness  # Readiness
curl -s http://localhost:8081/actuator/prometheus        # Metriken
curl -s -H "Authorization: Bearer <token>" https://<host>/api/v1/admin/diagnostics/client-address
```

Der letzte Aufruf (Systemverwaltung, über den Proxy) zeigt, welche Client-Adresse das Backend für
diese Anfrage ermittelt (`clientAddress`). Steht dort die Adresse des Proxys, fehlt er in
`OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS`. Dumps und Dateien aus dem Container:
[Docker Compose](docker-compose.md#diagnose-im-container), [Kubernetes](kubernetes.md#fehlersuche).

## Fehlerbehebung

| Bild | Wo weiter |
|---|---|
| Start bricht ab und nennt eine Variable | [Konfiguration](konfiguration.md); bei `OPAA_AUTH_JWT_SECRET` [Authentifizierung](authentifizierung.md#das-backend-startet-nicht-und-nennt-opaa_auth_jwt_secret) |
| Start bricht mit Hinweis auf pgvector, Schema oder Migration ab | [Datenbank](datenbank.md) |
| Start bricht mit `refuses to start` ab | [Konfigurationsabdruck](mehrere-instanzen.md#gleiche-konfiguration-der-konfigurationsabdruck) |
| Readiness meldet `DOWN` | Datenbank, Connection Pool ([Readiness und Liveness](images-und-laufzeit.md#readiness-und-liveness)) |
| Indexierung oder Chat schlagen fehl | [Modelle](modelle.md#fehlerbilder) |
| Niemand kommt herein, Anmeldung mit `429` | [Authentifizierung](authentifizierung.md#fehlerbehebung) |
| Originale lassen sich nicht abrufen oder hochladen | [Originalablage](originalablage.md#wenn-etwas-nicht-stimmt) |
| Mails kommen nicht an | [E-Mail-Versand](e-mail.md#fehlerbilder) |
| Lange Antworten enden mit `504` | Timeouts von Frontend und Proxy ([Netzwerkzugang](netz-und-sicherheit.md#netzwerkzugang)) |
| Verbindungen nach außen scheitern mit Proxy-Status | [Ausgangsproxy](netz-und-sicherheit.md#ausgangsproxy) |
| Container- oder Pod-Fehlerbilder | [Docker Compose](docker-compose.md#fehlerbehebung), [Kubernetes](kubernetes.md#fehlersuche) |

## Weiterführende Kapitel

- [Aktualisierung](aktualisierung.md) — nach einem Update prüfen, was läuft
- [Mehrere Backend-Instanzen](mehrere-instanzen.md) — Instanzregister und Konfigurationsabdruck
- [Images und Laufzeit](images-und-laufzeit.md) — Readiness und Liveness, Speichergrenzen
- [Kubernetes](kubernetes.md#betriebsüberwachung) — Metriken einsammeln und Alarme
