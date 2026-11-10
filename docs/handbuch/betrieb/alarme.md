# Alarme

Jeder Alarm, den OPAA mitbringt, hat hier seinen Abschnitt: was ausfällt, wer sich kümmert, die drei
ersten Prüfungen, was nachts reicht und wann man ihn stummschaltet. Die Annotation `runbook_url`
jedes Alarms zeigt auf seinen Abschnitt. Wie die Alarme in Prometheus und Grafana kommen, steht unter
[Dashboards und Alarme](dashboards-und-alarme.md).

## Schweregrade und Bereiche

| `severity` | Bedeutung | Zustellung |
|---|---|---|
| `critical` | Personen können OPAA nicht oder kaum benutzen, oder Daten gehen verloren | weckt die Bereitschaft |
| `warning` | eine Funktion ist gestört oder wird es bald; Personen merken es noch kaum | Ticket für den nächsten Arbeitstag |
| `info` | ein schleichender Trend oder ein Einzelereignis zum Nachsehen | Dashboard, keine Benachrichtigung |

Das Label `component` sagt, welcher Bereich betroffen ist, und taugt als Schlüssel für die Verteilung:
`platform` (Pods, Datenbank, Anmeldung), `chat`, `search`, `model`, `indexing`, `directory`
(Verzeichnisabgleich), `background` (geplante Läufe, Mails), `audit` (Revisionsprotokoll) und
`security`. Weckende Alarme stützen sich auf Metriken von OPAA selbst, nicht auf Modellnamen oder
Werte eines Anbieters.

