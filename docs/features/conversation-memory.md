# Gesprächsgedächtnis: Gesprächsfenster und Gesprächsnotiz

> **Status: Entschieden (Maintainer, 11.09.2026), Umsetzung in Epic #1482.** Architekturentscheidung
> in [ADR-0031](../decisions/0031-gespraechsgedaechtnis.md). Das Handbuch beschreibt den Ist-Stand und
> wird mit den Umsetzungs-Issues nachgezogen.

## Motivation

Ein Chat in OPAA ist kein Einzelabruf, sondern eine Unterhaltung: Rückfragen („Und bei
Bedürftigkeit?"), Rahmenangaben („Es geht um die Nebenstelle 3", „Stand 2024") und Wünsche zur
Antwortform („bitte knapp") sollen über Runden hinweg gelten, ohne dass die Person sie wiederholt.
Gleichzeitig wechseln Menschen in einem Chat das Thema, und dann soll das alte Thema die Suche nicht
mehr färben.

Heute bekommen Suche und Antwort denselben Verlauf: die letzten 20 Nachrichten, wörtlich, samt der
Zitiermarken früherer Antworten. Das erzeugt drei messbare Fehlerbilder (Issue #1446, Befund am
Code vom 11.09.2026):

1. **Themen-Bleed.** Die Teilfragen-Zerlegung sieht zehn Runden Verlauf und soll „kein neues Thema
   einführen"; ihr Sicherheitsgurt ankert Teilfragen auch gegen den Verlauf, und der Rückfall stellt
   die *älteste* Nutzerfrage im Fenster voran. Bei einem Themenwechsel ist all das die falsche Runde.
2. **Zitatwiederholung.** Antworttexte im Verlauf tragen formal gültige Zitiermarken für Dokumente,
   die in dieser Runde nicht im Kontext sind. Das Modell wiederholt sie; die Belegprüfung stuft sie
   als „nicht bestätigt" zurück.
3. **Harte Vergessensgrenze.** Was älter als zehn Runden ist, verschwindet vollständig — auch eine
   Rahmenangabe aus Runde 1, die für Runde 12 noch gilt.

Die naheliegenden Auswege — ein größeres Fenster oder eine laufende, vom System geschriebene
Zusammenfassung — verwerfen wir: Das größere Fenster verstärkt Bleed und Kosten; die unsichtbare
Zusammenfassung beeinflusst Antworten mit einem Text, den niemand sieht, und ist in der Verwaltung ein
Dienstvereinbarungsthema (ADR-0031, „Verworfene Alternativen").

---

## Überblick

1. **Zwei Bauteile, klar getrennt.** Das **Gesprächsfenster** ist der wörtliche Kurzzeitverlauf; die
   **Gesprächsnotiz** ist eine kleine, persistente, sichtbare Liste von Angaben der Person je Chat.
2. **Suche und Antwort werden unterschiedlich dosiert.** Die Suche (Teilfragen-Zerlegung) sieht nur
   die letzten zwei Runden wörtlich plus den Rahmen-Teil der Notiz; die Antwort sieht die letzten
   zehn Runden plus die ganze Notiz. Die Suche braucht Bezüge, die Antwort Kontinuität.
3. **Zitiermarken gehören nicht ins Gedächtnis.** Auf dem Weg ins Gesprächsfenster werden sie aus
   Antworttexten entfernt — an beiden Eingängen (nach der Antwort, beim Nachladen aus der
   Datenbank), nie im persistierten Text: der persistierte Text ist die Wahrheit für Fußnoten,
   Anker und Belegfenster.
4. **Die Notiz enthält nur, was die Person gesagt hat.** Sie wird aus Nutzernachrichten verdichtet,
   nie aus Antworten; sie enthält Angaben, keine Bewertungen; jeder Punkt trägt intern eine Art
   (`RAHMEN` oder `ANTWORTFORM`), und nur `RAHMEN`-Punkte erreichen die Suche. Sie ist im Chat
   sichtbar (ab der dritten abgeschlossenen Runde), punktweise löschbar, wird mit dem Chat gelöscht,
   und sie ist **pro Chat** — nie pro Person.
5. **Kein Themenwechsel-Detektor.** Themenwechsel werden nicht erkannt, sondern billig gemacht: Ein
   kurzes Suchfenster vergisst das alte Thema nach zwei Runden von selbst.
6. **Gemessen wird das Retrieval, bevor gebaut wird.** Der Retrieval-Harness bekommt Mehrrunden-Fälle
   mit Metrik je Runde; der heutige Stand wird gemessen, bevor das Fenster geändert wird. Gemessen
   werden damit Bleed, Bezugsauflösung und Rahmenübernahme (Fehlerbilder 1 und 3); die
   Zitatwiederholung (Fehlerbild 2) wird nicht eigenständig gemessen — siehe Erfolgs-Metriken.

---

## Begriffe

| Begriff | Bedeutung |
|---|---|
| **Runde** | eine Nutzerfrage und die zugehörige Antwort |
| **Gesprächsfenster** | die letzten *n* Nachrichten eines Chats, wörtlich, wie sie das Modell sieht; Zitiermarken entfernt |
| **Suchfenster** | der jüngste Teil des Gesprächsfensters, den die Teilfragen-Zerlegung sieht (Runden, nicht Nachrichten) |
| **Gesprächsnotiz** | gedeckelte Liste kurzer Angaben der Person, persistiert je Chat, in Zerlegung und Antwort gerendert |
| **Notizpunkt** | ein Eintrag der Gesprächsnotiz: ein Satz, höchstens 200 Zeichen, mit einer Art |
| **Verdichtung** | der nebenläufige Modellaufruf, der aus einer Nutzernachricht null bis wenige Notizpunkte gewinnt |

---

## Bauteil 1: Das Gesprächsfenster

### Was sich ändert

| Stelle | Heute | Neu |
|---|---|---|
| Fensterbreite | Konstante 20 Nachrichten | Property, Default 20 Nachrichten (10 Runden) |
| Verlauf der Zerlegung | das ganze Fenster | die letzten **2 Runden** des Fensters (Suchfenster) plus `RAHMEN`-Punkte der Notiz |
| Verlauf der Antwort | das ganze Fenster | das ganze Fenster plus alle Punkte der Notiz |
| Sicherheitsgurt der Zerlegung | ankert gegen Frage **und** ganzes Fenster | ankert gegen **genau den Kontext, den die Zerlegung bekommen hat** — Frage, Suchfenster und (ab Bauteil 2) die gerenderten Notizpunkte; eine Invariante, keine Aufzählung |
| Rückfall ohne Zerlegung | älteste Nutzerfrage im Fenster vorangestellt | **letzte** Nutzerfrage (Vorrunde) vorangestellt; ohne Vorrunde die Frage allein |
| Zitiermarken im Fenster | werden mitgeschleppt | an beiden Eingängen entfernt |
| Nachladen bei Cache-Miss | ganze Historie laden, auf 20 kappen | nur die letzten *n* Nachrichten laden, identisch normalisiert |

**Warum der Sicherheitsgurt nicht nur gegen die Frage ankert:** „Wie lange dauert das?" wird zu
„Bearbeitungsdauer für den Anwohnerparkausweis" — die Teilfrage teilt mit der Frage kein Ankerwort
(„dauert" ist kein Teilwort von „Bearbeitungsdauer", der Gurt ist bewusst kein Stemmer), sie teilt
es mit der Vorrunde. Nur gegen die Frage geankert fiele eine korrekt in eine Nominalphrase
aufgelöste Rückfrage in den Rückfall. Ein Thema, das älter als zwei Runden ist, kann eine Teilfrage
dagegen nicht mehr legitimieren.

**Invariante des Ankerraums:** Der Sicherheitsgurt ankert gegen **genau das, was der Zerlegung als
Kontext gegeben wurde** — nicht mehr, nicht weniger. Heute sind das Frage und Suchfenster; mit der
Gesprächsnotiz kommen die in den Zerlegungs-Prompt gerenderten Notizpunkte hinzu. Fehlte die Notiz
im Ankerraum, würde eine korrekt angereicherte Teilfrage (Notizpunkt „Bezugsjahr 2024", Teilfrage
„Anwohnerparkausweis Gebühren 2024") als unverwandt gewertet, und weil der Gurt alles-oder-nichts
ist, kippte der ganze Lauf in den Rückfall. Die Umsetzung bildet Ankerraum und Modellkontext deshalb
aus demselben Objekt; ein künftiger Kontextbaustein erweitert den Ankerraum automatisch.

**Warum Marken entfernt und nicht ersetzt werden:** Eine Kurzform wie „(Quelle: satzung.md)" im
Verlauf wird vom Modell als Zitierformat nachgeahmt; die Belegprüfung fände dann keine Marke und die
Antwort gälte als unbelegt. Ohne Marke kann das Modell nichts nachahmen; welches Dokument die frühere
Antwort trug, holt die Suche der aktuellen Runde ohnehin neu.

### Verhalten bei Themenwechsel

Es gibt keine Erkennung. Nach einem Themenwechsel enthält das Suchfenster noch zwei Runden des alten
Themas; der Zerlegungs-Prompt sagt weiterhin „kein neues Thema einführen", und die Frage selbst ist
eigenständig — die Zerlegung gibt sie wortgleich zurück (heutiges Verhalten für eigenständige
Fragen). Ab der übernächsten Runde ist das alte Thema aus dem Suchfenster verschwunden. Die Antwort
sieht es noch zehn Runden, was für Kontinuität erwünscht ist („wie vorhin beim Parkausweis").

Was das kostet: In den zwei Runden nach dem Wechsel kann eine Rückfrage, die sich mehrdeutig auf
beides beziehen könnte, falsch aufgelöst werden. Das Fehlerbild ist auf zwei Runden begrenzt, heilt
sich selbst und wird mit der Klasse `topic_switch` gemessen.

---

## Bauteil 2: Die Gesprächsnotiz

### Was hineinkommt — und was nicht

Die Notiz sammelt **Angaben der Person**, die über die aktuelle Frage hinaus gelten. Jeder Punkt
trägt eine von zwei **Arten**, die die Verdichtung vergibt:

| Art | Inhalt | Beispiel eines Notizpunkts | Erreicht |
|---|---|---|---|
| `RAHMEN` | Rolle, Zuständigkeit, Ort, Zeitraum, Fassung, Organisation, Festlegung im Gespräch | „Arbeitet im Bürgerbüro Nebenstelle 3"; „Bezugsjahr 2024"; „Es geht um einen Landkreis, keine kreisfreie Stadt"; „Es wurde Variante B gewählt" | Zerlegung **und** Antwort |
| `ANTWORTFORM` | Wünsche zur Darstellung | „Möchte knappe Antworten"; „Antworten sollen Paragrafen nennen" | nur Antwort |

Die Art ist eine **interne Unterscheidung**: Sie steuert, welcher Punkt in welchen Prompt gerendert
wird, und wird in der Oberfläche nicht angezeigt — eine Taxonomie für die Person wäre Aufwand ohne
Adressaten. Der Grund für die Trennung: „Möchte knappe Antworten" ist für die Suche Rauschen und kann
Teilfragen verfälschen; „Bezugsjahr 2024" ist für die Suche der Unterschied zwischen zwei
Fassungen. Ein Punkt, dessen Art die Verdichtung nicht erkennbar vergibt, gilt als `ANTWORTFORM` —
er wirkt dann nur auf die Antwort, wo ein überflüssiger Punkt am wenigsten schadet.

Geprüft und verworfen: die Zerlegung bekommt die Notiz gar nicht. Dann käme „Arbeitet in der
Nebenstelle 3" aus Runde 1 in Runde 4 nicht mehr bei der Suche an — genau der Fall, für den die
Klasse `constraint_carryover` steht. Die Art kostet ein Feld und löst beides.

Ausdrücklich **nicht** in die Notiz:

- **Inhalte aus Antworten.** Eine Antwort ist belegpflichtig; ein Notizpunkt aus einer Antwort wäre
  eine unbelegte Aussage im Systemprompt, die keine Belegprüfung mehr erreicht. Die Notiz wird nur
  aus Nutzernachrichten verdichtet.
- **Die Themen der Fragen** („fragt nach Anwohnerparkausweis"). Themen sind das, was bei einem
  Wechsel bleeden soll — genau das, was das kurze Suchfenster vermeidet. Bezüge auf ein Thema von
  vor mehr als zwei Runden benennen Menschen ohnehin neu.
- **Bewertungen oder Ableitungen über die Person** („wirkt unsicher", „fragt häufig nach …"). Der
  Verdichtungs-Prompt verbietet sie; die Sichtbarkeit und Löschbarkeit sind die zweite Sicherung.

### Lebenszyklus

```
Frage r ──► Suche (Suchfenster + RAHMEN-Punkte) ──► Antwort (Fenster + alle Punkte) ──► persistieren
                                                                                          │
                                                                    nebenläufig, nach der Antwort:
                                                                    Verdichtung der Nutzernachricht r
                                                                    ──► 0..2 neue Notizpunkte anhängen
```

- **Wann:** nach jeder Antwort, nebenläufig, für die Nutzernachricht der gerade beendeten Runde
  (Blaupause: Chat-Titel-Erzeugung — außerhalb des Anfrage-Threads, defensiv geparst, Fehler nie
  sichtbar). Die Notiz deckt damit jede Runde ab außer der laufenden und einer, deren Verdichtung
  fehlgeschlagen ist; die Denkzeit der Person
  verbirgt die Latenz. Trifft die nächste Frage ein, bevor die Verdichtung fertig ist, läuft sie mit
  dem Stand davor — harmlos, weil die Runde noch im Gesprächsfenster steht.
- **Womit:** das systemweit aktive Chat-Modell. Keine neue Modellrolle.
- **Merkmale eines Notizpunkts:** ein Satz, dritte Person, höchstens 200 Zeichen (längere werden
  mit „…" gekürzt), Deutsch, eine Art (`RAHMEN`/`ANTWORTFORM`, vom Modell je Zeile vorangestellt,
  defensiv geparst).
- **Höchstens zwei Punkte je Runde.** Liefert das Modell mehr, werden nur die ersten zwei
  übernommen. Sonst verdrängt eine gesprächige Nachricht über den Deckel die Rahmenangabe aus
  Runde 1 — genau der Fall, für den `constraint_carryover` steht.
- **Deckel:** höchstens 10 Punkte je Chat. Beim Anhängen über den Deckel hinaus fällt der älteste
  Punkt weg — ohne Modellaufruf, der die bestehende Liste umschreibt. Was die Person gesehen hat,
  bleibt so, bis es herausfällt oder sie es löscht.
- **Doppelte:** ein wortgleicher Punkt (ohne Berücksichtigung von Groß-/Kleinschreibung und
  Satzzeichen) wird nicht erneut angehängt. Geprüft wird gegen die aktuelle Liste, nicht gegen
  entfernte Punkte (siehe Löschsemantik in der Oberfläche).
- **Fehlschlag der Verdichtung** (kein Modell, Zeitüberschreitung, unparsebare Ausgabe): Notiz
  unverändert, Warnung im Log ohne Inhalt, Zähler `opaa.chat.note.extraction` mit Grund — und
  **kein Nachholen**: Diese Runde steuert keine Notizpunkte bei, fertig. Das kostet im schlechtesten
  Fall eine Rahmenangabe, die die Person bei Bedarf wiederholt, und spart einen Mechanismus mit
  eigenem Zustand, der einen Neustart überleben und für die letzte Runde eines Chats einen „nächsten
  Lauf" erfinden müsste. Die Blaupause (Chat-Titel) kennt ebenfalls keinen zweiten Versuch. Ein
  dauerhaft ausgefallenes Modell trifft die Antwort ohnehin früher.
- **Archivierung im Schreibfenster:** Wird der Space zwischen Antwort und Verdichtung archiviert,
  wird das Verdichtungsergebnis verworfen — ein archivierter Space nimmt keine Änderung an einem
  Chat an, und der Wächter dafür existiert bereits beim Schreiben einer Runde.
- **Eine Antwort weiß, welche Notiz sie hatte:** Die Antwort auf eine Frage liefert den Notizstand
  mit, der in diese Antwort eingeflossen ist. Das ist der Stand, den die Oberfläche zeigt.

### Wie die Notiz ins Modell kommt

Ein Block im Systemprompt, gleich aufgebaut, unterschiedlich gefüllt:

```
Gesprächsnotiz — Angaben der fragenden Person aus diesem Gespräch (kein Beleg, keine Quelle):
- Arbeitet im Bürgerbüro Nebenstelle 3
- Bezugsjahr 2024
- Möchte knappe Antworten            ← nur im Antwort-Block
```

Der **Zerlegungs-Block** enthält nur `RAHMEN`-Punkte, der **Antwort-Block** alle. Die Zerlegung
erhält zusätzlich die Regel, die Notiz nur zur Auflösung rückverweisender oder unterbestimmter
Wörter zu verwenden; eine eigenständige Frage bleibt unverändert. Ohne passende Punkte entfällt der
jeweilige Block ganz. Die gerenderten Punkte gehören zum Ankerraum des Sicherheitsgurts (Invariante
in Bauteil 1). Der Systemprompt bleibt wie bisher nicht konfigurierbar.

**Bei abgeschalteter Zerlegung** (`query-decomposition-enabled=false`) erreicht die Notiz die Suche
gar nicht: Der Rückfall baut die Suchanfrage ohne Modell und ohne Notiz. Das ist akzeptiert —
abgeschaltete Zerlegung ist eine Benchmark-Konfiguration, keine Auslieferungseinstellung; die Notiz
wirkt dann nur auf die Antwort.

**Was gegen eine falsch eingestufte Angabe sichert — und was nicht:** Die Art vergibt das Modell,
und es kann irren; eine Darstellungsangabe, die als `RAHMEN` eingestuft wird, erreicht die Suche.
Dagegen gibt es **keine maschinelle Sicherung**. Der Sicherheitsgurt kann sie nicht leisten: Nach
der Ankerraum-Invariante gehören gerenderte Notizpunkte zum Ankerraum, und ein zusätzlicher
Baustein im Ankerraum macht die Prüfung nur lockerer, nie strenger. Was bleibt, sind die
Prompt-Regel der Zerlegung (Notiz nur zur Auflösung rückverweisender Wörter) und die Löschbarkeit
durch die Person.

### Rechte, Sichtbarkeit, Nachweis — die Produkthaltung

- **Pro Chat, nie pro Person.** Ein Gedächtnis, das Angaben über Chats hinweg sammelt, ist ein vom
  System gepflegtes Profil einer beschäftigten Person. Das ist eine personenbezogene Auswertung mit
  eigenem Mitbestimmungstatbestand, unabhängig davon, wie nützlich es wäre. OPAA baut es nicht; die
  Grenze ist eine Produkthaltung, kein Aufwandsvorbehalt (siehe
  [Mitbestimmungsfähigkeit](./security-and-compliance.md#mitbestimmungsfähigkeit), Grundsatz 2).
- **Kein Index über Gesprächsinhalte.** Ein Vektorindex über das, was Personen in Chats geschrieben
  haben, wäre ein durchsuchbarer Speicher von Verhalten. Auch das ist eine Haltung.
- **Sichtbar für den, der den Chat sieht.** Heute ist das ausschließlich der Autor. Wird das Teilen
  von Chats gebaut, wird die Notiz **mit dem Chat geteilt** und ist für Mitlesende sichtbar, aber nur
  vom Autor änderbar — wer eine Antwort liest, soll sehen können, was sie beeinflusst hat.
- **Keine Verwaltungsansicht, keine Aggregation, kein Auditereignis.** Die Notiz ist Chatinhalt und
  fällt unter dieselben Regeln wie Frage und Antwort: nicht protokolliert, nicht auswertbar, kein
  Administrator-Durchgriff ([Was ausdrücklich nicht protokolliert
  wird](./security-and-compliance.md#was-ausdrücklich-nicht-protokolliert-wird)).
- **Derselbe Lebenszyklus wie der Chat.** Gelöscht mit dem Chat; sobald Chat-Export und
  Kontolöschung gebaut sind (Zielbild, #391/#395), schließen sie die Notiz ein. Sie erzeugt keine
  neue Datenkategorie: Alles darin stand bereits in einer Nachricht desselben Chats.
- **Kein Ein-/Ausschalter**, weder je Installation noch je Chat. Die Notiz enthält nur, was die
  Person selbst geschrieben hat, sichtbar und löschbar; ein Schalter hätte keinen Adressaten und
  müsste in der Oberfläche erklären, warum die Notiz in dieser Installation fehlt. Wiederaufnahme
  nur mit einer konkreten Klausel einer Dienstvereinbarung, die sie verlangt.

---

## Oberfläche

### Wo und wann

Die Notiz sitzt in der **Kopfzeile des Chats** (rechts neben dem Titel) als Schaltfläche
„Gesprächsnotiz · 3" mit der Anzahl der Punkte. Sie erscheint, sobald **beides** gilt: die Notiz hat
mindestens einen Punkt **und** der Chat hat mindestens **drei abgeschlossene Runden**. Wird die Notiz
später leer, verschwindet die Schaltfläche; eine leere Notiz hat keine Oberfläche. Die Untergrenze
von drei Runden ist ein fester Wert (Oberflächengröße, kein Parameter).

**Was diese Untergrenze kostet, ausdrücklich:** Die Verdichtung läuft ab Runde 1, die Anzeige ab
Runde 3. Eine Notiz kann also bis zu zwei Runden lang in Antworten wirken, bevor die Person sie
sieht. Das ist eine bewusste Abweichung von der Linie „nichts wirkt, was nicht sichtbar ist", und
sie ist vertretbar, weil drei Dinge zusammenkommen: Der Inhalt stammt **ausschließlich aus
Nachrichten, die die Person selbst in diesem Chat geschrieben hat** — nichts darin ist ihr
unbekannt; er ist **ab dem Erscheinen vollständig und rückwirkend einsehbar**, auch die Punkte aus
den ersten Runden, und jeder davon löschbar; und es entsteht **keine Aufbewahrung über den Chat
hinaus**. Auf der anderen Seite steht die Oberflächenruhe: Die meisten Chats sind kurz, und eine
Schaltfläche, die nach der ersten Rückfrage auftaucht, macht aus einer Zweiwort-Angabe ein Ereignis.
Die Abwägung selbst steht in ADR-0031; eine Verdichtung erst ab Runde 3 (die die Lücke schlösse) ist
verworfen, weil sie `constraint_carryover` die frühen Angaben kostet.

### Zustand und Bedienung

```
┌──────────────────────────────────────────────────────────────────────┐
│ Anwohnerparkausweis Nebenstelle 3                 [ Gesprächsnotiz · 2 ] │
├──────────────────────────────────────────────────────────────────────┤
│ Gesprächsnotiz                                                        │
│ Diese Angaben hat OPAA aus Ihren Nachrichten in diesem Chat           │
│ festgehalten. Sie fließen in die nächsten Antworten ein.              │
│                                                                       │
│  • Arbeitet im Bürgerbüro Nebenstelle 3                          [×]  │
│  • Bezugsjahr 2024                                               [×]  │
└──────────────────────────────────────────────────────────────────────┘
```

- **Zugeklappt** als Standard; die Zahl ist das Signal, dass sich etwas geändert hat. Aufklappen
  ist Sitzungszustand, kein persistierter — nach einem Neuladen ist die Notiz wieder zugeklappt.
- **Punkt entfernen** ([×], `aria-label` „Notizpunkt entfernen"): sofort, ohne Rückfrage. Der Punkt
  ist weg und fließt ab der nächsten Frage nicht mehr ein. Er wird **nicht** für die Zukunft
  gesperrt: Sagt die Person dasselbe später erneut, entsteht er erneut. Eine Sperrliste bräuchte eine
  Antwort auf „wann ist eine Formulierung dieselbe Angabe", die es deterministisch nicht gibt; die
  Person kann den Punkt schlicht wieder entfernen.
- **Kein Bearbeiten, kein Hinzufügen, kein „alle entfernen".** Korrigieren heißt: den falschen Punkt
  entfernen und die richtige Angabe in den Chat schreiben — sie steht sofort im Gesprächsfenster und
  nach der nächsten Antwort in der Notiz. Die Liste ist kurz; Einzelentfernen genügt.
  Wiederaufnahme, wenn Personen nachweislich Angaben wiederholen, weil die Verdichtung sie verfehlt.
- **Ein entfernter Punkt bleibt entfernt, bis der Server es bestätigt.** Die Antwort auf eine Frage
  liefert den Notizstand, der in sie eingeflossen ist — bei Entfernen während einer laufenden
  Antwort ist das der Stand *vor* dem Entfernen. Die Oberfläche filtert deshalb jeden lokal
  entfernten Punkt aus jedem mitgelieferten Notizstand heraus, bis das Laden des Chats die
  Entfernung bestätigt; ein gelöschter Punkt taucht nie kurz wieder auf.
- **Barrierefreiheit:** Schaltfläche mit `aria-expanded`, Panel als benannte Region; Entfernen ist
  eine echte Schaltfläche; Fokus bleibt nach dem Entfernen auf dem nächsten Punkt (sonst auf dem
  vorherigen). **Wird die Liste leer, geht der Fokus auf die Kopfzeile** — die Schaltfläche ist in
  diesem Moment selbst verschwunden, weil eine leere Notiz keine Oberfläche hat; die Kopfzeile ist
  benannt (`role="group"`, `aria-label`) und genau die Stelle, an der die Schaltfläche saß. Eine
  Live-Region meldet die Entfernung mit der Restanzahl (#1488).

### Anzeigeverzögerung

Die Verdichtung läuft nach der Antwort; die Antwort selbst trägt den Notizstand, der in sie
eingeflossen ist. Ein neuer Punkt wird deshalb mit der **nächsten** Antwort oder beim Neuladen
sichtbar — dieselbe Regel wie beim erzeugten Chat-Titel. Was angezeigt wird, ist immer ein Stand,
der tatsächlich gewirkt hat oder wirken wird; nie ein Stand, der bereits überholt ist, ohne dass die
Person es sehen konnte.

### Randfälle, entschieden

| Fall | Verhalten |
|---|---|
| Leere Notiz | keine Schaltfläche, kein Panel |
| Notiz mit Punkten, aber weniger als drei abgeschlossene Runden | keine Schaltfläche; die Punkte wirken bereits (siehe „Wo und wann") |
| Dritte Runde abgeschlossen | Schaltfläche erscheint mit allen bis dahin gesammelten Punkten, zugeklappt |
| Ein Punkt (ab Runde 3) | Schaltfläche „Gesprächsnotiz · 1", Liste mit einem Punkt |
| Alle Punkte entfernt | Schaltfläche verschwindet; sie erscheint wieder, sobald ein neuer Punkt entsteht (Rundengrenze ist dann längst erreicht) |
| Sehr langer Punkt | entsteht nicht: Kürzung auf 200 Zeichen bei der Verdichtung; die Oberfläche bricht um, kürzt nicht |
| Notiz während einer laufenden Antwort | Panel bleibt bedienbar, Entfernen erlaubt; die laufende Antwort hat ihren Stand bereits; die Entfernung wirkt ab der nächsten Frage. Der mitgelieferte Notizstand der eintreffenden Antwort enthält den Punkt noch — die Oberfläche filtert lokal entfernte Punkte heraus, bis `GET chat` die Entfernung bestätigt |
| Chat in archiviertem Space | Notiz sichtbar, Entfernen-Schaltflächen ausgeblendet; keine Verdichtung, weil keine neuen Runden. Der archivierte Space nimmt keine Änderung an einem Chat an; der ganze Chat bleibt löschbar |
| Space wird zwischen Antwort und Verdichtung archiviert | das Verdichtungsergebnis wird verworfen; die Notiz bleibt auf dem Stand vor der Antwort |
| Verdichtung schlägt fehl | Runde steuert keine Punkte bei; Log ohne Inhalt, Zähler mit Grund; kein Nachholen |
| Neuladen mitten in der Unterhaltung | Notiz kommt mit dem Chat aus der Datenbank; Zahl und Punkte sind identisch zu vorher; Panel zugeklappt |
| Neustart des Backends, Cache-Ablauf | Notiz liegt in der Datenbank; das Gesprächsfenster wird aus den letzten *n* Nachrichten mit derselben Marken-Normalisierung nachgeladen — derselbe Chat schickt vor und nach dem Neustart denselben Prompt |
| Chat ohne Wissensbasis (Leiste geleert) | Notiz wird geführt und verwendet — sie betrifft das Gespräch, nicht die Suche |
| Anfrage ohne gespeicherten Chat (nur API) | keine Notiz; nur das flüchtige Gesprächsfenster |
| Mitlesende eines geteilten Chats (sobald Teilen gebaut ist) | sehen die Notiz, ohne Entfernen-Schaltflächen |
| Entfernen und Verdichtung gleichzeitig | Entfernen trifft einen Punkt über seine Kennung; die Verdichtung hängt neue Punkte an — beides berührt sich technisch nicht. Für die *eigene* Runde tritt der Fall nicht auf: Ein Punkt wird erst mit der nächsten Antwort sichtbar, kann also nicht entfernt werden, während seine Verdichtung läuft. Die Verdichtung einer *späteren* Runde kann einen entfernten Punkt dagegen erneut erzeugen, weil Dedupe nur die aktuelle Liste prüft — das ist die entschiedene Löschsemantik (kein Sperren), und die Person entfernt ihn erneut |
| Löschung des Chats | Notiz wird mit gelöscht |
| Chat-Export, Kontolöschung (Zielbild, #391/#395) | schließen die Notiz ein, sobald gebaut |

---

## Konfiguration: Ebenenzuordnung

Nach dem [Konfigurations-Ebenenmodell](./hybrid-retrieval.md#konfigurations-ebenenmodell): Jeder
Wert braucht eine Antwort auf „Wer stellt das wann um, und woher weiß er, worauf?" — sonst ist er
ein fester Wert.

| Wert | Default | Ebene | Wer, wann, woher |
|---|---|---|---|
| Breite des Gesprächsfensters (Nachrichten) | 20 (2 bis 100, gerade) | 1 | Benchmark und Entwicklung; die Wirkung ist nur mit dem Mehrrunden-Harness beurteilbar. Heute Konstante — ohne Property ist der Wert nicht benchmarkbar |
| Suchfenster (Runden) | 2 (0 bis Fensterbreite ÷ 2) | 1 | dito; die Klassen `anaphora_resolution`/`topic_switch` messen genau diesen Wert. Darf das Gesprächsfenster nicht überschreiten — die Suche sieht nie mehr als die Antwort; 0 heißt „nur Frage und Notiz" |
| Deckel der Notiz (Punkte) | 10 (1 bis 50) | 1 | dito; Wirkung erst mit `constraint_carryover` sichtbar |
| Punkte je Runde | 2 | **fester Wert** | schützt frühe Rahmenangaben vor Verdrängung durch eine gesprächige Nachricht; niemand stellt das um |
| Höchstlänge eines Notizpunkts | 200 Zeichen | **fester Wert** | Niemand stellt das um; es ist eine Oberflächen- und Prompt-Größe |
| Anzeige-Untergrenze der Notiz (abgeschlossene Runden) | 3 | **fester Wert** | Oberflächengröße; niemand stellt sie um. Sichtbar ab „mindestens ein Punkt und mindestens drei Runden" |
| Art eines Notizpunkts (`RAHMEN`/`ANTWORTFORM`) | — | **kein Parameter** | interne Unterscheidung, nicht angezeigt, nicht einstellbar |
| Verdichtung an/aus | — | **kein Parameter** | siehe Produkthaltung: kein Adressat |
| Modell der Verdichtung | — | **kein Parameter** | das systemweit aktive Chat-Modell; keine neue Rolle |

Alle Ebene-1-Werte erscheinen im Handbuch ausschließlich in der Konfigurationstabelle
(`suche.md`, 10.3), in keiner Verwaltungsoberfläche.

---

## Messung

### Warum zuerst gemessen wird

Der Harness kennt heute nur Einzelfragen; jede Golden-Frage läuft ohne Verlauf. Der Folgefragen-Pfad
der Zerlegung ist damit ungemessen (Issue #1288, „Nicht Teil dieses Issues"). Bevor das Fenster
verändert wird, muss der heutige Stand beziffert sein — sonst ist nachher nicht zu sagen, ob die
Änderung etwas gebracht hat (Grundsatz aus
[retrieval-benchmark.md, Abschnitt 6](./retrieval-benchmark.md#6-der-benchmark-als-eintrittsbedingungs-maschine)).

### Mehrrunden-Fälle

Ein Mehrrunden-Fall ist eine Folge von Runden. Jede Runde trägt die Frage, die erwarteten Dokumente
**dieser Runde** und eine **von Hand geschriebene Kurzantwort** (ein bis drei Sätze, aus der
`answer_span` des Zieldokuments abgeleitet). Die Kurzantwort ist nötig, weil der Harness keine
Antworten erzeugt, das Gesprächsfenster in Produktion aber Antworten enthält; sie ist deterministisch,
billig und macht den Fall reproduzierbar.

```json
{
  "id": "verw-conv-001",
  "domain": "verwaltung",
  "category": "anaphora_resolution",
  "turns": [
    { "query": "Was kostet ein Anwohnerparkausweis?",
      "answer": "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.",
      "expected_documents": ["verwaltung-0012_anwohnerparken.md"] },
    { "query": "Und bei Bedürftigkeit?",
      "answer": "…",
      "expected_documents": ["verwaltung-0038_verwaltungsgebuehrensatzung.md"] }
  ],
  "expected_state": "known_gap",
  "expected_state_since": "2026-09-…",
  "expected_state_reason": "…"
}
```

Der Harness bildet **Gesprächsfenster und Suchfenster deterministisch** aus den Skriptrunden (mit
Marken-Normalisierung, die hier trivial ist). Die **Notiz ist nicht deterministisch**: Sie entsteht
über die produktive Verdichtung — ein Modellaufruf je vorangegangener Runde — und ist damit, wie die
Zerlegung, ein nichtdeterministischer Anteil, für den die Mehrfachlauf-Regel gilt; der Harness
skriptet die Notiz nicht. Gemessen wird **je Runde** mit den vier bestehenden Metriken; berichtet
wird je Runde, je Fall (Mittel) und je Klasse. Ein Fall gilt als gelöst, wenn **jede** Runde gelöst
ist (alle erwarteten Dokumente im Fenster und eines auf Rang 1) — **auf dem Pipeline-Pfad**, dem
einzigen, auf dem ein Mehrrunden-Fall laufen kann; die Ausnahme von der Zwei-Pfade-Regel steht in
[retrieval-benchmark.md, Abschnitt 5](./retrieval-benchmark.md#5-neue-golden-fall-klassen).

Die Mehrrunden-Messung braucht die Teilfragen-Zerlegung — ohne sie gibt es keine Auflösung von
Bezügen. Sie läuft deshalb wie jede zerlegende Messung: gepinntes Eval-Chat-Modell, Mehrfachlauf-Regel
(drei Läufe, Median). Sie bekommt eine eigene
Baseline-Datei (`eval/baseline/pipeline-verwaltung-conversations.json`) mit dem Chat-Modell als
geprüftem Fixpunkt; die Entscheidung aus #1288 über die Einzelfragen-Baseline bleibt davon unberührt.
Seit #1553 trägt sie ein **eigener** CI-Job (`conversations` in
`.github/workflows/retrieval-regression.yml`) mit denselben Auslösern wie die
Einzelfragen-Domänen und eigenem Zeitbudget — nicht der Job der Einzelfragen-Domänen, dessen Budget
sie sprengte (ADR-0012, Entscheidung 49).

### Fallklassen

| Klasse | Runden | Was sie misst | Baustein |
|---|---|---|---|
| `anaphora_resolution` | 2–3 | Rückfrage mit Bezugswort auf die Vorrunde(n); Zieldokument ist nur mit aufgelöstem Bezug findbar | Suchfenster, Zerlegung |
| `topic_switch` | 3–4 | Wechsel in Runde 2 oder 3; erwartete Dokumente der Wechselrunde sind ausschließlich das neue Thema — ein Altthemen-Dokument im Fenster ist Bleed. Ein bis zwei Fälle mit Rückkehr zum alten Thema durch Neubenennung | kurzes Suchfenster, Rückfall-Reparatur |
| `constraint_carryover` | 3–5 | Angabe in Runde 1 („Ich arbeite in der Nebenstelle 3", „Es geht um die Fassung 2024"), die in Runde 3 oder später das richtige Dokument vom Verwechslungspartner trennt; Runde 2 ist ein Zwischenthema, damit die Angabe aus dem Suchfenster gefallen ist | Gesprächsnotiz |

Mindestens acht Fälle je Klasse (bestehende Regel). Alle drei Klassen werden in der
Verwaltungsdomäne kuratiert; der Verwechslungspartner (`confusable_document`) ist bei
`constraint_carryover` Pflicht, sonst misst die Klasse nichts. Fehlerbild, Ground Truth und
Adressat je Klasse stehen in [retrieval-benchmark.md, Abschnitt 5](./retrieval-benchmark.md#5-neue-golden-fall-klassen).

**Verworfen: `long_horizon`.** Eine Angabe aus Runde 1 in Runde 13 läuft über denselben Mechanismus
wie in Runde 4 — die Notiz. Was die Klasse zusätzlich messen würde, ist die Kontinuität der
**Antwort** über die Fensterbreite hinaus, und die ist mit Ranking-Metriken nicht messbar. Ein Fall
kostet zwölf Skriptrunden mit Kurzantworten; acht Fälle wären hundert Runden Kuratierung für einen
Wert, den der Harness nicht sehen kann. Wiederaufnahme, sobald es einen Generationsharness gibt
(Relevanz-/Faktentreue-Evaluatoren, siehe `search-quality-evaluation.md`).

### Reihenfolge

1. Harness um Mehrrunden-Fälle erweitern (Schema, Lauf je Runde, Bericht je Klasse, Baseline-Typ).
2. Fälle kuratieren und **den heutigen Stand messen** — der Befund, den #1446 verlangt. Erwartung:
   `anaphora_resolution` überwiegend gelöst, `topic_switch` mit Bleed, `constraint_carryover`
   innerhalb des heutigen 20-Nachrichten-Fensters teilweise gelöst (das ganze Fenster geht heute an
   die Zerlegung) — diese Zahl ist die Referenz dafür, dass das kurze Suchfenster plus Notiz nichts
   verliert.
3. Bauteil 1 bauen, nachmessen: `topic_switch` muss steigen, `anaphora_resolution` darf nicht
   fallen, `constraint_carryover` darf **vorübergehend** fallen (Angabe aus Runde 1 liegt außerhalb
   des Suchfensters, Notiz noch nicht gebaut) — das ist der Nachweis, dass die Notiz gebraucht wird.
4. Gesprächsnotiz bauen, nachmessen: `constraint_carryover` muss mindestens die Referenz aus Schritt
   2 erreichen; `topic_switch` darf nicht fallen (Notiz bleedet nicht).
5. Zustandswechsel (`known_gap` → `solved`) datiert eintragen, Baseline ziehen, Befund an #1446.

> **Ergebnis der Schritte 3–5 (Nachmessung vom 2026-09-12, Issue #1490).** Von den drei Bedingungen
> der Schritte 3 und 4 ist eine erfüllt, eine teilweise und eine nicht:
> `anaphora_resolution` fällt nicht, sondern steigt (nDCG@8 0,722 → 0,763; 4 → 5 von 9 Fällen);
> `topic_switch` steigt in allen vier Rundenmetriken (nDCG@8 0,713 → 0,776), bleibt auf Fallebene
> aber bei 2 von 9; `constraint_carryover` erreicht die Referenz aus Schritt 2 **nicht** wieder
> (nDCG@8 0,857 → 0,835; 4 → 1 von 9 Fällen). Die für Schritt 4 und 5 festgelegte, empfindlichere
> Vergleichsgröße — Zielrunden, deren Teilfrage die Rahmenangabe trägt — fällt von 4 von 9 auf
> 1 von 9. Die Notiz entsteht dabei zuverlässig (83 von 83 Verdichtungen erfolgreich) und erreicht
> die Zerlegung; sie trägt die Fassungsangabe aber nur in drei von neun Fällen bis zur Zielrunde,
> weil das Verdichtungsmodell die Artenaufzählung des Prompts als Vorlage ausfüllt und die
> Zwei-Zeilen-Grenze die Jahres- oder Fassungszeile abschneidet. Einzelfälle, Ursachen und der
> Vorbehalt, dass der Vergleich kein reines A/B über Fenster und Notiz ist, stehen in
> [`eval/corpus/verwaltung/MAINTENANCE.md`](../../eval/corpus/verwaltung/MAINTENANCE.md),
> Abschnitt „Befund der Nachmessung".

---

## Integrationspunkte

- **Suche** ([retrieval-algorithm.md](./retrieval-algorithm.md), Stufe 2): Zerlegung erhält Suchfenster
  und `RAHMEN`-Punkte; Sicherheitsgurt und Rückfall ändern sich wie oben.
- **Antwort** ([llm-integration.md](./llm-integration.md), „Übergabe der Passagen"): der Aufruf
  bekommt einen fünften Teil, die Gesprächsnotiz, vor dem Gesprächsverlauf.
- **Chats** ([spaces-and-assets.md](./spaces-and-assets.md#chats)): Notiz ist Bestandteil des Chats
  (Löschung, Archivierung, künftiges Teilen; Export und Kontolöschung, sobald gebaut).
- **Sicherheit und Mitbestimmung** ([security-and-compliance.md](./security-and-compliance.md)):
  Notiz ist Chatinhalt, kein Protokoll, keine Auswertung, kein Profil.
- **Evaluierung** ([retrieval-benchmark.md](./retrieval-benchmark.md)): Mehrrunden-Fälle, eigene
  Baseline, Messvertrag-Fortschreibung (ADR-0012-Nachtrag: Fixpunkte Fensterbreite, Suchfenster,
  Notizdeckel, Chat-Modell).
- **Handbuch** (`docs/handbuch/suche.md`, Nachzug in den Umsetzungs-Issues): Abschnitt 2 (Verlauf und
  Notiz), Abschnitt 3 (Modellaufruf-Tabelle: „Gesprächsnotiz verdichten — einmal je Runde eines
  gespeicherten Chats, nebenläufig"), Stufe 3 (Suchfenster, Rückfall), Abschnitt 6 (Notiz im
  Aufruf), 10.3 (drei neue Schlüssel). Die falsche Aussage „erste Nutzerfrage des Chats" in Stufe 3
  wird ersetzt.
- **Frontend** ([user-frontends.md](./user-frontends.md)): Kopfzeile des Chats.

---

## Offene Fragen / Zukünftige Erweiterungen

- Ob das Gesprächsfenster der Antwort mit vorhandener Notiz kleiner sein darf (z. B. 12 Nachrichten),
  ist erst mit einem Generationsharness beurteilbar. Bis dahin bleibt der Default 20.
- Sollte das Teilen von Chats gebaut werden, braucht der Teilen-Dialog keinen Zusatz: Die Notiz ist
  Teil des Chats und wird mitgeteilt; ein Hinweis darauf im Dialog genügt.
- Die verworfenen Alternativen (größeres Fenster, Rolling Summary, Themenwechsel-Erkennung,
  Nutzerprofil, Vektorindex, Bearbeiten/Anlegen, Schalter, Überlauf-Verdichtung, Anzeige ab dem
  ersten Punkt, `long_horizon`) stehen mit Wiederaufnahmebedingung in ADR-0031 und werden hier nicht
  wiederholt.

---

## Erfolgs-Metriken

- `topic_switch`: Anteil gelöster Fälle nach Bauteil 1 gegenüber dem heutigen Stand. Die Bleed-Zahl
  ist als Kriterium zurückgezogen — sie hat auf dem Datensatz kaum Dynamikbereich
  (`eval/corpus/verwaltung/MAINTENANCE.md`, Abschnitt „Themen-Bleed").
- `anaphora_resolution`: kein Fall verschlechtert sich durch das kurze Suchfenster.
- `constraint_carryover`: nach der Notiz mindestens der heutige Stand, gemessen an den Zielrunden,
  deren Teilfrage die Rahmenangabe trägt.
- Betrieb: `opaa.chat.note.extraction`-Fehlschläge nahe null; Latenz der Antwort unverändert (die
  Verdichtung liegt außerhalb des Anfrage-Threads).
- **Nicht gemessen: die Zitatwiederholung (Fehlerbild 2).** Es gibt keinen Zähler über nicht
  bestätigte Marken, und der Benchmark endet vor der Antwortgenerierung. Die Entfernung der Marken
  aus dem Gesprächsfenster wird strukturell durch Test abgesichert (derselbe Chat erzeugt vor und
  nach Cache-Ablauf denselben markenfreien Prompt), nicht durch eine Metrik. Wiederaufnahme, sobald
  ein Zähler über nicht bestätigte Marken je Antwort existiert — er gehört nicht in dieses Epic.
