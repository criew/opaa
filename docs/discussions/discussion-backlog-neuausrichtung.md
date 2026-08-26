# Sichtung des Backlogs gegen die neue Produktausrichtung

## Wozu diese Sichtung — und was aus ihr geworden ist

Die Produktausrichtung von OPAA ist auf die öffentliche Verwaltung geschärft worden: drei Säulen
(Wissen · Agenten · KI für Teams und Organisation), zwei Leitprinzipien (Belegbarkeit und
Verteilbarkeit), elf Themenbereiche A bis K und vier Produktphasen. Der offene Backlog war über einen
längeren Zeitraum gewachsen und trug an mehreren Stellen noch die Sprache des abgelösten
Workspace-Modells oder eines allgemeinen Unternehmenskontexts.

Am **14. August 2026** wurden alle **79 offenen Vorgänge** einzeln gegen diese Ausrichtung gelesen und
in vier Kategorien einsortiert. **Diese Einordnung ist abgearbeitet.** Die Tabellen, die sie
festhielten, sind entfernt worden — sie beschrieben einen Zustand, den es nicht mehr gibt, und hätten
beim nächsten Lesen in die Irre geführt. Was aus ihnen wurde:

| Kategorie | Anzahl | Ergebnis |
|---|---|---|
| 1 — Trägt die neue Ausrichtung | 61 | Keine Handlung nötig; die Vorgänge passen unverändert |
| 2 — Passt, braucht neue Formulierung | 7 | Umformuliert: #224, #234, #119, #123, #143, #144, #145 |
| 3 — Ist zu prüfen | 10 | Aufgelöst (siehe unten) |
| 4 — Unklar | 1 | #76 mit Begründung geschlossen — der Vorgang benannte keinen Defekt |

**Kategorie 3 im Einzelnen:** #77 hängt an der Entscheidung zum Vektorspeicher (#348); seine
ursprüngliche Begründung ist damit entfallen, übrig bleibt allein die Konfigurierbarkeit des
Indextyps. #106 mit seinen Kindern #126 bis #130 hängt an #349, der bewusst offen bleibt, bis der
erste echte Konnektor gebaut ist. #54 ist als abgelöst durch #205 geschlossen. #60 und #63 sind
geschlossen, nachdem alle zwanzig Sicherheitsbefunde einzeln gegen den Code nachgeprüft waren —
fünfzehn waren erledigt, ohne dass es den Vorgängen anzusehen war; was offen blieb, läuft als #68,
#77, #78 und #409 weiter.

