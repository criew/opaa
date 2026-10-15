---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# IT-Grundschutz-Modellierung von OPAA

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

## Hinweise zur Verwendung

Diese Vorlage richtet sich an die oder den Informationssicherheitsbeauftragten (ISB) einer Behörde,
die OPAA in ihr Sicherheitskonzept nach
[BSI-Standard 200-2](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Standards-und-Zertifizierung/IT-Grundschutz/BSI-Standards/bsi-standards_node.html)
aufnimmt. Sie ordnet die Zielobjekte einer OPAA-Installation den Bausteinen des
[IT-Grundschutz-Kompendiums](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Standards-und-Zertifizierung/IT-Grundschutz/IT-Grundschutz-Kompendium/it-grundschutz-kompendium_node.html)
zu und trennt dabei, was das Produkt beiträgt, von dem, was der Betrieb umsetzt. Sie ersetzt weder
die Strukturanalyse noch den IT-Grundschutz-Check des Hauses; ein vollständiges Sicherheitskonzept
liegt außerhalb dieser Vorlage.

Angaben in eckigen Klammern **[…]** ersetzt die Behörde. Die Bausteinbezeichnungen folgen dem
Kompendium in der Edition 2023. Das BSI hat die Weiterentwicklung des IT-Grundschutzes unter dem
Namen Grundschutz++ angekündigt; vor der Übernahme prüft die Behörde, welche Fassung sie anwendet,
und gleicht Nummern und Titel ab.

Was OPAA speichert, protokolliert und wohin Daten fließen, schreibt diese Vorlage nicht ab. Es steht
in den Faktenblättern der eingesetzten Version: {{faktenblatt:datenkatalog}},
{{faktenblatt:protokollereignisse}}, {{faktenblatt:datenfluesse}} und
{{faktenblatt:voreinstellungen}}.

## Bei jedem Update fortschreiben

Ein Sicherheitskonzept beschreibt einen Stand. Jede neue OPAA-Version kann Zielobjekte,
Kommunikationsverbindungen oder Voreinstellungen ändern, und der Baustein OPS.1.1.3 (Patch- und
Änderungsmanagement) verlangt, Änderungen vor dem Einspielen zu bewerten. Für OPAA heißt das:

