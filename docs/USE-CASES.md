# Anwendungsfälle im Verwaltungsalltag

Zehn Anwendungsfälle aus verschiedenen Nutzerperspektiven, mit Schwerpunkt **Finanzverwaltung**
(Steuerverwaltung, Kämmerei, Rechnungsprüfung, Bezüge und Beihilfe).

**Wozu dieses Dokument:** Die [Produktvision](./VISION.md) beschreibt Fähigkeiten, nicht Alltag.
Anwendungsfälle machen daraus etwas, das eine Sachbearbeiterin oder ein Kämmerer wiedererkennt — und sie
zeigen, dass die elf Themenbereiche zusammen einen Arbeitstag tragen und nicht einzeln.

**Aufbau je Fall:** Perspektive · Situation heute · Ablauf mit OPAA · genutzte Themenbereiche ·
Verteilungs-Effekt (was daraus ein teilbares KI-Asset macht) · Phase. Die Bezeichnungen der Themenbereiche
A–K und der Phasen 1–4 stammen aus [VISION.md](./VISION.md); die Begriffe *Space*, *Asset*,
*Wissensbibliothek* und *Verteilungsstufe* sind in
[features/spaces-and-assets.md](./features/spaces-and-assets.md) und [CONCEPTS.md](./CONCEPTS.md)
definiert.

**Querschnittsrahmen Finanzverwaltung:** Das **Steuergeheimnis (§ 30 AO)** verbietet praktisch jede
Verarbeitung von Steuerdaten in fremden Clouds. Alle Fälle hier setzen deshalb lokal betriebene Modelle,
rechtebewusste Suche und ein revisionssicheres Protokoll voraus. Diese drei Eigenschaften sind keine
Zusatzausstattung, sondern die Bedingung dafür, dass die Fälle überhaupt zulässig sind.

---

## Übersicht

| # | Anwendungsfall | Perspektive | Schwerpunkt-Bereiche | Phase |
|---|---|---|---|---|
| 1 | Fachfrage zur Rechtslage belegt beantworten | Sachbearbeitung Veranlagung | A · B · F | 1 |
| 2 | Einspruch aufarbeiten und Entwurf vorbereiten | Rechtsbehelfsstelle | A · D · K | 1–2 |
| 3 | Haushaltsplan-Entwürfe vergleichen und aufbereiten | Kämmerei / Haushaltsreferat | D · A · H | 2 |
| 4 | Vergabe- und Belegprüfung mit Prüfvermerk | Rechnungsprüfungsamt | A · D · G | 2 |
| 5 | Auskunft an Beschäftigte in verständlicher Sprache | Bezüge- und Beihilfestelle | D · C · K | 1 |
| 6 | Aus einem guten Agenten den Standard machen | Sachgebietsleitung | **C** · D · H | 2–3 |
| 7 | KI zentral steuern statt lokal dulden | KI-Koordination / Digitalisierung | **E** · H · F | 1 |
| 8 | Souverän betreiben und Modelle wechseln | IT-Leitung / Betrieb | J · E · G · F | 1 |
| 9 | Vollstreckungsvorgänge vorbereiten mit Freigabe | Vollstreckungsstelle | B · D · G | 2 |
| 10 | Stellungnahmen auswerten und Synopse erstellen | Grundsatz- / Fachreferat | A · D · I | 2–3 |

---

## 1 · Fachfrage zur Rechtslage belegt beantworten

**Perspektive:** Sachbearbeiterin in der Veranlagung eines Finanzamts.

**Situation heute:** Eine Frage zur Abgrenzung von Erhaltungsaufwand und Herstellungskosten. Die Antwort
steckt in einem BMF-Schreiben, einem Anwendungserlass, zwei Verfügungen der Oberfinanzdirektion und einer
internen Arbeitsanweisung — verteilt über Netzlaufwerk, Intranet und eine Ordnerstruktur, die niemand mehr
vollständig kennt. Erfahrene Kollegen werden gefragt; wer sie nicht hat, sucht zwanzig Minuten oder rät.

