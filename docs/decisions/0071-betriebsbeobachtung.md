# ADR-0071: Betriebsbeobachtung — Metriken, Health und Korrelation ohne eigene Datenhaltung

## Status

Akzeptiert · zuletzt geändert 2026-11-09 · ergänzt ADR-0030, ADR-0053, ADR-0055, ADR-0059, ADR-0060 und ADR-0066

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
nicht um, solange die OpenTelemetry-Konventionen dafür nicht veröffentlicht sind. Jede
Inhalts-Property von Spring AI (`log-prompt`, `log-completion`, `log-query-response`,
`include-content`) steht in `application.yml` ausdrücklich auf `false`, damit ein geänderter
Standardwert der Bibliothek keine Inhalte in Observations, Logs oder Spans bringt; eine Prüfung
verlangt jede solche Property, die ein Spring-AI-Modul auf dem Klassenpfad deklariert.

### 4. Latenz als Histogramm mit SLO-Buckets, Reihen mit Anwendung, Host und Build

`http.server.requests`, `opaa.query.duration`, `opaa.ocr.duration`, `gen_ai.client.operation` und
`tasks.scheduled.execution` veröffentlichen Histogramm-Buckets an festen SLO-Schwellen
(`management.metrics.distribution.slo`), je Timer über eine `OPAA_METRICS_SLO_*`-Variable
einstellbar; die Long-Task-Timer daneben (`….active`) bleiben ohne Buckets. Feste Buckets statt
Perzentilen im Prozess, weil sich nur Buckets über Pods hinweg aggregieren lassen, und statt des
vollen Perzentil-Histogramms, weil wenige Buckets je Reihe die Zahl der Reihen klein halten. Jede
Reihe trägt `application` und `host` (Rechnername, unter Kubernetes der Pod); nicht `instance`,
weil Prometheus dieses Label selbst setzt und ein gleichnamiges umbenennt. `opaa.build.info`
(immer 1, mit `version` und `commit`) zeigt, welcher Build wo läuft, auch einen gemischten Stand
während eines Rolling Updates.

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

## Konsequenzen

- Einfacher: p95 und Fehlerquote je Route, Modellaufruf und Lauf lassen sich in jedem üblichen
  Prometheus ablesen und über Pods aggregieren; Grundsätze für neue Metriken stehen an einer Stelle.
- Schwieriger: Eine Frage, die nur eine Kennung je Objekt beantworten könnte („welche Bibliothek
  löst die meisten Funde aus?“), beantwortet das Laufprotokoll der Bibliothek, nicht die Metrik.
  `opaa.indexing.suspicious.patterns` zählt deshalb nur nach Art des Musters.
- Restrisiken: Ein Modellwechsel zeigt sich in `embedding-model` und `vector-store` erst nach Ablauf
  der Zwischenspeicherung. Werden die `gen_ai.*`-Namen umbenannt, brechen Dashboards darauf, bis sie
  nachgezogen sind. In sehr kleinen Installationen kann auch eine installationsweite Zahl auf wenige
  Personen deuten; feine Zeitauflösung und Kanalaufteilung bleiben deshalb aus Dashboards heraus.
  Eine vom Aufrufer gewählte gültige Request-ID kann Einträge verschiedener Anfragen in der Abfrage
  des Revisionsprotokolls nach Vorgang zusammenlegen; das wird hingenommen, weil die Kennung nichts
  freischaltet und nur die Suche betrifft. Traces über Dienstgrenzen hinweg gibt es nicht.

## Verworfene Alternativen

- Nutzungs- und Kostentabellen mit Cockpit in der Oberfläche — zweites Auswertungssystem mit Personenbezug, Entscheidung 1.
- Eigene `opaa.model.*`-Nachbauten der `gen_ai.*`-Metriken — doppelte Pflege, Namen sollen der Konvention folgen.
- Perzentile im Prozess (Summaries) — nicht über Pods aggregierbar.
- Gemeinsames Tag `instance` — kollidiert mit dem Label, das Prometheus jedem Ziel gibt.
- Endpunkt-Cache für Health — greift für Gruppenpfade nicht und hielte Ergebnisse je Person.
- Sperrliste der Span-Attribute — lässt jedes neue Attribut einer Bibliothek durch, Entscheidung 7.
- `traceparent` annehmen und weitergeben — der Aufrufer wählte die Stichprobe, Dritte bekämen eine zuordenbare Kennung.
