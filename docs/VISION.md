# OPAA Produktvision

## Nordstern

**OPAA** — Open Project AI Assistant — ist die **souveräne, quelloffene KI-Plattform für die öffentliche
Verwaltung**. Sie verbindet drei Säulen, ohne dass Daten das Haus verlassen:

- **Wissen** — verstreutes Wissen aus Akten, Wikis, Postfächern, Dateiablagen und Fachverfahren wird
  befragbar und **nachweisbar**.
- **Agenten** — wiederkehrende Aufgaben und Abläufe werden automatisiert, von reinem Lesen bis zu
  schreibenden Aktionen mit Freigabe, immer an das Wissen des Hauses gebunden.
- **KI für Teams und Organisation** — KI-Fähigkeit wird **verteilbar**: gemeinsame Arbeitsräume, teilbare
  Agenten und Prompt-Bibliotheken, zentral gesetzte Modellvorgaben.

On-Premises als Standard, quelloffener Kern, jede Antwort belegt und nachvollziehbar.

Die Ausrichtung ist in [ADR-0014](./decisions/0014-produktausrichtung-oeffentliche-verwaltung.md)
entschieden und begründet.

---

## Zwei Leitprinzipien

Sie entscheiden im Zweifel und sind der Maßstab für jede Feature-Frage.

### Belegbarkeit — kann ich der Antwort trauen?

Eine Auskunft in der Verwaltung ist keine Meinung. Jemand steht mit seinem Namen dafür gerade, und Jahre
später muss nachvollziehbar sein, worauf sie sich stützte.

OPAA bindet deshalb jede Aussage an ihre Quelle: Fundstelle, Sprung zur Textstelle, Konfidenz und ein
Chunking, dessen Ergebnis man sich ansehen kann. Der Kern ist die **Belegvalidierung**: Jeder Beleg wird
gegen die Fundstellen geprüft, die für diese Antwort tatsächlich abgerufen wurden — deterministisch, ohne
zweiten Modellaufruf, gleiche Eingabe und gleiches Urteil. Ein Beleg, der auf nichts zeigt, wird
**gekennzeichnet**; er wird weder stillschweigend entfernt noch stillschweigend als gültig behandelt.

Das ist eine Zusicherung und keine Wahrscheinlichkeit: Ein erfundener Beleg ist ausgeschlossen. Was sie
nicht leistet, steht ebenso ausdrücklich da — geprüft wird die **Echtheit** eines Belegs, nicht seine
inhaltliche Deckung mit dem Satz, an dem er steht.

Ein Modus, der bei fehlendem Beleg gar nicht erst antwortet, ist **bewusst nicht gebaut**
([ADR-0014](./decisions/0014-produktausrichtung-oeffentliche-verwaltung.md), Nachtrag vom 21.08.2026). Das
Modell sagt selbst, wenn es nichts gefunden hat, und fehlende Belege sind im Belegfenster unmittelbar
sichtbar. Ein Zwangs- und Verweigerungsapparat darüber hätte einen zweiten, selbst fehlbaren Prüfweg
gebraucht, dessen Fehlurteile — die Verweigerung einer ansonsten belegten Antwort — teurer sind als der
Schaden, den er verhindert.

### Verteilbarkeit — kommt die KI-Fähigkeit in der ganzen Organisation an?

Das reale Problem ist heute nicht, ob es ein gutes Modell gibt. Es ist die Frage, wie KI-Kompetenz von
wenigen Könnern zu allen Beschäftigten kommt. Ohne Antwort darauf entsteht Schatten-KI: Einzelne basteln
private Prompts, kopieren Amtsdaten in Verbraucherwerkzeuge, und das Können bleibt in Köpfen.

OPAA macht KI-Können zum **verteilbaren Asset**:

- **Assets statt Einzelwissen.** Agenten, Skills, Prompt-Bibliotheken, Wissensbibliotheken und Vorlagen
  sind benannte, beschriebene, auffindbare Objekte — nicht in Chatverläufen vergrabene Einzelfälle.
- **Verteilungswege.** Persönlich → Team → Fachbereich → organisationsweiter Katalog, jeweils mit
  Freigabe- und Prüfschritt. Schwarmintelligenz mit Governance statt Wildwuchs.
- **Zentrale Steuerung statt lokaler Bastelei.** Die Systemverwaltung legt einmal fest, welche Modelle
  erlaubt sind, welche Voreinstellungen gelten, welche Werkzeuge und Grenzen greifen — alle erben das.
