---
stand: 2026-10-17
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-01-15
---

# Anforderungsbausteine für einen KI-gestützten Wissensassistenten

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

## Hinweise zur Verwendung

Die Bausteine beschreiben einen Wissensassistenten, der Fragen aus den Dokumenten einer Behörde
beantwortet, und die Leistungen eines Dienstleisters rund um ihn. Sie nennen bewusst kein Produkt
und keinen Hersteller: Ein Produktverweis ist nur zulässig, wenn der Auftragsgegenstand ihn
rechtfertigt, sonst nur mit dem Zusatz „oder gleichwertig“ (§ 31 Abs. 6 VgV). Wie ein bestimmtes
Produkt die Bausteine erfüllt, steht getrennt davon in der [Erfüllungsmatrix](erfuellungsmatrix.md).

- **Muss** ist ein Ausschlusskriterium: Ein Angebot, das die Anforderung nicht erfüllt, wird nicht
  gewertet. **Kann** fließt in die [Zuschlagskriterien](zuschlagskriterien.md) ein.
- Die Stufe ist ein Vorschlag. Die Behörde passt sie an ihren Bedarf an und hält die Begründung im
  Vergabevermerk fest, besonders bei Anforderungen, die den Bieterkreis einengen (etwa O-01).
- Ausgeschrieben werden bei quelloffener Software meist Einführung, Betrieb, Pflege, Support und
  Schulung, nicht die Software selbst. Die Bausteine der Gruppe L beschreiben diese Leistungen; die
  übrigen Gruppen beschreiben die Software, die der Dienstleister einführt oder betreibt.
- Platzhalter in eckigen Klammern füllt die Behörde aus.
- Nummern bleiben stabil. Eine gestrichene Anforderung behält ihre Nummer nicht für eine neue.

## F · Fachliche Funktion

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| F-01 | Beantwortet Fragen in natürlicher Sprache aus einem festgelegten Dokumentenbestand und belegt jede Antwort mit der Fundstelle im Quelldokument. | Muss | Beschreibung, Vorführung in der Teststellung |
| F-02 | Berücksichtigt in Suche und Antwort nur Dokumente, die die fragende Person lesen darf. | Muss | Beschreibung des Rechtemodells |
| F-03 | Bindet die Ablagen der Behörde an: [Liste der Ablagen und Protokolle, z. B. Dateiablage über SMB, Wiki, Cloud-Ablage]. Produktnamen nur, wenn eine vorhandene Ablage sie erfordert. | Muss | Liste der Anbindungen mit Dokumentation |
| F-04 | Verarbeitet mindestens PDF, Office-Formate nach ODF und OOXML sowie HTML. | Muss | Liste der Formate |
| F-05 | Bietet eine Suche, die nur Treffer liefert und keine Antwort erzeugt. | Kann | Beschreibung |
| F-06 | Nimmt Fragen per Spracheingabe entgegen; die Spracherkennung lässt sich im eigenen Haus betreiben. | Kann | Beschreibung |
| F-07 | Bietet eine dokumentierte Programmierschnittstelle und eine Anbindung für KI-Assistenten über ein offenes Protokoll (z. B. Model Context Protocol), beide abschaltbar. | Kann | Schnittstellenbeschreibung |

## M · Modelle und Datenflüsse

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| M-01 | Sprach-, Embedding- und gegebenenfalls Reranking-Modell sind frei wählbar und lassen sich im eigenen Rechenzentrum über eine verbreitete Schnittstelle betreiben; kein Zwang zu einem bestimmten Modellanbieter. | Muss | Liste der unterstützten Schnittstellen |
| M-02 | Für jede Version liegt eine vollständige Liste der Verbindungen nach außen vor: Ziel, übermittelte Daten, Anlass, Abschaltbarkeit. | Muss | Liste der Datenflüsse |
| M-03 | Lässt sich ohne Verbindung ins Internet betreiben, wenn alle Modelle im Haus laufen. | Kann | Beschreibung |
| M-04 | Leitet ausgehende Verbindungen auf Wunsch ausschließlich über einen Ausgangsproxy der Behörde. | Kann | Konfigurationsbeschreibung |