**Ablauf mit OPAA:**

1. Frage im Chat des Space „Veranlagung", in Umgangssprache gestellt.
2. OPAA durchsucht die zugeordneten Wissensbibliotheken hybrid (Vektor und Volltext) und rankt die Treffer
   nach.
3. Die Antwort nennt **jede Aussage mit Fundstelle** — Dokument, Randnummer, Sprung zur Textstelle — dazu
   die Konfidenz.
4. Ist die Lage nicht belegbar, verweigert OPAA im **Zitierzwang-Modus** die Antwort, statt zu raten. Für
   eine Auskunft, die jemand mit seinem Namen trägt, ist „nicht feststellbar" das brauchbarere Ergebnis.
5. Die Sachbearbeiterin prüft die Fundstelle selbst und übernimmt sie in den Vermerk.

**Themenbereiche:** A (Zitierzwang, Konfidenz, hybride Suche, erklärbares Chunking) · B (Konnektoren zu
Dateiablagen und Intranet, selbst aktualisierende Bestände) · F (rechtebewusste Suche — sie sieht nur, was
ihre Rolle sehen darf).

**Verteilungs-Effekt:** Die Wissensbibliothek „Ertragsteuerrecht" wird einmal kuratiert und von allen
Veranlagungsteams genutzt. Wer einen guten Suchprompt findet, legt ihn in die Prompt-Bibliothek des Space.

**Phase 1.**

---

## 2 · Einspruch aufarbeiten und Entwurf vorbereiten

**Perspektive:** Bearbeiter in der Rechtsbehelfsstelle.

**Situation heute:** Ein Einspruch mit 40 Seiten Anlagen, teils gescannt, teils handschriftlich ergänzt.
Der Sachverhalt muss herausgearbeitet, die Rechtslage geprüft und eine Einspruchsentscheidung entworfen
werden. Der zeitfressende Teil ist nicht die juristische Wertung, sondern das Zusammentragen.

**Ablauf mit OPAA:**

1. Der Vorgang wird in den Chat gegeben; OPAA erkennt die Formate, extrahiert auch aus Scans und liest
   handschriftliche Randnotizen (modellabhängig).
2. Ein Agent „Einspruch aufarbeiten" erzeugt eine strukturierte Sachverhaltsdarstellung: Antragsteller,
   Streitgegenstand, Zeitschiene, vorgetragene Argumente — jeweils mit Seitenverweis.
3. **Deep Research** über die Wissensbibliothek „Rechtsbehelf" liefert vergleichbare Fälle und die
   einschlägigen Fundstellen als zitierbaren Bericht.
4. OPAA entwirft die Begründungsstruktur mit Fundstellen. Die Wertung bleibt beim Menschen — der Entwurf
   ist Rohmaterial, nicht Entscheidung.
5. Jeder Schritt ist protokolliert; die Fundstellen sind einzeln prüfbar.

**Themenbereiche:** A (Multi-Format einschließlich Scan und Handschrift, Deep Research, an die Quelle
gebundene Zitate) · D (Agent mit Aufgabenbeschreibung, Textwerkzeuge, Datei-Export) · K
(Revisionssicherheit, Amtssprache).

**Verteilungs-Effekt:** Der Agent „Einspruch aufarbeiten" ist der klassische Kandidat für den
Asset-Katalog — gebaut von einem erfahrenen Bearbeiter, geprüft von der Sachgebietsleitung, genutzt von der
ganzen Rechtsbehelfsstelle. Siehe Fall 6.

**Phase 1–2** (Grundlage in Phase 1, Agent und Deep Research in Phase 2).

---

## 3 · Haushaltsplan-Entwürfe vergleichen und aufbereiten

**Perspektive:** Mitarbeiterin der Kämmerei einer Kommune, Haushaltsaufstellung.