- **Wirkung.** Die beste Arbeitsweise einer Abteilung wird zum Standard aller — nachvollziehbar,
  rechtekonform, ohne dass jede Stelle KI neu erfindet.

OPAA ist damit nicht nur eine Chat-Oberfläche, sondern das Verteilungssystem für KI-Fähigkeit im Haus.

---

## Was OPAA unterscheidet

Die drei Säulen beschreiben, **was** OPAA tut. Als Unterscheidungsmerkmal trägt das allein nicht: Wissen
befragbar machen, Agenten bauen, Fähigkeiten verteilen — daran arbeiten viele, und Katalog, Freigabe,
Eigentümerschaft und Versionierung sind dabei, allgemeiner Standard zu werden.

Trennscharf ist das **Wie**. Vier Eigenschaften, jede für sich nachprüfbar:

1. **Rechte, Protokoll und Mandantengrenze liegen im Kern.** Sie sind quelloffen und an keine Bezahlstufe
   gebunden. Wo diese drei Dinge kostenpflichtige Zusätze sind, entscheidet eine Preisliste darüber, ob
   eine Behörde sie hat — und ohne sie ist eine Installation in der Verwaltung gar nicht einsetzbar.
2. **Betrieb im eigenen Haus, ohne Mindestgröße.** Ein Amt mit vierzig Beschäftigten betreibt dieselbe
   Software wie eines mit viertausend, bis hin zum Betrieb ohne Netzanbindung. Souveränität, die erst ab
   einer Bestellmenge beginnt, ist eine Vertragsbedingung und keine Eigenschaft.
3. **Auswertung ausschließlich aggregiert.** Es gibt keinen personenbezogenen Auswertungspfad und keine
   Ranglisten — nicht als Einstellung, sondern als Eigenschaft des Systems. Das ist die Voraussetzung
   dafür, dass eine Dienstvereinbarung überhaupt zustande kommt, und der Punkt, an dem sich ein
   Arbeitsmittel von einem Kontrollinstrument unterscheidet.
4. **Belege werden geprüft, nicht nur verlinkt.** Ein Quellenlink belegt, dass ein Dokument existiert. Die
   Belegvalidierung belegt, dass die Antwort aus diesem Dokument stammt.

Keine der vier lässt sich nachträglich anbauen. Sie entscheiden, wie Suche, Speicher und Protokoll
geschnitten sind — deshalb stehen sie hier und nicht in einer Feature-Liste.

---

## Für wen

**Primärer Nutzerkreis ist die interne Verwaltung**: Sachbearbeitung, Fachreferate, Querschnittsbereiche,
IT und Betrieb. Konkrete Abläufe zeigt [USE-CASES.md](./USE-CASES.md).

Ein Assistent für Bürgerinnen und Bürger ist als Ausblick mitgedacht (Phase 4), aber ausdrücklich **nicht
Teil des Fundaments**. Er hätte einen anderen Nutzerkreis, andere Haftungsfragen und andere Anforderungen
an Barrierefreiheit und Missbrauchsschutz.

---

## Die elf Themenbereiche

