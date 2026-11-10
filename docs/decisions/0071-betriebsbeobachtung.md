# ADR-0071: Betriebsbeobachtung — Metriken, Health und Korrelation ohne eigene Datenhaltung

## Status

Akzeptiert · zuletzt geändert 2026-11-10 · ergänzt ADR-0030, ADR-0053, ADR-0055, ADR-0059, ADR-0060 und ADR-0066

## Kontext

Betrieb und Behörde sollen Störungen erkennen, bevor Nutzer sie melden, und Kapazität planen, mit
den Bordmitteln eines üblichen Kubernetes-Monitorings. Actuator, Prometheus-Registry,
Health-Gruppen je Modellrolle, ServiceMonitor, Alarme und rund 40 eigene Metriken gab es bereits,
die Grundsätze dazu standen aber verstreut in ADR-0030 (Health-Gruppen), ADR-0053 (Metriken je
Pod), ADR-0055 und ADR-0059 (Metriken der Ausleitung und der Prüfsummenkette), ADR-0060
(Installationsmodus) und ADR-0066 (keine Suchbegriffe in Metriken). Es fehlten Latenzverteilungen
und eine Zuordnung der Reihen zu Anwendung und Instanz.

Drei Spannungen bestimmen die Entscheidung. Metriken sind eine Auswertungsmöglichkeit im Sinn der
Mitbestimmung: Eine Reihe je Person, Bibliothek oder Dokument macht aus einer Betriebszahl eine
Nutzungsauswertung. Die OpenTelemetry-Konventionen für generative KI (`gen_ai.*`) stehen im Status
„Development“ und werden noch umbenannt. Und die Health-Gruppen `embedding-model` und
`vector-store` lösen bei jedem Abruf einen echten, beim Anbieter bezahlten Modellaufruf aus; ihre
Details sah bisher jedes angemeldete Konto.

## Entscheidung

### 1. Auswertung nur über Prometheus und Grafana, keine eigene Datenhaltung

OPAA legt für Nutzung und Kosten keine Tabellen an, hat kein Auswertungscockpit in der Oberfläche
und wertet nichts je Person oder Organisationseinheit aus. Ausgewertet wird über die Metriken unter
`/actuator/prometheus` mit dem Prometheus und Grafana des Betriebs. Gauges dürfen vorhandene
Tabellen lesen (Tiefe einer Warteschlange, Zahl der Instanzen). Es gibt keine Zählung aktiver
Personen oder Chats je Zeitraum und keine Nutzung nach Kanal in feiner Zeitauflösung; Zählungen
bleiben installationsweit. Eine eigene Datenhaltung wäre ein zweites Auswertungssystem mit eigener
Frist, eigener Sichtbarkeit und eigenem Mitbestimmungsbedarf; der Betrieb hat Prometheus ohnehin.

### 2. Labels nur aus festen, kleinen Wertemengen

Ein Label nimmt nur Werte aus einer festen, kleinen Menge an: Modell, Anbieter, Rolle, Operation,
Konnektortyp, Ergebnisklasse, Stufe, Job- oder Queue-Name. Nie trägt eine Metrik Personen-, Space-,
Bibliotheks-, Ordner-, Dokument- oder Chat-Kennungen, Dateinamen, Pfade, Adressen, Suchbegriffe
oder Inhalte. Automatische Prüfungen weisen Tag-Schlüssel einer Sperrliste zurück, im Quelltext und
an jeder registrierten Metrik der laufenden Anwendung, dort auch Werte, die wie eine Kennung oder
eine Mailadresse aussehen. Für Labels, deren Werte die Verwaltung wählt (Modellname,
Werkzeugname), lässt die Anwendung höchstens 20 Werte je Label zu und verwirft weitere Reihen mit
einer Warnung im Log. Routen, deren Aufruf selbst etwas über eine Person aussagt, erscheinen in
keiner Metrik.

### 3. Namensschema: `opaa.*` eigen, `gen_ai.*` aus Spring AI