**Situation heute:** Achtzehn Fachämter liefern Budgetanmeldungen als Tabellenkalkulationen, jedes mit
eigener Struktur. Die Kämmerei konsolidiert manuell, sucht Abweichungen zum Vorjahr, schreibt Rückfragen
und baut am Ende Folien für den Finanzausschuss. Jedes Jahr dieselbe Fleißarbeit.

**Ablauf mit OPAA:**

1. Alle Anmeldungen in den Space „Haushalt 2027".
2. In der **isolierten Ausführungsumgebung** führt OPAA echte Auswertungen aus: Konsolidierung, Abweichung
   zum Vorjahr je Produkt und Kostenart, Auffälligkeiten oberhalb einer Schwelle.
3. Ergebnis als Arbeitsmappe samt **Diagrammen**; die auffälligen Positionen als Liste mit Bezug auf Zeile
   und Datei.
4. OPAA formuliert die Rückfragen an die Fachämter aus einer geprüften Vorlage.
5. Aus der Auswertung entsteht ein **Folienentwurf** für den Finanzausschuss — Zahlen belegt,
   Kommentierung im Amtsstil.

**Themenbereiche:** D (Ausführungsumgebung für Auswertungen, Tabellen, Diagramme und Folien) · A
(Tabellenverständnis) · H (Transparenz über Nutzung und Verbrauch).

**Verteilungs-Effekt:** Der Ablauf wird ein Agent „Haushaltsanmeldungen konsolidieren", der jedes Jahr
wieder läuft — und im Asset-Katalog exportierbar ist, sodass ihn andere Häuser übernehmen können
(Phase 4: behördenübergreifender Austausch).

**Phase 2.**

---

## 4 · Vergabe- und Belegprüfung mit Prüfvermerk

**Perspektive:** Prüfer im Rechnungsprüfungsamt.

**Situation heute:** Eine Vergabeakte soll gegen die Vergabeordnung und die interne Prüfcheckliste geprüft
werden: Vollständigkeit der Unterlagen, Fristen, Wertgrenzen, Dokumentation der Auswahlentscheidung. Die
Checkliste hat 60 Punkte, die Akte 200 Seiten in gemischten Formaten.

**Ablauf mit OPAA:**

1. Akte in den Space „Rechnungsprüfung" — mit eigenen Rechten, denn die Prüfung muss unabhängig bleiben.
2. Ein Agent „Vergabeakte prüfen" arbeitet die Checkliste Punkt für Punkt ab und gibt zu jedem Punkt:
   erfüllt / nicht erfüllt / nicht feststellbar — **mit Seitenverweis**.
3. Wo etwas fehlt, sagt OPAA „nicht feststellbar", statt zu interpretieren (Zitierzwang).
4. Der Prüfer arbeitet die Liste ab, korrigiert Fehleinschätzungen und lässt daraus den
   **Prüfvermerk-Entwurf** erzeugen.
5. Das Feedback des Prüfers fließt in die Qualitätsschleife: Der Agent wird gegen Referenzfälle
   nachgemessen, bevor eine neue Version freigegeben wird
   (siehe [features/search-quality-evaluation.md](./features/search-quality-evaluation.md)).

**Themenbereiche:** A (Multi-Format, Zitierzwang, Rückmeldung und Messbarkeit der Antwortqualität) · D
(Agent, Grenzen, Prüfstand vor der Freigabe) · G (revisionssicheres Protokoll — bei Prüfungshandlungen
zwingend).

**Verteilungs-Effekt:** Die Checkliste als Agent ist ein Asset mit **Version und Freigabestempel** —
nachvollziehbar, welche Fassung eine konkrete Prüfung getragen hat. Genau das verlangt Revisionssicherheit.

**Phase 2.**

---

## 5 · Auskunft an Beschäftigte in verständlicher Sprache

**Perspektive:** Sachbearbeiter in der Bezüge- und Beihilfestelle.

**Situation heute:** Täglich zwanzig ähnliche Anfragen: Beihilfefähigkeit einer Behandlung, Wirkung einer
Teilzeit auf die Bezüge, Fristen bei Elternzeit. Die Antworten stehen in Beihilfeverordnung,
Rundschreiben und internen Auslegungshinweisen. Die Auskunft muss korrekt und verständlich sein — beides
gleichzeitig ist die Mühe.