| | Bereich | Worum es geht | Spezifikation |
|---|---|---|---|
| **A** | Wissensschicht & Retrieval | Belegvalidierung, Konfidenz, hybride Suche mit Reranking, erklärbares Chunking, Deep Research | [data-indexing-rag.md](./features/data-indexing-rag.md) · [search-quality-evaluation.md](./features/search-quality-evaluation.md) |
| **B** | Wissensquellen & Konnektoren | Uploads und Konnektoren, selbst aktualisierende Wissensblöcke, Spiegelung der Rechte aus dem Quellsystem | [knowledge-sources.md](./features/knowledge-sources.md) |
| **C** | Spaces, Assets & Verteilung | Arbeitsräume, Assets mit eigenen Rechten, Verteilungsstufen, Freigabe, Versionierung, Katalog | [spaces-and-assets.md](./features/spaces-and-assets.md) |
| **D** | Agenten, Prompts & Werkzeuge | Agenten als teilbare Pakete, geführtes Onboarding, Prüfstand, Prüfagenten, Sandbox, Werkzeuge, MCP | [agents-and-tools.md](./features/agents-and-tools.md) |
| **E** | Modelle & zentrale Steuerung | Modellverwaltung, eigene Modelle zuerst, zentrale Vorgaben als Obergrenze, Schutz vor Weitergabe personenbezogener Daten | [llm-integration.md](./features/llm-integration.md) |
| **F** | Identität, Rechte & Mandanten | Anmeldung über den Verzeichnisdienst, Lebenszyklus der Konten, rechtebewusste Suche zur Abfragezeit | [access-control.md](./features/access-control.md) |
| **G** | Sicherheit, Nachweis & Prüfbarkeit | Revisionssicheres Protokoll, Vollständigkeit nach DSGVO, sichere Voreinstellungen, C5-Fähigkeit, Mitbestimmungsfähigkeit | [security-and-compliance.md](./features/security-and-compliance.md) |
| **H** | Monitoring, Kosten & Governance | Grenzen je Nutzer, Kostentransparenz, Auswertung des KI-Rollouts — aggregiert, ohne Personenbezug | [monitoring-and-governance.md](./features/monitoring-and-governance.md) |
| **I** | Kanäle & Oberflächen | Web-Oberfläche, REST-API, Anbindung an self-hosted Team-Chats, OPAA als belegte Wissensschicht für fremde KI-Werkzeuge | [user-frontends.md](./features/user-frontends.md) |
| **J** | Betrieb & Deployment | Docker Compose, Kubernetes mit Hochverfügbarkeit, air-gapped, mandantenfähiger Betrieb durch Rechenzentren | [deployment-infrastructure.md](./features/deployment-infrastructure.md) |
| **K** | Verwaltungs-Spezifika | Leichte Sprache und Amtssprache, Barrierefreiheit, Revisionssicherheit, Anbindung an die elektronische Akte | [public-sector.md](./features/public-sector.md) |

---

## Produktphasen

Vier Phasen. Jede ist **für sich genutzt wertvoll** und als Gesamtpaket sinnvoll. Die Nachweisfähigkeit
gehört in die erste Phase — ohne sie ergibt ein Start in einer Behörde keinen Sinn.

### Phase 1 — Souveräner Wissensassistent

*Eine Behörde kann ihr Wissen befragbar, belegt und nachweisbar nutzen.*

Retrieval mit geprüften Belegen, Konfidenz und Quellenbindung · hybride Suche mit Reranking · erklärbares
Chunking · Messbarkeit der Suchqualität · Uploads und lesende Konnektoren · Spaces, persönlicher Space und
Wissensbibliotheken als eigene Objekte · Gruppen aus dem Verzeichnisdienst als Rechtesubjekt ·
Organisation als harte Mandantengrenze · Textwerkzeuge einschließlich Leichter Sprache · eigene Modelle
mit zentralen Vorgaben · Anmeldung, Kontenlebenszyklus und rechtebewusste Suche · revisionssicheres
Protokoll und Vollständigkeit nach DSGVO · Web-Oberfläche und REST-API · Betrieb bis air-gapped.

### Phase 2 — Agenten, Werkzeuge und teilbare Assets

*Von „fragen" zu „erledigen" — und das Erledigte wird teilbar statt einmalig.*

Agenten und Skills als teilbare Pakete · geführtes Agenten-Onboarding · Prüfstand vor der Freigabe ·
Prüfagenten für kritische Vorgänge · isolierte Ausführungsumgebung für Dateiverarbeitung, Auswertungen,
Texterkennung, Transkription und Diagramme · Deep Research · schreibende Integrationen mit menschlicher
Freigabe · MCP · Asset-Katalog mit Export und Import · OPAA als Wissensschicht für fremde KI-Werkzeuge,
erreichbar über persönliche Zugangstokens und einen MCP-Server · Auswertung von Nutzung und Kosten.

### Phase 3 — Kollaboration und organisationsweiter Rollout

*Teams arbeiten gemeinsam; geprüfte Assets werden in die ganze Organisation verteilt.*

Gemeinsame Räume für Menschen und KI · Freigabe- und Prüfworkflow, Versionierung, organisationsweiter
Katalog · Vorlagenkatalog nach Fachbereich · Anbindung an self-hosted Team-Chats · Barrierefreiheit nach
BITV · Feinschliff der Amtssprache · Wissensgraph als Ergänzung des Vektor-Retrievals.

### Phase 4 — Ökosystem und Ausblick

*Reichweite, Skalierung, neue Nutzerkreise — bedarfsgetrieben, nicht fest eingeplant.*

