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
- **Textformat:** Jede Folgezeile einer mehrzeiligen Meldung oder Exception-Nachricht ist um vier
  Leerzeichen eingerückt, Steuer- und Bidi-Zeichen erscheinen als `?`. Ein Name oder eine Adresse
  aus einer Quelle kann so keinen eigenen Logeintrag vortäuschen; ein neuer Eintrag beginnt immer am
  Zeilenanfang. Ein eigenes Muster (`LOGGING_PATTERN_CONSOLE`) muss dafür
  `${LOG_EXCEPTION_CONVERSION_WORD}` oder `%opaaEx` enthalten, und `LOGGING_EXCEPTION_CONVERSION_WORD`
  muss `%opaaEx` bleiben; sonst hängt Logback seinen eigenen Stacktrace an, und Exception-Nachrichten
  erscheinen unbereinigt.
- **Inhalt:** Geheimnisse, Zugangsdaten und Request-Bodies erscheinen nicht, Namen aus privaten
  Bibliotheken auch auf `DEBUG` nicht. Ausgaben eingebundener Bibliotheken auf `DEBUG`/`TRACE` und
  Datenbankfehler können Inhalte nennen: Ein Log über `INFO` wie Inhalte behandeln.
- **Handlungen von Personen** stehen nicht im Log, sondern im Nachweis- und Revisionsprotokoll der
  Anwendung ([Rechte-Matrix](../referenz/rechte-matrix.md)).
- **Ohne Spur im Regelbetrieb:** Den Aufruf `PUT /api/v1/me/quick-tour-hint`, mit dem eine Person den Hinweis
  zum Rundgang für neue Nutzer erledigt, schreiben weder das Anfrageprotokoll des Backends noch das
  Zugriffslog des Frontend-Containers, und er zählt in keiner Metrik. Ein vorgelagerter Reverse
  Proxy oder Ingress-Controller protokolliert ihn trotzdem; dort richtet der Betrieb dieselbe
  Ausnahme ein. Spuren bleiben nur bei Störungen und in der Fehlersuche: Ist das Backend nicht
  erreichbar, nennt das Fehlerlog des Frontend-Containers Pfad und Client-Adresse jeder gescheiterten
  Anfrage; scheitert der Datenbankzugriff, steht ein Stacktrace mit Zeitstempel, aber ohne Konto im
  Fehlerlog des Backends. Auf `DEBUG` nennen die Spring-Komponenten jeden Aufruf, auch diesen: die
  Anmeldeschicht (`org.springframework.security`) mit Pfad und Client-Adresse, die Web-Schicht
  (`org.springframework.web`) mit Pfad und Controller, der Datenbankzugriff
  (`org.springframework.jdbc`) mit der SQL-Anweisung.
- **Metriken** im Prometheus-Format unter `/actuator/prometheus`, einzeln unter `/actuator/metrics`.
  Sie nennen Pfade, Modelle und Last und sind deshalb ohne Anmeldung nur auf einem eigenen
  Management-Port erreichbar (`MANAGEMENT_SERVER_PORT`, unter Kubernetes `backend.managementPort`),
  den eine Netzregel nur dem Prometheus öffnet. Mit eigenem Management-Port gibt es die
  Actuator-Pfade auf dem API-Port nicht. Ohne ihn liegen sie auf dem API-Port, und die Metriken
  antworten dort nur mit Anmeldung. Helm-Chart und Compose-Stapel richten den Management-Port ein;
  unter Docker Compose ist er auf dem Host unter `127.0.0.1` erreichbar
  (`OPAA_BACKEND_MANAGEMENT_PORT`, [Konfiguration](../referenz/konfiguration.md)).
- **Jede Reihe** trägt `application` (`opaa`) und `host`, den Rechnernamen der Instanz, unter
  Kubernetes den Namen des Pods. `opaa_build_info` nennt je Instanz `version` und `commit`; zwei
  Versionen nebeneinander zeigen einen laufenden oder hängengebliebenen Rolling Update.
