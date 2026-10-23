---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Sicherheitsnachweise des Projekts

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Diese Seite sammelt, wo das Projekt die Nachweise führt, die eine Behörde für ihr Sicherheitskonzept,
eine Lieferkettenprüfung oder eine Vergabe braucht. Sie beschreibt die Nachweise nicht nach, sondern
verweist auf die Stelle, die mit dem Code gepflegt wird. Die Nachweise belegen das Verfahren des
Projekts; sie sind keine Zertifizierung und kein Testat.

## Härtung

Die Härtung einer Installation beschreibt das Handbuch im Abschnitt
[Härtung für erreichbare Deployments](../../handbuch/betrieb.md#härtung-für-erreichbare-deployments),
mit einer Tabelle, welche Maßnahmen zwingend sind. Je Betriebsweg kommen hinzu:

- Docker Compose: [Härtung des Compose-Stapels](../../handbuch/docker-compose.md#härtung-des-compose-stapels)
- Kubernetes: [Netz und Sicherheit](../../handbuch/kubernetes.md#netz-und-sicherheit) mit
  NetworkPolicies und Pod Security; die Vorgaben selbst stehen im Helm-Chart unter
  [`deploy/helm/opaa`](../../../deploy/helm/opaa/)
- Identitätsanbieter: [Härtung des mitgelieferten Keycloak](../../handbuch/authentifizierung.md#härtung-des-mitgelieferten-keycloak)

Welche Voreinstellungen die eingesetzte Version mitbringt, steht in {{faktenblatt:voreinstellungen}};
Ports, Netzwege und Ziele ausgehender Verbindungen im
[Netzwerkzugang](../../handbuch/betrieb.md#netzwerkzugang) und in {{faktenblatt:datenfluesse}}.
Diese Vorlage nennt bewusst keine Ports und keine Ziele, weil sie sich mit dem Code ändern können.

**Prüfpunkt für das Haus:** Jede Zeile der Härtungstabelle im Handbuch ist entweder umgesetzt oder
mit Begründung als nicht zutreffend vermerkt. Das Ergebnis gehört zum IT-Grundschutz-Check der
Anwendung ([Grundschutz-Modellierung](grundschutz-modellierung.md)).

## Protokollierung

OPAA führt drei Arten von Aufzeichnungen:

| Aufzeichnung | Inhalt | Wo beschrieben |
|---|---|---|
| Anwendungsprotokoll | Betriebsmeldungen auf der Standardausgabe, auf Wunsch als JSON je Zeile | [Logs und Metriken](../../handbuch/betrieb.md#logs-und-metriken) |
| Nachweis- und Revisionsprotokoll | Handlungen von Personen: Rechtevergaben, Verwaltungsaktionen, Zugriffe auf Protokolldaten | {{faktenblatt:protokollereignisse}}, Fristen in {{faktenblatt:fristen}} |
| Metriken und Alarme | Zustand, Fehlerquoten, Anmeldeversuche, Indexierung | [Logs und Metriken](../../handbuch/betrieb.md#logs-und-metriken), [Alarme des Helm-Charts](../../handbuch/kubernetes.md#alarme) |

Welche Ereignisse das Nachweisprotokoll der eingesetzten Version schreibt, steht ausschließlich im
Faktenblatt. Für die Behörde wichtig:

- **Einsammeln und Aufbewahren** des Anwendungsprotokolls ist Sache des Betriebs; OPAA schreibt auf
  die Standardausgabe, ein Log-Sammler nimmt es dort ab.
- **Eine eigene Ausleitung** des Nachweisprotokolls an ein zentrales Sicherheitsmonitoring ist im
  Konzept beschrieben, aber nicht gebaut
  ([Sicherheit, Nachweis & Prüfbarkeit](../../features/security-and-compliance.md#anbindung-an-ein-zentrales-sicherheitsmonitoring)).
- **Das Anwendungsprotokoll über der Stufe `INFO`** kann Inhalte enthalten; das Handbuch nennt die
  Grenzen ([Logs und Metriken](../../handbuch/betrieb.md#logs-und-metriken)).
- **Auswertung und Mitbestimmung:** Wer Protokolle wie auswerten darf, regelt die Dienstvereinbarung
  ([#2566](https://github.com/criew/opaa/issues/2566)).

## Software-Stückliste (SBOM)

Jedes veröffentlichte Image trägt eine SBOM- und eine Provenance-Attestierung, dazu entstehen
CycloneDX-SBOMs je Ökosystem; der Helm-Chart eines Release trägt eine signierte
Provenance-Attestierung. Abruf und Prüfung: [Software Bill of Materials](../../sbom.md) und
[Was ein Release erzeugt](../../releases.md#was-ein-release-erzeugt). Die Lizenzen der Abhängigkeiten
prüft die CI ([Lizenzprüfung](../../sbom.md#lizenzprüfung)).

## Schwachstellen und Sicherheitsupdates

| Frage | Antwort des Projekts |
|---|---|
| Wie werden bekannte Schwachstellen in Abhängigkeiten erkannt? | Dependabot-Alerts und ein nächtlicher Image-Scan von `main` und dem jüngsten Release ([CVE-Erkennung](../../cve-scanning.md)) |
| Wie schnell reagiert das Projekt? | Reaktionszeiten nach Schwere für Befunde des eigenen Scans ([Triage von Image-Alerts](../../cve-scanning.md#triage-von-image-alerts)); gemeldete Lücken nach bestem Bemühen ohne zugesagte Frist ([`SECURITY.md`](../../../SECURITY.md#ablauf)) |
| Welche Versionen erhalten Sicherheitskorrekturen? | nur das jüngste Release und `main` ([Unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen)) |
| Wie kommt eine Korrektur zur Behörde? | als Patch-Release ([Sicherheitsupdates für Releases](../../releases.md#sicherheitsupdates-für-releases)), veröffentlicht als [GitHub Security Advisory](https://github.com/criew/opaa/security/advisories) |
| Wie meldet die Behörde eine Lücke? | vertraulich über den [Meldeweg](../../../SECURITY.md#meldeweg), nie in einem öffentlichen Issue |
| Welche Pflichten hat das Projekt nach dem Cyber Resilience Act? | [Rollenklärung zum Cyber Resilience Act](rollenklaerung-cra.md) |

**Folge für die Behörde:** Weil nur das jüngste Release Sicherheitskorrekturen erhält, muss der
Betrieb Updates zeitnah einspielen können. Wer eine Version länger festhalten will oder muss,
vereinbart das mit einem Dienstleister; das Projekt sagt keinen längeren Zeitraum zu.

## Weitere Unterlagen dieses Ordners

- [IT-Grundschutz-Modellierung](grundschutz-modellierung.md)
- [NIS2 in der Bundesverwaltung](nis2-bundesverwaltung.md)
- [C5:2026 für das Betriebsmodell Cloud-Anbieter](c5-cloud-betrieb.md)