## D · Datenschutz und Nachvollziehbarkeit

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| D-01 | Beschreibt je Version alle gespeicherten Daten mit Zweck, Personenbezug, Speicherort, Frist und Sichtbarkeit, als Grundlage für Verzeichnis und Folgenabschätzung der Behörde. | Muss | Datenbeschreibung der Version |
| D-02 | Aufbewahrungs- und Löschfristen sind dokumentiert und, wo sie Personenbezug haben, konfigurierbar. | Muss | Liste der Fristen |
| D-03 | Führt ein nur fortschreibbares Protokoll sicherheits- und rechterelevanter Vorgänge, das nur eine eigens berechtigte Rolle liest. | Muss | Liste der Protokollereignisse |
| D-04 | Beschreibt je Rolle, was sie über andere Personen sieht, als Grundlage für die Beteiligung der Personalvertretung. | Muss | Rollenbeschreibung |
| D-05 | Weist zu jeder neuen Version die Änderungen mit Wirkung auf Daten, Sichtbarkeit, Datenflüsse und Voreinstellungen gegenüber der Vorversion aus. | Muss | Beispiel einer Änderungsübersicht |

## S · Sicherheit und Lieferkette

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| S-01 | Anmeldung über den vorhandenen Identitätsanbieter der Behörde per OpenID Connect. | Muss | Beschreibung |
| S-02 | Zu jedem ausgelieferten Artefakt liegt eine Software-Stückliste (SBOM) in einem maschinenlesbaren Standardformat (CycloneDX oder SPDX) vor. | Muss | Beispiel-SBOM |
| S-03 | Die Herkunft der ausgelieferten Artefakte ist prüfbar (Signatur oder Provenance-Attestierung); eine veröffentlichte Version wird nie überschrieben. | Muss | Prüfanleitung |
| S-04 | Es gibt einen öffentlichen, vertraulichen Meldeweg für Sicherheitslücken; behobene Lücken werden veröffentlicht. | Muss | Sicherheitsrichtlinie |
| S-05 | Die Anwendung läuft als Container ohne Root-Rechte und mit nur lesbarem Dateisystem. | Muss | Betriebsdokumentation |
| S-06 | Die ausgelieferten Artefakte werden regelmäßig auf bekannte Schwachstellen geprüft; Reaktionszeiten je Schwere sind festgelegt. | Kann | Beschreibung des Verfahrens |

## B · Betrieb

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| B-01 | Läuft vollständig in der Infrastruktur der Behörde oder eines von ihr beauftragten Betreibers. | Muss | Architekturbeschreibung |
| B-02 | Lässt sich mit einem versionierten Installationspaket auf [Container-Plattform der Behörde, z. B. Kubernetes oder Einzelhost mit Container-Laufzeit] installieren. | Muss | Installationsanleitung |
| B-03 | Sicherung und Wiederherstellung aller Datenbestände sind dokumentiert; die Wiederherstellung ist erprobt. | Muss | Anleitung, Nachweis einer Probe |
| B-04 | Jede Version ist eindeutig benannt; jede Version nennt die Schritte für Bestandsinstallationen. | Muss | Release-Beschreibung |
| B-05 | Stellt Gesundheitsprüfungen und Metriken für die Betriebsüberwachung bereit. | Muss | Betriebsdokumentation |

## O · Offenheit und Souveränität

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| O-01 | Der Quellcode aller ausgelieferten Bestandteile steht unter einer von der Open Source Initiative anerkannten Lizenz; Nutzung und Betrieb kosten kein Lizenzentgelt. | Muss oder Kann, Begründung im Vergabevermerk | Lizenzangabe, Lizenzübersicht der Abhängigkeiten |
| O-02 | Inhalte, Konfiguration und Protokolle liegen in Systemen unter der Hoheit der Behörde und sind ohne den bisherigen Dienstleister zugänglich. | Muss | Beschreibung der Datenhaltung |
| O-03 | Installations-, Betriebs- und Verwaltungsdokumentation ist öffentlich zugänglich, so dass ein anderer Dienstleister den Betrieb übernehmen kann. | Muss | Fundstelle der Dokumentation |

