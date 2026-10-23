---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Anforderungskatalog

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** öffentliche Auftraggeber, also Behörden und auch kommunale Gesellschaften, die
ausschreiben müssen, oberhalb und unterhalb der EU-Schwellenwerte.

Der Anforderungskatalog ist der Kern der Leistungsbeschreibung, also des Teils der
Vergabeunterlagen, der festlegt, was gekauft wird. Die Bausteine beschreiben einen
Wissensassistenten für die Dokumente einer Behörde und die Leistungen eines Dienstleisters rund um
ihn. Verantwortlich sind Fachbereich und Vergabestelle.

## Was die Vorlage liefert

Bausteine in sieben Gruppen mit Nummer, vorgeschlagener Stufe und verlangtem Nachweis. Die
[Zuschlagskriterien](zuschlagskriterien.md) werten die Kann-Anforderungen; wie ein bestimmtes Produkt
die Bausteine erfüllt, steht getrennt in der [Erfüllungsmatrix](erfuellungsmatrix.md).

## Aufgaben der Behörde

1. **Platzhalter** in eckigen Klammern ausfüllen.
2. **Stufen prüfen:** **Muss** schließt ein Angebot aus, das die Anforderung nicht erfüllt; **Kann**
   fließt in die Wertung ein.
3. **„Muss oder Kann“ entscheiden:** O-01, O-03, D-03, S-03 und S-05 brauchen als Muss eine
   Begründung aus dem Auftragsgegenstand.
4. **Begründungen festhalten** im Vergabevermerk, der fortlaufenden Dokumentation des Verfahrens.
5. **Produktneutral bleiben:** Ein Produktname nur, wenn sich der Auftragsgegenstand sonst nicht genau
   genug beschreiben lässt, und dann mit dem Zusatz „oder gleichwertig“.

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
| F-08 | Sucht semantisch (nach Bedeutung) und nach Stichworten und kombiniert beide; Gewichtung und Verfahren sind konfigurierbar. | Muss | Beschreibung der Suche und ihrer Einstellungen |
| F-09 | Ermöglicht eine Neubewertung der Treffer durch ein eigenes Modell (Reranking), das sich im eigenen Haus betreiben lässt. | Muss | Beschreibung |
| F-10 | Ermittelt beim Aufnehmen Metadaten aus den Dokumenten, etwa Titel, Datum und Dokumentart, und nutzt sie als Filter in der Suche. | Kann | Beschreibung, Vorführung in der Teststellung |

## M · Modelle und Datenflüsse

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| M-01 | Sprach-, Embedding- und gegebenenfalls Reranking-Modell sind frei wählbar und lassen sich im eigenen Rechenzentrum über eine verbreitete Schnittstelle betreiben; kein Zwang zu einem bestimmten Modellanbieter. | Muss | Liste der unterstützten Schnittstellen |
| M-02 | Für jede Version liegt eine vollständige Liste der Verbindungen nach außen vor: Ziel, übermittelte Daten, Anlass, Abschaltbarkeit. | Kann | Liste der Datenflüsse |
| M-03 | Lässt sich ohne Verbindung ins Internet betreiben, wenn alle Modelle im Haus laufen. | Kann | Beschreibung |
| M-04 | Leitet ausgehende Verbindungen auf Wunsch ausschließlich über einen Ausgangsproxy der Behörde. | Kann | Konfigurationsbeschreibung |
| M-05 | Ruft die Modelle direkt über ihre Schnittstelle auf; dafür ist kein Vermittlungsdienst, Proxy oder Konto beim Anbieter der Software nötig. | Muss | Beschreibung der Modellanbindung |

