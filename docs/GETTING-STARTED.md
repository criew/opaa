# Einstieg in die OPAA-Dokumentation

Nicht sicher, wo Sie anfangen sollen? Dieser Leitfaden führt Sie zu den Dokumenten, die zu Ihrer Frage
passen. Er ist nicht nach Software-Rollen geschnitten, sondern nach dem, was Sie in einer Behörde
verantworten.

---

## Für alle: die ersten zwanzig Minuten

1. **[README](../README.md)** — was OPAA ist und für wen
2. **[VISION.md](./VISION.md)** — Nordstern, die beiden Leitprinzipien Belegbarkeit und Verteilbarkeit
3. **[USE-CASES.md](./USE-CASES.md)** — wie sich das im Arbeitsalltag anfühlt

Danach in den Lesepfad wechseln, der zu Ihnen passt.

> **Ein Hinweis vorweg.** [VISION.md](./VISION.md) beschreibt das Zielbild, [STATUS.md](./STATUS.md) den
> tatsächlich gebauten Stand. Wenn Sie eine Entscheidung auf eine Fähigkeit stützen wollen, prüfen Sie
> beides. OPAA ist im Aufbau, und die Dokumentation sagt das an jeder Stelle.

---

## Fachbereich und Amtsleitung

**Ihre Frage:** Was tut OPAA im Alltag, und kann ich mich auf das Ergebnis verlassen?

**Lesepfad:**

1. [VISION.md](./VISION.md) — Nordstern und die beiden Leitprinzipien
2. [USE-CASES.md](./USE-CASES.md) — die Abläufe, um die es geht
3. [CONCEPTS.md](./CONCEPTS.md) — nur die Abschnitte **Belegbarkeit**, **Verteilbarkeit**, **Fundstelle und
   Quellenbindung**, **Zitierzwang**, **Konfidenz**
4. [`features/spaces-and-assets.md`](./features/spaces-and-assets.md) — wie eine gute Arbeitsweise vom
   Einzelfall zum geprüften Standard wird
5. [`features/public-sector.md`](./features/public-sector.md) — Leichte Sprache, Amtssprache,
   Barrierefreiheit
6. [STATUS.md](./STATUS.md) — was davon heute geht

**Worauf es dabei ankommt:**

- Eine Antwort nennt ihre Fundstelle, und Sie springen mit einem Klick dorthin
- Wo nichts belegbar ist, wird nichts behauptet — im Zitierzwang ergeht gar keine Antwort
- Was Ihr Bereich an Arbeitsweise entwickelt, wird ein benanntes, freigegebenes, nachvollziehbares Asset
- Nachvollziehbar bleibt auch, wer wann welche Fassung freigegeben hat

---

## Behörden-IT und Betrieb

**Ihre Frage:** Was muss ich betreiben, aktuell halten und gegenüber Prüfern erklären?

**Lesepfad:**

1. [CONCEPTS.md](./CONCEPTS.md) — Abschnitte **Betrieb**, **Modelle und zentrale Steuerung**,
   **Retrieval und Belegbarkeit**
2. [VISION.md](./VISION.md) — Abschnitt „Systemüberblick"
3. [`features/deployment-infrastructure.md`](./features/deployment-infrastructure.md) — **vertieft**;
   ergänzend [deployment.md](./deployment.md) für die vorhandene Installation
4. [`features/llm-integration.md`](./features/llm-integration.md) — Modellverwaltung, Modell-Policy als
   Obergrenze, lokal betriebene Modelle
5. [`features/access-control.md`](./features/access-control.md) — Anmeldung, Kontenlebenszyklus, Mandanten
6. [`features/monitoring-and-governance.md`](./features/monitoring-and-governance.md) — Metriken, Grenzen,
   Kostentransparenz
7. [`features/data-indexing-rag.md`](./features/data-indexing-rag.md) und
   [`features/knowledge-sources.md`](./features/knowledge-sources.md) — was indiziert wird und wie oft