**Was von dieser Sichtung bleibt, ist der Abschnitt [Lücken](#lücken).** Er benennt die Themen der
neuen Ausrichtung, für die es keinen Vorgang gibt, und ist die einzige Aussage des Dokuments, die
weiter gilt.

Die Themenbereiche werden mit ihren Buchstaben A bis K aus `docs/VISION.md` benannt.

---

## Lücken

Systematisch gegen alle elf Themenbereiche geprüft. Aufgeführt ist, wofür es heute **kein einziges**
offenes Issue gibt.

### A — Wissensschicht & Retrieval

- **Zitierzwang als Umsetzung.** #354 bewertet ihn nur; die schärfste Ausprägung der Belegbarkeit hat
  keinen Umsetzungsvorgang, obwohl sie zu Phase 1 gehört.
- **Hybride Suche mit Reranking.** Reine Vektorsuche versagt bei attributreichen, prosaarmen
  Beständen — genau dem Normalfall in Fachdaten.
- **Konfidenz an der Antwort.** Ohne eine ausgewiesene Sicherheit kann niemand entscheiden, wann er
  eine Auskunft weiterverwendet.
- **Erklärbares Chunking und Sprung zur Textstelle.** Eine Fundstelle, die nicht bis in den
  Ausgangstext führt, trägt vor einem Prüfer nicht.
- **Deep Research über mehrere Quellen.** Mehrstufige Recherchefragen sind der Alltag in Referaten
  und heute nicht abgebildet.
- **Wissensgraph als Ergänzung des Vektor-Retrievals.** In `docs/discussions/GraphRAG.md` vorgedacht, in Phase 3
  benannt, ohne Vorgang.
- **Bewertung der Antwortqualität, nicht nur des Rankings.** Die Regressionsprüfung misst Retrieval;
  ob die Antwort durch ihre Quellen gedeckt ist, misst nichts.

### B — Wissensquellen & Konnektoren

- **Konkrete lesende Konnektoren.** Netzlaufwerk, Wiki und Postfach sind der eigentliche Wissensort
  einer Behörde; offen ist nur der Weg dorthin (#349), nicht die Quellen selbst.
- **Selbst aktualisierende Bestände.** Ohne inkrementellen Abgleich einschließlich Löschungen
  antwortet OPAA aus einem Bestand, den es im Quellsystem nicht mehr gibt.
- **Spiegelung der Rechte aus dem Quellsystem.** Wird eine Ablage mit eigenen Rechten eingelesen,
  entsteht ohne Übernahme dieser Rechte eine Umgehung.
- **Texterkennung für Scans und Handschrift.** Ein erheblicher Teil des Aktenbestands liegt als Bild
  vor und ist ohne sie unauffindbar.
- **Lebenszyklus einer Quelle.** Fehlerbehandlung, Wiederaufnahme und gezielte Neuindizierung
  bestimmen, ob der Betrieb beherrschbar bleibt.

### C — Spaces, Assets & Verteilung

- **Freigabe- und Prüfworkflow bis zum organisationsweiten Katalog.** In #198 ausdrücklich außerhalb
  des Umfangs, in der Ausrichtung aber der Kern der Verteilbarkeit.
- **Export und Import von Assets.** Ohne Portabilität gibt es weder eine Sicherung eines Assets noch
  den behördenübergreifenden Austausch der Phase 4.
- **Vorlagenkatalog nach Fachbereich.** Der schnellste Weg von der Installation zum ersten Nutzen ist
  ein fertiger Satz erprobter Vorlagen.

### D — Agenten, Prompts & Werkzeuge

Der am schwächsten belegte Bereich: Von den sechs tragenden Fähigkeiten hat keine einen Vorgang.

- **Geführtes Agenten-Onboarding.** Eine Aufgabenbeschreibung statt eines Freitext-Prompts ist der
  Unterschied zwischen einer Fachkraft und einem Prompt-Bastler.
- **Agenten-Prüfstand vor der Freigabe.** Ohne ihn wird ein Agent an die halbe Organisation verteilt,
  ohne dass jemand belegen kann, dass er tut, was er soll.
- **Prüfagenten als maschinelles Vier-Augen-Prinzip.** Für kritische Vorgänge ist eine zweite,
  unabhängige Prüfung die Bedingung, unter der eine Referatsleitung ein Ergebnis abzeichnet.
- **Isolierte Ausführungsumgebung.** Dateiverarbeitung, Auswertungen und Transkription brauchen eine
  Sandbox, sonst führt jede Erweiterung Code im Anwendungskontext aus.
- **Werkzeugaufrufe und MCP-Anbindung.** #349 klärt nur das Verhältnis zur Plugin-Architektur, nicht
  die Umsetzung.
- **Schreibende Aktionen mit menschlicher Freigabe.** Der Übergang von „fragen" zu „erledigen" steht
  und fällt mit einem belastbaren Freigabeschritt.

### E — Modelle & zentrale Steuerung

- **Modellverwaltung.** Mehrere Modelle je Aufgabe, gepflegt an einer Stelle statt über
  Umgebungsvariablen.
- **Zentrale Modellvorgaben als Obergrenze.** In #198 ausdrücklich außerhalb des Umfangs, in der
  Ausrichtung Teil des Leitprinzips Verteilbarkeit.
- **Beschränkung, die an den Daten hängt.** Eine Wissensbibliothek muss ihre Vorgabe „nur lokale
  Modelle" selbst mitführen, unabhängig davon, wer wo fragt.
- **Schutz vor Weitergabe personenbezogener Daten** an ein freigegebenes externes Modell.

### F — Identität, Rechte & Mandanten

- **Kontenlebenszyklus über SCIM.** Anlage, Änderung und Deaktivierung von Konten aus dem
  Verzeichnisdienst; in #198 ausdrücklich außerhalb des Umfangs.
- **Zugriffsbedingungen wie IP-Beschränkungen.** In vielen Häusern eine Voraussetzung der Freigabe.
- **Geschärftes Rollenmodell.** Die Trennung von Systemverwaltung, Fachadministration und
  Kuratorenrollen ist bisher nur in Teilen beschrieben.

### G — Sicherheit, Nachweis & Prüfbarkeit

- **Revisionssicheres Protokoll als Umsetzung.** #355 schneidet nur den Umfang; die Fähigkeit selbst
  gehört zu Phase 1 und hat keinen Vorgang.
- **Export in ein Sicherheitsmonitoring.** Behörden werten zentral aus; ohne Ausleitung bleibt OPAA
  ein blinder Fleck.
- **Stückliste der Bestandteile und Lieferkettennachweis.** Ohne sie kann ein Betreiber bei einer
  Schwachstellenmeldung nicht antworten.
- **Nachweispaket für die C5-Fähigkeit.** Das Produktziel ist, dass ein Betreiber die Prüfung mit
  OPAA im Prüfumfang besteht — dafür braucht er Unterlagen, nicht nur Eigenschaften.
- **Geprüfter sicherer Auslieferungszustand.** #250 dokumentiert die Härtung; ein abgesichertes
  Betriebsprofil und dessen automatische Prüfung fehlen.

### H — Monitoring, Kosten & Governance

- **Kontingente je Nutzer.** Heute gibt es nur eine Ratenbegrenzung, keine Obergrenze über den Monat.
- **Kostentransparenz.** Ohne Aufschlüsselung nach Modell und Organisationseinheit ist der Betrieb
  nicht planbar; die Aufschlüsselung muss aggregiert und ohne Personenbezug erfolgen.
- **Auswertung des KI-Rollouts.** Wie weit sich geprüfte Assets verbreiten, ist die einzige Größe, an
  der sich Verteilbarkeit überhaupt messen lässt.
- **Betriebsbeobachtbarkeit.** Metriken, Ablaufverfolgung und aussagekräftige Zustandsprüfungen
  fehlen; der einzige Hinweis darauf steckt in der überholten Sammelliste #60.

### I — Kanäle & Oberflächen

- **REST-API als zugesagte Außenschnittstelle.** Sie ist in Phase 1 versprochen, hat aber weder eine
  Zugangsart für Fremdsysteme noch eine Aussage zur Stabilität.
- **Anbindung an einen self-hosted Team-Chat.** Erst nach der Entscheidung in #352 zu schneiden, aber
  danach unmittelbar nötig.
- **Oberfläche für Wissensbibliotheken und Assets.** Zum Space- und Asset-Modell existiert
  frontendseitig nur #272; ohne Bedienoberfläche bleibt das Modell für Nutzende unsichtbar.

### J — Betrieb & Deployment

- **Betrieb auf Kubernetes mit Hochverfügbarkeit.** In Phase 1 zugesagt, ohne Vorgang.
- **Installation ohne Netzanbindung.** Bereitstellung von Abbildern, Modellen und Aktualisierungen im
  air-gapped Betrieb; #214 stößt an dieselbe offene Frage.
- **Sicherung und Wiederherstellung einschließlich Vektorindex.** Für einen Betriebsverantwortlichen
  die erste Frage, und heute nirgends beantwortet.
- **Aktualisierungs- und Migrationspfad zwischen Versionen.** Bisher gilt „harter Schnitt vor 1.0";
  danach braucht es einen belegten Weg.
- **Mandantenfähiger Betrieb durch ein Rechenzentrum.** Die Organisationsgrenze wird abgesichert,
  aber das Anlegen und Verwalten mehrerer Organisationen hat keinen Vorgang.

### K — Verwaltungs-Spezifika

Nach D der schwächste Bereich, obwohl er den Unterschied der Ausrichtung ausmacht.

- **Leichte Sprache und Amtssprache als Textwerkzeug.** Ausdrücklich Phase 1, ohne jeden Vorgang.
- **Barrierefreiheit nach BITV.** Für eine Behörde eine rechtliche Anforderung, nicht ein Merkmal;
  #193 ist ein Einzelbefund, keine Prüfung.
- **Deutsch als Standardsprache der Oberfläche.** Heute sind Texte gemischt hart verdrahtet; #145
  würde in seiner jetzigen Fassung Englisch zum Standard machen.
- **Revisionssichere Aufbewahrung von Vorgängen.** Über das Protokoll hinaus geht es um die Inhalte
  selbst und ihre Fristen.
- **Anbindung an elektronische Akte und Dokumentenmanagement.** Als Option der Phase 4 benannt, ohne
  Vorgang — hier genügt vorerst eine Notiz zu dem, was Phase 1 nicht verbauen darf.

---

## Empfohlene nächste Schritte

Priorisiert. **[Maintainer]** kennzeichnet einen Punkt, der eine Entscheidung braucht, bevor
Folgearbeit sinnvoll ist.

1. **Bereich D schneiden, sobald #349 entschieden ist.** Agenten-Onboarding, Prüfstand und
   Prüfagenten sind die Substanz der Phase 2 und haben bis heute keinen einzigen Vorgang. Das ist die
   größte Lücke im Backlog.
2. **Bereich K mit Vorgängen unterlegen.** Leichte Sprache und Amtssprache gehören in Phase 1; ohne
   sie fehlt der Ausrichtung ihr sichtbarster Verwaltungsbezug.
3. **Hybride Suche mit Reranking schneiden.** Von den drei schwersten Phase-1-Lücken ist sie die
   einzige ohne Vorstufe — Zitierzwang (#354) und Protokoll (#355) sind entschieden und über
   #386 bis #389 sowie #391 bis #395 unterlegt.
4. **Zwei Sicherheitsbefunde vorziehen: #271 und #307.** Der eine muss vor der zweiten Organisation
   geschlossen sein, der andere trifft jeden Rollout am ersten Tag. Dazu gehören #289, #400 und
   #401 aus derselben Erhebung.
5. **Über die Sprache der Vorgänge aus #198 entscheiden.** **[Maintainer]** #198 und seine
   Kindvorgänge #203 bis #216 sind auf Englisch verfasst und widersprechen damit der Projektsprache;
   die Übersetzung ist Aufwand ohne fachlichen Gewinn, das Belassen ein dauerhafter Bruch der
   Konvention. Empfehlung: beim nächsten inhaltlichen Anfassen jeweils mit übersetzen, keine
   gesonderte Aktion.

### Was diese Sichtung ausdrücklich nicht enthält

Keine Aufwands- oder Kostenaussagen, keine Anbieternamen und keine Aussage darüber, welche Lücke
zuerst gebaut wird — die Reihenfolge oben ist ein Vorschlag, keine Planung.
