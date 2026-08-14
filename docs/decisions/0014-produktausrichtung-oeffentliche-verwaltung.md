# ADR-0014: Produktausrichtung auf die öffentliche Verwaltung

## Status

Akzeptiert

## Kontext

OPAA ist als generisches, selbst gehostetes Wissensmanagement für Organisationen begonnen worden. Die
Dokumentation beschreibt entsprechend einen breiten Adressatenkreis: ein Konzern mit 5.000 Beschäftigten,
ein SaaS-Unternehmen mit 50 Leuten, ein Support-Team, eine Gesundheitsorganisation. Das Produktversprechen
war Austauschbarkeit — jede Komponente konfigurierbar, jede Datenbank ersetzbar, jeder Modellanbieter
gleichwertig, Betrieb wahlweise im eigenen Haus oder in einer öffentlichen Cloud.

Aus der Arbeit an konkreten Anwendungsfällen hat sich ein anderes Bild ergeben, und zwar in drei Punkten:

**Der Adressat ist enger und anspruchsvoller.** Die Anforderungen, die OPAA von einem Chat-Frontend über
einer Vektorsuche unterscheiden, kommen aus der öffentlichen Verwaltung: Eine Auskunft muss belegbar sein,
weil jemand mit seinem Namen dafür geradesteht. Steuerdaten dürfen § 30 AO zufolge das Haus nicht in eine
fremde Cloud verlassen. Ein Rollout beginnt nicht ohne Dienstvereinbarung, weil die Personalvertretung
mitbestimmt. Ein Betreiber muss die Prüfung nach BSI C5 bestehen. Diese Anforderungen sind nicht
zusätzliche Häkchen, sondern bestimmen die Architektur — von der Rechteprüfung zur Abfragezeit bis dahin,
welche Auswertungen es überhaupt geben darf.

**Das Produkt ist mehr als Wissensmanagement.** Fragen zu beantworten ist der Anfang, nicht das Ziel.
Wiederkehrende Abläufe sollen von Agenten erledigt werden, wissensgeerdet und abgestuft von reinem Lesen
bis zu schreibenden Aktionen mit Freigabe.

**Der eigentliche Engpass ist die Verteilung.** Das reale Problem in einer Behörde ist heute nicht, ob es
ein gutes Modell gibt, sondern wie KI-Kompetenz von wenigen Könnern zu allen Beschäftigten kommt. Ohne
Antwort darauf entsteht Schatten-KI: Einzelne basteln private Prompts und kopieren Amtsdaten in
Verbraucherwerkzeuge. Ein Produkt, das darauf keine Antwort hat, löst das kleinere Problem.

Der bestehende Dokumentationsbestand bildet das nicht ab. Die jüngeren Dokumente — `spaces-and-assets.md`,
`access-control.md`, `search-quality-evaluation.md` — sind bereits nach der neuen Ausrichtung geschrieben,
die älteren nicht. Das Repository behauptet damit an verschiedenen Stellen Verschiedenes.

## Entscheidung

OPAA wird als **souveräne, quelloffene KI-Plattform für die öffentliche Verwaltung** geführt.

**Drei Säulen** tragen das Produkt, ohne dass Daten das Haus verlassen:

1. **Wissen** — verstreutes Wissen wird befragbar und nachweisbar.
2. **Agenten** — wiederkehrende Aufgaben werden automatisiert, immer wissensgeerdet.
3. **KI für Teams und Organisation** — KI-Fähigkeit wird verteilbar: geteilte Arbeitsräume, teilbare
   Agenten, Prompt-Bibliotheken und zentral gesetzte Modellvorgaben.

**Zwei Leitprinzipien** entscheiden im Zweifel:

- **Belegbarkeit** — jede Aussage ist an ihre Quelle gebunden und für haftungskritische Kontexte in den
  Zitierzwang schaltbar: keine belegte Quelle, keine Antwort.
- **Verteilbarkeit** — KI-Können wird zum benannten, teilbaren, versionierbaren Asset und wandert über
  Freigabestufen von der einzelnen Person bis in die ganze Organisation.

**Primärer Nutzerkreis ist die interne Verwaltung** — Sachbearbeitung, Fachreferate, IT. Ein Assistent für
Bürgerinnen und Bürger bleibt ein späterer Ausblick und ist ausdrücklich nicht Teil des Fundaments.

Der Produktumfang wird in **elf Themenbereichen** geführt und in **vier Phasen** ausgeliefert, von denen
jede für sich nutzbar ist. Beides ist in [VISION.md](../VISION.md) beschrieben.

## Konsequenzen

### Was einfacher wird

Entscheidungen bekommen einen Maßstab. Bisher war „ist das konfigurierbar genug?" die Leitfrage; jetzt ist
es „hilft das der Belegbarkeit oder der Verteilbarkeit?". Das schließt Optionen aus, und genau darin liegt
der Gewinn: Ein Produkt für einen bekannten Adressaten kann Annahmen treffen, die ein generisches Produkt
sich nicht leisten kann — etwa, dass die Rechteprüfung in die Vektorsuche gehört und nicht dahinter.