**Ablauf mit OPAA:**

1. Anfrage in den Chat; OPAA zieht die einschlägigen Regelungen mit Fundstelle.
2. Antwortentwurf in zwei Fassungen: **Amtssprache** für die Akte und **Leichte Sprache** für die
   Antwortmail.
3. Der Sachbearbeiter prüft die Fundstellen, wählt die Fassung und ergänzt den Einzelfall.
4. Wiederkehrende Fälle laufen über geprüfte Prompts aus der Bibliothek des Space — gleiche Frage, gleiche
   Qualität, unabhängig davon, wer antwortet.

**Themenbereiche:** D (Textwerkzeuge: Zusammenfassung, Leichte Sprache, Amtssprache, Export) · C
(Prompt-Bibliothek als geteiltes Asset) · K (Leichte Sprache, Barrierefreiheit nach BITV) · A (belegte
Antwort).

**Verteilungs-Effekt:** Der Kern des Falls ist Gleichbehandlung: Ein geprüfter Prompt sorgt dafür, dass
zwanzig Beschäftigte dieselbe Auskunft in derselben Qualität bekommen — statt zwanzig individueller
Formulierungen unterschiedlicher Güte.

**Phase 1.**

---

## 6 · Aus einem guten Agenten den Standard machen

**Perspektive:** Sachgebietsleiterin.

**Situation heute:** Ein Kollege hat sich eine sehr gute Arbeitsweise mit KI erarbeitet. Er teilt sie per
Mail als Textbaustein, drei Kollegen nutzen eine veraltete Kopie, der Rest weiß nichts davon. Wechselt er
das Amt, geht die Arbeitsweise mit ihm. Das ist der eigentliche Engpass der KI-Einführung — nicht das
Modell.

**Ablauf mit OPAA:**

1. Der Kollege baut seinen Agenten in seinem **persönlichen Space**: Aufgabenbeschreibung, gebundene
   Wissensbibliothek, erlaubte Werkzeuge, Modellwahl — alles in einem Paket.
2. Er stellt ihn dem **Team** bereit. Das Team nutzt ihn sofort, ohne etwas nachzubauen: Das Wissen kommt
   mit.
3. Die Sachgebietsleiterin schlägt ihn für den **Fachbereich** vor. Es läuft ein **Freigabeverfahren**:
   fachliche Prüfung, Test gegen Referenzfälle, Freigabe mit Namen und Datum.
4. Nach Freigabe steht er im **organisationsweiten Katalog** — auffindbar über Fachbereich, Anwendungsfall
   und Verantwortlichen statt über eine Mail-Weiterleitung.
5. Verbessert der Eigentümer den Agenten, erhalten alle die neue **Version**; die Historie bleibt, ein
   Zurückrollen ist möglich.
6. Die Nutzungsauswertung zeigt aggregiert, welche Assets tatsächlich tragen und welche eingestellt werden
   können.

**Themenbereiche:** **C** (Assets, Verteilungsstufen, Freigabe, Versionierung, Katalog,
Nutzungstransparenz) · D (Agent als portables Paket) · H (Auswertung des Rollouts, ohne Personenbezug).

**Verteilungs-Effekt:** Das *ist* der Verteilungs-Effekt: Aus persönlichem Können wird ein
Organisationsgut mit Eigentümer, Version und Freigabe. Der Weggang des Kollegen nimmt der Behörde die
Arbeitsweise nicht mehr weg.

**Phase 2–3** (Teilen und Katalog in Phase 2, Freigabe und Versionierung in Phase 3).

---

## 7 · KI zentral steuern statt lokal dulden

**Perspektive:** KI-Koordinator oder Digitalisierungsbeauftragte einer Finanzbehörde.

