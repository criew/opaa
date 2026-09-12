# ADR-0031: Gesprächsgedächtnis — wörtliches Fenster plus sichtbare Gesprächsnotiz

## Status

Akzeptiert (12.09.2026, nach der Nachmessung in Issue #1490)

## Kontext

Das Gesprächsgedächtnis eines Chats ist ein festes Fenster von 20 Nachrichten, das Suche
(Teilfragen-Zerlegung) und Antwort unverändert und gemeinsam bekommen — samt der Zitiermarken
früherer Antworten. Issue #1446 hat drei Fehlerbilder benannt, der Befund am Code vom 11.09.2026 hat
sie bestätigt:

- **Themen-Bleed.** Die Zerlegung sieht zehn Runden, ihr Sicherheitsgurt ankert auch gegen den
  Verlauf, und der Rückfall stellt die älteste Nutzerfrage im Fenster voran. Nach einem
  Themenwechsel legitimiert das alte Thema weiterhin Teilfragen.
- **Zitatwiederholung.** Antworttexte im Verlauf tragen formal gültige Marken für Dokumente, die
  in der aktuellen Runde nicht im Kontext sind; das Modell wiederholt sie, die Belegprüfung stuft sie
  zurück.
- **Harte Vergessensgrenze.** Eine Rahmenangabe aus Runde 1 („Ich arbeite in der Nebenstelle 3") ist
  in Runde 12 weg.

Der Folgefragen-Pfad ist außerdem ungemessen: Der Retrieval-Harness kennt nur Einzelfragen (#1288).

Zwei Rahmenbedingungen aus der Produktausrichtung ([ADR-0014](./0014-produktausrichtung-oeffentliche-verwaltung.md),
[security-and-compliance.md](../features/security-and-compliance.md#mitbestimmungsfähigkeit)) sind
für die Lösung bindend: Es gibt keinen personenbezogenen Auswertungspfad, und was eine Antwort
beeinflusst, muss für die Person nachvollziehbar sein.

## Entscheidung

Das Gedächtnis wird in zwei Bauteile getrennt (Spezifikation:
[conversation-memory.md](../features/conversation-memory.md)):

1. **Gesprächsfenster — kurz und wörtlich, unterschiedlich dosiert.** Die Zerlegung sieht nur ein
   **Suchfenster von zwei Runden**; die Antwort das ganze Fenster (20 Nachrichten, künftig eine
   Ebene-1-Property statt einer Konstante). Der Sicherheitsgurt der Zerlegung ankert gegen **genau
   den Kontext, der der Zerlegung gegeben wurde** — Frage, Suchfenster, gerenderte Notizpunkte —
   als Invariante, nicht als Aufzählung. Der Rückfall stellt die letzte statt der ersten
   Nutzerfrage voran. **Zitiermarken werden an beiden Eingängen ins Gedächtnis entfernt** (nach der
   Antwort und beim Nachladen aus der Datenbank), nie im persistierten Text.
2. **Gesprächsnotiz — klein, persistent, sichtbar, löschbar, pro Chat.** Nach jeder Antwort
   verdichtet das systemweit aktive Chat-Modell nebenläufig die **Nutzernachricht** der Runde zu
   null bis zwei Notizpunkten (ein Satz, ≤ 200 Zeichen), gedeckelt auf zehn je Chat (ältester
   fällt). Eine fehlgeschlagene Verdichtung wird **nicht nachgeholt** — die Runde steuert dann
   keine Punkte bei; Zähler und Logzeile bleiben. Jeder Punkt trägt intern eine Art: `RAHMEN`
   (Rolle, Zuständigkeit, Ort, Zeitraum, Fassung, Organisation, Festlegung) erreicht Zerlegung und
   Antwort, `ANTWORTFORM` (Darstellungswünsche) nur die Antwort. Die Notiz enthält keine
   Antwortinhalte, keine Themen der Fragen, keine Bewertungen. Sie ist im Chat sichtbar (Kopfzeile),
   punktweise löschbar, wird mit dem Chat gelöscht (und, sobald gebaut, exportiert und mit dem
   Konto gelöscht), nicht protokolliert und nicht aggregiert. **In den Ankerraum des
   Sicherheitsgurts gehen die Notiz*punkte* ein, nicht die Überschrift ihres Blocks** — die
   Begründung steht unten in den Konsequenzen.
3. **Sichtbarkeit ab drei abgeschlossenen Runden.** Die Schaltfläche erscheint, sobald die Notiz
   mindestens einen Punkt hat **und** drei Runden abgeschlossen sind; die Verdichtung läuft ab
   Runde 1. Die Abwägung dazu steht unten.
4. **Keine Themenwechsel-Erkennung.** Das kurze Suchfenster macht den Wechsel billig; das alte Thema
   ist nach zwei Runden von selbst aus der Suche verschwunden.
5. **Messung vor Änderung.** Der Pipeline-Messpfad bekommt Mehrrunden-Fälle (Metrik je Runde,
   Klassen `anaphora_resolution`, `topic_switch`, `constraint_carryover`, eigene Baseline mit dem
   Chat-Modell als Fixpunkt, manuell oder per Label). Der heutige Stand wird gemessen, bevor das
   Fenster verändert wird.

### Die Abwägung zur Anzeige-Untergrenze

Die Konstruktion der Notiz ruht auf einer Transparenzlinie: Was eine Antwort beeinflusst, kann die
Person sehen und entfernen. Eine Anzeige erst ab Runde 3 bei Verdichtung ab Runde 1 bedeutet, dass
ein Notizpunkt **bis zu zwei Runden lang wirkt, bevor er sichtbar ist**. Das ist eine bewusste
Abweichung von dieser Linie und wird hier nicht verschwiegen, sondern begründet.

**Für die Untergrenze** spricht die Oberflächenruhe: Die meisten Chats sind kurz. Eine Schaltfläche,
die nach der ersten Rückfrage in der Kopfzeile auftaucht, macht aus einer Zweiwort-Angabe ein
Oberflächenereignis und lenkt in genau den Chats ab, in denen die Notiz noch nichts zu leisten hat.

**Gegen die Untergrenze** spricht die Linie selbst: Zwei Runden lang beeinflusst etwas die Antwort,
das die Person nicht angezeigt bekommt.

**Warum die Abweichung vertretbar ist** — drei Argumente, die zusammen tragen und einzeln nicht
genügen würden:

1. Der Inhalt der Notiz stammt **ausschließlich aus Nachrichten, die die Person selbst in diesem
   Chat geschrieben hat**. Nichts darin ist ihr unbekannt; es gibt keinen fremden Text, der auf sie
   wirkt, sondern nur ihre eigenen Angaben, gekürzt.
2. Ab dem Erscheinen ist die Notiz **vollständig und rückwirkend einsehbar** — auch die Punkte aus
   den ersten beiden Runden — und jeder Punkt ist löschbar. Die Lücke ist eine Verzögerung der
   Anzeige, kein dauerhaft verborgener Einfluss.
3. Es entsteht **keine Aufbewahrung über den Chat hinaus**. Die Notiz ist Chatinhalt mit dessen
   Lebenszyklus; die zwei Runden erzeugen nichts, das ohne den Chat fortbesteht.

Die naheliegende Alternative, die die Lücke schließen würde — Verdichtung ebenfalls erst ab Runde 3 —
ist verworfen: Sie kostet die Angaben aus den ersten beiden Runden, und das sind die häufigsten
(„Ich arbeite in …", „Es geht um …"). Die andere Alternative, Anzeige ab dem ersten Punkt, steht
unten. Die Untergrenze ist ein fester Wert (Oberflächengröße), kein Parameter.

### Verworfene Alternativen

| Alternative | Warum verworfen | Wiederaufnahme, wenn … |
|---|---|---|
| Größeres Gesprächsfenster | verstärkt Bleed und Zitatwiederholung, kostet Tokens je Runde, verschiebt das Vergessen nur nach hinten | nie in dieser Form; die Fensterbreite ist Ebene-1-Property und wird gemessen |
| Rolling Summary (unsichtbare, vom System geschriebene Zusammenfassung) | beeinflusst Antworten mit Text, den niemand sieht oder korrigieren kann; in der Verwaltung ein Dienstvereinbarungsthema; eine Verdichtung kann Fehler festschreiben | nie unsichtbar; eine sichtbare Zusammenfassung wäre nur eine längere Notiz |
| Themenwechsel-Erkennung (Ähnlichkeit, Marker) mit Vorschlag „Neuen Chat beginnen?" | Fehlalarme genau bei Rückfragen mit neuem Vokabular; unterbricht den Fluss; das kurze Suchfenster macht den Wechsel ohne Erkennung billig | `topic_switch` zeigt nach dem Fenster-Umbau in mehr als einem Viertel der Fälle weiterhin Bleed |
| Chatübergreifendes Nutzergedächtnis | **Produkthaltung, kein Aufwandsvorbehalt:** ein vom System gepflegtes Profil einer beschäftigten Person ist eine personenbezogene Auswertung mit eigenem Mitbestimmungstatbestand | nicht vorgesehen |
| Vektorindex über Gesprächsinhalte | **Produkthaltung:** ein durchsuchbarer Speicher dessen, was Personen geschrieben haben, ist ein Speicher von Verhalten | nicht vorgesehen |
| Notiz aus Antworten verdichten | unbelegte Aussagen im Systemprompt, außerhalb der Belegprüfung | nicht vorgesehen |
| Notiz ungefiltert in den Zerlegungs-Prompt | Darstellungswünsche sind für die Suche Rauschen und verfälschen Teilfragen | nicht vorgesehen; die Art kostet ein Feld |
| Zerlegung ohne Notiz (nur Suchfenster) | eine Rahmenangabe aus Runde 1 erreicht die Suche in Runde 4 nicht — der Kernfall von `constraint_carryover` | nicht vorgesehen |
| Verdichtung erst beim Überlauf des Fensters (LlamaIndex-Muster) | ließe eine Lücke zwischen Suchfenster (2 Runden) und Fensterende (10 Runden), in der eine Angabe weder wörtlich noch verdichtet bei der Suche ankommt | nicht vorgesehen |
| Anzeige ab dem ersten Punkt (ohne Rundenuntergrenze) | hält die Transparenzlinie lückenlos, macht aber in kurzen Chats aus einer Zweiwort-Angabe ein Oberflächenereignis; Abwägung oben | die drei tragenden Argumente gelten nicht mehr — etwa wenn die Notiz je etwas enthält, das nicht aus Nachrichten der Person stammt |
| Verdichtung erst ab Runde 3 | schlösse die Sichtbarkeitslücke, kostet aber die Angaben der ersten beiden Runden | nicht vorgesehen |
| Notizpunkte bearbeiten oder manuell anlegen; „alle entfernen" | Oberfläche und API für einen Weg, den der Chat selbst bietet (falschen Punkt entfernen, richtige Angabe schreiben); bei zehn Punkten ist Einzelentfernen zumutbar | Personen wiederholen nachweislich Angaben, weil die Verdichtung sie verfehlt |
| Ein-/Ausschalter je Installation oder Chat | kein Adressat: die Notiz enthält nur, was die Person selbst geschrieben hat, sichtbar und löschbar; die Oberfläche müsste das Fehlen erklären | eine konkrete Dienstvereinbarungsklausel verlangt es |
| Fallklasse `long_horizon` im Retrieval-Harness | misst nichts, was `constraint_carryover` nicht misst — der Mechanismus ist derselbe; die Antwortkontinuität über die Fensterbreite hinaus ist mit Ranking-Metriken nicht messbar; ~100 Skriptrunden Kuratierung | ein Generationsharness (Relevanz, Faktentreue) existiert |

## Gemessene Wirkung (Issue #1490, 12.09.2026)

Diese Entscheidung ist vollständig umgesetzt (#1486, #1487, #1488, #1489) und nachgemessen. Sie wird
**mit** diesem Ergebnis akzeptiert, nicht trotz ihm: Zwei ihrer drei Erwartungen bestätigt die
Messung, die dritte nicht. Wer dieses Dokument in einem Jahr liest, soll das sofort sehen.

Median aus drei Läufen, CPU-Testcontainer, auf CI-Hardware mit Delta ±0,000 gegengeprüft; **vorher**
ist der Referenzlauf vom 11.09.2026 ohne Suchfenster und ohne Notiz (Issue #1485).

| Klasse | nDCG@8 über ihre Runden | gelöste Fälle |
|---|---|---|
| `anaphora_resolution` | 0,722 → 0,763 | 4 → 5 von 9 |
| `topic_switch` | 0,713 → 0,776 | 2 → 2 von 9 |
| `constraint_carryover` | 0,857 → 0,835 | 4 → 1 von 9 |
| gesamt (83 Runden) | 0,771 → 0,796 | 10 → 8 von 27 |

**Bauteil 1 (Gesprächsfenster) trägt.** Das Suchfenster von zwei Runden hebt beide Klassen, für die
es gebaut wurde, in allen vier Rundenmetriken und den Gesamtwert über alle 83 Runden. Die
Wiederaufnahmebedingung der verworfenen **Themenwechsel-Erkennung** ist damit nicht eingetreten:
Sie lautete „`topic_switch` zeigt nach dem Fenster-Umbau in mehr als einem Viertel der Fälle
weiterhin Bleed", gemessen sind 2 von 9 Wechselrunden — und in beiden bleibt das Ziel auf Rang 1.
Die Kennzahl selbst ist als Kriterium zurückgezogen (sie hat auf dem Datensatz kaum
Dynamikbereich); beurteilt wird über den Anteil gelöster Fälle.

**Bauteil 2 (Gesprächsnotiz) erfüllt seinen Zweck heute nicht.** Die Klasse, für die es gebaut
wurde, erreicht ihren Ausgangswert nicht wieder. Die dafür festgelegte, empfindlichere
Vergleichsgröße — Zielrunden, deren Teilfrage die Rahmenangabe trägt — fällt von 4 von 9 auf 1 von
9. Der Mechanismus existiert und ist verdrahtet: Die Verdichtung läuft in allen 83 Runden ohne
Fehlschlag, die `RAHMEN`-Punkte erreichen die Zerlegung. Aber in 8 der 27 Gespräche entsteht
überhaupt kein `RAHMEN`-Punkt, und wo einer entsteht, trägt er die Fassungsangabe in nur drei von
neun Fällen bis zur Zielrunde. Ursache sind zwei Regeln der Auswertung im Zusammenspiel mit dem
Antwortformat des Modells: Es werden die **ersten zwei** Zeilen übernommen, und eine Zeile mit
unbekanntem Artpräfix wird `ANTWORTFORM` und erreicht die Suche nie. Beides ist OPAAs Code, nicht
Modellschwäche — **#1586**.

**Die verworfene Alternative „Zerlegung ohne Notiz (nur Suchfenster)" bleibt verworfen.** Die
Messung widerlegt nicht ihre Begründung, sondern zeigt, dass der gewählte Weg heute schlecht
ausgeführt ist: Wo die Angabe die Teilfrage erreicht (`verw-conv-cc-002`), löst sie den Fall.
Gegenevidenz gibt es ebenfalls und sie steht hier, nicht nur im Befund: In `verw-conv-ts-002#3`
zieht ein Notizpunkt aus dem **alten** Thema („SOZ-08") das Altthemen-Dokument auf Rang 1 und kostet
den Fall — die Notiz kann also auch schaden. Ob die Filterung auf `RAHMEN` dafür eng genug ist, ist
mit einem Fall nicht entschieden.

**Ein Vorbehalt zum Vergleich.** Die Umsetzung von Bauteil 2 hat der festen Instruktion der
Teilfragen-Zerlegung eine Zeile über die Gesprächsnotiz hinzugefügt, die bei jedem Aufruf mitgeht.
18 der 27 ersten Runden — ohne Fenster und ohne Notiz — liefern seither andere Teilfragen. Die
Zahlen oben sind die Summe aus drei Änderungen, nicht aus zweien; die Trennung ist **#1587**.

Vollständige Herleitung, Einzelfälle und Symptomtabellen:
[`eval/corpus/verwaltung/MAINTENANCE.md`](../../eval/corpus/verwaltung/MAINTENANCE.md), Abschnitt
„Befund der Nachmessung"; Abschlussbefund in Issue #1446.

## Konsequenzen

**Einfacher wird:**

- Themenwechsel brauchen keine Erkennung und keine Nutzerinteraktion; das Suchfenster vergisst von
  selbst.
- Rahmenangaben und Darstellungswünsche gelten über die Fensterbreite hinaus, ohne dass das Fenster
  wächst — und die Person sieht, was gilt, und kann es entfernen.
- Zitatwiederholung entfällt strukturell; die Belegprüfung sieht weniger „nicht bestätigte" Marken.
  Gemessen wird das nicht — es gibt keinen Zähler dafür, und der Benchmark endet vor der Antwort;
  abgesichert wird es durch einen Test auf den markenfreien Prompt.
- Die Fensterbreite ist benchmarkbar.
- Die Regel für ein späteres Teilen von Chats ist bereits festgelegt: Die Notiz wird mit dem Chat
  geteilt, Mitlesende sehen sie, nur der Autor ändert sie.

**Schwieriger oder teurer wird:**

- Ein zusätzlicher Chat-Modell-Aufruf je Runde eines gespeicherten Chats, nebenläufig — die Latenz
  der Antwort bleibt unberührt, die Modellkosten steigen.
- Eine weitere Tabelle im Chat-Datenmodell, ein Changeset, drei Ebene-1-Properties, drei Klassen
  im Golden Dataset mit eigener Baseline und Messvertrag-Nachtrag zu ADR-0012.
- Die Mehrrunden-Messung ist nur zerlegend sinnvoll und läuft deshalb nicht nächtlich; ihr Ergebnis
  ist ein manuell ausgelöster Befund, kein automatischer Regressionsschutz.
  > **Überholt durch ADR-0012, Entscheidung 49 (Issue #1553):** Sie läuft nächtlich, per
  > `workflow_dispatch` und beim Label `evaluation` — in einem eigenen CI-Job mit eigenem
  > Zeitbudget, nicht im Job der Einzelfragen-Domänen. Sie ist damit automatischer
  > Regressionsschutz. Unverändert ist nur, dass sie ausschließlich zerlegend misst.
- In den zwei Runden nach einem Themenwechsel kann eine mehrdeutige Rückfrage falsch aufgelöst
  werden; in den zwei Runden vor der Anzeige wirkt die Notiz unsichtbar. Beides ist benannt,
  begrenzt und heilt sich selbst.
- Die Art eines Notizpunkts wird vom Modell vergeben und ist damit fehlbar; eine falsch als
  `RAHMEN` eingestufte Darstellungsangabe erreicht die Suche. **Dagegen gibt es keine maschinelle
  Sicherung.** Der Sicherheitsgurt kann sie nicht leisten — gerenderte Notizpunkte gehören nach der
  Ankerraum-Invariante zum Ankerraum, und ein zusätzlicher Baustein dort macht die Prüfung nur
  lockerer, nie strenger; `topic_switch` trägt es auch nicht, weil Themen nie in die Notiz kommen.
  Was bleibt, sind die Prompt-Regel der Zerlegung (Notiz nur zur Auflösung rückverweisender Wörter)
  und die Löschbarkeit durch die Person.
- **Der Ankerraum wächst um die Notizpunkte, nicht um die Überschrift ihres Blocks** (#1487). Die
  Lockerung, die der vorige Punkt in Kauf nimmt, gilt für Material der Person: Steht „Bezugsjahr
  2024" im Ankerraum, kann eine damit angereicherte Teilfrage nicht mehr als unverwandt gelten —
  genau der Zweck. Für die Überschrift des Blocks („Gesprächsnotiz — Angaben der fragenden Person
  aus diesem Gespräch (kein Beleg, keine Quelle)") gilt sie **nicht**. Der Gurt vergleicht
  Teilwörter ab vier Zeichen; stünde die Überschrift im Ankerraum, ankerte „Person" jede Teilfrage
  mit „Personalausweis", „Beleg" jede mit „Belegschaft". Eine Teilfrage, die das Modell an die
  Stelle der Frage gesetzt hat, gälte dann als verwandt, sobald die Notiz überhaupt einen Punkt hat
  — der Gurt fiele aus, und zwar nur in Chats mit Notiz. Die Umsetzung reicht Modelltext und
  Ankertext deshalb aus **einem** Rendervorgang getrennt weiter (`ConversationNoteBlock`): Ein
  Baustein erreicht Modell und Ankerraum weiterhin in einem Schritt, aber der Ankerraum ist eine
  Teilmenge des Modelltexts — geprüft, nicht bloß zugesagt, denn ein Ankertext außerhalb des
  Modelltexts wäre wieder die separat gepflegte Aufzählung, diesmal in der lockernden Richtung.
  **Kein Anspruch auf strenge Monotonie:** Weniger Ankertoken machen den Gurt der Sache nach
  strenger, aber nicht ausnahmslos — die Prüfung bricht unterhalb von zwei Ankertoken ganz ab
  (`countUnrelated`), sodass eine sehr kleine Ankermenge sie theoretisch auch überspringen ließe.
  Mit einem Notizpunkt ist diese Schwelle praktisch nicht erreichbar, und das Weglassen der
  Kopfzeile nimmt nie das letzte Ankertoken weg. Die Aussage lautet also „in der ungefährlichen
  Richtung", nicht „monoton"; daraus ist keine Garantie ableitbar. **Wiederaufnahme, wenn** ein
  künftiger Kontextbaustein keine eigene Rahmung hat; dann fallen beide Hälften ohnehin zusammen.
- Eine fehlgeschlagene Verdichtung kostet die Angaben dieser einen Runde; die Person wiederholt sie
  bei Bedarf. Das ist der Preis dafür, keinen Nachholmechanismus mit eigenem Zustand zu bauen.
