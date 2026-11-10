# Dashboards und Alarme

OPAA bringt Alarme für Prometheus und Dashboards für Grafana mit. Unter Kubernetes bindet der Chart
beides an die vorhandene Überwachung des Clusters an, unter Docker Compose startet ein Overlay einen
eigenen Prometheus und ein eigenes Grafana. Was jeder Alarm bedeutet und was zu tun ist, steht unter
[Alarme](alarme.md); welche Metriken es gibt, unter
[Logs und Metriken](ueberwachung-und-fehlerbehebung.md#logs-und-metriken).

## Dashboards

Die Startseite **„OPAA · Lage“** beantwortet in einem Blick, ob jemand handeln muss: eine Kachel je
Baustein (Chat, Suche & Quellen, KI-Modelle, Indexierung, Hintergrund, Plattform, Nachweis, Alarme),
darunter Fragen pro Stunde, die Zeit bis zum ersten Wort, die feuernden Alarme, die Version je Pod und
was über Nacht liegen geblieben ist. Die Farben der Kacheln:

| Farbe | Bedeutung |
|---|---|
| grün | nichts zu tun |
| gelb | prüfen, Ticket für den nächsten Arbeitstag |
| rot | gestört, sofort handeln |
| grau | nicht eingerichtet oder zu wenig Verkehr für eine Aussage |

Jede Kachel öffnet ihren Bereich:

| Dashboard | Für |
|---|---|
| OPAA · Betrieb & Bereitschaft | Bausteine, Antwortzeiten, Fehlerantworten, Heap, Datenbankverbindungen, geplante Läufe, Warteschlangen, Instanzen |
| OPAA · Indexierung & Konnektoren | Läufe, Rückstände und Verarbeitungsschritte je Konnektortyp, Texterkennung, Neueinbettung |
| OPAA · Wissensbestand & Bibliotheken | Bestand, Aktualität, Suchen ohne Fundstelle, Belegstand, Speicher, Verzeichnisabgleich |
| OPAA · KI-Modelle & Kosten | Erreichbarkeit der Modellrollen, Antwortzeit, Drosselung, Tokens und Kosten |
| OPAA · Sicherheit & Nachweis | Prüfsummenkette, Siegel, Ausleitung, Löschlauf, abgewiesene Anmeldungen, Virenprüfung |

- **Auswahl oben:** Datenquelle, `namespace` und `host` (eine Instanz oder alle), in einigen
  Bereichen der Konnektortyp oder das Modell.
- **Kosten** stehen nur im Bereich Modelle. Die Preise je Million Eingabe- und Ausgabetokens trägt
  man oben in die beiden Felder ein; ohne Preise zeigt das Dashboard nur Tokens.
- **Schwellen** der Kacheln und Kurven sind die Vorgaben des Charts. Wer im Chart eine Schwelle
  ändert, ändert den Alarm, nicht die Farbe im Dashboard.
- **Mehrere Instanzen:** Werte, die jede Instanz aus der Datenbank liest, fassen die Dashboards mit
  dem Höchstwert zusammen, Zähler summieren sie.
- **Ohne Personenbezug:** Kein Dashboard zeigt Personen, aktive Konten, Chats je Zeitraum oder je
  Person.

In der Tabelle „Was feuert gerade?“ öffnet jeder Alarm seinen Abschnitt der Alarmseite. Die
Dashboards sind schreibgeschützt; eigene Änderungen gehören in eine Kopie.

## Kubernetes

Der Chart bringt kein Grafana und keinen Prometheus mit, sondern Bausteine für die vorhandenen:

```yaml
metrics:
  serviceMonitor:
    enabled: true
    labels:
      release: kube-prometheus-stack
  prometheusRule:
    enabled: true
    labels:
      release: kube-prometheus-stack
  grafanaDashboards:
    enabled: true
```

- **Alarme:** `metrics.prometheusRule.enabled` legt die Regeln als `PrometheusRule` an; sie setzen
  den `ServiceMonitor` voraus. Einzelne Gruppen und die Schwellen beschreibt
  [Alarme im Chart](kubernetes.md#alarme).
- **Dashboards:** `metrics.grafanaDashboards.enabled` legt je Dashboard eine ConfigMap mit dem Label
  `grafana_dashboard: "1"` und dem Ordner `OPAA` an. Der Sidecar des Grafana aus
  kube-prometheus-stack liest sie ohne weiteres Zutun; ein anders eingestellter Sidecar bekommt Label
  und Ordner über die Werte unter `metrics.grafanaDashboards`.
- **Zustellung:** Den Weg zur Bereitschaft legt der Alertmanager des Clusters fest. Eine Route auf
  `severity="critical"` weckt, `warning` geht als Ticket, `info` nirgendwohin; das Label `component`
  verteilt nach Zuständigkeit. Das Rezept gegen Folgealarme steht unter
  [Folgealarme unterdrücken](alarme.md#folgealarme-unterdrücken).
- **Runbook-Links** zeigen auf die Fassung dieses Handbuchs im Netz. Ohne Internet oder mit einer
  eigenen Kopie des Handbuchs setzt `metrics.runbookBaseUrl` die Adresse der Alarmseite; die Links
  der Dashboards folgen ihr.

## Compose

Das Overlay `docker-compose.monitoring.yml` startet Prometheus und Grafana neben dem Stapel. Das
Admin-Passwort des Grafana ist Pflicht und kommt aus der Shell oder einer `.env` im
Projektverzeichnis, nicht aus `.env.docker`:

```bash
echo "OPAA_GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 24)" >> .env
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d
```

Grafana öffnet sich danach unter `/grafana/` an der Adresse des Frontends, mit der Startseite
„OPAA · Lage“. Jeder weitere Befehl für diesen Stapel nennt beide Dateien; mit mehreren Instanzen
kommt `docker-compose.multi.yml` dazu, Prometheus findet `backend-a` und `backend-b` von selbst.

- **Netz:** Weder Prometheus noch Grafana veröffentlichen einen Port. Prometheus fragt den
  Management-Port des Backends im Netz des Stapels ab; Grafana ist nur über den nginx des Frontends
  erreichbar, hinter demselben Reverse-Proxy wie die Oberfläche.
- **Anmeldung:** nur mit dem Admin-Konto (`OPAA_GRAFANA_ADMIN_USER`, Vorgabe `admin`); kein anonymer
  Zugang, keine Selbstregistrierung. Grafana meldet nichts nach außen: keine Nutzungsstatistik,
  keine Update-Prüfung, kein Newsfeed.
- **Adresse:** Hinter einem Reverse-Proxy setzt `OPAA_GRAFANA_ROOT_URL` die Adresse, die der Browser
  sieht, mit `/grafana/` am Ende; Grafana braucht sie für Weiterleitungen und Links.
- **Aufbewahrung:** Prometheus hält die Messwerte `OPAA_PROMETHEUS_RETENTION` lang
  ([Konfiguration](../referenz/konfiguration.md)), in einem eigenen Volume wie die Einstellungen
  des Grafana. Beide gehören nicht in die Sicherung.
- **Alarme:** Prometheus wertet dieselben Regeln aus wie unter Kubernetes; sie erscheinen in der
  Kachel „Alarme“ und der Tabelle „Was feuert gerade?“. **Compose ohne Alertmanager hat keinen
  Zustellweg:** Niemand wird benachrichtigt. Wer Benachrichtigungen braucht, lässt einen eigenen
  Prometheus mit Alertmanager den Management-Port abfragen oder betreibt OPAA unter Kubernetes.

Ohne das Overlay hat der nginx des Frontends keinen Pfad `/grafana/`.

## Was OPAA nicht überwacht

Einige Störungen sieht OPAA selbst nicht; sie gehören in die Überwachung der Plattform:

- **Zertifikate** am Eingang und bei den Diensten, die OPAA aufruft: Ablauf rechtzeitig melden, etwa
  über cert-manager oder einen Blackbox-Exporter. OPAA meldet erst die gescheiterten Aufrufe.
- **Sicherung:** Ob Datenbank und Originalablage gesichert wurden, weiß nur das Sicherungswerkzeug
  ([Sicherung und Wiederherstellung](sicherung-und-wiederherstellung.md)).
- **Plattenplatz** der Datenbank, der Originalablage und des Prometheus.
- **Zeitsynchronisation** von Knoten und Datenbank: Herzschlag, geplante Läufe und Fristen rechnen mit
  der Uhr ([Eine Uhr für alle](mehrere-instanzen.md#eine-uhr-für-alle)).
- **CrashLoop** und Neustarts von Pods: kube-prometheus-stack bringt dafür eigene Alarme mit; die
  Alarme von OPAA melden nur bereite Pods gegen das Soll.