**Situation heute:** Es gibt keine Übersicht, wer welche KI wofür nutzt. Einzelne kopieren
Fallbeschreibungen in frei verfügbare Verbraucherwerkzeuge — bei Steuerdaten ein Verstoß gegen § 30 AO.
Gleichzeitig fragt die Amtsleitung nach dem Fortschritt der KI-Einführung, und niemand kann ihn belegen.

**Ablauf mit OPAA:**

1. **Modellvorgaben** zentral setzen: Die Wissensbibliothek mit steuerlichen Daten trägt die Beschränkung
   „ausschließlich lokal betriebene Modelle im eigenen Rechenzentrum" selbst — sie gilt überall dort, wo
   diese Daten verwendet werden, unabhängig davon, in welchem Space gefragt wird. Technisch erzwungen,
   nicht per Dienstanweisung erhofft, und nicht durch einen Raumwechsel zu umgehen.
2. **Voreinstellungen und Parameter** je Space und Rolle vorgeben: Standardmodell je Aufgabe, Vorgaben für
   die Systemanweisung, Kontextgrenzen. Mitarbeitende erhalten eine geeignete Voreinstellung statt einer
   Modellauswahl, die sie fachlich nicht beurteilen können. Alle Ebenen wirken als Obergrenze: Es gilt
   stets die restriktivste Festlegung, keine Ebene kann erweitern.
3. **Werkzeuge und Grenzen** festlegen: welche Konnektoren, welche schreibenden Aktionen, welche Limits.
4. **Rollout auswerten**: Welche Bereiche nutzen KI wie stark, welche Assets sind erfolgreich, wo ist die
   Adoption schwach — aggregiert je Organisationseinheit, ohne Personenbezug. Grundlage für Kuratierung,
   Schulung und den Bericht an die Leitung, ohne dass daraus eine Leistungskontrolle wird.
5. Ergebnis: Schatten-KI verliert ihren Anlass, weil das interne Werkzeug verfügbar, erlaubt und für die
   Fachaufgabe besser geeignet ist.

**Themenbereiche:** **E** (zentrale Modellvorgaben, eigene Modelle zuerst) · H (Auswertung, Limits,
Kostentransparenz) · F (Rollen, rechtebewusste Nutzung) · G (Protokoll als Nachweis).

**Verteilungs-Effekt:** Einmal zentral entschieden, überall wirksam — der Gegenentwurf zu vierzig
Fachbereichen, die je eigene KI-Regeln erfinden.

**Phase 1.**

---

## 8 · Souverän betreiben und Modelle wechseln

**Perspektive:** IT-Leitung und Betrieb in einem kommunalen Rechenzentrum.

**Situation heute:** Eine KI-Lösung soll eingeführt werden, aber der Betrieb muss ohne Internetzugang
funktionieren, an das vorhandene Identitätsmanagement andocken und prüffähige Protokolle liefern. Diese
drei Anforderungen entscheiden vor jeder Funktionsfrage darüber, ob eine Lösung im Haus überhaupt
betrieben werden darf.

**Ablauf mit OPAA:**

1. Installation **air-gapped** im eigenen Rechenzentrum — Docker Compose für den Start, Kubernetes mit
   Hochverfügbarkeit im Ausbau.
2. Anbindung an den **Verzeichnisdienst** über Single Sign-on und SCIM: Rollen und Gruppen kommen aus dem
   Verzeichnis, beim Ausscheiden wird automatisch deprovisioniert.
3. **Protokoll an das SIEM**; die Funktionen nach DSGVO (Auskunft, Löschung, Export) sind vorhanden und
   dokumentiert.
4. Modelle laufen lokal. Kommt ein besseres Modell, wird es **an einer Stelle** getauscht — die Agenten
   der Fachbereiche bleiben unangetastet.
5. Speicher und Vektorablage nach Hausstandard, im Rahmen der im Fundament getroffenen
   Technologieentscheidungen.