8. [STATUS.md](./STATUS.md), Bereiche **J**, **E**, **F**, **H** — der ehrliche Stand

**Worauf es dabei ankommt:**

- Betrieb im eigenen Rechenzentrum bis hin zur Installation ohne Netzanbindung
- Lokal betriebene Modelle sind die Voreinstellung; eine unkonfigurierte Installation spricht nicht nach
  außen
- Modelle sind austauschbar, ohne dass ein Fachbereich seine Agenten anfassen muss
- Quelloffen und damit prüfbar, statt zugesichert

**Für Beitragende mit Betriebshintergrund:** Build- und Testbefehle stehen in
[AGENTS.md](../AGENTS.md), der Technologiestapel in
[ADR-0002](./decisions/0002-mvp-technology-stack.md), die Anmeldung in
[ADR-0005](./decisions/0005-authentication-strategy.md).

---

## Datenschutz, Informationssicherheit und Personalvertretung

**Ihre Frage:** Wo liegen die Daten, was wird ausgewertet — und was ausdrücklich nicht?

**Lesepfad:**

1. [CONCEPTS.md](./CONCEPTS.md) — Abschnitte **Sicherheit, Nachweis und Mitbestimmung** sowie
   **Berechtigungsdurchsetzung zur Abfragezeit**
2. [`features/security-and-compliance.md`](./features/security-and-compliance.md) — **vertieft**:
   revisionssicheres Protokoll, Vollständigkeit nach DSGVO, C5-Fähigkeit
3. [`features/spaces-and-assets.md`](./features/spaces-and-assets.md) — Rechtemodell, Freigabekette,
   Mitbestimmung
4. [`features/access-control.md`](./features/access-control.md) — Kontenlebenszyklus, Mandantengrenze
5. [`features/monitoring-and-governance.md`](./features/monitoring-and-governance.md) — was ausgewertet wird
   und in welcher Aggregation
6. [`features/deployment-infrastructure.md`](./features/deployment-infrastructure.md) — Verschlüsselung,
   Netztrennung, Sicherung
7. [STATUS.md](./STATUS.md), Bereich **G** — die derzeit größte Lücke, offen benannt

**Worauf es dabei ankommt:**

- Daten verlassen das Haus nicht; der Betrieb ohne Netzanbindung ist ein vorgesehenes Szenario
- Die Rechteprüfung sitzt **in** der Suche, nicht dahinter: Was jemand nicht lesen darf, wird nicht geladen
  und nicht gerankt
- Beschränkungen hängen an den Daten, nicht am Arbeitsraum — ein Raumwechsel umgeht sie nicht
- Sichtbarkeit ist eine Handlung, keine Automatik; der persönliche Bereich bleibt unbeobachtet
- Es gibt keinen personenbezogenen Auswertungspfad und keine Ranglisten — nicht abgeschaltet, sondern nicht
  gebaut
- OPAA ist **nicht zertifiziert**. Das Ziel ist, dass ein Betreiber die Prüfung mit OPAA im Prüfumfang
  besteht

---

## Beitragende

**Ihre Frage:** Wie arbeite ich mit — und woran?

**Lesepfad:**

1. [CONCEPTS.md](./CONCEPTS.md) — vollständig, es ist die gemeinsame Sprache im Repository
2. [VISION.md](./VISION.md) — besonders die elf Themenbereiche und die vier Phasen
3. [STATUS.md](./STATUS.md) — **hier stehen die Lücken.** Die Bereiche **D** und **K** sind heute im Code
   und im Backlog leer, Bereich **G** ist die größte Lücke gegenüber Phase 1
4. [`../CONTRIBUTING.md`](../CONTRIBUTING.md) — Ablauf, Branch-Regel, Contributor License Agreement
5. [`../AGENTS.md`](../AGENTS.md) — Konventionen, Build- und Testbefehle, Pre-Push-Checkliste
6. [AGENT-ORGANIZATION.md](./AGENT-ORGANIZATION.md) — Rollen und der Weg von der Idee bis zum Merge
7. [`decisions/`](./decisions/) — vor jeder größeren strukturellen Änderung zu lesen
8. Die Spezifikation des Bereichs, an dem Sie arbeiten wollen — siehe [INDEX.md](./INDEX.md)