## D · Datenschutz und Nachvollziehbarkeit

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| D-01 | Beschreibt je Version alle gespeicherten Daten mit Zweck, Personenbezug, Speicherort, Frist und Sichtbarkeit, als Grundlage für Verzeichnis und Folgenabschätzung der Behörde. | Kann | Datenbeschreibung der Version |
| D-02 | Aufbewahrungs- und Löschfristen sind dokumentiert und, wo sie Personenbezug haben, konfigurierbar. | Muss | Liste der Fristen |
| D-03 | Führt ein nur fortschreibbares Protokoll sicherheits- und rechterelevanter Vorgänge, das nur eine eigens berechtigte Rolle liest. | Muss oder Kann, Begründung im Vergabevermerk | Liste der Protokollereignisse |
| D-04 | Beschreibt je Rolle, was sie über andere Personen sieht, als Grundlage für die Beteiligung der Personalvertretung. | Kann | Rollenbeschreibung |
| D-05 | Weist zu jeder neuen Version die Änderungen mit Wirkung auf Daten, Sichtbarkeit, Datenflüsse und Voreinstellungen gegenüber der Vorversion aus. | Kann | Beispiel einer Änderungsübersicht |

## S · Sicherheit und Lieferkette

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| S-01 | Anmeldung über den vorhandenen Identitätsanbieter der Behörde per OpenID Connect. | Muss | Beschreibung |
| S-02 | Zu jedem ausgelieferten Artefakt liegt eine Software-Stückliste (SBOM) in einem maschinenlesbaren Standardformat (CycloneDX oder SPDX) vor. | Muss | Beispiel-SBOM |
| S-03 | Die Herkunft der ausgelieferten Artefakte ist prüfbar (Signatur oder Provenance-Attestierung); eine veröffentlichte Version wird nie überschrieben. | Muss oder Kann, Begründung im Vergabevermerk | Prüfanleitung |
| S-04 | Es gibt einen öffentlichen, vertraulichen Meldeweg für Sicherheitslücken; behobene Lücken werden veröffentlicht. | Muss | Sicherheitsrichtlinie |
| S-05 | Die Anwendung läuft als Container ohne Root-Rechte und mit Read-only Filesystem. | Muss oder Kann, Begründung im Vergabevermerk | Betriebsdokumentation |
| S-06 | Die ausgelieferten Artefakte werden regelmäßig auf bekannte Schwachstellen geprüft; Reaktionszeiten je Schwere sind festgelegt. Für jede Version ist veröffentlicht, bis wann sie Sicherheitskorrekturen ohne neue Funktionen erhält. | Kann | Beschreibung des Verfahrens, Supportzeiträume |

## B · Betrieb

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| B-01 | Läuft vollständig in der Infrastruktur der Behörde oder eines von ihr beauftragten Betreibers. | Muss | Architekturbeschreibung |
| B-02 | Lässt sich mit einem versionierten Installationspaket auf [Container-Plattform der Behörde, z. B. Kubernetes oder Einzelhost mit Container-Laufzeit] installieren. | Muss | Installationsanleitung |
| B-03 | Sicherung und Wiederherstellung aller Datenbestände sind dokumentiert; die Wiederherstellung ist erprobt. | Muss | Anleitung, Nachweis einer Probe |
| B-04 | Jede Version ist eindeutig benannt; jede Version nennt die Schritte für Bestandsinstallationen. | Muss | Release-Beschreibung |
| B-05 | Stellt Health Checks und Metriken für die Betriebsüberwachung bereit. | Muss | Betriebsdokumentation |

## O · Offenheit und Souveränität

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| O-01 | Der Quellcode aller ausgelieferten Bestandteile steht unter einer von der Open Source Initiative anerkannten Lizenz; Nutzung und Betrieb kosten kein Lizenzentgelt. | Muss oder Kann, Begründung im Vergabevermerk | Lizenzangabe, Lizenzübersicht der Abhängigkeiten |
| O-02 | Inhalte, Konfiguration und Protokolle liegen in Systemen unter der Hoheit der Behörde und sind ohne den bisherigen Dienstleister zugänglich. | Muss | Beschreibung der Datenhaltung |
| O-03 | Installations-, Betriebs- und Verwaltungsdokumentation ist öffentlich zugänglich, so dass ein anderer Dienstleister den Betrieb übernehmen kann. | Muss oder Kann, Begründung im Vergabevermerk | Fundstelle der Dokumentation |