**Themenbereiche:** J (Deployment, air-gapped, Speicher) · E (eigene Modelle, zentraler Modellwechsel) · F
(Anmeldung über den Verzeichnisdienst, Kontenlebenszyklus) · G (Protokoll, SIEM, DSGVO, sichere
Voreinstellungen).

**Verteilungs-Effekt:** Der Betrieb ist die Voraussetzung dafür, dass Verteilung überhaupt erlaubt ist:
ohne Rechte, Protokoll und lokale Modelle darf kein Asset organisationsweit laufen.

**Phase 1.**

---

## 9 · Vollstreckungsvorgänge vorbereiten mit Freigabe

**Perspektive:** Sachbearbeiter in der Vollstreckungsstelle.

**Situation heute:** Offene Forderungen werden aus dem Fachverfahren gezogen, mit Zahlungseingängen
abgeglichen; daraus entstehen Erinnerungs- oder Mahnschreiben. Der Vorgang ist repetitiv, aber jede
Abweichung erfordert Fachurteil — Automatisierung ohne Kontrolle wäre riskant und rechtlich heikel.

**Ablauf mit OPAA:**

1. Der Konnektor liest die Vorgänge **nur lesend** aus dem Fachverfahren beziehungsweise dem
   Vorgangssystem; die Berechtigungen werden aus dem Quellsystem gespiegelt.
2. Ein Agent gruppiert die Fälle, erkennt Auffälligkeiten (Teilzahlung, laufende Stundung, offener
   Einspruch) und legt sie dem Sachbearbeiter mit Begründung und Fundstelle vor.
3. Für die unstrittigen Fälle bereitet OPAA die Schreiben vor und schlägt **schreibende Aktionen** vor:
   Vorgang aktualisieren, Wiedervorlage setzen.
4. **Nichts wird ohne Freigabe ausgeführt** — der Mensch entscheidet an einem ausdrücklichen Gate; jede
   ausgeführte Aktion landet revisionssicher im Protokoll.
5. Ausgeschlossene Fälle bleiben ausdrücklich beim Menschen, statt „irgendwie" mitbehandelt zu werden.

**Themenbereiche:** B (Konnektoren lesend und schreibend, Spiegelung der Rechte aus dem Quellsystem) · D
(Agent, menschliche Freigabe, Grenzen) · G (Protokoll jeder Aktion) · F (Rechte).

**Verteilungs-Effekt:** Die abgestufte Autonomie ist selbst ein verteilbares Muster: nur lesend wird breit
ausgerollt, schreibend nur dort freigeschaltet, wo Fachbereich und Datenschutz zugestimmt haben.

**Phase 2.**

---

## 10 · Stellungnahmen auswerten und Synopse erstellen

**Perspektive:** Referentin im Grundsatz- oder Fachreferat eines Ministeriums.

**Situation heute:** Zu einem Erlassentwurf gehen dreißig Stellungnahmen von Verbänden,
Oberfinanzdirektionen und Kammern ein — als PDF, als Textdokument, teils in Mails eingebettet. Gefragt ist
eine Synopse: Wer fordert was, wo widersprechen sich die Vorschläge, welche Punkte wiederholen sich, was
ist wirklich neu.

**Ablauf mit OPAA:**

1. Alle Stellungnahmen in den Space „Erlassverfahren"; die Formate werden erkannt und passend zerlegt.
2. OPAA erstellt eine **Synopse je Regelungspunkt**: Position, Absender, Zitat, Seitenverweis.
3. Ein **Deep-Research-Bericht** ordnet die Vorschläge in die bestehende Erlasslage ein und markiert
   Widersprüche zu geltenden Verwaltungsanweisungen.
4. Ausgabe als Textdokument und als Kurzvorlage für die Hausleitung, Zahlen und Zitate belegt.
5. Später ergänzt ein Wissensgraph das Vektor-Retrieval: Querbezüge zwischen Erlassen, Verfügungen und
   Rechtsprechung werden als Netz auswertbar — für mehrstufige Fragen wie „welche Regelungen hängen an
   dieser Definition?".

