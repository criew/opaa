# Überwachung und Fehlerbehebung

Dieses Kapitel beschreibt, was eine Installation über sich ausgibt — Log und Metriken —, wie der
Betrieb sie ohne Shell im Container untersucht und wo er bei einer Störung weiterliest. Es gilt für
jeden Betriebsweg und gehört zum Teil Betrieb des [Handbuchs](../README.md); die Bausteine einer
Installation beschreibt die [Betriebsübersicht](README.md).

## Logs und Metriken

- **Log** auf der Standardausgabe, als Text oder mit `LOGGING_STRUCTURED_FORMAT_CONSOLE` (`ecs`,
  `logstash`, `gelf`) als JSON. Beim Start stehen dort Version, Warnungen zur Konfiguration, das
  Installations-Token des ersten Starts ([Installation](installation.md#installations-token)), das
  Ergebnis einer Antwortdatei und jeder Startabbruch mit dem Namen der Variable.
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
| `opaa_indexing_suspicious_patterns_total` | verarbeitete Dokumente mit auffälligen Mustern nach Bibliothek (`library`: Kennung, für private Bibliotheken gemeinsam `private`) und Art (`class`: `INVISIBLE_CHARACTERS`, `ROLE_TOKEN`, `INSTRUCTION_PHRASE`, `DATA_URL_PLACEHOLDER`; [Wissensbibliotheken](../arbeitsmittel/wissensbibliotheken/README.md#61-ergebnis-je-dokument)) |
| `opaa_malware_scans_total` | Virenprüfungen nach Ergebnis (`clean`, `infected`, `failed`; [Netz und Sicherheit](netz-und-sicherheit.md#virenprüfung-der-aufgenommenen-dateien)) |
| `opaa_siem_export_total` | Ausleitung an das Sicherheitsmonitoring nach Ergebnis (`sent` je Eintrag, `failed` je gescheiterter Zustellung, `gap` je gemeldeter Lücke) |
| `opaa_siem_export_lag_seconds` | Alter des ältesten Eintrags, der noch nicht ausgeleitet ist, `0` ohne offene Einträge; wächst der Wert stetig, hält eine offene Transaktion oder der Empfänger die Ausleitung an |
| `opaa_audit_log_size_bytes` | Größe des Revisionsprotokolls samt Indizes; wächst bis zur Aufbewahrungsfrist und bleibt dann ungefähr gleich |
| `opaa_audit_log_oldest_partition_age_seconds`, `opaa_audit_retention_period_months` | Alter des ältesten Protokollmonats und eingestellte Frist; liegt das Alter mehr als zwei Monate über der Frist, löscht die automatische Löschung nicht |
| `opaa_audit_retention_last_run_age_seconds` | Zeit seit dem Monat des letzten Löschlaufs (am 1. jedes Monats um 03:00); über 35 Tage ist der Lauf ausgeblieben. Die Werte des Revisionsprotokolls liest jede Instanz alle fünf Minuten aus der Datenbank; kann sie einen nicht lesen oder antwortet die Datenbank nicht binnen zehn Sekunden, meldet sie `NaN` und warnt im Log |
| `opaa_audit_log_horizon_seconds` | Zeit bis zum Ende des letzten angelegten Protokollmonats; danach scheitert jedes Schreiben ins Protokoll |
| `opaa_audit_payload_dropped_total` | Einträge ins Revisionsprotokoll, aus denen OPAA eine Angabe entfernt hat, die der Ereigniskatalog für die Ereignisart (`event_type`) nicht nennt; jede Ereignisart beginnt beim Start eines Pods bei 0. Jeder Anstieg ist ein Programmfehler, das Log nennt die Angaben ohne ihre Werte |
| `opaa_audit_chain_lag_seconds` | Alter des ältesten Protokolleintrags, den noch kein Kontrollpunkt der Prüfsummenkette abdeckt, `0` ohne offene Einträge, alle fünf Minuten gelesen; der Kettenlauf findet alle zehn Minuten statt, über Stunden wächst der Wert nur, wenn er ausbleibt oder eine offene Transaktion ihn anhält. Beim ersten Start einer großen Installation ist der Wert hoch, bis der Lauf den Bestand mit 50 000 Einträgen je zehn Minuten aufgeholt hat ([Prüfsummenkette](nachvollziehbarkeit.md#prüfsummenkette-und-monatssiegel)) |
| `opaa_audit_chain_unsealed_months` | abgeschlossene Protokollmonate ohne Siegel; nach dem Monatswechsel kurz `1`, dauerhaft über `0` heißt, die Kette kommt nicht bis zum Monatsende, oder die Siegelzeile eines Monats wurde entfernt, dessen Eintrag `AUDIT_LOG_MONTH_SEALED` noch steht (der Lauf versiegelt ihn nicht neu; `GET /api/v1/audit/chain/{Monat}` nennt ihn `BROKEN`) |
| `opaa_target_validation_guarded_names`, `opaa_target_validation_refused_checks_total` | gespeicherte Ziele (Modell-Endpunkte, Identitätsanbieter, Mailserver), deren Namen diese Instanz bis zum Neustart bewacht, und Prüfungen neuer gespeicherter Ziele, die die volle Tabelle abgelehnt hat. **Alarm bei jedem Anstieg des Zählers:** Ihre Zahl begrenzt die Konfiguration, eine volle Tabelle deutet auf Missbrauch; neue Ziele lassen sich bis zum Neustart nicht speichern, das Log nennt den Grund ([Zielprüfung](netz-und-sicherheit.md#zielprüfung-abrufe-des-servers)) |
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
  Organisation sowie Art und Kennung des betroffenen Objekts und die Version des Ereigniskatalogs. Nie gehen Pseudonyme handelnder oder
  betroffener Personen, Begründungen oder Inhalte hinaus. Den Namen des Objekts (etwa eines Space
  oder einer Gruppe) sendet OPAA nur mit `OPAA_SIEM_EXPORT_INCLUDE_LABELS`, weil Namen oft Personen
  nennen. Gehört das Objekt genau einer Person (Konto, Zugangstoken, persönlicher Bereich, private
  Bibliothek, privater Prompt), fehlen Kennung und Name immer. Wer gehandelt hat, klärt nur die
  anlassbezogene Klärung in OPAA
  ([Berechtigungen](../konten-und-rechte/berechtigungen.md#10-systemrollen-und-vollmachten)).
- **Lesezugriff:** Eingeschaltet liest die Ausleitung das Revisionsprotokoll vollständig der Reihe
  nach, sonst nicht. Das ist neben der Revision, der Selbstauskunft und der Prüfsummenkette der
  einzige Leser.
- **Monatssiegel:** Mit dem Eintrag `AUDIT_LOG_MONTH_SEALED` gehen Monat (`cs5`), Anzahl der
  verketteten Einträge (`cn2`) und Kopf-Prüfsumme (`cs6`) der
  [Prüfsummenkette](nachvollziehbarkeit.md#prüfsummenkette-und-monatssiegel) hinaus, die einzige
  Nutzlast der Ausleitung. Das SIEM bewahrt damit das Siegel außerhalb der Datenbank auf.
- **Format:** Syslog (RFC 5424) mit CEF-Inhalt über TCP, voreingestellt mit TLS und dem
  Vertrauensspeicher der Java-Laufzeit samt Prüfung des Hostnamens. Die Kennung jedes Eintrags steht
  in `externalId`, die Version des Ereigniskatalogs, unter der er geschrieben ist, in `cn1`
  (`cn1Label=schemaVersion`); Einträge von vor dem Katalog tragen keine. Die Verbindung geht direkt,
  nie über den Ausgangsproxy.
- **Ab dem Einschalten:** Gesendet wird, was nach dem ersten Lauf ins Protokoll kommt; der ältere
  Bestand nicht. Nach dem Abschalten beginnt die Ausleitung beim Wiedereinschalten neu; die Pause
  meldet sie als Lücke.
- **Kein stiller Verlust:** Ein Eintrag geht erst hinaus, wenn jeder Vorgang abgeschlossen ist, der
  vor ihm begann; so überholt die Ausleitung keinen langen Vorgang wie einen Verzeichnisabgleich.
  Dafür muss die Datenbankverbindung zum Primär gehen, nicht zu einem Replikat. Eine Sitzung, die
  eine Transaktion offen liegen lässt, hält die Ausleitung an: Die Metrik
  `opaa_siem_export_lag_seconds` wächst, und das Log warnt nach zehn Minuten mit der Sitzung.
  `idle_in_transaction_session_timeout` in PostgreSQL beendet solche Sitzungen.
  Ist der Empfänger nicht erreichbar, bleiben die Einträge fällig und gehen beim nächsten
  erfolgreichen Lauf hinaus. Ein Lauf findet alle 30 Sekunden auf einer Instanz statt; nach
  Fehlschlägen wartet sie immer länger, höchstens zehn Minuten, und das Log meldet den ersten
  Fehlschlag und danach den längsten Abstand.
- **Zustellung mindestens einmal:** Eine Zustellung gilt als gelungen, wenn der Empfänger die Daten
  angenommen und die Verbindung nicht erkennbar abgewiesen hat; eine Bestätigung je Meldung gibt es nicht. Bricht
  ein Lauf danach ab, kommt derselbe Stapel noch einmal; das SIEM erkennt die Wiederholung an
  `externalId`.
- **Lücken:** Hat die Aufbewahrungsfrist Einträge gelöscht, bevor sie gesendet waren, oder war die
  Ausleitung abgeschaltet, kommt im SIEM eine Meldung `AUDIT_EXPORT_GAP` mit dem Zeitraum und dem
  Grund (`retention` oder `disabled`) an.
- **Sicherheitsereignisse des Logs:** Fehlgeschlagene Anmeldungen, abgewiesene Netzbereiche und der
  Abflussalarm externer KI-Werkzeuge stehen nur im Log auf der Standardausgabe. Die Behörde holt sie
  mit ihrer Log-Sammlung ab, als JSON mit `LOGGING_STRUCTURED_FORMAT_CONSOLE`. **Dabei entsteht ein
  Weg zur Person:** Manche Logzeilen nennen die Kennung eines Kontos, etwa bei einer Kontosperre.
  Liegen Log und Protokoll im selben SIEM, lässt sich ein Protokolleintrag ohne Kennung über den
  Zeitpunkt mit dieser Logzeile verbinden. Die Dienstvereinbarung regelt, dass das nur in der
  anlassbezogenen Klärung geschieht, und wie lange das SIEM beides aufbewahrt.

| Variable | Standard | Bedeutung |
|---|---|---|
| `OPAA_SIEM_EXPORT_ENABLED` | `false` | Ausleitung ein |
| `OPAA_SIEM_EXPORT_HOST` | — | Host des Syslog-Empfängers |
| `OPAA_SIEM_EXPORT_PORT` | `6514` | Port des Syslog-Empfängers |
| `OPAA_SIEM_EXPORT_TLS` | `true` | `false` sendet ohne TLS |
| `OPAA_SIEM_EXPORT_INCLUDE_LABELS` | `false` | `true` sendet auch Objektnamen |
| `OPAA_SIEM_EXPORT_TIMEOUT` | `30s` | Frist einer Zustellung insgesamt |
| `OPAA_SIEM_EXPORT_SETTLE_DELAY` | `5s` | zusätzliche Wartezeit eines Eintrags vor dem Senden |
| `OPAA_SIEM_EXPORT_BATCH_SIZE` | `500` | Einträge je Zustellung höchstens |

Unter Kubernetes stehen Schalter, Host, Port, TLS, Namen und Frist unter `siemExport` im Helm-Chart.
Den Weg zum Empfänger öffnet eine Regel unter `networkPolicy.egress.backendRules`; das Zertifikat
einer internen Zertifizierungsstelle kommt über `extraCACertificates` in den Vertrauensspeicher
([Kubernetes](kubernetes.md)). Nicht gebaut ist ein Client-Zertifikat für TLS.

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
