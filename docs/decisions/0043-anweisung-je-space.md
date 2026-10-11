# ADR-0043: Anweisung an das Modell je Space

## Status

Akzeptiert (Maintainer-Entscheidung vom 11.10.2026)

## Kontext

Die Systemanweisung der Antwortstufe ist im Code festgelegt und war bisher ausdrücklich **nicht
konfigurierbar**: Rolle, Antwortsprache Deutsch (#1635), die Anweisung, aus Verlauf und
Kontextdokumenten zu antworten, und die Zitierregeln. Tonfall, Rolle der fragenden Einheit oder
fachlicher Rahmen waren nicht Teil davon.

Ein Space bündelt die Arbeit einer Organisationseinheit an einem Thema. Was für dieses Thema gilt,
gilt für jede Frage darin: „Wir sind ein Landkreis, keine kreisfreie Stadt", „Nenne bei Fristen
immer die Rechtsgrundlage", „Fasse dich kurz". Heute muss das jede Person in jedem Gespräch neu
sagen. Die Gesprächsnotiz ([ADR-0031](./0031-gespraechsgedaechtnis.md)) trägt solche Angaben über
die Runden eines Chats, aber nur innerhalb dieses Chats und nur aus dem, was die fragende Person
selbst geschrieben hat.

Der größere Zuschnitt — Agenten als eigener Asset-Typ mit Freigabekette (#209) — ist als nicht
geplant verworfen. Ein Feld am Space braucht kein neues Rechtemodell, keine Freigabekette und keinen
zweiten Objekttyp.

Gegenstand dieser Entscheidung ist deshalb nicht das Datenfeld, sondern **die bewusste Aufweichung
der Festlegung „Systemanweisung nicht konfigurierbar"**. Ein von der Leitung gesetzter Text, der jede
Antwort mitprägt, ist in der öffentlichen Verwaltung kein Randdetail
([ADR-0014](./0014-produktausrichtung-oeffentliche-verwaltung.md)): Er steuert Auskünfte, für die
jemand mit seinem Namen geradesteht, und er könnte — ohne Vorkehrung — die Belegpflicht aushebeln,
auf der Fundstellen und Belegsprung aufbauen. Zwei Linien aus ADR-0014 und ADR-0031 sind deshalb
bindend: **Belegbarkeit** bleibt unantastbar, und **was eine Antwort beeinflusst, muss für die
betroffenen Personen nachvollziehbar sein**.

## Entscheidung

Ein Space trägt optional eine **Anweisung an das Modell**, die in jedem Gespräch dieses Space in der
Antwortstufe mitwirkt. Die feste Systemanweisung bleibt im Code und unverändert; die Anweisung des
Space **ergänzt** sie, sie ersetzt sie nicht.

1. **Datenmodell.** Ein nullbares Feld `spaces.model_instruction`, **höchstens 1000 Zeichen** —
   einheitlich in Datenbank, OpenAPI-Spezifikation, Dienst und Oberfläche. Ein leerer oder nur aus
   Leerraum bestehender Text ist **keine** Anweisung (`null`, durch einen `CHECK` abgesichert); leer
   heißt: kein Block, nicht ein leerer. 1000 statt der vorgeschlagenen 2000 Zeichen der Beschreibung,
   weil der Text bei jeder Frage mitgeht und Tokens kostet und ein knapper Deckel Prägnanz erzwingt.
2. **Berechtigung.** Setzen und Ändern nur mit der **Space-Rolle `ADMIN`** — direkt, über eine Gruppe
   oder als Eigentümer. Die Anweisung wirkt auf jede Antwort jedes Mitglieds; das ist eine
   Leitungs-, keine Kuratierungsentscheidung. Der Vorrang der Systemverwaltung reicht bewusst nicht
   hinein: Ohne die Rolle ändert sie Name und Beschreibung eines Space, aber nicht dessen Anweisung.
   Die Organisationsgrenze gilt wie für jede Space-Operation.
3. **Sichtbarkeit.** **Lesen dürfen alle Mitglieder**, und die Oberfläche zeigt ihnen, dass eine
   Anweisung wirkt: schreibgeschützt in den Stammdaten der Space-Einstellungen und in der Kopfzeile
   jedes Chats als aufklappbare Schaltfläche „Anweisung des Space", schon vor der ersten Frage. Ein
   unsichtbarer Text, der Antworten steuert, wäre gegenüber den Mitgliedern nicht vertretbar.
4. **Persönlicher Space.** Die Anweisung gibt es auch dort; sie ist dann eine persönliche
   Daueranweisung der Person selbst, die dort ohnehin `ADMIN` ist.
5. **Wirkungszeitpunkt.** Die Anweisung wird je Frage gelesen und wirkt **ab dem nächsten Aufruf in
   jedem Chat des Space**, auch in Chats, die vor der Änderung entstanden sind — wie die Modellwahl.
   Es gibt kein Einfrieren je Gespräch.
6. **Rang im Prompt.** Die Reihenfolge der Antwort-Anweisung ist: **feste Regeln → Space-Block →
   Gesprächsnotiz-Block → Kontextabschnitt**. Der Space-Block trägt eine statusklärende Kopfzeile
   nach dem Muster der Gesprächsnotiz und steht zwischen zwei Markenzeilen:
   - Er ist eine Vorgabe des Space, **kein Beleg und keine Quelle**.
   - Er darf Sprache, Tonfall, Form und fachlichen Rahmen festlegen. **Eine Sprachvorgabe darin geht
     der festen Sprachregel „Deutsch" (#1635) vor**, denn Sprache ist keine Belegregel. Die
     wiederholte Sprachzeile nach dem letzten Kontextabschnitt überlässt die Sprache dann der
     Anweisung.
   - Er **hebt die Zitierregeln nicht auf**: Zitierpflicht, Zitatformat und das Verbot erfundener
     Quellen gelten unabhängig von seinem Wortlaut. Was er dazu anders verlangt, gilt nicht.
   - Ein abweichender Wunsch der fragenden Person aus diesem Gespräch geht ihm vor: Das Aktuelle
     überschreibt im Zweifel die Hausregel, nicht umgekehrt. Deshalb steht die Notiz hinter ihm.
7. **Ohne Anweisung bleibt alles, wie es war.** Fehlt sie, entfällt der Block, und der Prompt ist
   **zeichengleich** zum bisherigen; ein Test hält den vollständigen Prompt dafür fest.
8. **Nur die Antwortstufe.** Die Teilfragen-Zerlegung bekommt die Anweisung nicht — eine Stilvorgabe
   hat in einer Suchanfrage nichts zu suchen. Ob fachliche Rahmenangaben aus dem Space-Text künftig
   auch die Zerlegung erreichen sollen, ist eine eigene Frage und hier nicht entschieden. Die
   Belegprüfung kennt die Anweisung ebenfalls nicht und arbeitet mit ihr genau wie ohne sie.
9. **Nachweis.** Jede Änderung ist ein Audit-Ereignis über das bestehende `SPACE_CHANGED`; es nennt in
   `changedFields` nur `modelInstruction`. Der Text selbst gelangt nicht ins Protokoll — wie Name und
   Beschreibung, deren Freitext dort ebenfalls nicht steht.

## Konsequenzen

**Einfacher wird:** Eine Fachverantwortliche hinterlegt den Rahmen ihres Space einmal, statt dass
jede Person ihn in jedem Gespräch wiederholt. Mitglieder sehen, was mitwirkt, und können es lesen.
Der Einbau folgt dem vorhandenen Muster der Gesprächsnotiz und braucht weder ein neues Rechtemodell
noch einen neuen Objekttyp.

**Schwieriger wird bzw. bleibt offen:**

- **Die Sicherung der Belegregeln ist eine Rangfolge im Prompt, keine harte Schranke.** Kein
  Sprachmodell trennt Anweisung und Vorgabe zuverlässig; ein geschickt formulierter Space-Text kann
  ein Modell dennoch beeinflussen. Was nicht am Modell hängt, bleibt davon unberührt: Rechteprüfung
  vor der Suche, deterministische Belegprüfung nach der Erzeugung und das Protokoll. Ein unbelegter
  Satz bleibt unbelegt, gleich was die Anweisung verlangt. Ein Test hält fest, dass ein Space-Text,
  der die Zitierpflicht aufzuheben versucht, an Zitierregeln, Zitatformat der Passagen und
  Belegprüfung nichts ändert.
- **Ein Text der Leitung prägt Antworten aller Mitglieder.** Dagegen stehen Sichtbarkeit für alle
  Mitglieder, die Beschränkung auf die Space-Rolle `ADMIN` und der Protokolleintrag je Änderung.
  Wer was geschrieben hat, sagt das Protokoll über den Zeitpunkt und die handelnde Person; den
  früheren Wortlaut bewahrt es bewusst nicht.
- **Der Hilfetext ist die einzige Vorkehrung gegen personenbezogene Angaben im Feld.** Eine
  maschinelle Prüfung gibt es nicht.
- **Jede Frage in einem Space mit Anweisung kostet bis zu 1000 Zeichen mehr Eingabe.**
- **Gemessene Antworten bleiben vergleichbar**, weil ein Space ohne Anweisung den unveränderten Prompt
  sendet. Die Mehrrunden-Fälle der Suchqualitäts-Evaluierung messen ohnehin nur die Suchstrecke samt
  Zerlegung, die die Anweisung nie erreicht.

### Verworfene Alternativen

| Alternative | Warum verworfen |
|---|---|
| Feste Systemanweisung konfigurierbar machen | hebt die Belegregeln in die Hand jedes Betreibers; die Ergänzung je Space genügt dem Bedarf |
| Agenten als eigener Asset-Typ mit Freigabekette (#209) | als nicht geplant verworfen; für eine Anweisung je Arbeitsraum zu schwer |
| Rolle `CURATOR` statt `ADMIN` | Kuratoren ordnen Inhalte; eine Vorgabe für jede Antwort jedes Mitglieds ist eine Leitungsentscheidung |
| Systemverwaltung darf die Anweisung jedes Space ändern | sie würde in fremde Räume und in persönliche Daueranweisungen hineinschreiben; Name und Beschreibung genügen ihr für die Pflege |
| Anweisung je Gespräch einfrieren | erzeugt Gespräche, in denen ein überholter Rahmen weiterwirkt, ohne dass die Person es sieht |
| Space-Block nach der Gesprächsnotiz | ließe die Hausregel einen Wunsch der fragenden Person überschreiben |
| Anweisung auch an die Teilfragen-Zerlegung | Stilvorgaben verfälschen Suchanfragen; als eigene Frage offen gelassen |
| Text im Protokoll mitschreiben | Freitext im Revisionsprotokoll; Name und Beschreibung werden dort ebenfalls nur benannt |
