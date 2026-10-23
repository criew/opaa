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
| F-01 | ja | [Belege prüfen und Fundstellen bilden](../../handbuch/spaces/suche.md#7-belege-prüfen-und-fundstellen-bilden) | Handbuch |
| F-02 | teilweise; Rechte der Quelle werden nicht übernommen, Leserechte werden je Bibliothek in OPAA vergeben und wirken in jeder Suchabfrage | [Aufbau der Berechtigungen](../../handbuch/konten-und-rechte/berechtigungen.md#1-der-aufbau-in-vier-objekten), {{faktenblatt:rollensicht#matrix}} | Handbuch, Faktenblatt |
| F-03 | je nach Liste der Behörde | [Konnektoren im Überblick](../../handbuch/arbeitsmittel/wissensbibliotheken/konnektoren/README.md#3-die-konnektoren-im-überblick); Verbindungen je Quelle in {{faktenblatt:datenfluesse}} | Handbuch, Faktenblatt |
| F-04 | ja | [Zugelassene Formate](../../handbuch/arbeitsmittel/wissensbibliotheken/formate/README.md#zugelassene-formate) | Handbuch |
| F-05 | ja | [Suchen ohne Antwort](../../handbuch/spaces/suche.md#71-suchen-ohne-antwort) | Handbuch |
| F-06 | ja | [Spracheingabe einschalten](../../handbuch/betrieb/modelle.md#spracheingabe-einschalten), [Endpunkt im Haus](../../handbuch/betrieb/modelle.md#vorhandenen-endpunkt-im-haus-eintragen), {{faktenblatt:voreinstellungen#ki-funktion-speech-transcription}} | Handbuch, Konfiguration, Faktenblatt |
| F-07 | ja | [Was ein externes KI-Werkzeug erreicht](../../handbuch/spaces/fremdzugaenge.md#8-was-ein-externes-ki-werkzeug-erreicht), [Externe KI-Werkzeuge abschalten](../../handbuch/spaces/fremdzugaenge.md#externe-ki-werkzeuge-ganz-abschalten), [Ein eigenes Werkzeug anschließen](../../handbuch/spaces/fremdzugaenge.md#ein-eigenes-werkzeug-anschließen) | Handbuch, Konfiguration |
| F-08 | ja; Vektor- und Volltextsuche laufen zusammen und werden fusioniert, Verfahren und Gewichtung stellt der Betrieb in der Konfiguration ein | [Die Suchstrecke](../../handbuch/spaces/suche.md#4-die-suchstrecke-stufe-für-stufe), [Konfiguration der Suche](../../handbuch/spaces/suche.md#103-konfiguration) | Handbuch, Konfiguration |
| F-09 | ja; ab Werk aus, braucht einen eigenen Modell-Endpunkt | [Reranking](../../handbuch/spaces/suche.md#stufe-8-reranking), [Reranking einschalten](../../handbuch/betrieb/modelle.md#reranking-einschalten) | Handbuch, Konfiguration |
| F-10 | ja; regelbasiert aus Dateieigenschaften und Bibliotheksfeldern, auf Wunsch je Bibliothek zusätzlich modellgestützt (ab Werk aus, Güte hängt am Vokabular des Bestands); wirkt als Filter in der Suche | [Automatische Ermittlung](../../handbuch/arbeitsmittel/wissensbibliotheken/metadaten.md#5-automatische-ermittlung), [Wirkung in der Suche](../../handbuch/arbeitsmittel/wissensbibliotheken/metadaten.md#8-wirkung-in-der-suche), [Güte der modellgestützten Ermittlung](../../handbuch/arbeitsmittel/wissensbibliotheken/metadaten.md#14-güte-der-modellgestützten-ermittlung) | Handbuch |

## M · Modelle und Datenflüsse

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| M-01 | ja | [LLM-Anbieter](../../handbuch/betrieb/modelle.md#llm-anbieter), [OpenAI-kompatible Server](../../handbuch/betrieb/modelle.md#vllm--openai-kompatible-server), {{faktenblatt:voreinstellungen#ki-funktionen}} | Handbuch, Konfiguration, Faktenblatt |
| M-02 | ja | {{faktenblatt:datenfluesse}}; der Wächter lässt den Build scheitern, wenn eine Verbindung nach außen keinen Eintrag hat ([Was bei jeder Änderung geprüft wird](../../handbuch/betrieb/nachvollziehbarkeit.md#was-bei-jeder-änderung-geprüft-wird)) | Faktenblatt, Test |
| M-03 | teilweise; die Ziele sind je Version beschrieben und die Modelle im Haus betreibbar, ein Betrieb ganz ohne Internet ist im Projekt nicht als Probe belegt | {{faktenblatt:datenfluesse}}, [OpenAI-kompatible Server](../../handbuch/betrieb/modelle.md#vllm--openai-kompatible-server) | Faktenblatt, Handbuch |
| M-04 | ja | [Ausgangsproxy](../../handbuch/betrieb/netz-und-sicherheit.md#ausgangsproxy) | Handbuch, Konfiguration |
| M-05 | ja; OPAA ruft den eingetragenen Endpunkt jedes Modells direkt auf, ohne Dienst des Projekts dazwischen | [LLM-Anbieter](../../handbuch/betrieb/modelle.md#llm-anbieter), {{faktenblatt:datenfluesse}} | Handbuch, Faktenblatt |

## D · Datenschutz und Nachvollziehbarkeit

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| D-01 | ja | {{faktenblatt:datenkatalog}} | Faktenblatt |
| D-02 | ja | {{faktenblatt:fristen}} | Faktenblatt, Konfiguration |
| D-03 | ja | {{faktenblatt:protokollereignisse}}, {{faktenblatt:rollensicht#auditor}}, [TOM des Produktanteils](../04-datenschutz/technische-und-organisatorische-massnahmen.md) | Faktenblatt, Projektunterlage |
| D-04 | ja | {{faktenblatt:rollensicht#matrix}} | Faktenblatt |
| D-05 | ja; beim ersten finalen Release erscheint jeder Eintrag als neu | [Update-Paket](../../handbuch/betrieb/releases-und-nachweise.md#das-update-paket), [Update-Paket eines Release](../../handbuch/betrieb/nachvollziehbarkeit.md#das-update-paket-eines-release) | Handbuch |

## S · Sicherheit und Lieferkette

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| S-01 | ja | [Anmeldewege im Betriebsmodus OIDC](../../handbuch/betrieb/authentifizierung.md#anmeldewege-im-betriebsmodus-oidc) | Handbuch, Konfiguration |
| S-02 | ja | [Provenance und Software-Stückliste](../../handbuch/betrieb/releases-und-nachweise.md#provenance-und-software-stückliste) | Handbuch |
| S-03 | ja | [Was ein Release liefert](../../handbuch/betrieb/releases-und-nachweise.md#was-ein-release-liefert) | Handbuch |
| S-04 | ja | [Meldeweg](../../../SECURITY.md#meldeweg), [Ablauf](../../../SECURITY.md#ablauf) | Projektunterlage |
| S-05 | ja | [Read-only Filesystem](../../handbuch/betrieb/images-und-laufzeit.md#read-only-filesystem), [Pod Security](../../handbuch/betrieb/kubernetes.md#pod-security) | Handbuch |
| S-06 | ja; Supportzeiträume je Linie ab dem ersten finalen Release, Scan und Korrekturen heute für `main` und die jüngste Linie | [Schwachstellen](../../handbuch/betrieb/releases-und-nachweise.md#schwachstellen), [Sicherheitsupdates](../../handbuch/betrieb/releases-und-nachweise.md#sicherheitsupdates), [Linien und Supportzeiträume](../../handbuch/betrieb/releases-und-nachweise.md#linien-und-supportzeiträume) | Handbuch, Test |

## B · Betrieb

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| B-01 | ja | [Bausteine](../../handbuch/betrieb/README.md#bausteine) | Handbuch |
| B-02 | ja; mit einer oder mehreren Backend-Instanzen, mit mehreren auch Aktualisierung ohne Ausfall, soweit die neue Version die laufende neben sich zulässt | [Kubernetes-Installation](../../handbuch/betrieb/kubernetes.md#installation), [Docker Compose](../../handbuch/betrieb/docker-compose.md#schnellstart), [Mehrere Backend-Instanzen](../../handbuch/betrieb/mehrere-instanzen.md) | Handbuch |
| B-03 | ja | [Sicherung und Wiederherstellung](../../handbuch/betrieb/sicherung-und-wiederherstellung.md), [Automatisierte Wiederherstellungsprobe](../09-betrieb-update/wiederherstellungsprobe.md#automatisierte-probe-im-projekt) | Handbuch, Test |
| B-04 | ja | [Versionen](../../handbuch/betrieb/releases-und-nachweise.md#versionen), [Vorbereitung für Bestandsinstallationen](../../handbuch/betrieb/releases-und-nachweise.md#was-ein-release-liefert) | Handbuch |
| B-05 | ja | [Readiness und Liveness](../../handbuch/betrieb/images-und-laufzeit.md#readiness-und-liveness), [Betriebsüberwachung](../../handbuch/betrieb/kubernetes.md#betriebsüberwachung) | Handbuch |

## O · Offenheit und Souveränität

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| O-01 | ja; GNU Affero General Public License v3.0 | [Lizenz](../../../LICENSE), [Lizenzprüfung der Abhängigkeiten](../../handbuch/betrieb/releases-und-nachweise.md#lizenzen) | Projektunterlage, Test |
| O-02 | ja | Speicherorte in {{faktenblatt:datenkatalog}}, [Originalablage](../../handbuch/betrieb/originalablage.md#die-drei-wege), [eigene Datenbank](../../handbuch/betrieb/datenbank.md#voraussetzungen-einer-eigenen-postgresql) | Faktenblatt, Handbuch |
| O-03 | ja | [Handbuch](../../handbuch/README.md) | Handbuch |

## N · Nutzung

| Nr. | Erfüllung | Nachweis bei OPAA | Art |
|---|---|---|---|
| N-01 | teilweise; das letzte Audit prüfte einen Entwicklungsstand, nicht eine Version | [Prüfstand der Barrierefreiheit](../08-nutzung/erklaerung-barrierefreiheit.md#prüfstand) | Projektunterlage, Test |

## L · Leistungen des Dienstleisters

| Nr. | Erfüllung | Grundlage aus dem Projekt | Art |
|---|---|---|---|
| L-01 | Dienstleister | [Kubernetes-Installation](../../handbuch/betrieb/kubernetes.md#installation), [Authentifizierung](../../handbuch/betrieb/authentifizierung.md), [Konnektoren](../../handbuch/arbeitsmittel/wissensbibliotheken/konnektoren/README.md) | Handbuch |
| L-02 | Dienstleister | [Notfallkonzept](../09-betrieb-update/notfallkonzept.md), [Mehrere Backend-Instanzen](../../handbuch/betrieb/mehrere-instanzen.md) | Projektunterlage, Handbuch |
| L-03 | Dienstleister; Patch-Releases einer Linie bringen keine neue Funktion, das LTS wechselt einmal im Jahr | [Linien und Supportzeiträume](../../handbuch/betrieb/releases-und-nachweise.md#linien-und-supportzeiträume), [Unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen), [Update-Paket](../../handbuch/betrieb/releases-und-nachweise.md#das-update-paket) | Projektunterlage, Handbuch |
| L-04 | Dienstleister; das Projekt sagt keine Reaktionszeiten zu | [Ablauf bei Sicherheitslücken](../../../SECURITY.md#ablauf) | Projektunterlage |
| L-05 | Dienstleister | [Handbuch](../../handbuch/README.md) | Handbuch |
| L-06 | Dienstleister; eine Übernahme ins Projekt setzt die unterzeichnete CLA voraus, eine nur für die Behörde gepflegte Erweiterung trägt der Dienstleister bei jedem Update selbst nach | [Lizenz](../../../LICENSE), [Contributor License Agreement](../../handbuch/betrieb/releases-und-nachweise.md#lizenzen) | Projektunterlage, Handbuch |
| L-07 | Dienstleister | [Sicherung und Wiederherstellung](../../handbuch/betrieb/kubernetes.md#sicherung-und-wiederherstellung), Speicherorte in {{faktenblatt:datenkatalog}} | Handbuch, Faktenblatt |
| L-08 | Dienstleister | [Rollenklärung zum CRA](../06-informationssicherheit/rollenklaerung-cra.md#die-rollen-im-überblick) | Projektunterlage |

## Gut zu wissen

- Die CI prüft bei jeder Änderung, dass Katalog und Matrix dieselben Nummern führen und jeder
  Verweis sein Ziel trifft. Ob ein
  Nachweis inhaltlich noch trägt, prüft die regelmäßige Durchsicht.
- „Konfiguration“ in der Spalte „Art“ heißt: eine Einstellung der Installation.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Wo die Faktenblätter liegen](../../handbuch/betrieb/nachvollziehbarkeit.md#wo-die-faktenblätter-liegen):
  Faktenblätter und Update-Paket einer Version finden.

**Rechtsgrundlagen** für die Vergabestelle:

| Worum es geht | Fundstelle |
|---|---|
| Leistungsbeschreibung produktneutral, Matrix deshalb getrennt vom Katalog (Wortlaut zu prüfen) | § 31 Abs. 6 [VgV](https://www.gesetze-im-internet.de/vgv_2016/__31.html) |
