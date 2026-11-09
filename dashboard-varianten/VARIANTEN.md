# Grafana-Dashboards für OPAA: vier Varianten

Stand: 09.11.2026. Laborbau mit synthetischen Daten, nichts im Repository geändert.
Grundlage: Betriebsmetriken-Analyse (Soll-Katalog Abschnitt 3, Alarme Abschnitt 4) und
Stakeholder-Bewertung Betrieb (Anhang A Alarmtabelle, Anhang B Dashboard-Fragen).

## Was gebaut wurde

- **Laufzeitumgebung:** Grafana OSS 12.1.1 und Prometheus 3.7.3 (Docker). Die Prometheus-Daten
  wurden per Backfill eingespielt (`promtool tsdb create-blocks-from openmetrics`).
- **Daten:** 32 h synthetische Zeitreihen (2 388 Serien, Schritt 60 s) mit den geplanten Namen
  `opaa_*`, `gen_ai_*`, `http_server_requests_seconds_*`, JVM, Hikari, kube-state-metrics,
  `pg_up` und `ALERTS`. Erzeugt von `json/gen_metrics.py`.
- **Zeitraum der Screenshots:** 08.11.2026 14:30 bis 09.11.2026 14:30 (Europe/Berlin), also ein
  Sonntagabend, eine ruhige Nacht und ein Montag als Arbeitstag. Das Fenster endet bewusst
  **mitten in einem kleinen Zwischenfall**. Die Startseiten zeigen damit, was die Bereitschaft in
  diesem Moment sähe.
- **Gestellte Ereignisse:**
  - 03:12 Häufung fehlgeschlagener lokaler Anmeldungen mit zwei Kontosperrungen
  - 06:40 und 06:44 Rolling Update von 1.14.1 auf 1.14.2; das erscheint als Markierung auf allen
    Zeitreihen
  - 10:30 Volllauf des SMB-Konnektors mit 2 400 Dokumenten; danach Rückstau bei OCR und
    Indizierung
  - 11:42 Virenfund in einem Upload
  - **ab 14:10** drosselt der Modellanbieter die Chat-Modelle (HTTP 429). Die Zeit bis zum ersten
    Wort steigt im p95 auf 11 s, die Erfolgsquote fällt auf 94,7 %.
  - **eine von sechs SharePoint-Bibliotheken** scheitert seit über 24 h mit abgelaufenem Zugang.
    Die anderen fünf laufen weiter, also wäre „jüngster Erfolg je Typ“ grün. Der Fall aus
    Stakeholder 3.3 ist damit nachgestellt.
  - Die Rolle „Bildverstehen“ ist nicht eingerichtet. Sie hat deshalb keine Serie und erscheint grau.

### Regeln aus dem Stakeholder-Review, die alle Varianten einhalten

- Jede Startseite beantwortet die **sechs Fragen aus Anhang B**:
  1. Arbeiten möglich?
  2. Welcher Baustein ist betroffen?
  3. Ist über Nacht etwas liegen geblieben?
  4. Sind die Nachweispflichten erfüllt?
  5. Was hat sich geändert?
  6. Was feuert gerade?
- Ampeln stehen nur auf `opaa_*` und Plattformmetriken. `gen_ai_*` erscheint nur als Anzeige im
  Bereich Modelle.
- Latenzen werden nur als Perzentile gezeigt. Auch die Stufen von Pipeline und Indizierung sind
  Histogramme, keine Mittelwerte.
- **Mindestvolumen** gilt für jede Quote (Chat ab 8 Fragen in 15 min, Anmeldung ab 5, Drosselung ab
  20 Aufrufen in 30 min, leere Retrievals ab 30 Fragen). Darunter steht „wenig Verkehr“ in Grau.
  Nachts gibt es deshalb kein Rot.
- Nichts wird je Person, Space, Bibliothek oder Dokument gezeigt, auch kein Top-N. Es gibt keine
  aktiven Nutzer, keine Chats pro Zeitraum und keine Nutzung nach Kanal. Fragen pro Stunde stehen
  nur als grober Kontext da.
- Kosten und Tokens erscheinen nur im Bereich „KI-Modelle & Kosten“, nie auf einer Startseite.
- Die ERROR-Lograte ist keine Ampel.
- Die Konnektor-Frische ist die Anzahl überfälliger Bibliotheken je Konnektortyp
  (`opaa_indexing_libraries_overdue{connector}`).