**Themenbereiche:** A (Multi-Format, Deep Research, Zitierung, später Wissensgraph) · D (Textwerkzeuge,
Export, Folien) · I (Web-Oberfläche und REST-API, Anbindung an den Arbeitsplatz).

**Verteilungs-Effekt:** Die Synopse-Vorlage wird ein Asset des Grundsatzreferats und steht allen Referaten
für jedes weitere Anhörungsverfahren bereit — einschließlich Weitergabe an andere Häuser über den
behördenübergreifenden Austausch (Phase 4).

**Phase 2–3.**

---

## Kurzkatalog nach Amt

Ergänzung zu den zehn ausführlichen Fällen: ein breiter Katalog nach Amt oder Sachgebiet, jeweils Titel
und ein Satz zum Nutzen. Dieselbe Liste taugt später als Einstiegskatalog im Produkt (Themenbereich D).

### Finanzamt · Veranlagung und Rechtsbehelf

- **Rechtslage klären** — Fundstelle statt Flurgespräch, belegt und prüfbar.
- **Einspruch aufarbeiten** — Sachverhalt und Argumente aus umfangreichen Anlagen.
- **Vermerk entwerfen** — Struktur und Belege; die Wertung bleibt beim Menschen.
- **Akte zusammenfassen** — Zeitschiene und Streitpunkte auf einer Seite.
- **Ähnliche Fälle finden** — Vergleichsfälle mit Aktenzeichen und Ergebnis.
- **Prüfhinweise abarbeiten** — Hinweise des Risikomanagements einordnen.
- **Schriftsatz gegenlesen** — Vollständigkeit und Fristenbezug prüfen.
- **Auskunft formulieren** — Amtssprache und Leichte Sprache in einem Zug.

### Kämmerei · Haushalt und Finanzsteuerung

- **Haushaltsanmeldungen konsolidieren** — achtzehn Ämter, eine Struktur.
- **Abweichungsanalyse** — Plan gegen Vorjahr, Auffälligkeiten benannt.
- **Ausschussvorlage** — Vorlage und Folien aus der Auswertung.
- **Rückfragen an Fachämter** — einheitlich formuliert, nachvollziehbar.
- **Zuschussbescheide prüfen** — Nebenbestimmungen und Fristen erfassen.
- **Fördermittel recherchieren** — Programme gegen das Vorhaben abgleichen.
- **Kennzahlenbericht** — Quartalszahlen kommentiert statt nur tabelliert.
- **Verträge auf Fristen prüfen** — Kündigungs- und Verlängerungstermine.

### Rechnungsprüfungsamt

- **Vergabeakte prüfen** — Checkliste Punkt für Punkt mit Seitenverweis.
- **Belegprüfung** — Vollständigkeit und Wertgrenzen abgleichen.
- **Prüfvermerk entwerfen** — Feststellungen strukturiert festhalten.
- **Stellungnahmen einordnen** — Antworten der geprüften Stelle bewerten.
- **Prüfungsplanung** — Risikoschwerpunkte aus Vorjahresfeststellungen.
- **Nachschau vorbereiten** — offene Feststellungen zusammentragen.

### Bezüge, Beihilfe und Personal

- **Auskunft zur Beihilfefähigkeit** — Regelung, Fundstelle, verständliche Antwort.
- **Antwortbausteine pflegen** — geprüfte Vorlagen statt individueller Formulierungen.
- **Leichte Sprache** — Bescheide und Schreiben verständlich fassen.
- **Rundschreiben aufbereiten** — was sich für wen ändert, kurz gefasst.
- **Onboarding-Leitfaden** — Einarbeitung Schritt für Schritt.
- **Fragen der Beschäftigten** — Wiederkehrendes aus dem Regelwerk beantworten.

### Grundsatz- und Fachreferat