Jede Gruppe lässt sich abschalten; die Schwellen sind Werte des Charts
([Alarme im Chart](kubernetes.md#alarme)).

## Wenig Verkehr, fehlende und unlesbare Werte

- **Mindestvolumen:** Quoten wie Fehleranteil, leere Suchen oder gescheiterte Mails zählen erst ab
  einer Mindestzahl von Vorgängen im Fenster. Eine einzelne gescheiterte Frage am Sonntagmorgen
  weckt niemanden.
- **Unlesbare Werte:** Werte, die OPAA aus der Datenbank liest (Warteschlangen, Bestand,
  Instanzregister, Sperren, Revisionsprotokoll), sind `NaN`, wenn die Abfrage scheitert oder länger
  als zehn Sekunden braucht. Ein Vergleich mit `NaN` ist nie wahr; Alarme auf diesen Wert schweigen
  deshalb. Das meldet [OpaaMetricUnreadable](#OpaaMetricUnreadable); die Alarme des
  Revisionsprotokolls feuern bei `NaN` selbst. Ausgenommen sind die Texterkennung, solange sie aus
  ist, und private Bibliotheken unter der Mindestgruppengröße: Dort ist `NaN` gewollt.
- **Fehlende Werte:** Fehlen alle Metriken eines Backends, feuert
  [OpaaBackendMetricsMissing](#OpaaBackendMetricsMissing); die Alarme auf Werte von OPAA schweigen
  dann. Die Alarme des Revisionsprotokolls melden auch eine fehlende Reihe, solange Prometheus das
  Backend erreicht.

## Folgealarme unterdrücken

Ein Ausfall zieht weitere Alarme nach sich. Mit einem Alertmanager unterdrückt dieses Rezept die
Folgen und lässt die Ursache stehen:

```yaml
inhibit_rules:
  # Ohne Backend zählt nur noch der Ausfall selbst.
  - source_matchers: [alertname="OpaaBackendNotReady", severity="critical"]
    target_matchers: [component=~"chat|search|model|indexing|directory|background|audit|security"]
  - source_matchers: [alertname="OpaaBackendNotReady", severity="critical"]
    target_matchers: [alertname="OpaaBackendMetricsMissing"]
  # Ohne Chat- oder Embedding-Modell scheitern Fragen, das sagt der Modellalarm schon.
  - source_matchers: [alertname="OpaaModelRoleDown", role=~"chat|embedding"]
    target_matchers: [alertname=~"OpaaHighErrorRate|OpaaChatSloBurn|OpaaTimeToFirstTokenHigh|OpaaRetrievalMostlyEmpty"]
  # Ein kritischer Alarm verdrängt seine eigene Warnstufe.
  - source_matchers: [severity="critical"]
    target_matchers: [severity="warning"]
    equal: [alertname]
```

Laufen mehrere Installationen gegen einen Alertmanager, bekommt jede Regel zusätzlich `equal` auf
ein Label, das die Installation trennt, etwa ein externes Label ihres Prometheus.

## Plattform

<a id="OpaaBackendNotReady"></a>

### OpaaBackendNotReady

- **Auswirkung:** `critical`: kein Backend-Pod bereit, Anmeldung und Fragen gehen nicht. `warning`:
  weniger bereite Pods als gewünscht, der Rest trägt die Last.
- **Zuständig:** Betrieb der Plattform.
- **Prüfen:** 1. Log und Ereignisse des Pods (`kubectl describe pod`), Startabbruch mit Namen der
  Variable? 2. Datenbank erreichbar, `/actuator/health/readiness`? 3. OOM-Kill oder CrashLoop, Speicher-Limit?
- **Nachts:** `critical` sofort; `warning` reicht am Morgen, solange ein Pod bereit ist.
- **Stummschalten:** während eines geplanten Wartungsfensters. Ein mit `backend.replicas: 0`
  angehaltenes Backend schweigt von selbst.

<a id="OpaaBackendMetricsMissing"></a>

### OpaaBackendMetricsMissing

- **Auswirkung:** Prometheus fragt weniger Backend-Pods ab, als laufen sollen; alle Alarme auf Werte
  von OPAA sind für diese Pods blind.
- **Zuständig:** Betrieb der Überwachung.
- **Prüfen:** 1. Laufen die Pods? 2. `ServiceMonitor` und dessen Labels für den Prometheus.
  3. `metrics.scrapeFrom` und NetworkPolicy zum Management-Port.
- **Nachts:** reicht am Morgen, außer zusammen mit `OpaaBackendNotReady`.
- **Stummschalten:** nicht; ohne Metriken sieht man nichts anderes.

<a id="OpaaFrontendNotReady"></a>

### OpaaFrontendNotReady

- **Auswirkung:** `critical`: die Oberfläche ist nicht erreichbar, auch wenn das Backend läuft.
  `warning`: ein Teil der Frontend-Pods fehlt.
- **Zuständig:** Betrieb der Plattform.
- **Prüfen:** 1. Ereignisse des Pods: Image ziehbar, Readiness Probe? 2. Log des nginx.
  3. Zuletzt geänderte Werte unter `frontend` oder des Eingangs.
- **Nachts:** `critical` sofort, `warning` am Morgen.
- **Stummschalten:** im Wartungsfenster.

<a id="OpaaHeapUsageHigh"></a>

### OpaaHeapUsageHigh

- **Auswirkung:** Ein Pod hält nach der Speicherbereinigung fast seinen ganzen Heap belegt; bald
  folgen lange Pausen oder ein OOM-Kill.
- **Zuständig:** Betrieb der Plattform.
- **Prüfen:** 1. Kurve „Heap nach der Speicherbereinigung“ im Dashboard „Betrieb & Bereitschaft“:
  wächst sie stetig? 2. Laufende Indexierung großer Dateien oder viele gleichzeitige Agentenläufe?
  3. Speicher-Limit des Pods und Heap-Anteil.
- **Nachts:** reicht am Morgen; Kubernetes startet einen Pod nach einem OOM-Kill neu.
- **Stummschalten:** nicht; Speicher-Limit oder Heap-Anteil anheben.

<a id="OpaaDatabasePoolTimeouts"></a>

### OpaaDatabasePoolTimeouts

- **Auswirkung:** Anfragen eines Pods haben vergeblich auf eine Datenbankverbindung gewartet und
  sind gescheitert.
- **Zuständig:** Betrieb der Plattform mit der Datenbankbetreuung.
- **Prüfen:** 1. Kurve „Datenbankverbindungen“: alle belegt? 2. Lange Abfragen und offene
  Transaktionen in `pg_stat_activity`. 3. Reichen `max_connections` der Datenbank für alle Pods
  ([Verbindungen bei mehreren Instanzen](datenbank.md#verbindungen-bei-mehreren-instanzen))?
- **Nachts:** reicht am Morgen, außer zusammen mit `OpaaHighErrorRate` `critical`.
- **Stummschalten:** nicht.

<a id="OpaaInstanceHeartbeatStale"></a>

### OpaaInstanceHeartbeatStale

- **Auswirkung:** Ein Pod schreibt seinen Herzschlag ins Instanzregister nicht mehr; die anderen
  halten ihn bald für ausgefallen und übernehmen seine Arbeit
  ([Das Instanzregister](mehrere-instanzen.md#das-instanzregister)).
- **Zuständig:** Betrieb der Plattform.
- **Prüfen:** 1. Antwortet die Datenbank langsam? 2. Lange Pausen der Speicherbereinigung im Pod?
  3. Log des Pods nach Fehlern beim Herzschlag.
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht.

<a id="OpaaInvalidationBusDisconnected"></a>

### OpaaInvalidationBusDisconnected

- **Auswirkung:** Ein Pod hört die Meldungen zu geänderten Zwischenspeichern nicht und liest an ihnen
  vorbei aus der Datenbank: richtig, aber langsamer
  ([Änderungen erreichen jede Instanz](mehrere-instanzen.md#änderungen-erreichen-jede-instanz)).
- **Zuständig:** Betrieb der Plattform.
- **Prüfen:** 1. Log des Pods zum Kanal der Cache-Meldungen. 2. Steht ein Connection Pooler im
  Transaktionsmodus zwischen Backend und Datenbank? 3. Neustarts oder Failover der Datenbank.
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht.

<a id="OpaaVersionMix"></a>

### OpaaVersionMix

- **Auswirkung:** Instanzen verschiedener Versionen laufen seit einer halben Stunde nebeneinander;
  ein Rolling Update hängt.
- **Zuständig:** wer das Update eingespielt hat.
- **Prüfen:** 1. `kubectl rollout status` des Backends. 2. Startet der neue Pod, oder bricht er ab?
  3. [Update ohne Ausfall](mehrere-instanzen.md#update-ohne-ausfall).
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht; das Update zu Ende bringen oder zurücknehmen.

<a id="OpaaMetricUnreadable"></a>

### OpaaMetricUnreadable

- **Auswirkung:** Kein Pod kann den Wert im Label `metric` aus der Datenbank lesen; Alarme auf diesen
  Wert schweigen so lange.
- **Zuständig:** Betrieb der Plattform.
- **Prüfen:** 1. Log der Pods: die Warnung nennt Wert und Fehler. 2. Antwortet die Datenbank binnen
  zehn Sekunden? 3. Hat eine Migration oder ein Rücksichern Rechte oder Tabellen verändert?
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht.

<a id="OpaaIdentityProviderRejected"></a>

### OpaaIdentityProviderRejected

- **Auswirkung:** Der Identitätsanbieter oder der Verzeichnisdienst (Label `target`) lehnt die
  Anmeldung von OPAA ab; Anmeldungen über den Anbieter oder der Verzeichnisabgleich scheitern.
- **Zuständig:** Betrieb mit der Betreuung des Identitätsanbieters.
- **Prüfen:** 1. Client-Geheimnis oder Verzeichniszugang abgelaufen oder geändert
  ([Anbieterverwaltung](authentifizierung.md#anbieterverwaltung))? 2. Log nach der Antwort des
  Anbieters. 3. Hat der Anbieter seine Adresse oder den Client geändert?
- **Nachts:** sofort; ohne Anbieter kommen nur lokale Konten herein.
- **Stummschalten:** während einer angekündigten Wartung des Anbieters.

<a id="OpaaHighErrorRate"></a>

### OpaaHighErrorRate

- **Auswirkung:** Ein merklicher (`warning`) oder großer (`critical`) Teil der Anfragen endet mit
  einem Status 5xx.
- **Zuständig:** Betrieb der Plattform.
- **Prüfen:** 1. Kurve „Fehlerantworten (5xx) je Pod“: ein Pod oder alle? 2. Feuert
  `OpaaModelRoleDown`? Auch die `503` einer Installation ohne Chat- oder Embedding-Modell zählen
  mit ([Ohne Modelle installiert](installation.md#ohne-modelle-installiert)). 3. Log nach dem Fehler
  der häufigsten Route.
- **Nachts:** `critical` sofort, `warning` am Morgen.
- **Stummschalten:** solange eine Installation bewusst ohne Modelle läuft.

## Chat und Suche

<a id="OpaaChatSloBurn"></a>

### OpaaChatSloBurn

- **Auswirkung:** Es scheitern mehr Fragen, als das Ziel für 30 Tage erlaubt. `critical`: in einer
  Stunde mit 14,4-facher Geschwindigkeit, das Budget ist in zwei Tagen weg. `warning`: sechs Stunden
  lang sechsfach. `info`: drei Tage lang über dem Ziel. Abgewiesene Fragen (fehlende Berechtigung,
  ungültige Eingabe) zählen nicht.
- **Zuständig:** Betrieb, bei Modellursache mit der Modellbetreuung.
- **Prüfen:** 1. Feuert `OpaaModelRoleDown` oder `OpaaOutboundThrottled`? 2. Dashboard „KI-Modelle
  & Kosten“: Ausgehende Aufrufe nach Ergebnis. 3. Log nach Fehlern der Fragen.
- **Nachts:** `critical` sofort, die anderen am Morgen.
- **Stummschalten:** während eines angekündigten Ausfalls des Modells.

<a id="OpaaTimeToFirstTokenHigh"></a>

### OpaaTimeToFirstTokenHigh

- **Auswirkung:** Antworten zeigen ihr erstes Wort spät; Personen warten oder brechen ab.
- **Zuständig:** Modellbetreuung.
- **Prüfen:** 1. Dauer je Stufe einer Frage: Suche, Neubewertung oder Antwort des Modells?
  2. Auslastung des Modelldienstes. 3. Lange Chats mit großem Eingabekontext.
- **Nachts:** nichts.
- **Stummschalten:** nicht nötig, der Alarm benachrichtigt nicht.

<a id="OpaaRetrievalMostlyEmpty"></a>

### OpaaRetrievalMostlyEmpty

- **Auswirkung:** Fast keine Frage mit Suche findet eine Fundstelle; Antworten kommen ohne Belege.
- **Zuständig:** Betrieb mit der Modellbetreuung.
- **Prüfen:** 1. Embedding-Modell erreichbar, Health Groups `embedding-model` und `vector-store`?
  2. Läuft gerade eine Neueinbettung nach einem Modellwechsel
  ([Modell wechseln](modell-endpunkte.md#modell-wechseln))? 3. Ist der Bestand im Dashboard
  „Wissensbestand & Bibliotheken“ gesunken?
- **Nachts:** sofort.
- **Stummschalten:** während einer geplanten Neueinbettung.

## Modelle

<a id="OpaaModelRoleDown"></a>

### OpaaModelRoleDown

- **Auswirkung:** Kein Pod erreicht den Dienst der Rolle im Label `role`. `critical` für Chat und
  Embedding, solange Fragen gestellt werden: Fragen scheitern. `warning` für Neubewertung,
  Spracherkennung, Texterkennung und Bildbeschreibung, solange OPAA sie aufruft: die Funktion fällt
  aus, der Rest läuft ([Ausfall eines Modells](modell-endpunkte.md#ausfall-eines-modells)).
- **Zuständig:** Modellbetreuung.
- **Prüfen:** 1. Läuft der Modelldienst, ist er aus dem Cluster erreichbar (Ausgangsproxy,
  NetworkPolicy)? 2. Log nach der Antwort des Dienstes. 3. Geänderte Adresse oder Zugangsschlüssel
  in der Modellverwaltung.
- **Nachts:** `critical` sofort, `warning` am Morgen.
- **Stummschalten:** während einer angekündigten Wartung des Dienstes.

<a id="OpaaOutboundAuthFailing"></a>

### OpaaOutboundAuthFailing

- **Auswirkung:** Aufrufe an ein Ziel (Label `target`) scheitern an Anmeldung oder TLS: Modelle
  (`component` `model`), Quellen und Originalablage (`indexing`) oder Virenscanner (`security`).
- **Zuständig:** wer das Ziel betreut; für Quellen die Verwaltung der Bibliothek.
- **Prüfen:** 1. Zugangsschlüssel oder Zugang abgelaufen? 2. Zertifikat des Ziels abgelaufen oder
  von einer Stelle, die der Truststore nicht kennt
  ([Eigene Zertifizierungsstelle](kubernetes.md#eigene-zertifizierungsstelle))? 3. Log nach der
  Antwort des Ziels.
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht; ein dauerhaft entzogener Zugang wird in OPAA entfernt.

<a id="OpaaOutboundThrottled"></a>

### OpaaOutboundThrottled

- **Auswirkung:** Ein Anbieter drosselt Aufrufe, auf die eine Person wartet; Antworten verzögern
  sich oder scheitern.
- **Zuständig:** Modellbetreuung.
- **Prüfen:** 1. Kurve „Gedrosselte Aufrufe“: welches Ziel? 2. Kontingent beim Anbieter. 3. Läuft
  zugleich eine große Indexierung gegen denselben Anbieter?
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht; Kontingent anheben oder Last verteilen.

<a id="OpaaTokenBudgetForecast"></a>

### OpaaTokenBudgetForecast

- **Auswirkung:** Beim Tempo der letzten sieben Tage verbraucht das Chat-Modell in 30 Tagen mehr
  Tokens als `metrics.prometheusRule.monthlyTokenBudget`. Nur mit gesetztem Budget.
- **Zuständig:** wer das Budget verantwortet.
- **Prüfen:** 1. Kurve „Tokens pro Stunde nach Zweck“: Antworten, Agenten oder Zerlegung?
  2. Neue Nutzung, etwa externe KI-Werkzeuge? 3. Ist das Budget noch richtig?
- **Nachts:** nichts.
- **Stummschalten:** nach einer bewussten Entscheidung für mehr Verbrauch; besser das Budget anheben.

## Indexierung

<a id="OpaaIndexingStalled"></a>

### OpaaIndexingStalled

- **Auswirkung:** Ein Indexierungslauf in einem Pod ist aktiv, verarbeitet aber kein Dokument mehr;
  neue und geänderte Dokumente bleiben unauffindbar.
- **Zuständig:** Betrieb mit der Verwaltung der Bibliothek.
- **Prüfen:** 1. Laufstatus und Laufprotokoll der Bibliothek
  ([Laufprotokoll](../referenz/wie-indexierung-arbeitet.md#laufprotokoll)). 2. Antwortet die Quelle?
  3. Embedding-Modell erreichbar?
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht. Ein Upload während eines hängenden Laufs verschiebt den Alarm um eine
  Frist.

<a id="OpaaLibrariesOverdue"></a>

### OpaaLibrariesOverdue

- **Auswirkung:** Bibliotheken eines Konnektortyps (Label `connector`) haben seit mehr als zwei
  ihrer Intervalle keinen erfolgreichen Lauf; ihr Inhalt veraltet.
- **Zuständig:** Verwaltung der Bibliotheken, bei allen eines Typs der Betrieb.
- **Prüfen:** 1. Laufprotokoll einer betroffenen Bibliothek. 2. Feuert `OpaaOutboundAuthFailing` für
  `connector`? 3. Staut sich die Warteschlange `indexing`?
- **Nachts:** reicht am Morgen.
- **Stummschalten:** solange eine Quelle angekündigt abgeschaltet ist.

<a id="OpaaIndexingRunsFailing"></a>

### OpaaIndexingRunsFailing

- **Auswirkung:** Ein großer Teil der Läufe eines Konnektortyps scheitert.
- **Zuständig:** Betrieb mit der Verwaltung der Bibliotheken.
- **Prüfen:** 1. Kurve „Gescheiterte Läufe nach Grund“. 2. Laufprotokoll einer gescheiterten
  Bibliothek. 3. Zugang, Zertifikat oder Adresse der Quelle geändert?
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht.

## Verzeichnisabgleich

<a id="OpaaDirectorySyncStale"></a>

### OpaaDirectorySyncStale

- **Auswirkung:** Der Abgleich mit dem Verzeichnis eines Anbieters (Label `provider`) wurde länger
  als einen Tag nicht angewendet; Ausgeschiedene behalten so lange ihre Gruppen und Rechte. Wartet
  ein Plan auf Bestätigung, meldet das stattdessen `OpaaDirectorySyncPlanPending`.
- **Zuständig:** Systemverwaltung mit dem Betrieb.
- **Prüfen:** 1. Seite Verzeichnisabgleich: letzter Lauf und Meldung
  ([Verzeichnisabgleich je Anbieter](authentifizierung.md#verzeichnisabgleich-je-anbieter)).
  2. Verzeichniszugang hinterlegt und gültig? 3. Feuert `OpaaIdentityProviderRejected`?
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht.

<a id="OpaaDirectorySyncPlanPending"></a>

### OpaaDirectorySyncPlanPending

- **Auswirkung:** Ein Abgleich würde mehr entziehen, als die Plausibilitätsschwelle erlaubt, und
  wartet seit einem Tag auf die Systemverwaltung; bis dahin gilt der alte Stand.
- **Zuständig:** Systemverwaltung.
- **Prüfen:** 1. Plan auf der Seite Verzeichnisabgleich ansehen. 2. Stimmt die Änderung im
  Verzeichnis (Umorganisation, Fehler beim Anbieter)? 3. Bestätigen oder verwerfen.
- **Nachts:** nichts.
- **Stummschalten:** nicht; entscheiden.

## Hintergrund

<a id="OpaaScheduledJobOverdue"></a>

### OpaaScheduledJobOverdue

- **Auswirkung:** Ein geplanter Lauf (Label `job`) ist seit mehreren Intervallen auf keinem Pod
  gelungen, auch einer, der seit dem Start nie gelang.
- **Zuständig:** Betrieb der Plattform.
- **Prüfen:** 1. Log nach dem Namen des Laufs. 2. Kurve „Instanzen und Sperren“: hält eine Sperre
  seit Stunden ([Geplante Läufe laufen genau einmal](mehrere-instanzen.md#geplante-läufe-laufen-genau-einmal))?
  3. Ausfall einer Instanz, die die Sperre hielt?
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht.

<a id="OpaaQueueBacklog"></a>

### OpaaQueueBacklog

- **Auswirkung:** Der älteste Eintrag einer Warteschlange (Label `queue`) wartet zu lange:
  `mail_outbox` Mails, `source_events` gemeldete Änderungen der Quellen, `indexing` angenommene
  Läufe einschließlich des längsten laufenden, `ocr_backlog` Scans für die Texterkennung.
- **Zuständig:** `mail_outbox` Betrieb mit dem Mailserver, sonst Betrieb mit der Verwaltung der
  Bibliotheken.
- **Prüfen:** 1. Kurve „Warteschlangen“: wächst das Alter stetig? 2. Der Dienst dahinter: Mailserver
  ([E-Mail-Versand](../administration/e-mail.md)), Quelle, Embedding-Modell oder
  [Texterkennung](texterkennung.md). 3. Ein einzelner sehr großer Lauf?
- **Nachts:** reicht am Morgen.
- **Stummschalten:** während eines geplanten großen Erstlaufs für `indexing`.

<a id="OpaaMailDeliveryFailing"></a>

### OpaaMailDeliveryFailing

- **Auswirkung:** Die meisten Mails sind endgültig gescheitert; Einladungen, Rücksetzlinks und
  Benachrichtigungen kommen nicht an.
- **Zuständig:** Betrieb mit dem Mailserver.
- **Prüfen:** 1. Erreichbarkeit und Zugang des Mailservers. 2. Log nach der Antwort des Servers.
  3. Absenderadresse oder Relay-Regeln geändert?
- **Nachts:** reicht am Morgen.
- **Stummschalten:** während einer angekündigten Wartung des Mailservers.

## Revisionsprotokoll

<a id="OpaaAuditRetentionOverdue"></a>

### OpaaAuditRetentionOverdue

- **Auswirkung:** Die automatische Löschung des Revisionsprotokolls ist seit mehr als 35 Tagen nicht
  gelaufen (sie läuft am 1. jedes Monats um 03:00), oder ihr Stand ist nicht lesbar; Einträge
  bleiben länger als die Frist.
- **Zuständig:** Betrieb, Bericht an Datenschutz und Revision.
- **Prüfen:** 1. Log der Pods um den 1. des Monats, 03:00. 2. Sperre `audit-retention`.
  3. Rechte des Datenbankkontos nach einem Rücksichern.
- **Nachts:** nichts.
- **Stummschalten:** nicht.

<a id="OpaaAuditPayloadDropped"></a>

### OpaaAuditPayloadDropped

- **Auswirkung:** OPAA hat Einträge einer Ereignisart (Label `event_type`) ohne Angaben geschrieben,
  die der Ereigniskatalog nicht nennt. Ein Programmfehler; der Vorgang selbst ist durchgeführt.
- **Zuständig:** Betrieb, Fund an das Projekt melden.
- **Prüfen:** 1. Log nach „Dropped audit payload keys“. 2. Ereignisart und Version notieren.
  3. Fund melden.
- **Nachts:** nichts.
- **Stummschalten:** nach der Meldung bis zur behebenden Version.

<a id="OpaaAuditChainStalled"></a>

### OpaaAuditChainStalled

- **Auswirkung:** Neue Einträge bekommen keinen Kontrollpunkt der Prüfsummenkette
  ([Prüfsummenkette und Monatssiegel](nachvollziehbarkeit.md#prüfsummenkette-und-monatssiegel)), oder
  der Stand der Kette ist nicht lesbar. Beim ersten Start einer großen Installation auch, bis der
  Lauf den Bestand aufgeholt hat.
- **Zuständig:** Betrieb, Bericht an die Revision.
- **Prüfen:** 1. Log der Pods. 2. Sperre `audit-chain`. 3. Offene Transaktionen in
  `pg_stat_activity`.
- **Nachts:** nichts.
- **Stummschalten:** beim Aufholen nach dem ersten Start.

<a id="OpaaAuditChainUnsealed"></a>

### OpaaAuditChainUnsealed

- **Auswirkung:** Ein abgeschlossener Protokollmonat trägt kein Siegel: Der Lauf kommt nicht bis zum
  Monatsende, oder die Siegelzeile eines Monats wurde entfernt, dessen Eintrag
  `AUDIT_LOG_MONTH_SEALED` noch steht.
- **Zuständig:** Betrieb mit der Revision.
- **Prüfen:** 1. Log der Pods nach „no seal“. 2. `GET /api/v1/audit/chain/{Monat}` für den Vormonat.
  3. Steht dort `BROKEN`, prüft die Revision den Befund.
- **Nachts:** nichts.
- **Stummschalten:** für einen dauerhaft `BROKEN` Monat nach dem Befund der Revision, gezielt für
  diesen Monat; der Lauf versiegelt ihn nicht neu.

<a id="OpaaAuditLogHorizon"></a>

### OpaaAuditLogHorizon

- **Auswirkung:** Der letzte angelegte Protokollmonat endet bald. Danach scheitert jeder Vorgang,
  der ins Revisionsprotokoll schreibt. Die Monate liegen weit im Voraus an; der Alarm heißt, die Uhr
  der Datenbank steht falsch oder Protokollmonate wurden entfernt.
- **Zuständig:** Betrieb mit der Datenbankbetreuung.
- **Prüfen:** 1. Uhrzeit der Datenbank und der Pods. 2. Wurden Tabellen des Protokolls von Hand
  geändert oder unvollständig zurückgesichert? 3. Fund an das Projekt melden.
- **Nachts:** `critical` sofort, `warning` am Morgen.
- **Stummschalten:** nicht.

## Sicherheit

<a id="OpaaMalwareScanFailing"></a>

### OpaaMalwareScanFailing

- **Auswirkung:** Die Virenprüfung scheitert bei den meisten Dateien; diese werden nicht aufgenommen
  ([Virenprüfung](netz-und-sicherheit.md#virenprüfung-der-aufgenommenen-dateien)).
- **Zuständig:** Betrieb des Virenscanners.
- **Prüfen:** 1. Läuft der Scanner, ist er erreichbar? 2. Größengrenze des Scanners. 3. Log nach der
  Antwort des Scanners.
- **Nachts:** reicht am Morgen.
- **Stummschalten:** während einer angekündigten Wartung des Scanners.

<a id="OpaaMalwareDetected"></a>

### OpaaMalwareDetected

- **Auswirkung:** Die Virenprüfung hat eine Datei abgewiesen; sie wurde nicht aufgenommen.
- **Zuständig:** Informationssicherheit.
- **Prüfen:** 1. Log nach dem Fund. 2. Woher kam die Datei (Upload oder Quelle)? 3. Ist die Quelle
  selbst befallen?
- **Nachts:** nichts.
- **Stummschalten:** nicht nötig, der Alarm benachrichtigt nicht.

<a id="OpaaMalwareSignaturesOutdated"></a>

### OpaaMalwareSignaturesOutdated

- **Auswirkung:** Die Signaturen des Virenscanners sind veraltet; neue Schadsoftware bleibt
  unerkannt.
- **Zuständig:** Betrieb des Virenscanners.
- **Prüfen:** 1. Log der Signaturaktualisierung des Scanners. 2. Erreicht der Scanner seine
  Spiegelquelle? 3. Ohne Internet: Spiegel im Haus aktuell?
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht.

<a id="OpaaGlobalRateLimitHit"></a>

### OpaaGlobalRateLimitHit

- **Auswirkung:** Anfragen sind an einer installationsweiten Grenze (Label `limit`) abgewiesen
  worden; an der Anmeldegrenze ein Hinweis auf verteiltes Ausprobieren von Zugangsdaten.
- **Zuständig:** Informationssicherheit mit dem Betrieb.
- **Prüfen:** 1. Kurve „Abgewiesen an einer Grenze“. 2. Zugriffslog des Eingangs nach
  Client-Adressen. 3. Stimmt die Client-Adresse hinter dem Proxy
  ([Vertraute Proxys](netz-und-sicherheit.md#vertraute-proxys-und-client-adresse))?
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht.

<a id="OpaaLoginFailureSpike"></a>

### OpaaLoginFailureSpike

- **Auswirkung:** Lokale Konten werden ein Vielfaches häufiger abgewiesen als zur selben Zeit in den
  Wochen davor; ein Hinweis auf das Ausprobieren von Zugangsdaten.
- **Zuständig:** Informationssicherheit.
- **Prüfen:** 1. Kurve „Abgewiesene Anmeldungen“: falsche Zugangsdaten oder gesperrte Konten?
  2. Zugriffslog nach Client-Adressen. 3. Feuert `OpaaGlobalRateLimitHit`?
- **Nachts:** reicht am Morgen; die Anmeldesperre wirkt ohne Zutun.
- **Stummschalten:** nach einem bekannten Anlass, etwa einer Passwortänderung für viele Konten.

<a id="OpaaTargetValidationTableFull"></a>

### OpaaTargetValidationTableFull

- **Auswirkung:** Die Zielprüfung bewacht so viele gespeicherte Ziele, wie die Konfiguration
  erlaubt; neue Ziele lassen sich bis zum Neustart nicht speichern. Eine volle Tabelle deutet auf
  Missbrauch ([Zielprüfung](netz-und-sicherheit.md#zielprüfung-abrufe-des-servers)).
- **Zuständig:** Informationssicherheit mit dem Betrieb.
- **Prüfen:** 1. Log nach dem Grund. 2. Wer hat zuletzt viele Ziele angelegt (Nachweisprotokoll)?
  3. Nach der Klärung die Pods neu starten.
- **Nachts:** reicht am Morgen.
- **Stummschalten:** nicht.

<a id="OpaaSiemExportLagging"></a>

### OpaaSiemExportLagging

- **Auswirkung:** Einträge des Revisionsprotokolls erreichen das Sicherheitsmonitoring verspätet
  ([Ausleitung](ueberwachung-und-fehlerbehebung.md#ausleitung-an-ein-sicherheitsmonitoring)).
- **Zuständig:** Betrieb mit dem Betrieb des Sicherheitsmonitorings.
- **Prüfen:** 1. Erreicht OPAA den Empfänger? 2. Log nach gescheiterten Zustellungen. 3. Offene
  Transaktionen in `pg_stat_activity`.
- **Nachts:** reicht am Morgen.
- **Stummschalten:** während einer angekündigten Wartung des Empfängers.

<a id="OpaaInstallationPending"></a>

### OpaaInstallationPending

- **Auswirkung:** Die Installation steht seit einer Stunde offen; wer das Installations-Token kennt,
  kann sie übernehmen ([Installations-Token](installation.md#installations-token)).
- **Zuständig:** wer installiert.
- **Prüfen:** 1. Installation abschließen. 2. Ist die Adresse von außen erreichbar? 3. Wer hatte
  Zugriff auf das Log mit dem Token?
- **Nachts:** reicht am Morgen, wenn die Adresse nicht von außen erreichbar ist.
- **Stummschalten:** nicht.
