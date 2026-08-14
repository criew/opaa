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
Chunking, dessen Ergebnis man sich ansehen kann. Für haftungskritische Zusammenhänge lässt sich das System
in den **Zitierzwang** schalten — **keine belegte Quelle, keine Antwort**. Ein Assistent, der in dieser
Lage „nicht feststellbar" sagt, ist mehr wert als einer, der plausibel klingt.

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
| **A** | Wissensschicht & Retrieval | Zitierzwang, Konfidenz, hybride Suche mit Reranking, erklärbares Chunking, Deep Research | [data-indexing-rag.md](./features/data-indexing-rag.md) · [search-quality-evaluation.md](./features/search-quality-evaluation.md) |
| **B** | Wissensquellen & Konnektoren | Uploads und Konnektoren, selbst aktualisierende Wissensblöcke, Spiegelung der Rechte aus dem Quellsystem | `features/knowledge-sources.md` *(in Arbeit)* |
| **C** | Spaces, Assets & Verteilung | Arbeitsräume, Assets mit eigenen Rechten, Verteilungsstufen, Freigabe, Versionierung, Katalog | [spaces-and-assets.md](./features/spaces-and-assets.md) |
| **D** | Agenten, Prompts & Werkzeuge | Agenten als teilbare Pakete, geführtes Onboarding, Prüfstand, Prüfagenten, Sandbox, Werkzeuge, MCP | `features/agents-and-tools.md` *(in Arbeit)* |
| **E** | Modelle & zentrale Steuerung | Modellverwaltung, eigene Modelle zuerst, zentrale Vorgaben als Obergrenze, Schutz vor Weitergabe personenbezogener Daten | [llm-integration.md](./features/llm-integration.md) |
| **F** | Identität, Rechte & Mandanten | Anmeldung über den Verzeichnisdienst, Lebenszyklus der Konten, rechtebewusste Suche zur Abfragezeit | [access-control.md](./features/access-control.md) |
| **G** | Sicherheit, Nachweis & Prüfbarkeit | Revisionssicheres Protokoll, Vollständigkeit nach DSGVO, sichere Voreinstellungen, C5-Fähigkeit, Mitbestimmungsfähigkeit | `features/security-and-compliance.md` *(in Arbeit)* |
| **H** | Monitoring, Kosten & Governance | Grenzen je Nutzer, Kostentransparenz, Auswertung des KI-Rollouts — aggregiert, ohne Personenbezug | `features/monitoring-and-governance.md` *(in Arbeit)* |
| **I** | Kanäle & Oberflächen | Web-Oberfläche, REST-API, Anbindung an self-hosted Team-Chats | [user-frontends.md](./features/user-frontends.md) |
| **J** | Betrieb & Deployment | Docker Compose, Kubernetes mit Hochverfügbarkeit, air-gapped, mandantenfähiger Betrieb durch Rechenzentren | [deployment-infrastructure.md](./features/deployment-infrastructure.md) |
| **K** | Verwaltungs-Spezifika | Leichte Sprache und Amtssprache, Barrierefreiheit, Revisionssicherheit, Anbindung an die elektronische Akte | `features/public-sector.md` *(in Arbeit)* |

Die mit *(in Arbeit)* gekennzeichneten Spezifikationen entstehen im Zuge der Neuausrichtung; bis dahin
gilt die Beschreibung in dieser Tabelle.

---

## Produktphasen

Vier Phasen. Jede ist **für sich genutzt wertvoll** und als Gesamtpaket sinnvoll. Die Nachweisfähigkeit
gehört in die erste Phase — ohne sie ergibt ein Start in einer Behörde keinen Sinn.

### Phase 1 — Souveräner Wissensassistent

*Eine Behörde kann ihr Wissen befragbar, belegt und nachweisbar nutzen.*

Retrieval mit Zitierzwang, Konfidenz und Quellenbindung · hybride Suche mit Reranking · erklärbares
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
Freigabe · MCP · Asset-Katalog mit Export und Import · Auswertung von Nutzung und Kosten.

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

Der tatsächliche Umsetzungsstand gegen diese Phasen wird in einem eigenen Statusdokument geführt, das
die bisherige MVP-Statusübersicht ablöst.

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
│  Konfidenz, Zitierzwang │   └────────────────────────────────┘
└──────┬──────────────────┘
       │
┌──────▼───────────────────────────────────────────────────────┐
│  WISSENSBIBLIOTHEKEN                                         │
│  Anker der rechtebewussten Suche · eigene Rechte je Bestand  │
│  gespeist aus Uploads und Konnektoren                        │
└──────────────────────────┬───────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│  DOKUMENTENSPEICHER · Netzlaufwerk, objektbasiert, lokal     │
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
- [decisions/](./decisions/) — die Architekturentscheidungen dahinter
