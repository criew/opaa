# Überwachung und Fehlerbehebung

Dieses Kapitel beschreibt, was eine Installation über sich ausgibt — Log und Metriken —, wie der
Betrieb sie ohne Shell im Container untersucht und wo er bei einer Störung weiterliest. Es gilt für
jeden Betriebsweg und gehört zum Teil Betrieb des [Handbuchs](../README.md); die Bausteine einer
Installation beschreibt die [Betriebsübersicht](README.md).

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
  Anwendung ([Berechtigungen](../konten-und-rechte/berechtigungen.md)).
- **Metriken** im Prometheus-Format unter `/actuator/prometheus`:

| Metrik | Bedeutung |
|---|---|
| `http_server_requests_seconds_*` | Anfragen nach Pfad, Methode, Status |
| `opaa_query_duration_seconds_*`, `opaa_query_count_total` | Dauer und Zahl der Chat-Fragen |
| `opaa_query_tokens_total` | Token der Antwortaufrufe |
| `opaa_indexing_documents_total` | verarbeitete Dokumente nach Ergebnis |
| `opaa_indexing_embedding_inputs_total` | Chunks an der Eingabegrenze des Embedding-Modells, nach Ergebnis (`split`, `cut`, `unverified`; [Modelle](modelle.md)) |
| `opaa_siem_export_total` | Ausleitung an das Sicherheitsmonitoring nach Ergebnis (`sent` je Eintrag, `failed` je gescheiterter Zustellung, `gap` je gemeldeter Lücke) |
| `opaa_rate_limit_rejected_total` | abgewiesene Anfragen; die globale Anmeldegrenze warnt vor verteiltem Ausprobieren von Zugangsdaten |
| `opaa_auth_local_account_lockout_total` | Sperren lokaler Konten |
| `opaa_self_disclosure_exports_total` | Selbstauskünfte nach Ergebnis (`started`, `completed`, `aborted`) und Abbruchgrund; viele Abbrüche deuten auf Proxy oder Timeout |
| `opaa_self_disclosure_refused_total` | abgewiesene Selbstauskünfte nach Grund (`reason`) |
| `opaa_self_disclosure_duration_seconds_*`, `opaa_self_disclosure_size_bytes_*`, `opaa_self_disclosure_running` | Dauer, Größe und laufende Selbstauskünfte dieser Instanz, ohne Bezug auf eine Person |
| `hikaricp_connections_*` | Connection Pool der Datenbank |
| `jvm_memory_used_bytes`, `jvm_memory_max_bytes` | Speicher der JVM |

