---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Sicherheitsnachweise

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden.

Für ihr Sicherheitskonzept, eine Prüfung der Lieferkette oder eine Vergabe braucht eine Behörde
Belege, wie das Projekt OPAA absichert. Diese Seite sagt, wo sie stehen; gepflegt werden sie mit dem
Code. Die Nachweise belegen das Verfahren des Projekts, sie sind keine Zertifizierung und kein Testat.

> **Beispiel.** Die Informationssicherheit eines Landratsamts arbeitet vor dem Start die
> Härtungstabelle des Handbuchs durch und vermerkt je Zeile „umgesetzt“ oder „trifft nicht zu, weil
> …“. Der Betrieb sammelt das Anwendungsprotokoll mit dem vorhandenen Log-Sammler ein und abonniert
> die Sicherheitsmeldungen des Projekts.

## Was OPAA liefert

| Nachweis | Was er belegt | Wo er steht |
|---|---|---|
| Härtung | welche Maßnahmen zwingend sind, je Betriebsweg | [Härtung](../../handbuch/betrieb.md#härtung-für-erreichbare-deployments), [Docker Compose](../../handbuch/docker-compose.md#härtung-des-compose-stapels), [Kubernetes](../../handbuch/kubernetes.md#netz-und-sicherheit), [Keycloak](../../handbuch/authentifizierung.md#härtung-des-mitgelieferten-keycloak); Voreinstellungen in {{faktenblatt:voreinstellungen}} |
| Protokollierung | Anwendungsprotokoll, Nachweisprotokoll über Handlungen von Personen, Metriken und Alarme | [Logs und Metriken](../../handbuch/betrieb.md#logs-und-metriken), {{faktenblatt:protokollereignisse}}, {{faktenblatt:fristen}} |
| Software-Stückliste (SBOM) | woraus jedes Image besteht, Herkunftsnachweise, Lizenzprüfung | [SBOM](../../sbom.md), [Was ein Release erzeugt](../../releases.md#was-ein-release-erzeugt) |
| Schwachstellen | Erkennung in Abhängigkeiten und Images, Reaktionszeiten nach Schwere | [CVE-Erkennung](../../cve-scanning.md), [`SECURITY.md`](../../../SECURITY.md#ablauf) |
| Sicherheitsupdates | Patch-Releases, veröffentlichte Lücken | [Sicherheitsupdates](../../releases.md#sicherheitsupdates-für-releases), [Security Advisories](https://github.com/criew/opaa/security/advisories) |
| Pflichten des Projekts | Rolle nach dem Cyber Resilience Act | [Rollenklärung](rollenklaerung-cra.md) |

## Aufgaben der Behörde

1. **Härtungstabelle abarbeiten:** jede Zeile umgesetzt oder begründet als nicht zutreffend
   vermerkt; das Ergebnis gehört zum [IT-Grundschutz](grundschutz-modellierung.md)-Check.
2. **Protokolle einsammeln und aufbewahren.** OPAA schreibt das Anwendungsprotokoll auf die
   Standardausgabe; ein Log-Sammler des Betriebs nimmt es dort ab.
3. **Auswertung regeln:** Wer Protokolle wie auswerten darf, gehört in die Dienstvereinbarung.
4. **Updates zeitnah einspielen.** Sicherheitskorrekturen erhalten nur das jüngste Release und `main`
   ([unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen)).
5. **Lücken vertraulich melden** über den [Meldeweg](../../../SECURITY.md#meldeweg), nie in einem
   öffentlichen Issue.

## Gut zu wissen

- Gemeldete Lücken bearbeitet das Projekt nach bestem Bemühen, ohne zugesagte Frist. Wer eine Version
  länger festhalten muss oder Fristen braucht, vereinbart das mit einem Dienstleister.
- Schreibt das Anwendungsprotokoll ausführlicher als auf Stufe `INFO`, kann es Inhalte enthalten und
  ist entsprechend zu schützen ([Logs und Metriken](../../handbuch/betrieb.md#logs-und-metriken)).
- Eine Ausleitung des Nachweisprotokolls an ein zentrales Sicherheitsmonitoring ist
  [beschrieben](../../features/security-and-compliance.md#anbindung-an-ein-zentrales-sicherheitsmonitoring),
  aber nicht gebaut.

## Zum Nachlesen

**Arbeitshilfen:**

- [`SECURITY.md`](../../../SECURITY.md): Meldeweg, unterstützte Versionen und Ablauf bis zur
  Veröffentlichung einer Lücke.
- [Triage von Image-Alerts](../../cve-scanning.md#triage-von-image-alerts): wie schnell das Projekt
  auf Befunde des eigenen Scans reagiert.

**Rechtsgrundlagen:**

| Worum es geht | Fundstelle |
|---|---|
| Pflichten des Projekts als Open-Source-Steward | Art. 24 [Cyber Resilience Act](https://eur-lex.europa.eu/eli/reg/2024/2847/oj/deu), siehe [Rollenklärung](rollenklaerung-cra.md) |