## N · Nutzung

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| N-01 | Die Oberfläche ist barrierefrei nach BITV 2.0 bzw. [Landesrecht], auch im Intranet; der Prüfstand ist mit Datum und Verfahren angegeben. | Muss | Prüfbericht oder Erklärung zur Barrierefreiheit |

## L · Leistungen des Dienstleisters

| Nr. | Anforderung | Stufe | Nachweis im Angebot |
|---|---|---|---|
| L-01 | **Einführung:** Installation, Anbindung von Identitätsanbieter und Ablagen, Modellanbindung, Konfiguration nach den Vorgaben der Behörde, Abnahme gegen [Abnahmekriterien]. | Muss | Einführungskonzept |
| L-02 | **Betrieb:** Betrieb der Installation mit [Verfügbarkeit], Wiederherstellungszeit [RTO] und Wiederherstellungspunkt [RPO], Sicherung und jährlicher Wiederherstellungsprobe. | Muss oder entfällt bei Eigenbetrieb | Betriebskonzept |
| L-03 | **Pflege:** Einspielen neuer Versionen binnen [Frist] nach Veröffentlichung, sicherheitsrelevanter Versionen binnen [kürzere Frist]; Wechsel auf eine unterstützte Version vor dem Supportende der eingesetzten; Fehleranalyse und Weitergabe an das Projekt. | Muss | Pflegekonzept |
| L-04 | **Support:** Ansprechstelle in deutscher Sprache mit Reaktionszeiten je Schwere [Tabelle der Behörde]. | Muss | Supportkonzept |
| L-05 | **Schulung:** Schulung für Verwaltung und für Anwenderinnen und Anwender, barrierefreie Unterlagen. | Muss | Schulungskonzept |
| L-06 | **Anpassungen:** Erweiterungen werden unter der Lizenz der Software erstellt. Allgemein nützliche Erweiterungen bietet der Dienstleister dem Projekt zur Übernahme an; Erweiterungen nur für die Behörde darf er für sie allein pflegen und passt sie bei jedem Update binnen [Frist] an. | Kann | Eigenerklärung, Liste geplanter Erweiterungen |
| L-07 | **Übergabe am Vertragsende:** Übergabe von Daten, Konfiguration und Betriebsdokumentation an die Behörde oder einen Nachfolger in [Frist]. | Muss | Exit-Konzept |
| L-08 | **Pflichten aus dem Cyber Resilience Act:** Stellt der Dienstleister die Software kommerziell als Produkt bereit, erklärt er, wie er die Pflichten des Herstellers wahrnimmt. | Kann | Eigenerklärung |

## Gut zu wissen

- Bei quelloffener Software werden meist die Leistungen der Gruppe L ausgeschrieben, nicht die
  Software selbst; die übrigen Gruppen beschreiben die Software, die der Dienstleister einführt.
- Eine Muss-Anforderung, die nur wenige Produkte erfüllen, wirkt wie eine verdeckte Produktvorgabe.
  Deshalb stehen D-01, D-04, D-05 und M-02 als Kann.
- Die Nummern bleiben stabil; eine gestrichene gibt ihre Nummer nicht an eine neue ab.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Auftragswertschätzung](auftragswertschaetzung.md): was die Leistungen der Gruppe L kosten können.

**Rechtsgrundlagen** für die Vergabestelle:

| Worum es geht | Fundstelle |
|---|---|
| Produktnamen nur ausnahmsweise und mit „oder gleichwertig“ (Wortlaut zu prüfen) | § 31 Abs. 6 [VgV](https://www.gesetze-im-internet.de/vgv_2016/__31.html) |
| Dasselbe unterhalb der EU-Schwellenwerte | UVgO bzw. Landesrecht, zu prüfen |
| Barrierefreiheit auch im Intranet, für den Bund; Länder mit eigenen Gesetzen | § 12a [BGG](https://www.gesetze-im-internet.de/bgg/__12a.html) |
| Pflichten eines kommerziellen Bereitstellers als Hersteller | [VO (EU) 2024/2847](https://eur-lex.europa.eu/eli/reg/2024/2847/oj/deu) (Cyber Resilience Act) |