- Schwellen folgen der Alarmtabelle aus Anhang A:
  - Warteschlangen haben je eigene Schwellen (Mail 30 min, Quellereignisse 1 h, Indizierung 2 h,
    OCR 6 h).
  - Ein Scheduler-Job ist überfällig ab 3 × `expected_interval`, aggregiert mit `max by (job)` über
    die Pods.
  - Ein wartender Abgleichplan ist eine eigene Anzeige und gehört nicht zur Warteschlange.
  - Für den Heap zählt der Wert nach GC.
  - Für Verbindungen zählen die Hikari-Timeouts.
- Es gibt vier Ampelstufen: grün = nichts zu tun, gelb = Ticket für den nächsten Arbeitstag,
  rot = sofort handeln, grau = nicht eingerichtet oder wenig Verkehr.
- Die Farben sind die Rollen aus den Gestaltungsleitlinien (`#11774F`, `#8F5A00`, `#CE2C31`).
  Sie erscheinen nur als gefüllte Fläche mit weißer Schrift. Das erreicht in beiden Schemata
  mindestens 4,5:1, weil Grafana dieselbe Füllung hell und dunkel verwendet. Farbiger Text auf dem
  Panel-Hintergrund ist bewusst vermieden: im dunklen Schema läge er unter 4,5:1.
- Kurven verwenden die themenabhängigen Grafana-Farben. Navy `#012142` steht nur im Kopfband,
  weil es im dunklen Schema als Linie verschwindet.

---

## Variante A: Ampel je Baustein mit Drilldown

**Screenshots:** `A-hell.png`, `A-dunkel.png`, `A-drilldown-indizierung.png`
**JSON:** `json/A-start.json`, `json/A-drilldown-indizierung.json`

**Grundidee:** Oben steht eine Reihe großer Kacheln, je technischem Baustein eine, mit einem Wort
statt einer Zahl („in Ordnung / prüfen / gestört“). Darunter folgen zwei Kernkurven sowie die
Tabellen „Aktive Alarme“ und „Version je Pod“. Jede Kachel führt per Klick in ein
Detail-Dashboard. Gebaut ist der Drilldown „Indizierung & Konnektoren“; die übrigen Kacheln
verlinken auf die Bereichs-Dashboards von Variante D.

**Für wen:** Bereitschaft und Leitstand, auch als Wandbildschirm. Wer OPAA nicht im Detail
kennt, sieht in einer Sekunde, wo es klemmt.

**Stärken**
- Am schnellsten erfassbar; man muss keine Zahlen deuten.
- Die Kachel nennt den Baustein und damit die Zuständigkeit.
- Die Regeln der Kacheln stehen im Tooltip jeder Kachel und sind nachvollziehbar.
- Lässt sich gut mit Drilldowns erweitern.

**Schwächen**
- Jede Kachel ist ein „schlechtester Wert aus mehreren Regeln“. Warum sie gelb ist, sieht man
  erst im Tooltip oder im Drilldown.
- Die Schwellen sind in PromQL eingebaut. Sie müssen mit den Werten der PrometheusRule
  übereinstimmen, sonst zeigen Kachel und Alarm Unterschiedliches.
- Acht Kacheln sind die Obergrenze; eine weitere Kachel verwässert das Bild.

**Panels Startseite (14, davon ein Kopfband):** Kopfband · Kacheln Chat, Suche & Quellen,
KI-Modelle, Indizierung, Hintergrund, Plattform, Nachweis, Alarme · Fragen pro Stunde (Balken je
Stunde) · Zeit bis zum ersten Wort (p95 und Median mit Zielbändern) · Was feuert gerade? (Alarm,
Schweregrad, Zuständigkeit, betrifft, Runbook-Link) · Version je Pod (Version, Startzeit) · Über
Nacht liegen geblieben? (überfällige Bibliotheken, überfällige Jobs, Warteschlangen im Verzug,
ältester Eintrag)

**Drilldown Indizierung (12 Panels):**
- Kennzahlen: überfällige Bibliotheken, Bibliotheken mit Zeitplan, gescheiterte Läufe in 6 h
  (Lauf-Quote, ab 4 Läufen), verarbeitete Dokumente, ältester Eintrag in Indizierung und OCR