## N · Nutzung

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| N-01 | Die Oberfläche ist barrierefrei nach BITV 2.0, auch im Intranet (§ 12a BGG); der Prüfstand ist mit Datum und Verfahren angegeben. | Muss | Prüfbericht oder Erklärung zur Barrierefreiheit |

## L · Leistungen des Dienstleisters

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| L-01 | **Einführung:** Installation, Anbindung von Identitätsanbieter und Ablagen, Modellanbindung, Konfiguration nach den Vorgaben der Behörde, Abnahme gegen [Abnahmekriterien]. | Muss | Einführungskonzept |
| L-02 | **Betrieb:** Betrieb der Installation mit [Verfügbarkeit], Wiederherstellungszeit [RTO] und Wiederherstellungspunkt [RPO], Sicherung und jährlicher Wiederherstellungsprobe. | Muss oder entfällt bei Eigenbetrieb | Betriebskonzept |
| L-03 | **Pflege:** Einspielen neuer Versionen binnen [Frist] nach Veröffentlichung, sicherheitsrelevanter Versionen binnen [kürzere Frist]; Fehleranalyse und Weitergabe an das Projekt. | Muss | Pflegekonzept |
| L-04 | **Support:** Ansprechstelle in deutscher Sprache mit Reaktionszeiten je Schwere [Tabelle der Behörde]. | Muss | Supportkonzept |
| L-05 | **Schulung:** Schulung für Verwaltung und für Anwenderinnen und Anwender, barrierefreie Unterlagen. | Muss | Schulungskonzept |
| L-06 | **Anpassungen:** Erweiterungen werden unter der Lizenz der Software erstellt und dem Projekt zur Übernahme angeboten. | Kann | Eigenerklärung |
| L-07 | **Übergabe am Vertragsende:** Übergabe von Daten, Konfiguration und Betriebsdokumentation an die Behörde oder einen Nachfolger in [Frist]. | Muss | Exit-Konzept |
| L-08 | **Pflichten aus dem Cyber Resilience Act:** Stellt der Dienstleister die Software kommerziell als Produkt bereit, erklärt er, wie er die Pflichten des Herstellers wahrnimmt. | Kann | Eigenerklärung |

## Quellen und Prüfstand

| Aussage | Fundstelle | Herkunft |
|---|---|---|
| Produktverweis nur, wenn der Auftragsgegenstand ihn rechtfertigt, sonst mit „oder gleichwertig“ | [§ 31 Abs. 6 VgV](https://www.gesetze-im-internet.de/vgv_2016/__31.html) | Normtext; Recherche vom 13.10.2026 |
| BITV 2.0 gilt auch im Intranet | [§ 12a BGG](https://www.gesetze-im-internet.de/bgg/__12a.html) | Normtext; Recherche vom 13.10.2026 |
| Pflichten eines kommerziellen Bereitstellers als Hersteller | [VO (EU) 2024/2847](https://eur-lex.europa.eu/eli/reg/2024/2847/oj/deu), eingeordnet in der [Rollenklärung zum CRA](../07-ki-verordnung-cra/rollenklaerung-cra.md) | Normtext; Recherche vom 13.10.2026 |

Die Recherche vom 13.10.2026 steht im ersten Kommentar von Epic
[#2542](https://github.com/criew/opaa/issues/2542). Unterhalb der EU-Schwellenwerte gelten die UVgO
oder Landesrecht; ob sie eine § 31 Abs. 6 VgV entsprechende Regel enthalten, prüft die Behörde für
ihr Land (zu prüfen).