Die Anforderungen, die bisher als Compliance-Anhängsel erschienen, werden zu Produktmerkmalen mit eigenem
Wert: revisionssicheres Protokoll, Nachweisbarkeit gegenüber Prüfern, Mitbestimmungsfähigkeit.

### Was schwieriger wird

Ein Teil des bisherigen Versprechens trägt nicht mehr, und ein Teil davon ist gebaut oder eingeplant.
Diese Punkte werden **nicht mit diesem ADR entschieden**. Sie werden benannt und einzeln geprüft, weil ihre
stillschweigende Streichung eine Konsistenz vortäuschen würde, die im Code nicht existiert:

| Punkt | Spannung zur neuen Ausrichtung |
|---|---|
| Austauschbare Vektorspeicher | ADR-0002 hat pgvector gewählt; wer im Behördenrechenzentrum betreibt, wählt die Datenbank in der Regel nicht selbst |
| Cloud-Deployment und Managed Service | steht gegen On-Premises als Standard und gegen air-gapped-Fähigkeit |
| Verbraucher-Chatkanäle | geringer Wert in der Verwaltung, offene Fragen zum Datenabfluss |
| Cloud-Modelle als Standardeinstellung | die neue Ausrichtung ist lokal-first mit Cloud nur bei ausdrücklicher Freigabe |
| Plugin-Architektur für Konnektoren | Verhältnis zu MCP ist ungeklärt; bleibt vorerst als Option bestehen |

Die Prüfung dieser Punkte läuft über ein eigenes Epic mit je einer Entscheidungsvorlage. Bis dahin bleibt
alles bestehen.

Umgekehrt entstehen Anforderungen, für die es heute nichts gibt: Zitierzwang, hybride Suche mit Reranking,
Agenten-Onboarding und -Prüfstand, zentrale Modellvorgaben, SCIM-Lebenszyklus, revisionssicheres
Protokoll, Leichte Sprache, Barrierefreiheit nach BITV. Der Abstand zwischen Anspruch und Stand wird damit
sichtbar größer — das ist beabsichtigt und wird in [STATUS.md](../STATUS.md) offen geführt statt
beschönigt.

### Was in der Dokumentation nicht vorkommt

Für alle Dokumente und Marketing-Assets gilt: keine Namen von Mitbewerbern, keine Vergleiche mit
benannten Produkten, keine beteiligten Personen oder Partnerunternehmen, keine Referenzkunden, keine
Preise, keine Aufwands- oder Kostenschätzungen. Abgrenzungen werden mit dem Sachgrund begründet.

## Referenzen

- [VISION.md](../VISION.md) — Nordstern, Themenbereiche, Phasen
- [USE-CASES.md](../USE-CASES.md) — Anwendungsfälle im Verwaltungsalltag
- [ADR-0002](0002-mvp-technology-stack.md) — Technologieentscheidungen des Fundaments
- [features/spaces-and-assets.md](../features/spaces-and-assets.md) — das Verteilungsmodell

## Nachträge: entschiedene Punkte

Die Tabelle unter [Was schwieriger wird](#was-schwieriger-wird) benennt Punkte, die dieser ADR
ausdrücklich **nicht** entscheidet. Sobald einer davon entschieden ist, kommt er hier als Nachtrag
hinzu — der ADR selbst bleibt im Wortlaut unverändert, damit erkennbar bleibt, was wann galt. Jeder
Nachtrag hat denselben Aufbau: Datum, Punkt, Entscheidung, Begründung, Verweis.

### 14.08.2026 — Umfang der Speicher-Abstraktion für Dokumente

- **Punkt:** Umfang der Abstraktion über Speicher-Backends für Originaldokumente.
- **Entscheidung:** Das Dateisystem ist der Vertrag. OPAA schreibt und liest Quelldokumente gegen genau
  ein konfiguriertes Verzeichnis; eine Abstraktion über mehrere Speicherarten gibt es nicht und ist für
  die dateibasierten Fälle auch nicht vorgesehen. Ein Netzlaufwerk (SMB/NFS) wird vom Betrieb dorthin
  eingehängt. Objektbasierter Speicher wird als eigener Weg geführt, aber ohne Termin; ein
  Objektspeicher-Dienst gehört nicht in den mitgelieferten Compose-Stapel. Der Code bleibt unverändert —
  geändert wird allein das Versprechen in der Dokumentation.
- **Begründung:** Zwei der drei bisher zugesagten Backends sind mit dem heutigen Code bereits abgedeckt,
  weil ein eingehängtes Netzlaufwerk für die Anwendung wie ein Verzeichnis aussieht, sodass der
  zugesagten Abstraktion in diesen Fällen kein Bedarf gegenübersteht. Objektbasierter Speicher braucht
  als einziger einen eigenen Pfad im Code und bleibt deshalb als Weg bestehen; der Grund dafür ist der
  mandantenfähige Rechenzentrumsbetrieb, in dem ein geteiltes Netzlaufwerk bei Mandantentrennung,
  Kontingenten und Sicherung der unangenehmere Weg ist.
- **Verweis:** [#351](https://github.com/criew/opaa/issues/351) ·
  [features/deployment-infrastructure.md](../features/deployment-infrastructure.md#speicher-backends)