- **Latenzen als Histogramm:** Die Dauer von HTTP-Anfragen, Chat-Fragen bis zum ersten Wort und
  bis zum Ende, Modellaufrufen, Texterkennung und geplanten Läufen hat Buckets an festen Schwellen
  (`_bucket` mit `le`), aus denen Prometheus Perzentile über alle Pods rechnet, etwa das p95 der
  Chat-Fragen:
  `histogram_quantile(0.95, sum by (le) (rate(opaa_query_duration_seconds_bucket[5m])))`. Die
  Schwellen lassen sich je Timer über `OPAA_METRICS_SLO_*` an die eigenen Zielwerte anpassen; ein
  Zielwert sollte genau auf einer Schwelle liegen.
- **Ohne Personenbezug:** Keine Metrik nennt Person, Space, Bibliothek, Dokument, Datei, Adresse
  oder Suchbegriff; Werte stammen aus festen kleinen Mengen wie Ergebnis, Modell oder Konnektortyp.
  Zählungen gelten für die ganze Installation.

| Metrik | Bedeutung |
|---|---|
| `http_server_requests_seconds_*` | Anfragen nach Pfad, Methode, Status, mit Buckets |
| `opaa_build_info` | Version und Commit der Instanz, immer `1` |
| `opaa_query_duration_seconds_*`, `opaa_query_count_total` | Dauer je Chat-Frage mit Buckets und Fragen nach Ausgang (`status`: `success`, `error`, `rejected`), beide nach Art der Antwort (`mode`: `rag` mit Suche oder Anhängen, `no_search` ohne Suche, `agent` mit Werkzeugschleife). `error` zählt jeden Fehler des Dienstes, auch Fragen, deren Leser vorher gegangen ist; `rejected` zählt Anfragefehler wie fehlende Berechtigung, ungültige Eingabe oder fehlendes Chat-Modell, ohne Dauer. Die Erfolgsquote für ein SLO ist `success / (success + error)` |
| `opaa_query_time_to_first_token_seconds_*` | Zeit von der Frage bis zum ersten sichtbaren Text der Antwort nach `mode`, mit Buckets; die Wartezeit, die man im Chat spürt. Ein vorangestellter Denkblock des Modells zählt nicht mit. Nur gestreamte Antworten |
| `opaa_query_stage_duration_seconds_*` | Zeit einer Frage je Stufe (`stage`): `decompose` Teilfragen-Zerlegung, `embed_query` Einbetten der Suchanfragen, `retrieve` Suche und Auswahl, `rerank` Neubewertung, `generate` Antwort des Chat-Modells, `persist` Speichern im Chat. Die Stufen überschneiden sich nicht; ihre Summe bleibt unter der Dauer der Frage |
| `opaa_retrieval_hits_*`, `opaa_retrieval_empty_total` | Ausgewählte Fundstellen je Chat-Frage mit Suche und Fragen ganz ohne Fundstelle; ein steigender Anteil leerer Suchen deutet auf fehlende Indizierung oder einen gestörten Suchweg |
| `opaa_query_context_tokens_*`, `opaa_query_context_truncated_total` | Eingabe-Tokens je Antwortaufruf, soweit der Anbieter sie meldet, und beantwortete Fragen in einem Chat mit mehr Nachrichten als das Gesprächsfenster, bei denen ältere Nachrichten nicht mehr ins Modell gehen |
| `opaa_query_finish_reason_total` | Antworten nach dem Ende, das das Modell meldet (`reason`: `stop`, `length` an der Ausgabegrenze abgeschnitten, `content_filter`, `tool`, `other`) |
| `opaa_query_aborted_total` | Chat-Fragen ohne Antwort nach Ursache (`cause`: `client_cancel` Leser gegangen, `timeout` ein Aufruf hat nicht rechtzeitig geantwortet, `error` jeder andere Fehler) |
| `opaa_model_tokens_total` | Tokens jedes Aufrufs des Chat-Modells nach Zweck (`purpose`: `answer`, `decompose`, `note`, `auxiliary` Titel und Metadaten, `agent`), Art (`token_type`: `input`, `output`) und `model`, dem Modellnamen aus der Modellverwaltung; höchstens 20 Modellnamen, weitere Reihen verwirft OPAA mit einer Warnung im Log |
| `gen_ai_client_operation_seconds_*` | Dauer jedes Aufrufs von Chat- und Embedding-Modell nach Operation (`chat`, `embedding`), Anbieter und Modell, mit Buckets; `error` nennt die Fehlerklasse |
| `gen_ai_client_token_usage_total` | Tokens nach Operation, Modell und Art (`input`, `output`, `total`), ohne Person und Inhalt |
| `opaa_conversations_active` | Gespräche, die diese Instanz im Speicher hält |
| `opaa_installation_pending` | `1`, solange die Installation nicht abgeschlossen ist ([Installation](installation.md)) |
| `opaa_indexing_documents_total` | verarbeitete Dokumente nach Ergebnis |
| `opaa_indexing_embedding_inputs_total` | Chunks an der Eingabegrenze des Embedding-Modells, nach Ergebnis (`split`, `cut`, `unverified`; [Modelle](modell-endpunkte.md)) |
| `opaa_indexing_suspicious_patterns_total` | verarbeitete Dokumente mit auffälligen Mustern nach Art (`class`: `INVISIBLE_CHARACTERS`, `ROLE_TOKEN`, `INSTRUCTION_PHRASE`, `DATA_URL_PLACEHOLDER`; [Wissensbibliotheken](../referenz/wie-indexierung-arbeitet.md#laufprotokoll)) |
| `opaa_ocr_documents_total`, `opaa_ocr_pages_total`, `opaa_ocr_duration_seconds_*` | Texterkennung gescannter Dokumente nach Ergebnis (`recognized`, `empty`, `reused`, `failed`, `blocked`, `pending`) und Verbindungstyp, gesendete Seiten und Dauer mit Buckets; `empty` heißt, der Dienst hat geantwortet, aber keinen Text erkannt; steigt `failed`, ist der Dienst nicht verfügbar ([Texterkennung einschalten](modell-endpunkte.md#texterkennung-einschalten)) |
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
| `opaa_target_validation_refused_connection_checks_total` | Prüfungen vor einer Verbindung, die abgelehnt wurden, weil in der letzten Minute zu viele verschiedene Hostnamen geprüft wurden; ein Anstieg deutet auf Weiterleitungen auf immer neue Namen ([Zielprüfung](netz-und-sicherheit.md#zielprüfung-abrufe-des-servers)) |
| `opaa_target_validation_guarded_names`, `opaa_target_validation_refused_checks_total` | gespeicherte Ziele (Modell-Endpunkte, Identitätsanbieter, Mailserver), deren Namen diese Instanz bis zum Neustart bewacht, und Prüfungen neuer gespeicherter Ziele, die die volle Tabelle abgelehnt hat. **Alarm bei jedem Anstieg des Zählers:** Ihre Zahl begrenzt die Konfiguration, eine volle Tabelle deutet auf Missbrauch; neue Ziele lassen sich bis zum Neustart nicht speichern, das Log nennt den Grund ([Zielprüfung](netz-und-sicherheit.md#zielprüfung-abrufe-des-servers)) |
| `opaa_rate_limit_rejected_total` | abgewiesene Anfragen; die globale Anmeldegrenze warnt vor verteiltem Ausprobieren von Zugangsdaten |
| `opaa_auth_local_account_lockout_total` | Sperren lokaler Konten |
| `opaa_auth_personal_space_provisioning_total` | gescheiterte Anlagen des persönlichen Bereichs bei der ersten Anmeldung (`result="failed"`); jeder Anstieg verdient einen Blick ins Log |
| `opaa_self_disclosure_exports_total` | Selbstauskünfte nach Ergebnis (`started`, `completed`, `aborted`) und Abbruchgrund; viele Abbrüche deuten auf Proxy oder Timeout |
| `opaa_self_disclosure_refused_total` | abgewiesene Selbstauskünfte nach Grund (`reason`) |
| `opaa_self_disclosure_duration_seconds_*`, `opaa_self_disclosure_size_bytes_*`, `opaa_self_disclosure_running` | Dauer, Größe und laufende Selbstauskünfte dieser Instanz, ohne Bezug auf eine Person |
| `opaa_query_decomposition_fallback_total`, `opaa_query_decomposition_no_search_total` | Rückfälle der Teilfragen-Zerlegung nach Ursache; Nachrichten, in denen nichts zu suchen war |
| `opaa_agent_runs_total`, `opaa_agent_run_duration_seconds_*` | Agentenläufe nach Art (`kind`: `builtin` für den mitgelieferten, `custom` für angelegte Agenten) und Ergebnis (`completed`, `limit_calls`, `limit_duration`, `limit_volume`, `model_not_capable`, `aborted`, `error`), Dauer mit Buckets; ohne Agentennamen |
| `opaa_agent_tool_calls_total` | Werkzeugaufrufe nach Werkzeug und Ergebnis (`success`, `empty`, `argument_ignored`, `invalid_arguments`, `unknown_tool`, `denied`, `timeout`, `error`) |
| `opaa_agent_workspace_occupied`, `opaa_agent_workspace_capacity`, `opaa_agent_workspace_rejected_total` | belegte und verfügbare Plätze für gleichzeitige Agentenläufe, abgewiesene Läufe; steigt `rejected` stetig, `OPAA_AGENT_WORKSPACE_CAPACITY` anheben |
| `opaa_agent_citations_unverifiable_total` | Quellenangaben in Agentenantworten, die zu keiner gefundenen Stelle passen; sie erscheinen nicht als Quelle |
| `opaa_query_evidence_total` | Antworten je Belegstand (`supported`, `partially_supported`, `unsupported`), ohne Person, Chat oder Space |
| `opaa_chat_search_duration_seconds_*` | Dauer und Anzahl der Chatsuchen, ohne Suchbegriff und Person |
| `opaa_chat_note_extraction_total` | Verdichtungen der Gesprächsnotiz nach Ausgang (`applied`, `empty`, `failed`, `discarded`, `rejected`) |
| `tasks_scheduled_execution_seconds_*` | Dauer und Ausgang der geplanten Läufe nach Methode, mit Buckets |
| `executor_*` | Auslastung, Warteschlange und erledigte Aufgaben der Thread-Pools nach Name (`name`), etwa Indexierung und Uploads |
| `hikaricp_connections_*` | Connection Pool der Datenbank |
| `jvm_memory_used_bytes`, `jvm_memory_max_bytes` | Speicher der JVM |

Fragetext, Suchanfragen und Antwort stehen in keiner Logzeile oberhalb der Debug-Ebene. Rückfall der
Zerlegung und gescheiterte Verdichtung der Gesprächsnotiz gehen als Warnung ohne Inhalt ins Log,
unbestätigte Belege je Antwort nur als Anzahl auf Info-Ebene. Einsammeln und Alarme unter Kubernetes:
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
  ([Administration](../administration/systemrollen.md)).
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

## Korrelation und Tracing

> **Beispiel.** Eine Person meldet „Beim Speichern kam ein Fehler“. Die Oberfläche zeigt die
> Kennung der Anfrage nicht; die Person liest sie in den Entwicklerwerkzeugen des Browsers (Reiter
> Netzwerk, Antwort-Header `X-Request-Id` oder Feld `requestId` der Fehlerantwort) und meldet sie
> mit. Der Betrieb sucht diese Kennung im Zugriffslog des Frontends und im Log des Backends und
> findet beide Zeilen der Anfrage, auch die eines Hintergrundlaufs, den sie angestoßen hat.

- **Request-ID:** Jede Anfrage bekommt eine Kennung aus 32 Hexadezimalzeichen. Das Frontend
  übernimmt eine mitgeschickte Kennung (`X-Request-Id`) nur, wenn sie 8 bis 64 Zeichen aus
  Buchstaben, Ziffern, `-` und `_` hat; jede andere ersetzt es, ebenso das Backend bei einem Aufruf
  ohne Frontend. Dieselbe Kennung steht am Ende der Zeile im Zugriffslog des Frontends, in jeder
  Logzeile des Backends zur Anfrage, auch aus Hintergrundaufgaben, die sie startet, im
  Antwort-Header `X-Request-Id` und im Feld `requestId` jeder Fehlerantwort. Logzeilen einzelner
  Hilfs-Threads, etwa der Zeitüberwachung von Modellaufrufen oder des Mailversands, tragen sie
  nicht.
- **Im Log:** Im Textformat steht sie in eckigen Klammern vor dem Loggernamen, in `ecs`, `logstash`
  und `gelf` als Feld `requestId`. Dort tragen Logzeilen eines Indexierungslaufs zusätzlich dessen
  Kennung (`indexingRunId`), auch ein geplanter Lauf ohne Anfrage.
- **Im Revisionsprotokoll:** Ein Eintrag ohne eigene Vorgangskennung trägt die Request-ID als
  `request:<id>` in der Korrelationsangabe. Die Ausleitung an ein Sicherheitsmonitoring sendet sie
  nicht. Schickt ein Aufrufer dieselbe gültige Kennung mehrfach, erscheinen Einträge verschiedener
  Anfragen in der Suche nach Vorgang zusammen.
- **Personenbeziehbar über Verknüpfung:** Für sich nennt die Kennung niemanden. Das Zugriffslog
  enthält aber die Client-Adresse, manche Logzeilen des Backends die Kennung eines Kontos und das
  Revisionsprotokoll die handelnde Person; über die Request-ID lassen sich diese Zeilen verbinden.
  Wie lange die Logs liegen, bestimmt die Log-Sammlung des Betriebs, nicht OPAA; solange das
  Zugriffslog liegt, führt jeder Revisionseintrag über die Request-ID zur Client-Adresse. Die
  Muster-Dienstvereinbarung lässt die Verknüpfung nur zur Fehlerbehebung an einer einzelnen
  gemeldeten Anfrage und in der anlassbezogenen Klärung zu; das Revisionsprotokoll wird nur in der
  Klärung einbezogen.
- **Tracing**, ab Werk aus: Mit `OPAA_TRACING_ENABLED=true` sendet das Backend Spans über OTLP/HTTP
  an einen Collector (`OPAA_TRACING_OTLP_ENDPOINT`, Pflicht, sonst bricht der Start ab), über den
  [Ausgangsproxy](netz-und-sicherheit.md#ausgangsproxy), falls einer eingerichtet ist. Ausgewählt
  wird nur ein kleiner Teil der Anfragen (`OPAA_TRACING_SAMPLING_PROBABILITY`). Einen mitgeschickten
  `traceparent` verwerfen Frontend und Backend, die Auswahl trifft OPAA über eine selbst erzeugte
  Trace-ID. Ein Trace endet an der Installation: Kein ausgehender Aufruf an Modelle, Quellen oder
  Mailserver trägt einen `traceparent`, und keine Trace-ID erscheint in den Metriken. Ist das
  Tracing aus, verlässt nichts die Installation.
- **Inhalt eines Spans:** Name, Dauer, Methode, Routenmuster mit Platzhaltern statt IDs, Status,
  Art des Fehlers, Modell und Tokenzahlen eines Modellaufrufs, Art der Datenbankoperation. Nicht
  enthalten sind vollständige URL mit Parametern, Konto, SQL-Anweisung, Prompt, Antwort,
  Fehlermeldung und Ereignisse. Den Aufruf `PUT /api/v1/me/quick-tour-hint` erfasst kein Span.
- **Collector:** Er erhält die Trace-ID, die in den Formaten `ecs`, `logstash` und `gelf` auch in
  den Logzeilen steht, und ist damit ein weiterer Zugriffsweg auf die Verknüpfung. Zugriff und Aufbewahrung regelt der Betrieb dort wie beim Log.

| Variable | Standard | Bedeutung |
|---|---|---|
| `OPAA_TRACING_ENABLED` | `false` | Export der Spans ein |
| `OPAA_TRACING_OTLP_ENDPOINT` | — | Adresse des Collectors mit Pfad, etwa `http://otel-collector.monitoring:4318/v1/traces` |
| `OPAA_TRACING_SAMPLING_PROBABILITY` | `0.05` | Anteil der erfassten Anfragen, 0.0 bis 1.0 |

Unter Kubernetes stehen die drei Werte unter `tracing` im Helm-Chart; `tracing.egress` öffnet den
Weg zum Collector, solange das Tracing an ist ([Kubernetes](kubernetes.md)).

## Diagnose ohne Shell

Actuator-Pfade sind nur über den Management-Port erreichbar, nicht über das Frontend. Unter Docker
Compose ist das `localhost:8082` auf dem Host (`OPAA_BACKEND_MANAGEMENT_PORT`), unter Kubernetes
die Portweiterleitung `kubectl port-forward deploy/opaa-backend 8081:8081`; die Beispiele nennen
den Port von Kubernetes:

```bash
curl -s http://localhost:8081/actuator/info              # Version
curl -s http://localhost:8081/actuator/health/readiness  # Readiness
curl -s http://localhost:8081/actuator/prometheus        # Metriken
curl -s -H "Authorization: Bearer <token>" http://localhost:8081/actuator/health/embedding-model
curl -s -H "Authorization: Bearer <token>" https://<host>/api/v1/admin/diagnostics/client-address
```

`<token>` ist das Token einer angemeldeten Sitzung, etwa aus dem Browser. Die Health Groups außer
Readiness und Liveness verlangen ein Token; Einzelheiten wie Anbieter, Adresse oder Fehler zeigen
sie nur der Systemverwaltung, jedem anderen Konto nur den Status. `embedding-model` und
`vector-store` rufen das Modell bzw. die Vektorablage wirklich auf und geben ihr Ergebnis danach
kurz zwischengespeichert aus, sodass häufige Abfragen keine Kosten beim Modellanbieter erzeugen.

Der letzte Aufruf (Systemverwaltung, über den Proxy) zeigt, welche Client-Adresse das Backend für
diese Anfrage ermittelt (`clientAddress`). Steht dort die Adresse des Proxys, fehlt er in
`OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS`. Dumps und Dateien aus dem Container:
[Docker Compose](docker-compose.md#diagnose-im-container), [Kubernetes](kubernetes.md#fehlersuche).

## Fehlerbehebung

| Bild | Wo weiter |
|---|---|
| Start bricht ab und nennt eine Variable | [Konfiguration](../referenz/konfiguration.md); bei `OPAA_AUTH_JWT_SECRET` [Authentifizierung](authentifizierung.md#das-backend-startet-nicht-und-nennt-opaa_auth_jwt_secret) |
| Start bricht mit Hinweis auf pgvector, Schema oder Migration ab | [Datenbank](datenbank.md) |
| Start bricht mit `refuses to start` ab | [Konfigurationsabdruck](mehrere-instanzen.md#gleiche-konfiguration-der-konfigurationsabdruck) |
| Readiness meldet `DOWN` | Datenbank, Connection Pool ([Readiness und Liveness](images-und-laufzeit.md#readiness-und-liveness)) |
| Indexierung oder Chat schlagen fehl | [Modelle](modell-endpunkte.md#fehlerbilder) |
| Niemand kommt herein, Anmeldung mit `429` | [Authentifizierung](authentifizierung.md#fehlerbehebung) |
| Originale lassen sich nicht abrufen oder hochladen | [Originalablage](originalablage.md#wenn-etwas-nicht-stimmt) |
| Mails kommen nicht an | [E-Mail-Versand](../administration/e-mail.md#fehlerbilder) |
| Im SIEM kommt nichts an, Log meldet `SIEM export … failed` | Adresse, Port, TLS-Vertrauen des Empfängers ([Ausleitung an ein Sicherheitsmonitoring](#ausleitung-an-ein-sicherheitsmonitoring)) |
| Lange Antworten enden mit `504` | Timeouts von Frontend und Proxy ([Netzwerkzugang](netz-und-sicherheit.md#netzwerkzugang)) |
| Verbindungen nach außen scheitern mit Proxy-Status | [Ausgangsproxy](netz-und-sicherheit.md#ausgangsproxy) |
| Container- oder Pod-Fehlerbilder | [Docker Compose](docker-compose.md#fehlerbehebung), [Kubernetes](kubernetes.md#fehlersuche) |