Eigene Metriken heißen `opaa.<bereich>.<größe>` in Micrometer-Schreibweise mit Basiseinheiten.
Die `gen_ai.*`-Metriken (`gen_ai.client.operation`, `gen_ai.client.token.usage`) liefert Spring AI
aus den Observations der Chat- und Embedding-Clients; OPAA baut sie nicht nach und benennt sie
nicht um, solange die OpenTelemetry-Konventionen dafür nicht veröffentlicht sind. Was sie nicht
unterscheiden, zählt OPAA selbst: `opaa.model.tokens` trägt den Zweck eines Aufrufs des
Chat-Modells (Antwort, Zerlegung, Notiz, Nebenaufgaben, Agent) und als `model` den Namen aus der
Modellverwaltung, nicht den Namen, den die Antwort des Anbieters nennt. Titel und Metadaten teilen
sich den Zweck Nebenaufgaben, damit keine Reihe die neuen Chats eines Zeitraums zählt. Jede
Inhalts-Property von Spring AI (`log-prompt`, `log-completion`, `log-query-response`,
`include-content`) steht in `application.yml` ausdrücklich auf `false`, damit ein geänderter
Standardwert der Bibliothek keine Inhalte in Observations, Logs oder Spans bringt; eine Prüfung
verlangt jede solche Property, die ein Spring-AI-Modul auf dem Klassenpfad deklariert.

### 4. Latenz als Histogramm mit SLO-Buckets, Reihen mit Anwendung, Host und Build

`http.server.requests`, `opaa.query.duration`, `opaa.query.time_to_first_token`,
`opaa.ocr.duration`, `gen_ai.client.operation` und `tasks.scheduled.execution` veröffentlichen
Histogramm-Buckets an festen SLO-Schwellen (`management.metrics.distribution.slo`), je Timer über
eine `OPAA_METRICS_SLO_*`-Variable einstellbar; die Long-Task-Timer daneben (`….active`) bleiben
ohne Buckets. Feste Buckets statt Perzentilen im Prozess, weil sich nur Buckets über Pods hinweg
aggregieren lassen, und statt des vollen Perzentil-Histogramms, weil wenige Buckets je Reihe die
Zahl der Reihen klein halten. Jede Reihe trägt `application` und `host` (Rechnername, unter Kubernetes der Pod); nicht `instance`,
weil Prometheus dieses Label selbst setzt und ein gleichnamiges umbenennt. `opaa.build.info`
(immer 1, mit `version` und `commit`) zeigt, welcher Build wo läuft, auch einen gemischten Stand
während eines Rolling Updates.

Das SLO der Chat-Fragen ist `success / (success + error)` aus `opaa.query.count`. Anfragefehler —
ungültige Eingabe, fehlende Berechtigung, nicht gefunden, archivierter Space, kein Chat-Modell
eingerichtet — zählen als `status=rejected`, ohne Dauer und ohne Abbruchursache, und bleiben
außerhalb des SLO, weil sie nichts über den Zustand des Dienstes sagen.

### 5. Management-Port, Health-Gruppen und Probe-Modell

Probes, Health und Metriken laufen auf einem eigenen Management-Port, unter Kubernetes und Docker
Compose als Vorgabe. Nur dort antworten die Metriken ohne Anmeldung; eine Netzregel öffnet ihn
allein dem Prometheus, und der API-Port kennt dann keine Actuator-Pfade. Die Readiness prüft
Prozess und Datenbank, sonst nichts. Jede Modellrolle, die Vektorablage, die Originalablage und
der Mailversand haben eine eigene Health-Gruppe außerhalb des Gesamtstatus (`SeparateHealthGroup`,
ADR-0030 Entscheidung 9), damit ein fremder Dienst keine Instanz aus der Rotation nimmt. Gruppen
außer den beiden Probes verlangen eine Anmeldung, Details sieht nur die Systemverwaltung
(`management.endpoint.health.roles`). Die echten Proben `embedding-model` und `vector-store` geben
ihr Ergebnis je Instanz kurz zwischengespeichert aus (`CachedProbe`), damit wiederholte Abrufe
keinen Modellaufruf je Abruf auslösen. Der Endpunkt-Cache von Spring Boot hilft dafür nicht: Er
speichert keine Operation mit Pfadselektor und hielte das Ergebnis je angemeldeter Person.