- Tabelle je Konnektortyp: überfällig, mit Zeitplan, Läufe, gescheitert, Dokumente, Laufdauer p95
- Gescheiterte Läufe nach Ursache
- Dokumente je Konnektortyp pro Stunde
- Warteschlangen
- Dauer je Verarbeitungsschritt (p95)
- OCR-Seiten pro Stunde

Variablen: Pod, Konnektortyp.

## Variante B: Sechs Fragen

**Screenshots:** `B-hell.png`, `B-dunkel.png` · **JSON:** `json/B-sechs-fragen.json`

**Grundidee:** Die Startseite ist wörtlich Anhang B. Fünf Zeilen tragen Fragen als Überschrift,
von „Können die Leute gerade arbeiten?“ bis „Was hat sich geändert, was feuert gerade?“.
Darunter steht jeweils nur, was die Frage beantwortet. Kapazität ist eingeklappt; Kosten stehen
ausdrücklich nur unter „Modelle & Kosten“. Die ursprünglich vorgeschlagenen „drei Fragen“ wurden
nach dem Stakeholder-Review zu sechs erweitert.

**Für wen:** Bereitschaft, Schichtübergabe und Morgenroutine. Die Seite lässt sich von oben nach
unten vorlesen und funktioniert ohne Vorwissen.

**Stärken**
- Erklärt sich selbst, weil die Fragen in Klartext dastehen.
- Deckt die Anforderung aus Anhang B am direktesten ab.
- Zeigt echte Zahlen mit Einheit, also „94,7 %“ statt nur „gestört“.
- Die Reihenfolge entspricht dem Vorgehen bei einer Störung: erst Wirkung, dann Ursache.

**Schwächen**
- Die Seite ist dicht; bei 1600 × 1000 ist sie voll.
- Keine Zeitreihen außer den Fragen pro Stunde: wann etwas begann, sieht man hier nicht.
- Viele Kacheln haben die gleiche Form; das Auge braucht etwas länger als bei A.

**Panels (7 sichtbar, 3 eingeklappt):**
- Zeile 1: Chat-Erfolgsquote, Anmelde-Erfolgsquote, erstes Wort (p95); dazu Fragen pro Stunde
- Zeile 2: Bausteine (Backend, Frontend, Datenbank, IdP und sechs Modellrollen; nicht
  eingerichtete Rollen grau)
- Zeile 3: überfällige Bibliotheken je Konnektortyp, Verzeichnisabgleich zuletzt angewendet,
  Abgleichplan wartet, überfällige Jobs, Warteschlangen im Verzug
- Zeile 4: Prüfsummenkette, Monate ohne Siegel, SIEM-Rückstand, letzter Löschlauf,
  Protokollhorizont
- Zeile 5: Version je Pod, aktive Alarme
- Eingeklappt: Speicher gegen Quote, Heap nach GC, DB-Verbindungen

Variable: Pod.

## Variante C: Ziele und Fehlerbudget (SLO)

**Screenshots:** `C-hell.png`, `C-dunkel.png` · **JSON:** `json/C-ziele-fehlerbudget.json`

**Grundidee:** Oben stehen drei Service-Ziele, je mit erreichtem Wert, verbleibendem
Fehlerbudget und Burn-Rate über 1 h und 6 h:

- Chat verfügbar 99,5 %
- erstes Wort < 3 s für 95 %
- Bibliotheken aktuell 99 %

Darunter folgen die Burn-Rate über die Zeit mit den Linien 1×, 6× und 14,4× sowie die aktiven
Alarme. Im Abschnitt „Ursachen“ stehen Bausteine, Nachweis, gescheiterte Fragen nach Ursache,
erstes Wort als Kurve und überfällige Bibliotheken je Typ.

**Für wen:** Betriebsverantwortung, Servicemanagement und Gespräche mit Fachbereich oder
Auftraggeber. Die Variante beantwortet „Halten wir zu, was wir versprochen haben?“ und
priorisiert danach.

**Stärken**
- Unterscheidet „ärgerlich“ von „verbraucht unser Monatsbudget“. In der Szene: Chat-Budget noch
  21 %, TTFT-Budget aufgebraucht (−2 %), Bibliotheken −33 % durch die eine tote
  SharePoint-Bibliothek.