Tiefe Anbindung an den souveränen Arbeitsplatz · behördenübergreifender Austausch geprüfter Assets ·
Assistent für Bürgerinnen und Bürger · Erweiterungen für Office und Browser · Anbindung an elektronische
Akten und Dokumentenmanagement.

Den tatsächlichen Umsetzungsstand gegen diese Phasen führt der [Gesamtstand](./fortschritt/gesamtstand.md).

---

## Roadmap

### Meilenstein 1 — 31.08.2026

Erster datierter Meilenstein innerhalb von Phase 1, abgestimmt zwischen bugpuritz und criew. Bis dahin
müssen stehen:

1. **Komplettes UI-Redesign** mit dem Claude Design Skill. Die Benutzerführung wird für alle
   Kernbereiche neu gedacht: Spaces, Assets (Wissensbibliotheken), Chats, Zitierung und die übrigen
   sichtbaren Bereiche.
2. **Anlegen von Wissensdatenbanken** — sowohl über Konnektoren mit Vorlagen (Dateisystem /
   Netzwerkadresse, URL-Scraping) als auch über manuellen Upload von Dateien und Ordnern. Wissensquellen
   werden per Berechtigung an Nutzer vergeben; Nutzer legen Spaces an und assoziieren damit die
   Wissensquellen, auf die sie eine Berechtigung haben.
3. **Testsystem mit Testdatensätzen**, die über das bisherige Superhelden-Beispielkorpus hinausgehen.
4. **Aufstellung der bisher implementierten Leistungen** — eine Bestandsaufnahme dessen, was zum
   Meilenstein tatsächlich steht, als Grundlage für die Abnahme und die nächste Priorisierung. Siehe
   [Gesamtstand](./fortschritt/gesamtstand.md) für den laufend gepflegten Umsetzungsstand.

**Bewusste Einschränkung für diesen Meilenstein:** Das Konzept aus Assets und Spaces wird umgesetzt, aber
die vollständige Berechtigungsproblematik (siehe [access-control.md](./features/access-control.md)) wird
zunächst noch nicht angegangen.

**Arbeitsteilung:** bugpuritz übernimmt UI-Design und -Anpassungen, criew übernimmt Wissensbibliotheken,
Konnektoren und die Verlinkung zu Spaces.

---

## Ideen in Prüfung

Aufgenommene Richtungen, die **noch nicht entschieden** sind. Sie stehen hier, damit sie nicht verloren
gehen und damit erkennbar ist, was geprüft wird — eine Zusage ist keine davon. Jede braucht vor einer
Umsetzung einen eigenen Schnitt, und wo sie eine bestehende Festlegung berührt, einen ADR.

### Abgeleitete Wissensformen · Bereich A

Heute liefert ein Bestand Fundstellen. Die Idee ist, aus demselben Bestand zusätzlich abgeleitete
Darstellungen zu erzeugen: eine Übersicht dessen, was überhaupt darin steht, ein Netz der vorkommenden
Begriffe und ihrer Beziehungen, eine Gliederung vom Groben ins Feine. Die Fragen „was ist hier eigentlich
drin" und „wie hängt das zusammen" beantwortet kein einzelner Treffer, sondern erst eine solche Ableitung —
und es sind die Fragen, mit denen jemand an einen fremden Bestand herangeht.

Der **Wissensgraph** aus Phase 3 ist der Teil dieser Idee, der bereits verortet ist; neu wären die
übrigen Ableitungen und der Gedanke, sie gemeinsam aus einem Bestand zu erzeugen statt einzeln.

Der Preis ist offen und nicht klein: Ableitungen altern mit dem Bestand. Wer sie nicht nachführt, hat ein
zweites, stilles Gedächtnis, das irgendwann etwas anderes sagt als die Quelle — genau die Art von Fehler,
gegen die die Belegvalidierung gebaut ist.

### Mehrstufiges Nachfassen im Retrieval · Bereich A

Eine zusammengesetzte Frage wird in Teilfragen zerlegt, jede einzeln belegt und erst am Ende
zusammengeführt; wo eine Runde nichts Tragfähiges liefert, wird gezielt nachgefasst, statt einmal zu suchen
und das Beste zu nehmen. Das ist die Verallgemeinerung dessen, was Deep Research für den Einzelfall tut.

Abzuwägen gegen Antwortzeit und Modellkosten — und gegen die Nachvollziehbarkeit: Mehr Runden heißen mehr
Fundstellen je Antwort und einen längeren Weg, den das Protokoll und das Belegfenster abbilden müssen,
ohne dass der Mensch davor die Übersicht verliert.