- **Stellungnahmen auswerten** — Synopse je Regelungspunkt mit Zitat.
- **Erlasslage recherchieren** — zitierbarer Bericht statt Aktenwanderung.
- **Widersprüche finden** — Konflikte zur geltenden Anweisung markieren.
- **Hausleitungsvorlage** — Kurzfassung mit belegten Kernaussagen.
- **Länderabgleich** — Regelungen anderer Länder gegenüberstellen.
- **Sprachliche Endabstimmung** — Amtsstil und Verständlichkeit prüfen.

### IT, Betrieb und KI-Koordination

- **Modellvorgaben setzen** — welche Modelle wo erlaubt sind.
- **Rollout messen** — Adoption je Bereich, erfolgreiche Assets erkennen.
- **Rechte und Lebenszyklus** — Verzeichnisdienst statt Handpflege.
- **Protokoll auswerten** — Aktionen nachvollziehen, Berichte für die Revision.
- **Modell tauschen** — an einer Stelle, ohne Aufwand in den Teams.
- **Asset-Katalog kuratieren** — freigeben, versionieren, ausmustern.
- **Anfragen an die IT** — interne Fragen aus dem Handbuch beantworten.

### Problemsätze für den Einstieg

Knappe Sätze, in denen sich der Ausgangszustand wiedererkennen lässt:

- Gute Prompts wandern per Mail und veralten.
- Wissen geht mit jeder Versetzung verloren.
- Niemand weiß, wer welche KI wofür nutzt.
- Steuerdaten in Verbraucherwerkzeugen — § 30 AO sagt nein.
- Welches Modell ist eigentlich erlaubt?
- Kein Prüfpfad für die Revision.
- Fundstellen suchen dauert länger als entscheiden.
- Jedes Amt erfindet KI neu.
- KI-Vorhaben versanden in Workshops.
- Die Amtsleitung fragt nach Fortschritt — niemand kann ihn belegen.

---

## Abdeckung der Themenbereiche

| Bereich | Anwendungsfälle |
|---|---|
| **A** Wissensschicht & Retrieval | 1, 2, 4, 10 (und Grundlage aller übrigen) |
| **B** Wissensquellen & Konnektoren | 1, 9 |
| **C** Spaces, Assets & Verteilung | **6**, 3, 4, 5 |
| **D** Agenten, Prompts & Werkzeuge | 2, 3, 4, 5, 9, 10 |
| **E** Modelle & zentrale Steuerung | **7**, 8 |
| **F** Identität, Rechte & Mandanten | 1, 7, 8, 9 |
| **G** Sicherheit, Nachweis & Prüfbarkeit | 4, 8, 9 |
| **H** Monitoring, Kosten & Governance | 3, 6, 7 |
| **I** Kanäle & Oberflächen | 5, 10 |
| **J** Betrieb & Deployment | 8 |
| **K** Verwaltungs-Spezifika | 2, 4, 5 |

Alle elf Themenbereiche kommen mindestens einmal vor.

**Lesart:** Die Fälle 1–5 sowie 9 und 10 sind Fachanwendungen — an ihnen zeigt sich der Nutzen in der
täglichen Arbeit. Die Fälle 6 bis 8 sind die Plattformfälle — an ihnen zeigt sich, warum es dafür eine
Plattform und nicht eine Chat-Oberfläche braucht. Beide Gruppen gehören zusammen: ohne die erste bleibt
der Wert abstrakt, ohne die zweite bleibt er unzulässig.

---

## Offene Punkte

- [ ] Zwei bis drei Fälle mit Fachpraktikern gegenprüfen; Kämmerei und Rechtsbehelfsstelle sind die
      realistischsten Einstiegspunkte.
- [ ] Referenzfragen für die Messung der Suchqualität aus den Fällen 1, 2 und 4 ableiten — siehe
      [features/search-quality-evaluation.md](./features/search-quality-evaluation.md).

---

## Weiterlesen

- [VISION.md](./VISION.md) — Nordstern, Themenbereiche, Phasen
- [features/spaces-and-assets.md](./features/spaces-and-assets.md) — Spaces, Assets, Verteilungsstufen
- [CONCEPTS.md](./CONCEPTS.md) — Begriffe und Glossar