- Passt zum geplanten Alarm „Chat-SLO-Burn“ (14,4× und 6×).
- Bringt die Diskussion über Zielwerte voran.

**Schwächen**
- Braucht Erklärung: Burn-Rate und Fehlerbudget sind für viele Betriebsleute neu.
- Ist erst mit 30 Tagen Historie aussagekräftig; die Screenshots zeigen den 24-h-Zeitraum.
- Zielwerte müssen festgelegt werden; der TTFT-Zielwert ist modellabhängig.
- Für „was ist kaputt“ braucht es den unteren Teil; die Ziele allein sagen das nicht.

**Panels (13):**
- je Ziel: erreicht und Fehlerbudget übrig, dazu Burn-Rate 1 h und 6 h (6 Panels)
- Burn-Rate je Ziel als Kurve
- aktive Alarme
- Bausteine
- Nachweis (Sammelstatus)
- gescheiterte Fragen nach Ursache (Fehler, 429, Zeitüberschreitung)
- erstes Wort, Median und p95
- überfällige Bibliotheken je Konnektortyp

Variable: Pod.

## Variante D: Nach Bereich und Zielgruppe (Dashboard-Familie)

**Screenshots:** `D-start-hell.png`, `D-start-dunkel.png`, `D-bereich-betrieb.png`,
`D-bereich-wissen.png`, `D-bereich-modelle.png`, `D-bereich-sicherheit.png`
**JSON:** `json/D-start.json`, `json/D-bereich-*.json`

**Grundidee:** Eine gemeinsame Startseite, der „Wegweiser“, zeigt vier große Bereichskacheln mit
Status, darunter je zwei Kennzahlen ohne Kosten, außerdem die aktiven Alarme mit Zuständigkeit
und die Version je Pod. Jede Kachel öffnet ein schlankes Bereichs-Dashboard für die jeweils
zuständige Gruppe:

- Betrieb & Bereitschaft
- Wissensbestand & Bibliotheken
- KI-Modelle & Kosten
- Sicherheit & Nachweis

Die Bereiche entsprechen dem Label `component`, nach dem Alertmanager laut Stakeholder 3.1
routet.

**Für wen:** Häuser mit verteilten Zuständigkeiten:

- Bereitschaft oder IT-Betrieb
- Systemverwaltung und Fachbetreuung für den Wissensbestand
- Modellbetreiber und Steuerung für Modelle und Kosten
- ISB und Revision für Sicherheit und Nachweis

Jede Gruppe öffnet direkt ihr Dashboard und sieht nur, was sie angeht.

**Stärken**
- Die Bereiche folgen der Zuständigkeit, nicht der Technik.
- Kosten sind vom Betrieb getrennt, wie im Review gefordert.
- Die Bereichs-Dashboards bleiben mit 6 bis 11 Panels schlank.
- Lässt sich je Installation abschalten, etwa ohne OCR (passend zu den Gruppenschaltern in
  `values.yaml`).
- Dient gleichzeitig als Drilldown für A.

**Schwächen**
- Fünf Dashboards müssen gepflegt werden.
- Statusregeln wiederholen sich zwischen Startseite und Bereichen und müssen synchron bleiben;
  im Labor erzeugt sie deshalb ein Generator.
- Wer nur die Startseite sieht, erfährt weniger als bei B.
- Die Sicht „Bereitschaft“ ist kaum anders als A.

**Panels**
- **Wegweiser (11):** Kopfband · vier Bereichskacheln · vier Kennzahlenpaare:
  - Chat erfolgreich und erstes Wort
  - überfällige Bibliotheken und ältester Auftrag
  - Modellrollen bereit und Chat gedrosselt
  - Virenfunde und Nachweispflichten
  
  Dazu aktive Alarme und Version je Pod.
- **Betrieb & Bereitschaft (8):**
  - Bausteine
  - Nutzerseite: Chat, Anmeldung, erstes Wort
  - Hintergrund: Jobs, Warteschlangen, Verzeichnisabgleich, Invalidierungs-Bus
  - Antwortzeit je Route (p95, ohne Chat-Stream)
  - Serverfehler je Pod
  - Heap nach GC
  - DB-Verbindungen mit Timeouts
  - Tabelle der Hintergrundjobs: zuletzt erfolgreich, Intervall, Fehler