### Fähigkeiten aus dem Bestand ableiten · Bereiche C und D

Aus wiederkehrenden Arbeitsweisen, die in einem Bestand oder in der Nutzung sichtbar werden, schlägt das
System ein teilbares Asset vor — einen Prompt, eine Skill, den Entwurf eines Agenten. Das setzt genau dort
an, wo Verteilbarkeit heute scheitert: nicht am Teilen selbst, sondern daran, dass niemand die Zeit hat,
Wiederverwendbares als solches aufzuschreiben.

Der Vorschlag bleibt ein Vorschlag; Freigabeweg, Prüfung und Eigentümerschaft ändern sich nicht. Die
eigentliche Hürde ist eine andere: Der Weg zu einem solchen Vorschlag darf keinen personenbezogenen
Auswertungspfad erzeugen. An dieser Stelle ist die Idee mit einer bestehenden Zusage am ehesten
unvereinbar, und daran entscheidet sie sich.

### Besprechungsnotiz als Quelle · Bereiche A und B

Transkription ist bereits vorgesehen. Die Idee geht einen Schritt weiter: Aus der Mitschrift entsteht eine
strukturierte Notiz mit Ergebnissen, Aufträgen und Zuständigen, die selbst in eine Wissensbibliothek
wandert und damit belegfähig wird.

Das ist der Punkt, an dem eine Quelle nicht mehr aus einem Aktenbestand kommt, sondern aus einer Sitzung —
mit allem, was das für Mitbestimmung, Aufbewahrungsfristen und die Richtigkeit des Festgehaltenen
bedeutet. Ohne eine Antwort darauf ist es kein Verwaltungsfeature.

---

## Bewusst nicht

Jede Auslassung hat einen Sachgrund. Keine davon ist eine Reaktion auf ein anderes Produkt.

**Grundsätzliche Abgrenzungen**

- **Keine Spielwiese für private Nutzung.** OPAA ist ein Arbeitsmittel; alles darin ist zurechenbar.
- **Keine Bildgenerierung.** Kein Verwaltungswert, dafür ein Missbrauchs- und Fälschungsrisiko, das ein
  Amt nicht tragen will.
- **Kein reiner Modellvermittler.** Ein Zugang zu vielen Modellen ohne Wissensbindung löst keines der
  beiden Leitprinzipien ein.
- **Kein reines Mietangebot ohne echte Souveränität.** Wo die Anwendungsschicht beim Anbieter bleibt, ist
  die Datenhoheit eine Zusage und keine Eigenschaft.

**Bewusst ausgelassene Fähigkeiten**

- **Kein visueller Prozessbaukasten.** OPAA ist die belegte Wissens- und Agentenschicht, nicht das
  System, das Verwaltungsprozesse ausführt. Leichte Verkettung mehrerer Schritte bleibt eine Option.
- **Kein Sprachassistent und keine Sprachausgabe.** Verbraucherfunktion ohne Verwaltungswert.
  Transkription von Besprechungen ist enthalten, ein sprechender Bot nicht.
- **Kein öffentlich eingebettetes Widget** im Fundament — es setzt einen Bürger-Scope voraus und gehört
  damit in den Ausblick.
- **Keine Lernplattform.** Die Pflicht zur KI-Kompetenz trifft den Betreiber, nicht die Software.
  OPAA verteilt KI-**Assets** und macht Können damit nutzbar, ohne es zu unterrichten.
- **Keine Massenintegration über fremde Automatisierungsdienste.** Das widerspricht der Souveränität;
  stattdessen MCP und eigene Konnektoren.
- **Keine automatische Auswahl des jeweils stärksten Cloud-Modells.** Das widerspricht dem Vorrang
  eigener Modelle und der Betriebsfähigkeit ohne Netz.
- **Keine nativen Mobil-Apps und kein gleichzeitiges Bearbeiten im Dokument.** Hoher Aufwand, geringer
  Kernwert.

---

## Systemüberblick

