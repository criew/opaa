---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Erfüllungsmatrix

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** öffentliche Auftraggeber, also Behörden und auch kommunale Gesellschaften, die
ausschreiben müssen, oberhalb und unterhalb der EU-Schwellenwerte.

Die Matrix ordnet jedem Baustein des [Anforderungskatalogs](anforderungskatalog.md) den Nachweis zu,
mit dem OPAA ihn erfüllt. Sie gehört **nicht** in die Leistungsbeschreibung, die produktneutral
bleiben muss, sondern in Markterkundung, Teststellung und die Prüfung eines Angebots, das OPAA
anbietet.

## Was OPAA liefert

Je Baustein einen Nachweis: Handbuch, Projektunterlage, eine Prüfung in der CI des Projekts oder ein
Faktenblatt. Ein Platzhalter wie {{faktenblatt:datenkatalog}} steht für das Blatt der angebotenen
Version im Update-Paket ([Faktenblätter](../README.md#faktenblätter)).

## Aufgaben der Behörde

1. **Faktenblätter der angebotenen Version** der Prüfung beilegen, statt Werte abzuschreiben.
2. **Nachweise öffnen,** besonders bei Muss-Anforderungen.
3. **Lücken klären:** „teilweise“ nennt, was fehlt; das klärt die Teststellung oder der Bieter.
4. **Zusagen des Bieters prüfen,** wo „Dienstleister“ steht: Dort liefert das Projekt nur die
   Grundlage.

## F · Fachliche Funktion

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| F-01 | ja | [Belege prüfen und Fundstellen bilden](../../handbuch/suche.md#7-belege-prüfen-und-fundstellen-bilden) | Handbuch |
| F-02 | teilweise; Rechte der Quelle werden nicht übernommen, Leserechte werden je Bibliothek in OPAA vergeben und wirken in jeder Suchabfrage | [Aufbau der Berechtigungen](../../handbuch/berechtigungen.md#1-der-aufbau-in-vier-objekten), {{faktenblatt:rollensicht#matrix}} | Handbuch, Faktenblatt |
| F-03 | je nach Liste der Behörde | [Konnektoren im Überblick](../../handbuch/konnektoren/README.md#3-die-konnektoren-im-überblick); Verbindungen je Quelle in {{faktenblatt:datenfluesse}} | Handbuch, Faktenblatt |
| F-04 | ja | [Zugelassene Formate](../../handbuch/formate/README.md#zugelassene-formate) | Handbuch |
| F-05 | ja | [Suchen ohne Antwort](../../handbuch/suche.md#71-suchen-ohne-antwort) | Handbuch |
| F-06 | ja | [Spracheingabe einschalten](../../handbuch/modelle.md#spracheingabe-einschalten), [Endpunkt im Haus](../../handbuch/modelle.md#vorhandenen-endpunkt-im-haus-eintragen), {{faktenblatt:voreinstellungen#ki-funktion-speech-transcription}} | Handbuch, Konfiguration, Faktenblatt |
| F-07 | ja | [Was ein externes KI-Werkzeug erreicht](../../handbuch/fremdzugaenge.md#8-was-ein-externes-ki-werkzeug-erreicht), [Externe KI-Werkzeuge abschalten](../../handbuch/fremdzugaenge.md#externe-ki-werkzeuge-ganz-abschalten), [Ein eigenes Werkzeug anschließen](../../handbuch/fremdzugaenge.md#ein-eigenes-werkzeug-anschließen) | Handbuch, Konfiguration |

## M · Modelle und Datenflüsse

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| M-01 | ja | [LLM-Anbieter](../../handbuch/modelle.md#llm-anbieter), [OpenAI-kompatible Server](../../handbuch/modelle.md#vllm--openai-kompatible-server), {{faktenblatt:voreinstellungen#ki-funktionen}} | Handbuch, Konfiguration, Faktenblatt |
| M-02 | ja | {{faktenblatt:datenfluesse}}; der Wächter lässt den Build scheitern, wenn eine Verbindung nach außen keinen Eintrag hat ([Was bei jeder Änderung geprüft wird](../../handbuch/nachvollziehbarkeit.md#was-bei-jeder-änderung-geprüft-wird)) | Faktenblatt, Test |
| M-03 | teilweise; die Ziele sind je Version beschrieben und die Modelle im Haus betreibbar, ein Betrieb ganz ohne Internet ist im Projekt nicht als Probe belegt | {{faktenblatt:datenfluesse}}, [OpenAI-kompatible Server](../../handbuch/modelle.md#vllm--openai-kompatible-server) | Faktenblatt, Handbuch |
| M-04 | ja | [Ausgangsproxy](../../handbuch/betrieb.md#ausgangsproxy) | Handbuch, Konfiguration |

## D · Datenschutz und Nachvollziehbarkeit

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| D-01 | ja | {{faktenblatt:datenkatalog}} | Faktenblatt |
| D-02 | ja | {{faktenblatt:fristen}} | Faktenblatt, Konfiguration |
| D-03 | ja | {{faktenblatt:protokollereignisse}}, {{faktenblatt:rollensicht#auditor}}, [TOM des Produktanteils](../04-datenschutz/technische-und-organisatorische-massnahmen.md) | Faktenblatt, Projektunterlage |
| D-04 | ja | {{faktenblatt:rollensicht#matrix}} | Faktenblatt |
| D-05 | ja; beim ersten finalen Release erscheint jeder Eintrag als neu | [Update-Paket](../../handbuch/releases-und-nachweise.md#das-update-paket), [Update-Paket eines Release](../../handbuch/nachvollziehbarkeit.md#das-update-paket-eines-release) | Handbuch |

## S · Sicherheit und Lieferkette

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| S-01 | ja | [Anmeldewege im Betriebsmodus OIDC](../../handbuch/authentifizierung.md#anmeldewege-im-betriebsmodus-oidc) | Handbuch, Konfiguration |
| S-02 | ja | [Herkunft und Software-Stückliste](../../handbuch/releases-und-nachweise.md#herkunft-und-software-stückliste) | Handbuch |
| S-03 | ja | [Was ein Release liefert](../../handbuch/releases-und-nachweise.md#was-ein-release-liefert) | Handbuch |
| S-04 | ja | [Meldeweg](../../../SECURITY.md#meldeweg), [Ablauf](../../../SECURITY.md#ablauf) | Projektunterlage |
| S-05 | ja | [Nur lesbares Dateisystem](../../handbuch/betrieb.md#nur-lesbares-dateisystem), [Pod Security](../../handbuch/kubernetes.md#pod-security) | Handbuch |
| S-06 | ja, für `main` und das jüngste Release | [Schwachstellen](../../handbuch/releases-und-nachweise.md#schwachstellen), [Sicherheitsupdates](../../handbuch/releases-und-nachweise.md#sicherheitsupdates) | Handbuch, Test |

## B · Betrieb

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| B-01 | ja | [Bausteine](../../handbuch/betrieb.md#bausteine) | Handbuch |
| B-02 | ja; mit einer oder mehreren Backend-Instanzen, mit mehreren auch Aktualisierung ohne Ausfall, soweit die neue Version die laufende neben sich zulässt | [Kubernetes-Installation](../../handbuch/kubernetes.md#installation), [Docker Compose](../../handbuch/docker-compose.md#schnellstart), [Mehrere Backend-Instanzen](../../handbuch/mehrere-instanzen.md) | Handbuch |
| B-03 | ja | [Sicherung und Wiederherstellung](../../handbuch/betrieb.md#sicherung-und-wiederherstellung), [Automatisierte Wiederherstellungsprobe](../09-betrieb-update/wiederherstellungsprobe.md#automatisierte-probe-im-projekt) | Handbuch, Test |
| B-04 | ja | [Versionen](../../handbuch/releases-und-nachweise.md#versionen), [Vorbereitung für Bestandsinstallationen](../../handbuch/releases-und-nachweise.md#was-ein-release-liefert) | Handbuch |
| B-05 | ja | [Bereitschaft und Lebendigkeit](../../handbuch/betrieb.md#bereitschaft-und-lebendigkeit), [Betriebsüberwachung](../../handbuch/kubernetes.md#betriebsüberwachung) | Handbuch |

## O · Offenheit und Souveränität

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| O-01 | ja; GNU Affero General Public License v3.0 | [Lizenz](../../../LICENSE), [Lizenzprüfung der Abhängigkeiten](../../handbuch/releases-und-nachweise.md#lizenzen) | Projektunterlage, Test |
| O-02 | ja | Speicherorte in {{faktenblatt:datenkatalog}}, [Originalablage](../../handbuch/originalablage.md#die-drei-wege), [eigene Datenbank](../../handbuch/datenbank.md#voraussetzungen-einer-eigenen-postgresql) | Faktenblatt, Handbuch |
| O-03 | ja | [Handbuch](../../handbuch/README.md) | Handbuch |

## N · Nutzung

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| N-01 | teilweise; das letzte Audit prüfte einen Entwicklungsstand, nicht eine Version | [Prüfstand der Barrierefreiheit](../08-nutzung/erklaerung-barrierefreiheit.md#prüfstand) | Projektunterlage, Test |

## L · Leistungen des Dienstleisters

| Nr. | Erfüllung | Grundlage aus dem Projekt | Art |
|---|---|---|---|
| L-01 | Dienstleister | [Kubernetes-Installation](../../handbuch/kubernetes.md#installation), [Authentifizierung](../../handbuch/authentifizierung.md), [Konnektoren](../../handbuch/konnektoren/README.md) | Handbuch |
| L-02 | Dienstleister | [Notfallkonzept](../09-betrieb-update/notfallkonzept.md), [Mehrere Backend-Instanzen](../../handbuch/mehrere-instanzen.md) | Projektunterlage, Handbuch |
| L-03 | Dienstleister | [Unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen), [Update-Paket](../../handbuch/releases-und-nachweise.md#das-update-paket); Supportzeiträume sind offen ([EVB-IT-Hinweise](evb-it-hinweise.md#programmstände-sbom-und-supportzeiträume)) | Projektunterlage, Handbuch |
| L-04 | Dienstleister; das Projekt sagt keine Reaktionszeiten zu | [Ablauf bei Sicherheitslücken](../../../SECURITY.md#ablauf) | Projektunterlage |
| L-05 | Dienstleister | [Handbuch](../../handbuch/README.md) | Handbuch |
| L-06 | Dienstleister; eine Übernahme ins Projekt setzt die unterzeichnete CLA voraus | [Lizenz](../../../LICENSE), [Contributor License Agreement](../../handbuch/releases-und-nachweise.md#lizenzen) | Projektunterlage, Handbuch |
| L-07 | Dienstleister | [Sicherung und Wiederherstellung](../../handbuch/kubernetes.md#sicherung-und-wiederherstellung), Speicherorte in {{faktenblatt:datenkatalog}} | Handbuch, Faktenblatt |
| L-08 | Dienstleister | [Rollenklärung zum CRA](../06-informationssicherheit/rollenklaerung-cra.md#die-rollen-im-überblick) | Projektunterlage |

## Gut zu wissen

- Die CI prüft bei jeder Änderung, dass Katalog und Matrix dieselben Nummern führen und jeder
  Verweis sein Ziel trifft ([Automatische Prüfung](../AGENTS.md#automatische-prüfung)). Ob ein
  Nachweis inhaltlich noch trägt, prüft die regelmäßige Durchsicht.
- „Konfiguration“ in der Spalte „Art“ heißt: eine Einstellung der Installation.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Wo die Faktenblätter liegen](../../handbuch/nachvollziehbarkeit.md#wo-die-faktenblätter-liegen):
  Faktenblätter und Update-Paket einer Version finden.

**Rechtsgrundlagen** für die Vergabestelle:

| Worum es geht | Fundstelle |
|---|---|
| Leistungsbeschreibung produktneutral, Matrix deshalb getrennt vom Katalog (Wortlaut zu prüfen) | § 31 Abs. 6 [VgV](https://www.gesetze-im-internet.de/vgv_2016/__31.html) |
