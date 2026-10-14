# ADR-0043: Anweisung an das Modell je Space

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt [ADR-0031](0031-gespraechsgedaechtnis.md)

## Kontext

Die Systemanweisung der Antwortstufe ist im Code festgelegt und war ausdrücklich nicht
konfigurierbar: Rolle, Antwortsprache Deutsch (#1635), Antworten aus Verlauf und Kontextdokumenten,
Zitierregeln. Ein Space bündelt die Arbeit einer Organisationseinheit an einem Thema; was dort gilt
(„Wir sind ein Landkreis", „Nenne bei Fristen die Rechtsgrundlage"), musste jede Person in jedem
Gespräch neu sagen. Die Gesprächsnotiz (ADR-0031) trägt solche Angaben nur innerhalb eines Chats.
Agenten als eigener Asset-Typ mit Freigabekette (#209) sind als nicht geplant verworfen.

Gegenstand ist die bewusste Aufweichung von „Systemanweisung nicht konfigurierbar". Ein von der Leitung
gesetzter Text, der jede Antwort mitprägt, ist in der öffentlichen Verwaltung kein Randdetail
([ADR-0014](0014-produktausrichtung-oeffentliche-verwaltung.md)): Er steuert Auskünfte, für die jemand
geradesteht, und könnte ohne Vorkehrung die Belegpflicht aushebeln. Bindend sind deshalb:
Belegbarkeit bleibt unantastbar, und was eine Antwort beeinflusst, muss für die Betroffenen
nachvollziehbar sein.

## Entscheidung

Ein Space trägt optional eine **Anweisung an das Modell**, die in jedem Gespräch dieses Space in der
Antwortstufe mitwirkt. Die feste Systemanweisung bleibt im Code; die Anweisung ergänzt sie.

### 1. Datenmodell

`spaces.model_instruction`, nullbar, höchstens 1000 Zeichen in Datenbank, Spezifikation, Dienst und
Oberfläche. Leerer oder nur aus Leerraum bestehender Text ist `null`
(`chk_spaces_model_instruction_not_blank`); leer heißt kein Block. 1000 statt 2000, weil der Text bei
jeder Frage mitgeht und ein knapper Deckel Prägnanz erzwingt.

### 2. Berechtigung

Setzen und Ändern nur mit Space-Rolle `ADMIN` (direkt, über Gruppe oder als Eigentümer): Die Anweisung
wirkt auf jede Antwort jedes Mitglieds, das ist eine Leitungsentscheidung. Die Systemverwaltung ändert
ohne die Rolle Name und Beschreibung, nicht die Anweisung. Organisationsgrenze wie bei jeder
Space-Operation.

### 3. Sichtbarkeit

Lesen dürfen alle Mitglieder: schreibgeschützt in den Space-Einstellungen und in der Kopfzeile jedes
Chats als aufklappbare Schaltfläche „Anweisung des Space", schon vor der ersten Frage. Ein unsichtbarer
Text, der Antworten steuert, wäre den Mitgliedern gegenüber nicht vertretbar.

### 4. Standard-Space

Im persönlichen Standard-Space ist die Anweisung eine Daueranweisung der Person selbst, die dort
`ADMIN` ist.

### 5. Wirkungszeitpunkt

Je Frage gelesen, wirksam ab dem nächsten Aufruf in jedem Chat des Space, auch in älteren Chats — wie
die Modellwahl. Kein Einfrieren je Gespräch.

### 6. Rang im Prompt

Reihenfolge: feste Regeln → Space-Block → Gesprächsnotiz-Block → Kontextabschnitt. Der Space-Block
trägt eine statusklärende Kopfzeile zwischen zwei Markenzeilen: Er ist eine Vorgabe des Space, kein
Beleg und keine Quelle; er darf Sprache, Tonfall, Form und fachlichen Rahmen festlegen; eine
Sprachvorgabe geht der festen Regel „Deutsch" vor, weil Sprache keine Belegregel ist — die wiederholte
Sprachzeile nach dem letzten Kontextabschnitt überlässt die Sprache dann der Anweisung
(`AnswerGenerationService.SPACE_LANGUAGE_REMINDER`); Zitierpflicht,
Zitatformat und das Verbot erfundener Quellen gelten unabhängig von seinem Wortlaut. Ein abweichender
Wunsch der fragenden Person aus diesem Gespräch geht ihm vor, deshalb steht die Notiz hinter ihm.

### 7. Ohne Anweisung bleibt alles, wie es war

Fehlt sie, entfällt der Block, und der Prompt ist zeichengleich zum bisherigen; ein Test hält den
vollständigen Prompt fest.

### 8. Nur die Antwortstufe

Die Teilfragen-Zerlegung bekommt die Anweisung nicht (eine Stilvorgabe hat in einer Suchanfrage nichts
zu suchen); die Belegprüfung kennt sie ebenfalls nicht.

### 9. Nachweis

Jede Änderung ist ein `SPACE_CHANGED` mit `modelInstruction` in `changedFields`; der Text selbst gelangt
nicht ins Protokoll, wie Name und Beschreibung.

## Konsequenzen

**Einfacher:** Eine Fachverantwortliche hinterlegt den Rahmen ihres Space einmal; Mitglieder sehen, was
mitwirkt. Der Einbau folgt dem Muster der Gesprächsnotiz ohne neues Rechtemodell oder Objekttyp.
Gemessene Antworten bleiben vergleichbar, weil ein Space ohne Anweisung den unveränderten Prompt
sendet; die Suchqualitäts-Evaluierung misst nur die Suchstrecke, die die Anweisung nie erreicht.

**Schwieriger und Restrisiken:** Die Sicherung der Belegregeln ist eine Rangfolge im Prompt, keine
harte Schranke — ein geschickt formulierter Text kann ein Modell beeinflussen; was nicht am Modell
hängt (Rechteprüfung vor der Suche, deterministische Belegprüfung, Protokoll), bleibt unberührt, und
ein Test hält fest, dass ein Text, der die Zitierpflicht aufzuheben versucht, an Zitierregeln und
Belegprüfung nichts ändert. Ein Text der Leitung prägt Antworten aller Mitglieder; dagegen stehen
Sichtbarkeit, `ADMIN`-Beschränkung und Protokolleintrag — den früheren Wortlaut bewahrt das Protokoll
bewusst nicht. Der Hilfetext ist die einzige Vorkehrung gegen personenbezogene Angaben im Feld. Jede
Frage kostet bis zu 1000 Zeichen mehr Eingabe.

## Verworfene Alternativen

- Feste Systemanweisung konfigurierbar machen — hebt die Belegregeln in die Hand jedes Betreibers.
- Agenten als Asset-Typ mit Freigabekette (#209) — für eine Anweisung je Raum zu schwer.
- Rolle `CURATOR` statt `ADMIN` — Kuratoren ordnen Inhalte; die Vorgabe ist eine Leitungsentscheidung.
- Systemverwaltung darf jede Anweisung ändern — schriebe in fremde Räume und persönliche Daueranweisungen.
- Anweisung je Gespräch einfrieren — überholter Rahmen wirkt weiter, ohne dass die Person es sieht.
- Space-Block nach der Gesprächsnotiz — die Hausregel überschriebe einen Wunsch der fragenden Person.
- Anweisung auch an die Teilfragen-Zerlegung — Stilvorgaben verfälschen Suchanfragen.
- Text im Protokoll mitschreiben — Freitext im Revisionsprotokoll.

## Offen

- Ob fachliche Rahmenangaben aus dem Space-Text künftig auch die Teilfragen-Zerlegung erreichen sollen.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-10-11 | #1637 | Erstfassung | 1–9 |
| 2026-10-14 | #2701 | Neufassung auf den gültigen Endstand; persönlicher Space heißt Standard-Space (#2099) | 4 |