### 6. Jedes Feature plant seine Betriebsbeobachtung im Epic

Ein Epic legt im Abschnitt „Betriebsbeobachtung“ fest, welche Metriken das Feature braucht (Rate,
Fehler und Dauer seiner Anfragen, Auslastung seiner Ressourcen, bei Hintergrundjobs der Zeitpunkt
des letzten Erfolgs), in welchem Dashboard-Bereich sie erscheinen und welche Alarme es mitbringt,
oder es begründet, warum keine nötig sind. Die Dashboard-Bereiche sind Betrieb, Wissensbestand,
Modelle und Kosten sowie Sicherheit und Nachweis; auf die Startseite kommt nur, was den Betrieb
unmittelbar betrifft. Die Sub-Issues tragen das als Abnahmekriterium, der Code Review prüft neue
Metriken gegen Entscheidung 2 und die Metriktabelle des Handbuchs, und eine wöchentliche, nur
lesende Prüfroutine sucht fehlende Metriken und tote Metrikbezüge in Dashboards und Alarmen. Eine
Metrik, die erst nach einer Störung nachgerüstet wird, fehlt genau in der Störung, für die sie
gebraucht worden wäre.

### 7. Korrelation durchgängig, Tracing optional und an der Installation endend

Jede Anfrage trägt eine Request-ID: nginx und `RequestIdFilter` übernehmen eine mitgeschickte
`X-Request-Id` nur bei 8 bis 64 Zeichen aus `[A-Za-z0-9_-]` und erzeugen sonst eine. Sie steht im
Zugriffslog, im MDC jeder Logzeile der Anfrage und ihrer Hintergrundaufgaben in Spring-Executoren
(`MdcTaskDecorator`), im
Antwort-Header, in `ErrorResponse.requestId` und, wo ein Eintrag keine eigene Vorgangskennung hat,
als `request:<id>` in `correlation_ref` des Revisionsprotokolls; die Ausleitung an das
Sicherheitsmonitoring (ADR-0055) sendet `correlation_ref` nicht. Tracing über OpenTelemetry ist ab
Werk aus; eingeschaltet trägt ein Span nur Attribute einer Positivliste (`AllowlistedSpanExporter`),
keine Ereignisse und Links, und die Vorgabe der Stichprobe ist 5 %. Ein Trace endet an der
Installation: Frontend und Backend verwerfen `traceparent`, `tracestate`, `baggage` und B3 des
Aufrufers, kein ausgehender Aufruf trägt einen `traceparent`, keine Trace-ID wird Exemplar einer
Metrik.

Die Request-ID nennt niemanden, ist aber personenbeziehbar über Verknüpfung: Zugriffslog mit
Client-Adresse, Logzeilen mit Konto und Revisionsprotokoll lassen sich über sie verbinden. Die
Netzadresse bleibt aus dem Protokollsatz (ADR-0068 Entscheidung 4); den Weg über die Verknüpfung
regelt die Dienstvereinbarung, und das SIEM bekommt die Kennung nicht, damit dort niemand selbst
verknüpft. Eine Positivliste bleibt dicht, wenn eine Bibliothek neue Attribute einführt. Ein
Trace-Kontext von außen ließe den Aufrufer die Trace-ID und damit die Stichprobe wählen, einer nach
außen gäbe Modellanbietern und Quellen eine Kennung, die das eigene Log einer Anfrage zuordnet.

### 9. Indizierung und Verzeichnisabgleich: Konnektortyp als Label, Zustand aus der Datenbank

