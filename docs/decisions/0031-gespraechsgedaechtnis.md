# ADR-0031: Gesprächsgedächtnis — wörtliches Fenster plus sichtbare Gesprächsnotiz

## Status

Vorgeschlagen

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
   Konto gelöscht), nicht protokolliert und nicht aggregiert.
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
- Eine fehlgeschlagene Verdichtung kostet die Angaben dieser einen Runde; die Person wiederholt sie
  bei Bedarf. Das ist der Preis dafür, keinen Nachholmechanismus mit eigenem Zustand zu bauen.