1. **Release-Notizen lesen**, besonders den Abschnitt „Vorbereitung für Bestandsinstallationen“ und
   die „Datenrelevanten Änderungen“ ([Release-Verfahren](../../releases.md#datenwirkung-kennzeichnen)).
2. **Faktenblätter der alten und der neuen Version vergleichen**, vor allem
   {{faktenblatt:datenfluesse}}: Ein neues Ziel ausgehender Verbindungen ist eine neue
   Kommunikationsverbindung in der Strukturanalyse.
3. **Modellierung und IT-Grundschutz-Check nachziehen**, wenn ein Zielobjekt hinzukommt oder
   entfällt.
4. **Vor dem Update sichern**; der Rückweg auf eine ältere Version führt nur über die Sicherung
   ([Notfallkonzept](../09-betrieb-update/notfallkonzept.md)).

Das Update-Paket je Release, das diese Schritte bündeln soll, entsteht in
[#2552](https://github.com/criew/opaa/issues/2552).

## Zielobjekte einer Installation

Die Bausteine einer Installation beschreibt das Handbuch in der
[Betriebsübersicht](../../handbuch/betrieb.md#bausteine). Für die Strukturanalyse ergeben sich daraus
diese Zielobjekte:

| Zielobjekt | Art | Schutzbedarf bestimmt durch | Hinweis |
|---|---|---|---|
| Anwendung OPAA (Backend und Frontend) | Anwendung | die Dokumente der angebundenen Bibliotheken und die Chats | genau eine Backend-Instanz, keine Hochverfügbarkeit ([Betriebsübersicht](../../handbuch/betrieb.md#genau-eine-backend-instanz)) |
| Datenbank (PostgreSQL mit pgvector) | Anwendung, Datenhaltung | wie die Anwendung; enthält auch den Vektorindex, die Volltextsuche und das Nachweisprotokoll | die einzige Wahrheit der Installation |
| Originalablage | Datenhaltung | die hochgeladenen Originale | Verzeichnis, Netzlaufwerk oder Objektspeicher ([Originalablage](../../handbuch/originalablage.md)) |
| Modelldienste (Chat, Einbettung, optional Reranking und Transkription) | Anwendung oder Dienst eines Dritten | alles, was an das Modell geht: Fragen, Textausschnitte der Dokumente | im Haus betrieben oder extern; der Weg steht in {{faktenblatt:datenfluesse}} |
| Container-Plattform (Docker Compose oder Kubernetes) | IT-System | höchster Schutzbedarf der darauf laufenden Anwendungen (Maximumprinzip) | [Docker Compose](../../handbuch/docker-compose.md), [Kubernetes](../../handbuch/kubernetes.md) |
| Identitätsanbieter | Anwendung des Hauses | bereits im Sicherheitskonzept des Hauses | OpenID Connect ([Authentifizierung](../../handbuch/authentifizierung.md)) |
| Angebundene Quellen | Anwendungen des Hauses oder Dritter | bereits im Sicherheitskonzept des Hauses | nur lesend angebunden |
| Kommunikationsverbindungen | Verbindung | die übertragenen Inhalte | alle ausgehenden Ziele in {{faktenblatt:datenfluesse}}, Netzwege im [Handbuch](../../handbuch/betrieb.md#netzwerkzugang) |

## Bausteinzuordnung

„Produkt“ meint, was OPAA als Software mitbringt oder dokumentiert; die Spalte nennt den Nachweis.
„Betrieb“ meint, was die Behörde oder ihr Dienstleister umsetzt. Ein Baustein mit Produktanteil ist
nicht erfüllt, nur weil OPAA seinen Anteil liefert: Der IT-Grundschutz-Check bewertet die
Anforderungen am laufenden System.

### Prozess-Bausteine

| Baustein | Zielobjekt | Anteil des Produkts | Anteil des Betriebs |
|---|---|---|---|
| ISMS.1 Sicherheitsmanagement | Informationsverbund | — | vollständig |
| ORP.4 Identitäts- und Berechtigungsmanagement | Anwendung OPAA | Rollen- und Rechtemodell, Anbindung an den Identitätsanbieter, Historisierung der Rechte ([Berechtigungen](../../handbuch/berechtigungen.md)); wer was sieht: {{faktenblatt:rollensicht}} | Rollen vergeben, Konten- und Rechteprüfung, Notanker-Konto verwahren |
| CON.1 Kryptokonzept | Anwendung OPAA, Datenbank | verschlüsselte Ablage gespeicherter Zugangsdaten, Schlüsselübersicht ([Schlüssel und Geheimnisse](../../handbuch/betrieb.md#schlüssel-und-geheimnisse)) | TLS am Eingang, Verschlüsselung ruhender Daten im Speicher, Schlüssel erzeugen, verwahren und sichern |
| CON.2 Datenschutz | Anwendung OPAA | Faktenblätter zu Daten, Fristen und Datenflüssen | Vorlagen zum Datenschutz entstehen in [#2565](https://github.com/criew/opaa/issues/2565) |
| CON.3 Datensicherungskonzept | Datenbank, Originalablage | beschriebene Sicherungsbestandteile und Reihenfolge ([Sicherung und Wiederherstellung](../../handbuch/betrieb.md#sicherung-und-wiederherstellung)) | Sicherung einrichten, Wiederherstellung üben ([Wiederherstellungsprobe](../09-betrieb-update/wiederherstellungsprobe.md)) |
| CON.6 Löschen und Vernichten | Anwendung OPAA, Datenbank | Lösch- und Aufbewahrungsfristen: {{faktenblatt:fristen}} | Datenträger, Sicherungen und Kopien außerhalb der Anwendung |
| CON.8 Software-Entwicklung | Projekt OPAA | geordneter Entwicklungsprozess: Review jedes Pull Requests, automatisierte Tests, Release-Verfahren ([Release-Verfahren](../../releases.md)) | nur, wenn die Behörde OPAA selbst verändert |
| OPS.1.1.3 Patch- und Änderungsmanagement | Anwendung OPAA, Container-Plattform | unveränderliche Release-Images, Patch-Releases für Sicherheitsupdates, Release-Notizen mit Vorbereitungsschritten ([Sicherheitsupdates für Releases](../../releases.md#sicherheitsupdates-für-releases)) | Updates bewerten, testen, einspielen; siehe [Bei jedem Update fortschreiben](#bei-jedem-update-fortschreiben) |
| OPS.1.1.5 Protokollierung | Anwendung OPAA | Anwendungsprotokoll auf der Standardausgabe, optional strukturiert; Nachweis- und Revisionsprotokoll in der Anwendung ([Logs und Metriken](../../handbuch/betrieb.md#logs-und-metriken)); Ereignisse: {{faktenblatt:protokollereignisse}} | Protokolle einsammeln, aufbewahren, auswerten; Zugriff regeln |
| OPS.1.1.6 Software-Tests und -Freigaben | Anwendung OPAA | Tests im Projekt vor jedem Release | Abnahme jeder Version im Haus, etwa in einer Testinstanz |
| OPS.2.2 Cloud-Nutzung | Modelldienste, Betrieb beim Dienstleister | — | nur bei externen Modelldiensten oder Betrieb in einer Cloud; siehe [C5:2026](c5-2026-cloud-anbieter.md) |
| OPS.2.3 Nutzung von Outsourcing | Betrieb beim Dienstleister | — | wenn ein Dienstleister OPAA betreibt |
| DER.1 Detektion von sicherheitsrelevanten Ereignissen | Anwendung OPAA | Metriken und Alarme ([Betriebsüberwachung](../../handbuch/kubernetes.md#betriebsüberwachung)), Frühwarnsignale bei Anmeldeversuchen ([Logs und Metriken](../../handbuch/betrieb.md#logs-und-metriken)) | Anbindung an das zentrale Sicherheitsmonitoring |
| DER.2.1 Behandlung von Sicherheitsvorfällen | Informationsverbund | Meldeweg für Schwachstellen ([`SECURITY.md`](../../../SECURITY.md)), Security Advisories | Vorfallbehandlung des Hauses, Meldepflichten |
| DER.4 Notfallmanagement | Informationsverbund | Bestandteile, Abhängigkeiten und Wiederherstellungsweg | [Notfallkonzept](../09-betrieb-update/notfallkonzept.md) nach [BSI-Standard 200-4](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Standards-und-Zertifizierung/IT-Grundschutz/BSI-Standards/bsi-standards_node.html) |

### System-Bausteine

| Baustein | Zielobjekt | Anteil des Produkts | Anteil des Betriebs |
|---|---|---|---|
| APP.3.1 Webanwendungen und Webservices | Anwendung OPAA | Anmeldung, Rate-Limits, Sicherheits-Header, Zielprüfung für Abrufe des Servers ([Härtung](../../handbuch/betrieb.md#härtung-für-erreichbare-deployments)) | Härtungsmaßnahmen umsetzen, die das Handbuch als zwingend nennt |
| APP.3.2 Webserver | Frontend (nginx) | gehärtetes Frontend-Image ohne Zustand ([Frontend-Laufzeitimage](../../handbuch/betrieb.md#frontend-laufzeitimage)) | TLS und Zertifikate am vorgelagerten Proxy |
| APP.4.3 Relationale Datenbanken | Datenbank | Anforderungen an eine eigene PostgreSQL; die Protokolltabellen gehören einer eigenen Rolle, das Konto der Anwendung kann sie nicht löschen ([Voraussetzungen einer eigenen PostgreSQL](../../handbuch/datenbank.md#voraussetzungen-einer-eigenen-postgresql)) | Betrieb, Härtung, Sicherung und Zugriffe der Datenbank |
| APP.4.4 Kubernetes | Container-Plattform | Helm-Chart mit NetworkPolicies und Pod Security „restricted“ ([Netz und Sicherheit](../../handbuch/kubernetes.md#netz-und-sicherheit)) | Cluster-Betrieb |
| APP.6 Allgemeine Software | Anwendung OPAA | Software-Stückliste, Lizenzprüfung ([SBOM](../../sbom.md)) | Beschaffung, Freigabe, Inventarisierung |
| SYS.1.6 Containerisierung | Backend- und Frontend-Image | schlankes Laufzeitimage ohne Shell, nicht-root, nur lesbares Dateisystem ([Backend-Laufzeitimage](../../handbuch/betrieb.md#backend-laufzeitimage)); nächtlicher Image-Scan ([CVE-Erkennung](../../cve-scanning.md)) | Container-Host, Registry-Zugriff, Image-Prüfung vor dem Einsatz |
| SYS.1.1 und SYS.1.3 Server | Host der Container | — | vollständig |
| NET.1.1 Netzarchitektur und -design, NET.3.2 Firewall | Kommunikationsverbindungen | ausgehende Ziele: {{faktenblatt:datenfluesse}}; Ausgangsproxy ([Ausgangsproxy](../../handbuch/betrieb.md#ausgangsproxy)) | Netztrennung, Egress-Regeln, Proxy mit Zielliste |

### Was das Kompendium nicht abdeckt

Für die Risiken eines Sprachmodells, etwa falsche oder erfundene Antworten, Prompt-Injection über
Dokumentinhalte oder den Abfluss von Inhalten an einen Modellanbieter, enthält das Kompendium in der
Edition 2023 keinen eigenen Baustein. Für solche Zielobjekte sieht BSI-Standard 200-2 eine
Risikoanalyse nach
[BSI-Standard 200-3](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Standards-und-Zertifizierung/IT-Grundschutz/BSI-Standards/bsi-standards_node.html)
vor. Anhaltspunkte:

- **Fachliche Richtigkeit:** OPAA nennt zu jeder Antwort die Fundstellen
  ([Suche](../../handbuch/suche.md)); die Nutzungsrichtlinie regelt, dass Antworten zu prüfen sind
  ([Muster-Nutzungsrichtlinie](../08-barrierefreiheit-nutzung/nutzungsrichtlinie.md)).
- **Vertraulichkeit gegenüber dem Modellanbieter:** Ob Inhalte das Haus verlassen, hängt allein an
  den eingetragenen Modell-Endpunkten ({{faktenblatt:datenfluesse}}).
- **Einstufung nach der KI-Verordnung:** [Einstufungshilfe](../07-ki-verordnung-cra/einstufungshilfe-ki-verordnung.md).
- **Prüfkataloge für KI-Dienste:** [AIC4](https://www.bsi.bund.de/DE/Themen/Unternehmen-und-Organisationen/Informationen-und-Empfehlungen/Kuenstliche-Intelligenz/AIC4/aic4.html)
  und der neuere BSI-Prüfkatalog A5 richten sich an Anbieter von KI-Cloud-Diensten. OPAA ist danach
  nicht geprüft; den aktuellen Stand von A5 prüft die Behörde beim BSI.

**Verschlusssachen:** OPAA hat keine Zulassung für Verschlusssachen, auch nicht für VS-NfD. Der
Baustein CON.11.1 (Geheimschutz VS-NfD) ist deshalb kein Weg, OPAA für Verschlusssachen einzusetzen;
die Nutzungsrichtlinie schließt das aus.

## Vorlage für das Sicherheitskonzept

Der folgende Abschnitt kann in das Sicherheitskonzept übernommen werden.

> **Anwendung [Name der Installation] (OPAA)**
>
> - Eingesetzte Version: [X.Y.Z], Image-Digests [Backend, Frontend], eingespielt am [Datum]
> - Betriebsweg: [Docker Compose / Kubernetes mit Helm-Chart X.Y.Z], betrieben von [Stelle oder
>   Dienstleister]
> - Modelldienste: [im Haus / extern bei Anbieter]; Datenflüsse nach dem Faktenblatt der
>   eingesetzten Version
> - Schutzbedarf (Vertraulichkeit, Integrität, Verfügbarkeit): [hoch / normal / …], begründet mit
>   [höchster Schutzbedarf der angebundenen Bibliotheken]
> - Modellierte Bausteine: [Liste aus der Bausteinzuordnung oben, angepasst]
> - Ergänzende Risikoanalyse nach BSI-Standard 200-3 für [KI-spezifische Gefährdungen], Stand
>   [Datum]
> - Notfallvorsorge: Notfallkonzept vom [Datum], letzte Wiederherstellungsprobe am [Datum] mit
>   Version [X.Y.Z]
> - Nächste Fortschreibung: mit dem nächsten Update, spätestens [Datum]

## Weitere Unterlagen dieses Ordners

- [Sicherheitsnachweise des Projekts](sicherheitsnachweise.md): Härtung, Protokollierung, SBOM,
  Schwachstellen
- [NIS2 in der Bundesverwaltung](nis2-bundesverwaltung.md)
- [C5:2026 für das Betriebsmodell Cloud-Anbieter](c5-2026-cloud-anbieter.md)