Die Metriken der Indizierung tragen als `connector` den Schlüssel des Quellentyps klein geschrieben
(`confluence`, `sharepoint`, `upload`), denselben Wert, den `opaa.outbound.requests` als `kind`
nennt; die Bibliothek ist nie ein Label. Ein Lauf zählt an seinem Ende in `opaa.indexing.runs` mit
Betriebsart, Ergebnis und `failure_category` aus der festen Liste der Laufgründe; einen Lauf, den
die Wiederherstellung nach Neustart, Ausfall oder Zeitüberschreitung beendet, zählt sie als
`abandoned`. `opaa.indexing.run.duration` und `opaa.indexing.stage.duration` veröffentlichen
SLO-Buckets wie die Timer aus Entscheidung 4 (`OPAA_METRICS_SLO_INDEXING_RUN`,
`OPAA_METRICS_SLO_INDEXING_STAGE`).

Läufe, letzter Erfolg und Überfälligkeit einer privaten Bibliothek zählen nicht unter ihrem
Quellentyp, sondern unter `connector="private"`. Als `failure_category` gilt dort eine Positivliste:
`none`, `abandoned`, `private` und die Sperren der Verwaltung (`type_locked`, `profile_locked`,
`profile_required`), die für alle gleich gelten. Jeder andere Laufgrund, auch ein künftiger, fällt in
die Sammelkategorie `private`, denn Kontingent, Konto, Verbindung und Anmeldedaten sprechen von der
einen Person dahinter. Eine
private Bibliothek ist oft die einzige ihres Typs; unter ihrem Typ ergäbe die Reihe den Verlauf
einer Person. Das folgt derselben Linie wie die Maskierung geschützter Gruppen (ADR-0036,
Entscheidung 9) und der Zahlen über private Bibliotheken (ADR-0049): keine Personensicht aus
Betriebsdaten. `opaa.indexing.documents` bleibt beim Typ, auch bei `upload`, weil es
installationsweit über alle Bibliotheken zählt.

Was nur die Datenbank weiß, liest jede Instanz jede Minute aus den vorhandenen Tabellen: den
jüngsten Erfolg je Konnektortyp, die Zahl überfälliger Bibliotheken je Typ, den letzten angewendeten
Verzeichnisabgleich und das Alter der ausstehenden Entscheidung über einen Abgleichplan. Den
Rückstand eines Modellwechsels liest sie alle fünf Minuten, weil er dann den ganzen Vektorindex
zählt. Jeder Pod meldet dann denselben Wert; Dashboards und Alarme fassen solche Gauges mit `max`
über die Pods zusammen, nicht mit `sum`. Scheitert das Lesen, meldet der Gauge `NaN`, nicht den
letzten Wert: Ein veralteter Wert sähe aus wie ein gesunder. Der jüngste Erfolg je Typ verdeckt eine tote Bibliothek,
solange eine andere desselben Typs läuft. Deshalb zählt `opaa.indexing.libraries.overdue` die
Bibliotheken mit Zeitplan, deren letzter Erfolg länger als zwei ihrer Intervalle zurückliegt, als
Anzahl ohne Kennung; eine Bibliothek ohne Erfolg zählt ab ihrer Anlage, weil der Zeitpunkt des
Einschaltens ihres Zeitplans nicht gespeichert ist. Ein unvollständiger Lauf zählt als Erfolg: Er
hat geendet und den erreichbaren Teil indiziert. Der Verzeichnisabgleich nennt als `provider` den
Typ des Verzeichniszugangs (`keycloak`); sein letzter Erfolg ist der letzte angewendete Lauf und
steht still, solange ein Plan auf Bestätigung wartet. Ersetzt ein neuer Lauf den wartenden Plan,
behält der neue Plan die Entstehungszeit des ersten, denn so lange wartet die Entscheidung.

### 10. Hintergrundläufe zentral gemessen, installationsweite Werte von jeder Instanz