Weitere Metriken: [Suche](../spaces/suche.md#102-metriken-und-log); Einsammeln und Alarme unter Kubernetes:
[Betriebsüberwachung](kubernetes.md#betriebsüberwachung).

## Ausleitung an ein Sicherheitsmonitoring

OPAA kann sein Revisionsprotokoll laufend an das zentrale Sicherheitsmonitoring (SIEM) der Behörde
geben, damit Verwaltungshandeln wie Rechteänderungen, Kontosperren oder Zugriffe auf das Protokoll
dort neben den Ereignissen anderer Fachverfahren erscheint.

> **Beispiel.** Das Rechenzentrum eines Landes betreibt ein SIEM mit einem Syslog-Empfänger. Der
> Betrieb trägt dessen Adresse ein und schaltet die Ausleitung ein. Ab diesem Moment erscheint jede
> neue Freigabe einer Bibliothek als eigene Meldung im SIEM, mit Zeitpunkt, Bibliothek und Ergebnis,
> aber ohne die Person, die sie erteilt hat.

- **Was hinausgeht:** je Eintrag des Revisionsprotokolls Ereignisart, Zeitpunkt, Ergebnis,
  Organisation und betroffenes Objekt. Nie gehen Pseudonyme handelnder oder betroffener Personen,
  Begründungen oder Inhalte hinaus; bei einem Konto, einem Zugangstoken und einer privaten
  Bibliothek fehlen auch Kennung und Bezeichnung des Objekts. Wer gehandelt hat, klärt nur die
  anlassbezogene Klärung in OPAA
  ([Berechtigungen](../konten-und-rechte/berechtigungen.md#10-systemrollen-und-vollmachten)).
- **Lesezugriff:** Eingeschaltet liest die Ausleitung das Revisionsprotokoll vollständig der Reihe
  nach, sonst nicht. Das ist neben der Revision und der Selbstauskunft der einzige Leser.
- **Format:** Syslog (RFC 5424) mit CEF-Inhalt über TCP, voreingestellt mit TLS und dem
  Vertrauensspeicher der Java-Laufzeit samt Prüfung des Hostnamens. Die Kennung jedes Eintrags steht
  in `externalId`; daran erkennt das SIEM eine Meldung, die nach einem Abbruch doppelt kommt. Die
  Verbindung geht direkt, nie über den Ausgangsproxy.
- **Ab dem Einschalten:** Gesendet wird, was nach dem ersten Lauf ins Protokoll kommt; der ältere
  Bestand nicht. Abschalten hält die Ausleitung an, wieder eingeschaltet geht es an derselben Stelle
  weiter.
- **Kein stiller Verlust:** Ist der Empfänger nicht erreichbar, bleiben die Einträge fällig und
  gehen beim nächsten erfolgreichen Lauf hinaus; das Log meldet jeden gescheiterten Lauf. Ein Lauf
  findet alle 30 Sekunden auf einer Instanz statt. Ein Eintrag wartet vor dem Senden die Zeit
  `OPAA_SIEM_EXPORT_SETTLE_DELAY`, damit ein später abgeschlossener Vorgang nicht übersprungen wird.
- **Lücken:** Hat die Aufbewahrungsfrist Einträge gelöscht, bevor sie gesendet waren, kommt im SIEM
  eine Meldung `AUDIT_EXPORT_GAP` mit dem Zeitraum an.
- **Sicherheitsereignisse des Logs:** Fehlgeschlagene Anmeldungen, abgewiesene Netzbereiche und der
  Abflussalarm externer KI-Werkzeuge stehen nur im Log auf der Standardausgabe. Die Behörde holt sie
  mit ihrer Log-Sammlung ab, als JSON mit `LOGGING_STRUCTURED_FORMAT_CONSOLE`.

| Variable | Standard | Bedeutung |
|---|---|---|
| `OPAA_SIEM_EXPORT_ENABLED` | `false` | Ausleitung ein |
| `OPAA_SIEM_EXPORT_HOST` | — | Host des Syslog-Empfängers |
| `OPAA_SIEM_EXPORT_PORT` | `6514` | Port des Syslog-Empfängers |
| `OPAA_SIEM_EXPORT_TLS` | `true` | `false` sendet ohne TLS |
| `OPAA_SIEM_EXPORT_TIMEOUT` | `30s` | Frist einer Zustellung insgesamt |
| `OPAA_SIEM_EXPORT_SETTLE_DELAY` | `2m` | Wartezeit eines Eintrags vor dem Senden |
| `OPAA_SIEM_EXPORT_BATCH_SIZE` | `500` | Einträge je Zustellung höchstens |

Unter Kubernetes stehen Schalter, Host, Port, TLS und Frist unter `siemExport` im Helm-Chart; eine
NetworkPolicy muss dem Backend den Weg zum Empfänger öffnen. Nicht gebaut sind ein
Client-Zertifikat für TLS und ein eigener Vertrauensspeicher neben dem der Java-Laufzeit.

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
| Im SIEM kommt nichts an, Log meldet `SIEM export … failed` | Adresse, Port, TLS-Vertrauen des Empfängers ([Ausleitung an ein Sicherheitsmonitoring](#ausleitung-an-ein-sicherheitsmonitoring)) |
| Lange Antworten enden mit `504` | Timeouts von Frontend und Proxy ([Netzwerkzugang](netz-und-sicherheit.md#netzwerkzugang)) |
| Verbindungen nach außen scheitern mit Proxy-Status | [Ausgangsproxy](netz-und-sicherheit.md#ausgangsproxy) |
| Container- oder Pod-Fehlerbilder | [Docker Compose](docker-compose.md#fehlerbehebung), [Kubernetes](kubernetes.md#fehlersuche) |

## Weiterführende Kapitel

- [Aktualisierung](aktualisierung.md) — nach einem Update prüfen, was läuft
- [Mehrere Backend-Instanzen](mehrere-instanzen.md) — Instanzregister und Konfigurationsabdruck
- [Images und Laufzeit](images-und-laufzeit.md) — Readiness und Liveness, Speichergrenzen
- [Kubernetes](kubernetes.md#betriebsüberwachung) — Metriken einsammeln und Alarme