- **Wissensbestand & Bibliotheken (11):**
  - Kennzahlen: Bibliotheken mit Zeitplan, davon überfällig, Dokumente neu oder geändert, nicht
    verarbeitbar, OCR-Seiten, Speicher belegt
  - überfällige Bibliotheken je Typ
  - Dokumente je Typ pro Stunde
  - Rückstand
  - gescheiterte Läufe je Typ
  - Speicher gegen Quote
  
  Variable: Konnektortyp.
- **KI-Modelle & Kosten (6):**
  - Modellrollen
  - Steuerung: Kosten, Tokens, Hochrechnung 30 Tage, gedrosselte Aufrufe
  - Modell-Antwortzeit p95 je Modell (`gen_ai_*`, nur Anzeige)
  - fehlgeschlagene Modellaufrufe nach Ursache
  - Tokens je Zweck
  - Kosten je Modell
  
  Variable: Modell.
- **Sicherheit & Nachweis (6):**
  - Nachweispflichten (5 Werte)
  - Schutzfunktionen über 24 h: Virenfunde, Prüfung gescheitert, Signaturalter, globales
    Rate-Limit, Kontosperrungen, Abflussalarme
  - Anmeldungen pro Stunde nach Ergebnis
  - Rate-Limit-Abweisungen
  - Virenprüfung: Dauer p95 und Durchsatz
  - SIEM-Rückstand und Prüfsummenkette

---

## Kombinieren

Die Varianten schließen sich nicht aus, denn sie verwenden dieselben PromQL-Bausteine (im
Generator `build_dashboards.py` zentral definiert). Sinnvolle Kombinationen:

1. **Ampel-Startseite A + Bereichs-Dashboards D:** Die acht Kacheln von A verlinken bereits auf
   die Bereiche von D, Indizierung auf den eigenen Drilldown. Den Wegweiser von D braucht es dann
   nicht. Die Bereitschaft hat die schnelle Ampel, die Fachgruppen haben ihre Bereiche.
2. **B als Startseite, D als Drilldown:** Jede Fragezeile bekommt einen Link in den passenden
   Bereich, etwa „Baustein“ nach Betrieb, „liegen geblieben“ nach Wissensbestand und „Nachweis“
   nach Sicherheit.
3. **C als eigene Seite für Monats- und Steuerungsgespräche,** nicht als Startseite. Ihre
   Burn-Rate-Kurve kann zusätzlich als eine Zeile in A oder B erscheinen.
4. **Kacheln von A über den Zeilen von B:** Das ergibt Ampel plus Erklärung. Es wird schnell zu
   voll und ist nur ohne Kopfband sinnvoll.

## Empfehlung

**A als Startseite, D als Bereichs-Dashboards dahinter, C als zusätzliches Dashboard, sobald
Zielwerte vereinbart sind.** Die Fragezeilen von B dienen als Gliederung und Text der
Bereitschafts-Runbooks.

Begründung:

- **Nicht überfordern:** A ist das einzige Layout, das in zehn Sekunden ohne Lesen funktioniert:
  acht Worte in Farbe. Die sechs Fragen aus Anhang B sind trotzdem abgedeckt, nämlich in den
  Kacheln, in den beiden Tabellen und im Block „Über Nacht liegen geblieben?“.
- **Zuständigkeit:** Die Bereiche von D folgen dem Routing-Label `component`. So sehen ISB,
  Modellbetrieb und Fachbetreuung nur ihren Teil, und Kosten bleiben aus dem Betrieb heraus.
- **Pflegeaufwand:** A und D teilen sich die Statusregeln, also eine Quelle für Schwellen
  (später aus `values.yaml` bzw. der PrometheusRule). B wäre als dritte Startseite redundant.
- **C** ist fachlich die sauberste Sicht. Sie setzt aber 30 Tage Historie, abgestimmte Zielwerte
  und Erklärung voraus, deshalb kommt sie erst in der zweiten Welle.

Für #3667 heißt das: zuerst `A-start` und `D-bereich-*` ausliefern (der Wegweiser `D-start` ist
optional), danach `C`.

## Hinweise für die Übernahme in #3667