Letzter Erfolg, Fehler und erwartetes Intervall jedes geplanten Laufs (`opaa.scheduled.*` mit
`scheduled_job`, dem Namen seiner Sperre; nicht `job`, das Prometheus für den Scrape-Job vergibt
und ein gleichnamiges Tag in `exported_job` umbenennt) misst ein Handler an der Observation, die Spring um jeden Lauf
legt, nicht Code im Lauf; ein Lauf zählt nur dort als erfolgreich, wo er seine Sperre bekam, und ein
abgeschalteter Lauf hat keine Reihe. Bis zum ersten Erfolg gilt der Start der Instanz als letzter
Erfolg, damit ein Lauf, der nie gelingt, ebenso überfällig wird wie einer, der aufhört zu gelingen.
Ein Lauf, der einen Fehler selbst abfängt, um weiterzumachen oder eine Meldung aus dem Log zu halten,
meldet ihn ausdrücklich als Fehlschlag, statt ihn erneut zu werfen. Werte der ganzen Installation
(Warteschlangen, Bestand, Instanzregister, Sperren) liest jede Instanz selbst in festen Abständen
aus vorhandenen Tabellen und meldet sie gleich; ein Dashboard fasst sie mit `max by` zusammen, Zähler je Instanz mit `sum`.
Eine eigene Instanz, die solche Werte für alle meldet, bräuchte eine Wahl und fiele mit ihr aus.
Zahlen über private Bibliotheken unterliegen derselben Mindestgruppengröße wie in der Oberfläche.

Was dem Verhalten einzelner Personen folgt, wird nicht gemessen: erfolgreiche Anmeldungen bei keiner
Methode, auch nicht bei lokalen Konten, denn über die Zeit gezählt zeichnen sie die
Arbeitszeitkurve der Belegschaft; gezählt werden nur abgewiesene nach Grund. Ebenso wenig ein
Zwischenspeicher, dessen Schlüssel eine Person, ein Token oder ein Gespräch ist (gesperrte
Anmeldungen, Gruppenmitgliedschaften, persönliche Bereiche, Filterwerte, Kontingente externer
Zugänge, Gesprächsgedächtnis): Treffer, Fehlgriffe und Größe zeigten aktive Personen und
Gesprächsanfänge. Eine Health-Gruppe, deren Prüfung ein
Modell aufruft, prüft die Metrik nicht selbst; sie zeigt das letzte Ergebnis einer Prüfung von außen.

### 11. Alarmmodell: Schweregrad, Bereich, Runbook und eine Quelle der Schwellen

Jeder mitgelieferte Alarm trägt `severity` mit genau drei Stufen (`critical` weckt, `warning` wird
ein Ticket, `info` zeigt nur), `component` mit dem betroffenen Bereich als Schlüssel der Verteilung
und `runbook_url` auf seinen Abschnitt im Handbuch; die CI prüft alle drei, den Abschnitt und einen
Testfall je Alarm. Quoten zählen erst ab einem Mindestvolumen im Fenster, damit wenig Verkehr in der
Nacht keinen Fehlalarm auslöst. Weckende Alarme stehen nur auf Metriken von OPAA selbst
(`opaa.*`, HTTP-Status, Bereitschaft der Pods), nicht auf `gen_ai.*`, deren Namen sich ändern
(Entscheidung 3). Die Fehlerquote der Fragen ist `success / (success + error)`; abgewiesene Fragen
und Client-Fehler ausgehender Aufrufe zählen nicht. Ein Wert, den eine Instanz nicht aus der
Datenbank lesen kann, meldet `NaN` (Entscheidung 10); ein Vergleich darauf ist nie wahr, deshalb
meldet ein eigener Alarm jeden dauerhaft unlesbaren Wert, und die Alarme des Revisionsprotokolls
werten `NaN` selbst als Befund. Die Alarme stehen in Gruppen je Bereich, jede einzeln abschaltbar.

Jeder Alarm trägt `namespace` der Installation, damit Grafana und die Inhibit-Regeln eines
geteilten Alertmanagers die Installationen trennen.