```
┌──────────────────────────────────────────────────────────────┐
│  OBERFLÄCHEN                                                 │
│  Web-Oberfläche · REST-API · self-hosted Team-Chats          │
│  Fragen & Antworten · Uploads · Verwaltung von Assets        │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│  ORCHESTRIERUNG                                              │
│  Rechteprüfung · Auswahl des Suchbereichs · Modellvorgaben   │
│  Agentenausführung · Freigaben · Protokollierung             │
└──────┬──────────────────────────────────┬────────────────────┘
       │                                  │
┌──────▼──────────────────┐   ┌───────────▼────────────────────┐
│  RETRIEVAL              │   │  MODELLE                       │
│  hybride Suche          │   │  eigene Modelle zuerst         │
│  Reranking              │   │  zentrale Vorgaben je Aufgabe  │
│  Quellenbindung         │   │  Cloud nur nach Freigabe       │
│  Konfidenz, Belegprüfung│   └────────────────────────────────┘
└──────┬──────────────────┘
       │
┌──────▼───────────────────────────────────────────────────────┐
│  WISSENSBIBLIOTHEKEN                                         │
│  Anker der rechtebewussten Suche · eigene Rechte je Bestand  │
│  gespeist aus Uploads und Konnektoren                        │
└──────────────────────────┬───────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│  DOKUMENTENSPEICHER · ein Verzeichnis · Netzlaufwerk durch   │
│  Einhängen · objektbasiert als Zielbild                      │
└──────────────────────────────────────────────────────────────┘

Quer über alle Schichten: Identität und Rechte · revisionssicheres Protokoll ·
Betrieb bis air-gapped
```

Die Rechteprüfung sitzt **in** der Suche, nicht dahinter: Was ein Mensch nicht lesen darf, wird nicht
geladen und nicht gerankt. Das ist der Grund, warum Wissensbibliotheken eigene Objekte mit eigenen Rechten
sind und nicht bloß Ordner in einem Arbeitsraum.

---

## Häufige Fragen

**Läuft OPAA ohne Internetverbindung?**
Ja. Mit lokal betriebenen Modellen und ohne externe Konnektoren ist der Betrieb ohne Netzanbindung das
vorgesehene Szenario, nicht die Ausnahme.

**Wie geht OPAA mit Daten um, die das Haus nicht verlassen dürfen — etwa Steuerdaten?**
Die Beschränkung hängt an den Daten, nicht am Arbeitsraum. Eine Wissensbibliothek führt ihre Vorgabe
„nur lokale Modelle" selbst mit sich; sie gilt überall, wo diese Daten verwendet werden, unabhängig davon,
wer wo fragt.

**Sieht jeder alles, was indexiert ist?**
Nein. Gefiltert wird über die Wissensbibliotheken, die eine Person lesen darf, und zwar bereits in der
Vektorsuche. Ein Agent liest **immer** mit den Rechten der aufrufenden Person; einen Modus, in dem er mit
eigenen Rechten liest, gibt es nicht.

**Können mehrere Häuser dieselbe Installation nutzen?**
Ja. Die Organisation ist die harte Mandantengrenze: keine Freigabe, keine Suche, kein Katalogtreffer und
keine Systemverwaltung überschreitet sie.

**Was heißt „C5-fähig"?**
OPAA wird nie selbst zertifiziert — der Kriterienkatalog des BSI prüft den **Betrieb**, nicht ein Stück
Software. Das Produktziel ist, so gebaut und dokumentiert zu sein, dass ein Betreiber die Prüfung mit
OPAA im Prüfumfang besteht.

**Was bedeutet die neue Ausrichtung für die Personalvertretung?**
OPAA erzeugt Daten mit Personenbezug, und ein Rollout beginnt in aller Regel nicht ohne
Dienstvereinbarung. Sichtbarkeit ist deshalb eine Handlung und keine Automatik, es gibt keinen
personenbezogenen Auswertungspfad und keine Ranglisten, und Auswertungen sind aggregiert. Details in
[spaces-and-assets.md](./features/spaces-and-assets.md).

**Welche Modelle werden unterstützt?**
Jede OpenAI-kompatible Schnittstelle, einschließlich lokal betriebener Modelle. Die Auswahl ist eine
Vorgabe der Systemverwaltung, keine Entscheidung der einzelnen Nutzerin.

---

## Weiterlesen

- [USE-CASES.md](./USE-CASES.md) — wie sich das im Arbeitsalltag anfühlt
- [CONCEPTS.md](./CONCEPTS.md) — Begriffe und Glossar
- [Gesamtstand](./fortschritt/gesamtstand.md) — was davon heute gebaut ist
- [decisions/](./decisions/) — die Architekturentscheidungen dahinter