- **Cluster-weite Gauges, die jeder Pod meldet,** werden vor dem Summieren mit `max by (...)`
  dedupliziert. Das betrifft zum Beispiel `opaa_indexing_libraries_overdue`,
  `opaa_scheduled_last_success_seconds` und Werte rund um die Warteschlangen. Sonst zählt jeder
  Fund doppelt; im Labor gab es genau diesen Fehler.
- **Annahmen zu Namen und Labels**, die im Soll-Katalog noch nicht festgelegt sind:
  - Indizierung: `opaa_indexing_libraries_overdue{connector}`,
    `opaa_indexing_libraries_scheduled{connector}`
  - Scheduler: `opaa_scheduled_expected_interval_seconds{job}`
  - Verzeichnisabgleich: `opaa_directory_sync_last_applied_seconds`,
    `opaa_directory_sync_plan_pending`
  - Audit: `opaa_audit_chain_lag_seconds`, `opaa_audit_retention_last_run_age_seconds`,
    `opaa_audit_log_horizon_seconds`
  - Kosten: `opaa_model_cost_euros_total{model,purpose}`
  - Modellaufrufe: `opaa_model_requests_total{role,model,result}` mit `throttled`
  - Anmeldung: `opaa_auth_logins_total{method,result}` mit `idp_error`
  - Bausteine: `opaa_invalidation_bus_connected`, `opaa_storage_used_bytes{kind}`,
    `opaa_storage_quota_bytes`
  - Labels `instance` (Pod) und `application` als Common Tags
- **Alarm-Tabelle:** Sie liest `ALERTS{alertstate="firing",alertname=~"Opaa.*"}` und führt
  `connector` und `role` zu „betrifft“ zusammen. Der Runbook-Link ist ein Platzhalter
  (`https://opaa.example/handbuch/betrieb/alarme/#<Alarm>`) und muss auf die echte
  Handbuch-Adresse zeigen.
- **Grafana zeigt keine Labels einer Annotation (`runbook_url`) aus ALERTS.** Der Link wird deshalb
  aus dem Alarmnamen gebaut.
- **Nicht eingerichtete Modellrollen** erkennt die Logik daran, dass keine Serie existiert
  (`or on() vector(-1)`). Ist die Metrik ganz weg, etwa weil das Scrapen ausfällt, wird die Kachel
  ebenfalls grau. Davor schützt `OpaaBackendMetricsMissing`.
- **Einheiten:** Grafana formatiert Dauern englisch („mins“, „weeks“). Dauern stehen deshalb in
  PromQL umgerechnet mit Suffix „min“, „h“ oder „Tage“. Bei Geld und Tokens bleibt der
  Dezimalpunkt englisch.
- **Datenquelle:** Die Dashboards verwenden die Datenquelle mit UID `prometheus`. Für die
  Auslieferung gehört sie als `${DS_PROMETHEUS}`-Eingabe parametrisiert.

## Dateien in `json/`

| Datei | Inhalt |
|---|---|
| `A-start.json`, `A-drilldown-indizierung.json` | Variante A |
| `B-sechs-fragen.json` | Variante B |
| `C-ziele-fehlerbudget.json` | Variante C |
| `D-start.json`, `D-bereich-{betrieb,wissen,modelle,sicherheit}.json` | Variante D |
| `gen_metrics.py` | Datengenerator (OpenMetrics, 32 h, festes Zufallssaatgut) |
| `build_dashboards.py` | erzeugt alle Dashboard-JSONs aus gemeinsamen PromQL-Bausteinen |
| `check_queries.py` | prüft jede Abfrage der Dashboards gegen Prometheus |
| `shoot.js` | Playwright-Screenshots (1600 × 1000, Faktor 2, hell und dunkel, Kiosk) |
| `lab/` | Prometheus-Konfiguration und Grafana-Provisionierung |

Nachbauen:

```bash
python3 gen_metrics.py metrics.om
docker run --rm -u 0 -v $PWD:/in -v $PWD/tsdb:/out --entrypoint promtool prom/prometheus:v3.7.3 \
  tsdb create-blocks-from openmetrics --max-block-duration=24h /in/metrics.om /out
# Prometheus auf ./tsdb starten, Grafana 12.1.1 mit lab/provisioning und den JSONs
```