Die Dashboards bestehen aus einer Startseite mit einer Ampel je Baustein und einem Dashboard je
Bereich; sie zeigen nichts je Person, keine aktiven Personen und keine Chats je Zeitraum
(Entscheidung 1), und wie sich die Nutzung zusammensetzt nur als Anteil, nie als absolute Zahl über
die Zeit. Eine Ampel liest den Zustand der Alarme ihres Bereichs (`ALERTS`), so steuert eine
geänderte Schwelle Alarm und Ampel zugleich; der Chart legt die Dashboards deshalb nur mit den
Alarmen an. Chart und Compose-Overlay liefern dieselben Dateien aus, die Compose-Regeln sind aus dem
Chart gerendert, nicht nachgeschrieben. Ein eigener Grafana oder Alertmanager kommt nicht in den
Chart: Ein Cluster hat seine Überwachung, und Compose ohne Alertmanager zeigt Alarme, stellt sie
aber nicht zu. Grafana läuft nie auf der Origin von OPAA, sondern auf eigener Adresse: Auf derselben
Origin könnte ein Skript in Grafana die Sitzung von OPAA lesen und in ihrem Namen handeln.

## Konsequenzen

- Einfacher: p95 und Fehlerquote je Route, Modellaufruf und Lauf lassen sich in jedem üblichen
  Prometheus ablesen und über Pods aggregieren; Grundsätze für neue Metriken stehen an einer Stelle.
- Schwieriger: Eine Frage, die nur eine Kennung je Objekt beantworten könnte („welche Bibliothek
  löst die meisten Funde aus?“), beantwortet das Laufprotokoll der Bibliothek, nicht die Metrik.
  `opaa.indexing.suspicious.patterns` zählt deshalb nur nach Art des Musters.
- Mit jedem neuen Alarm entsteht ein Runbook-Abschnitt und ein Testfall, sonst bricht die CI ab.
  Eine im Chart geänderte Schwelle wirkt nur auf den Alarm, die Farben der Dashboards folgen den
  Vorgaben.
- Restrisiken: Ein Modellwechsel zeigt sich in `embedding-model` und `vector-store` erst nach Ablauf
  der Zwischenspeicherung. Werden die `gen_ai.*`-Namen umbenannt, brechen Dashboards darauf, bis sie
  nachgezogen sind. In sehr kleinen Installationen kann auch eine installationsweite Zahl auf wenige
  Personen deuten; feine Zeitauflösung und Kanalaufteilung bleiben deshalb aus Dashboards heraus.
  Eine vom Aufrufer gewählte gültige Request-ID kann Einträge verschiedener Anfragen in der Abfrage
  des Revisionsprotokolls nach Vorgang zusammenlegen; das wird hingenommen, weil die Kennung nichts
  freischaltet und nur die Suche betrifft. Traces über Dienstgrenzen hinweg gibt es nicht.

## Verworfene Alternativen

- Nutzungs- und Kostentabellen mit Cockpit in der Oberfläche — zweites Auswertungssystem mit Personenbezug, Entscheidung 1.
- Eigene `opaa.model.*`-Nachbauten der `gen_ai.*`-Metriken — doppelte Pflege, Namen sollen der Konvention folgen; `opaa.model.tokens` baut nichts nach, sondern ergänzt den Zweck.
- Perzentile im Prozess (Summaries) — nicht über Pods aggregierbar.
- Gemeinsames Tag `instance` — kollidiert mit dem Label, das Prometheus jedem Ziel gibt.
- Endpunkt-Cache für Health — greift für Gruppenpfade nicht und hielte Ergebnisse je Person.
- Sperrliste der Span-Attribute — lässt jedes neue Attribut einer Bibliothek durch, Entscheidung 7.
- `traceparent` annehmen und weitergeben — der Aufrufer wählte die Stichprobe, Dritte bekämen eine zuordenbare Kennung.
- Letzter Erfolg je Bibliothek als eigene Reihe — Bibliothekskennung im Label, Entscheidung 9.
- Alarme auf `gen_ai.*` oder Modellnamen — Namen ändern sich, ein weckender Alarm bräche still, Entscheidung 11.
- Eigenes Grafana und Alertmanager im Chart — doppelt zur Überwachung des Clusters, Entscheidung 11.