**Worauf es dabei ankommt:**

- Projektsprache ist Deutsch; Englisch bleibt dem Quellcode vorbehalten
- Jeder Branch hängt an einem Issue, jeder PR an einer logischen Änderung
- Ohne unterzeichnete [CLA](../CLA.md) wird kein Pull Request zusammengeführt
- Beiträge von Menschen und KI-Agenten sind gleichermaßen willkommen; KI-Beiträge werden im PR offengelegt

---

## Lesestrategien

**Großes Bild zuerst** — für Leitung und Entscheidungsvorbereitung: VISION.md, dann USE-CASES.md, dann
gezielt in eine Spezifikation.

**Lernen während des Tuns** — für Beitragende: CONCEPTS.md, dann die eigene Spezifikation vollständig, dann
die angrenzenden, sobald eine Abhängigkeit auftaucht.

**Prüfen statt lesen** — für Datenschutz, Informationssicherheit und Prüfung: mit STATUS.md beginnen, nicht
mit VISION.md. Was dort nicht als gebaut steht, ist ein Vorhaben.

---

## Häufige Fragen

**Woher weiß ich, ob eine beschriebene Fähigkeit existiert?**
→ Aus [STATUS.md](./STATUS.md). Feature-Spezifikationen beschreiben das Zielbild.

**Kann ich nur eine Spezifikation lesen?**
→ Ja, aber lesen Sie zuvor „Systemüberblick" in [VISION.md](./VISION.md) für den Zusammenhang.

**Wo finde ich die Antwort auf eine bestimmte Frage?**
→ [INDEX.md](./INDEX.md), Abschnitt „Häufige Fragen".

**Ein Begriff sagt mir nichts.**
→ [CONCEPTS.md](./CONCEPTS.md), Abschnitt „Schnellreferenz".

**Gibt es Code-Beispiele in diesen Dokumenten?**
→ Nein. Es sind Produkt- und Entwurfsdokumente; der Code liegt in `backend/` und `frontend/`.

---

## Dokumentenkarte

```
Einstieg
├── README.md              — was OPAA ist und für wen
├── docs/VISION.md         — Zielbild: Prinzipien, elf Bereiche, vier Phasen
├── docs/USE-CASES.md      — Abläufe aus dem Verwaltungsalltag
├── docs/CONCEPTS.md       — Begriffe und Glossar
├── docs/STATUS.md         — was heute gebaut ist
├── docs/INDEX.md          — vollständiger Index
└── docs/GETTING-STARTED.md — diese Datei

Spezifikationen (docs/features/) — je Themenbereich
├── A  data-indexing-rag.md · search-quality-evaluation.md
├── B  knowledge-sources.md
├── C  spaces-and-assets.md
├── D  agents-and-tools.md
├── E  llm-integration.md
├── F  access-control.md
├── G  security-and-compliance.md
├── H  monitoring-and-governance.md
├── I  user-frontends.md
├── J  deployment-infrastructure.md
└── K  public-sector.md
    (document-sharing.md ist überholt und nur als Historie erhalten)

Entscheidungen und Recherche
├── docs/decisions/        — ADRs, darunter 0014 zur Produktausrichtung
├── docs/discussions/      — offene Erörterungen
├── docs/GraphRAG.md       — Wissensgraph als Ergänzung des Vektor-Retrievals
└── docs/design/           — Oberflächenentwürfe

Mitarbeit
├── CONTRIBUTING.md        — Leitfaden für Beitragende
├── CLA.md                 — Contributor License Agreement
├── AGENTS.md              — Konventionen und Befehle
├── docs/AGENT-ORGANIZATION.md — Rollen und Workflow
└── docs/tagesreport.md    — täglicher Projektbericht

Betrieb
└── docs/deployment.md     — die vorhandene Installation
```
