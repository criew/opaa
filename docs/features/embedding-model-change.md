# Wechsel des Embedding-Modells

> **Status: umgesetzt (#3214).** Gebaut sind der geführte Wechsel in der Oberfläche, die
> Generation, die Vorschau, das Verwerfen mit Nachfüllen und der Endpunktwechsel, auf der schon
> vorhandenen Neueinbettung (Zielspalte, Lauf mit Besitz, Abbruch, Fortsetzen, Übernahme,
> Umschalten). Wo die Umsetzung vom Konzept abweicht, beschreibt der Text den gebauten Stand. Entscheidungen: [ADR-0061](../decisions/0061-wechsel-des-embedding-modells.md),
> ergänzt [ADR-0060](../decisions/0060-schlanke-konfiguration-und-einrichtungsassistent.md),
> Entscheidung 10.

## Motivation

Die Installation legt das Embedding-Modell fest, mit einer Probeeinbettung, die die Dimension der
Vektortabelle bestimmt. Häuser wechseln es später aus guten Gründen: ein Modell, das deutsche Texte
besser trifft, ein Anbieter, der ein Modell abkündigt, ein eigener Server statt eines
Cloud-Dienstes. Heute ist das ein Eingriff in die Datenbank bei angehaltenen Instanzen, ohne
Probeeinbettung und ohne Möglichkeit, den Zugangsschlüssel zu ändern. Dasselbe gilt für den bloßen
Umzug auf einen anderen Server mit demselben Modell, etwa bei einem Ausfall.

Ziel ist ein Wechsel, den die Systemverwaltung in der Oberfläche durchführt: mit Prüfung, mit einer
ehrlichen Ankündigung von Dauer und Folgen, mit Abbruch und Rückweg bis zum Umschalten.

---

## Überblick

1. **Zwei Vorgänge:** Modellwechsel (anderes Modell, andere Fassung oder andere Dimension, mit
   Neueinbettung) und Endpunktwechsel (gleiches Modell, andere Adresse, anderer Schlüssel oder
   andere Eingabegrenze, ohne Neueinbettung).
2. **Probeeinbettung vor allem anderen**, wie in der Installation.
3. **Vorschau** mit Chunks, Tokens, Dauer, Speicherbedarf und der angekündigten Unterbrechung der
   Vektorsuche.
4. **Bestätigung** setzt das neue Modell mit neuer Generation und startet die Neueinbettung; Suche
   und Chat antworten bis zum Umschalten mit der Volltextsuche.
5. **Abbrechen** pausiert, **Verwerfen** kehrt vor dem Umschalten zum alten Modell zurück und füllt
   fehlende Vektoren nach; nach dem Umschalten gibt es keinen Rückweg außer einem neuen Wechsel.
6. **Generation** entscheidet zusammen mit Modellname und Dimension, ob Vektoren vergleichbar sind,
   beim Start, beim Schreiben und bei der Suche, geprüft auch in der Datenbank.
7. **Mehrinstanzbetrieb:** genau ein Wechsel, nur bei gleicher Version aller Instanzen; Lauf und
   Übernahme wie gebaut.
8. **Kein Datenbankeingriff mehr** im Handbuch.

---

## Begriffe

| Begriff | Bedeutung |
|---|---|
| **Modellwechsel** | Wechsel auf ein Modell, dessen Vektoren mit den gespeicherten nicht vergleichbar sind; verlangt die Neueinbettung des Bestands |
| **Endpunktwechsel** | Änderung von Adresse, Schlüssel oder Eingabegrenze bei gleichem Modell; die gespeicherten Vektoren bleiben gültig |
| **Ersatz-Endpunkt** | Endpunktwechsel des Zielmodells, während der Lauf eines Modellwechsels angehalten ist |
| **Generation** | Fortlaufende Kennung eines Modells; jeder Modellwechsel vergibt eine neue, auch ein verworfener, keine wird wieder vergeben |
| **Vorgänger** | Das bisherige Modell samt Schlüssel und Generation, aufbewahrt von der Bestätigung bis zum Umschalten oder Verwerfen |
| **Referenzanker** | Die Vektoren fester Probetexte, gespeichert mit dem Modell bei Installation oder Modellwechsel; Maßstab des Endpunktwechsels |
| **Nachfüllen** | Lauf nach dem Verwerfen, der Chunks ohne Vektor mit dem wiederhergestellten Modell einbettet |
| **Umschalten** | Der Schritt am Ende der Neueinbettung, nach dem die neuen Vektoren gelten und die alten wegfallen |

---

## Ablauf des Modellwechsels

```
┌──────────────────────────────────────────────────────────────────────┐
│  Modelle › Embedding                                                 │
│  Aktiv: nomic-embed-text (768 Dimensionen) · https://ollama:11434    │
│  ──────────────────────────────────────────────────────────────────  │
│  Modell wechseln                                                     │
│  Basis-Adresse   [ https://ollama:11434/v1        ]                  │
│  Modell-Kennung  [ bge-m3                         ]                  │
│  Schlüssel       ( ) beibehalten  (•) neu [ •••• ]  ( ) ohne         │
│  Eingabegrenze   [ 8192 ]                                            │
│                                          [Probeeinbettung]           │
│  ✓ Probeeinbettung erfolgreich: 1024 Dimensionen                     │
│                                                                      │
│  Vorschau                                                            │
│  Chunks              48.120 (davon privat: nicht ausgewiesen)        │
│  Tokens              etwa 19 Mio.                                    │
│  Dauer               etwa 7 Stunden (gemessen am neuen Endpunkt)     │
│  Speicher            etwa 0,4 bis 1,5 GB zusätzlich                  │
│                                                                      │
│  ⚠ Ab dem Start bis zum Abschluss ist die Vektorsuche ausgesetzt.    │
│    Suche und Chat finden nur über die Volltextsuche.                 │
│  ⚠ Nach dem Abschluss fallen die bisherigen Vektoren weg; zurück     │
│    geht es dann nur mit einem neuen Wechsel.                         │
│                                       [Abbrechen]  [Wechsel starten] │
└──────────────────────────────────────────────────────────────────────┘
```

1. **Eingabe.** Basis-Adresse, Modell-Kennung, Schlüssel und Eingabegrenze, mit denselben Regeln wie
   in der Installation (Abschnitt „Prüfungen auf dem Server").
2. **Schlüssel.** „Beibehalten" gibt es nur, solange die Adresse denselben Endpunkt nennt: gleiches
   Schema, gleicher Host, gleicher Port, gleicher Pfad, wie in der Modellverwaltung. Sonst ist der
   Schlüssel neu einzugeben oder ausdrücklich „ohne Schlüssel" zu wählen. Der Schlüssel des einen
   Anbieters geht so nie unbemerkt an einen anderen.
3. **Probeeinbettung.** Die festen Probetexte ergeben Erfolg und Dimension. Gleicht das neue Modell
   dem aktiven (gleiche Kennung, gleiche Dimension, Ähnlichkeit zum Referenzanker mindestens 0,99),
   bietet die Oberfläche statt des Modellwechsels den Endpunktwechsel an.
4. **Vorschau** (nächster Abschnitt).
5. **Bestätigung.** „Wechsel starten" öffnet einen Dialog mit den beiden Warnungen und verlangt eine
   ausdrückliche Bestätigung. Der Server prüft alle Angaben erneut und bettet die Probe erneut ein;
   weicht die Dimension ab, lehnt er ab. Dann setzt eine Transaktion unter dem Schemalock der
   Vektortabelle das neue Modell mit neuer Generation und Referenzanker, bewahrt den Vorgänger, legt
   die leere Zielspalte an und startet den Lauf.
6. **Neueinbettung** wie gebaut: Dokument für Dokument, mit Fortschritt im Reiter „Indexstatus" der
   Seite „Suche & Indexierung", Abbruch, Fortsetzen und Übernahme.
7. **Umschalten.** Haben alle Chunks einen neuen Vektor, baut der Lauf den Vektorindex der
   Zielspalte und tauscht die Spalten. Der Vorgänger wird gelöscht, die Vektorsuche läuft wieder.

Es gibt keinen Entwurf auf dem Server: Eingaben und Probeergebnis leben in der Oberfläche, bis die
Bestätigung sie in einem Aufruf schickt.

---

## Vorschau

| Angabe | Herleitung |
|---|---|
| Chunks | Zahl der Chunks des Bestands wie im Fortschritt der Neueinbettung; private Bibliotheken nur, wenn genug Personen solche führen (Mindestgruppengröße) |
| Tokens | Textlänge der Chunks geteilt durch vier Zeichen je Token, ausgewiesen als „etwa" |
| Dauer | Chunks geteilt durch den Durchsatz des neuen Endpunkts aus der Durchsatzprobe; ohne Probe aus `opaa.indexing.embedding-rate-estimate`, dann so gekennzeichnet |
| Speicher | Spanne, siehe unten; daneben die heutige Größe der Vektortabelle samt Indizes |
| Unterbrechung | Vektorsuche ausgesetzt von der Bestätigung bis zum Umschalten, mit der geschätzten Dauer |

- **Durchsatzprobe.** Höchstens drei Aufrufe mit je höchstens 16 künstlichen Texten in der Zielgröße
  der Chunks, fest im Code. Vor der Bestätigung geht kein Inhalt des Bestands an den neuen
  Empfänger.
- **Speicher als Spanne.** Die Untergrenze sind die neuen Vektoren (vier Byte je Dimension und
  Chunk) und ihr Vektorindex, etwa ebenso groß. Die Neueinbettung schreibt jede Zeile neu: Für den
  zusätzlichen Vektor ist auf der Seite kein Platz, also entsteht eine neue Zeilenversion mit neuen
  Einträgen in jedem Index der Tabelle, auch im alten Vektorindex und im Volltextindex. Autovacuum
  macht die alten Versionen wieder nutzbar, verkleinert die Dateien aber nicht. Die Obergrenze
  rechnet deshalb die heutige Größe der Vektortabelle samt Indizes hinzu. Faustregel für den
  Betrieb: freier Platz mindestens in Höhe der Obergrenze.
- **Nach dem Umschalten** entfällt die alte Spalte; ihren Platz gibt PostgreSQL erst frei, wenn die
  Zeilen neu geschrieben werden. Freien Plattenplatz kann OPAA nicht sehen.
- **Kein Preis.** OPAA kennt die Tarife der Anbieter nicht; die Tokenzahl lässt sich mit dem eigenen
  Tarif rechnen.

---

## Während des Wechsels

| Bereich | Verhalten |
|---|---|
| Suche und Chat | Vektorsuche ausgesetzt, Volltextsuche allein; Chat zeigt „Eingeschränkte Suche", das Erklärprotokoll die Stufe als nicht verfügbar |
| Reranking | unverändert; es arbeitet auf dem Text der Kandidaten |
| Indexierung, Uploads | laufen weiter; neue und geänderte Chunks bekommen nur den Vektor des neuen Modells |
| Nachläufe (Kontextpräfix, Nachzug, Metadatenschema) | laufen weiter und schreiben über denselben Weg in die Zielspalte |
| Modelle › Embedding | zeigt aktives Modell, Vorgänger und Fortschritt; ein zweiter Modellwechsel ist gesperrt, ein Ersatz-Endpunkt nur bei angehaltenem Lauf möglich |
| Statusseite | „Index veraltet" mit beiden Modellen, wie gebaut |

Ein doppeltes Einbetten neuer Dokumente mit beiden Modellen gibt es nicht.

---

## Abbrechen, Verwerfen, Rückweg

| Aktion | Wann | Wirkung |
|---|---|---|
| Abbrechen | Lauf läuft | Lauf hält nach dem aktuellen Dokument an; das Eingebettete bleibt; Vektorsuche bleibt ausgesetzt |
| Fortsetzen | Lauf angehalten oder fehlgeschlagen | macht bei den Chunks ohne neuen Vektor weiter |
| Verwerfen | vor dem Umschalten, nicht während des Indexbaus | Vorgänger wird wieder aktiv, Zielspalte entfällt, ein laufender Lauf endet; Vektorsuche sofort wieder mit dem alten Modell; anschließend Nachfüllen |
| Zurück nach dem Umschalten | — | nur als neuer Modellwechsel mit vollständiger Neueinbettung |

- **Indexbau.** Beginnt der Lauf mit dem Bau des Vektorindex der Zielspalte, vermerkt er das im
  Index-Datensatz. Der Vermerk sperrt Verwerfen nur, solange der Lauf läuft und seine Besitzerin
  lebt; jedes Ende des Laufs (Umschalten, Abbruch, Fehler) löscht ihn, ebenso die Übernahme durch
  eine andere Instanz, die den Bau neu beginnt. Solange er gilt, antwortet Verwerfen `409` mit dem
  Hinweis, dass das Umschalten läuft. Ein Indexbau ohne Transaktion ließe sich nur abbrechen, indem
  eine fremde Datenbanksitzung beendet wird.
- **Nach einem Abbruch während des Baus** läuft der Bau in seiner Datenbanksitzung noch zu Ende,
  denn der Abbruch wirkt erst nach dem laufenden Schritt. Verwerfen wartet dann auf dessen
  Tabellensperre und antwortet nach der Wartegrenze `409`, bis der Bau fertig ist; danach gelingt
  es.
- **Nachfüllen.** Chunks, die seit der Bestätigung neu oder mit geändertem Text geschrieben wurden,
  haben nach dem Verwerfen keinen Vektor der Vektorspalte. Das Verwerfen startet deshalb in
  derselben Transaktion einen Lauf mit dem wiederhergestellten Modell, der nur diese Chunks
  einbettet. Die Vektorsuche läuft währenddessen; die fehlenden Chunks findet bis dahin nur die
  Volltextsuche. Der Dialog des Verwerfens nennt ihre Zahl.

### Zwei Arten von Läufen

Es gibt einen Platz für einen Lauf. Der Index-Datensatz vermerkt neben Status und Besitzerin die Art
des Laufs und die Generation, für die er arbeitet.

| | Neueinbettung | Nachfüllen |
|---|---|---|
| Auswahl | Chunks ohne Vektor in der Zielspalte | Chunks ohne Vektor in der Vektorspalte |
| schreibt in | Zielspalte | Vektorspalte |
| Start und Fortsetzen | nur bei veraltetem Index | nur bei aktuellem Index und vermerktem Nachfüllen |
| Übernahme | wie gebaut, bei veraltetem Index | wie gebaut, bei aktuellem Index |
| Abschluss | Indexbau und Umschalten | Ende ohne Umschalten, sobald kein Chunk ohne Vektor bleibt; bleiben welche, „fehlgeschlagen" mit Fortsetzen |
| Revisionsereignis am Ende | `EMBEDDING_REINDEX_COMPLETED` | keines |

- **Bestätigung während des Nachfüllens** beendet das Nachfüllen in ihrer Transaktion, statt `409`
  zu antworten. Die Neueinbettung bettet ohnehin jeden Chunk ein, auch die noch nicht nachgefüllten;
  ein `409` hielte die Systemverwaltung nur bis zum Ende eines Laufs auf, der danach nichts mehr
  nützt.
- **Ein Worker arbeitet nur, solange Besitzerin, Art und Generation** im Index-Datensatz seinen
  eigenen gleichen. Löst eine Bestätigung auf derselben Instanz das Nachfüllen ab, endet dessen
  Worker daher auch, obwohl die Besitzerin dieselbe bleibt; dasselbe gilt für die Neueinbettung, die
  ein Verwerfen beendet.

---

## Endpunktwechsel

Ein Endpunktwechsel ändert Basis-Adresse, Schlüssel oder Eingabegrenze, ohne die gespeicherten
Vektoren anzufassen. Er gelingt nur, wenn

- die Modell-Kennung gleich bleibt,
- die Probeeinbettung dieselbe Dimension liefert und
- die Vektoren der festen Probetexte denen des Referenzankers nahezu gleichen: Gewertet wird die
  kleinste Kosinus-Ähnlichkeit der drei Probetexte, sie muss mindestens 0,99 betragen (fest im
  Code).

**Referenzanker.** Er entsteht mit der Probeeinbettung eines Modellwechsels und, weil der
Installationsschritt nur die Dimension speichert, unmittelbar nach dem Abschluss der Installation
mit dem gerade eingerichteten Endpunkt. Er bleibt über alle Endpunktwechsel derselbe; ein Endpunktwechsel überschreibt ihn nicht, sonst
könnte eine Kette knapp bestandener Wechsel schrittweise abdriften. Fehlt er, etwa bei einer
Installation von vor dieser Funktion, erhebt ihn die erste Instanz, deren Modell-Client danach
erfolgreich einbettet oder deren Statusprobe gelingt, mit einer bedingten Schreibanweisung: nur
solange er fehlt, Generation und Adresse unverändert sind. So ist er in der Regel da, bevor ein
Ausfall einen Endpunktwechsel verlangt. Fehlt er trotzdem und ist der bisherige Endpunkt nicht
erreichbar, lehnt die Prüfung ab und nennt den Modellwechsel als Weg.

**Grenze des Schutzes.** Er zielt auf Versehen: eine andere Fassung oder Quantisierung unter
gleichem Namen, ein falsch eingetragener Server. Ein Endpunkt, der die öffentlichen Probetexte
gezielt vortäuscht und anderes einbettet, besteht ihn; gegen einen böswilligen Endpunkt schützen
Adressprüfung der Modelle und die Wahl des Anbieters. Ein nachträglich erhobener Anker friert den
Endpunkt ein, der gerade aktiv ist. War der bereits eine andere Fassung, etwa nach einem früheren
Eingriff in die Datenbank, lehnt die Prüfung den Rückweg zum ursprünglichen Server ab; dann bleibt
nur der Modellwechsel.

**Ersatz-Endpunkt.** Ist der Lauf eines Modellwechsels angehalten oder fehlgeschlagen, etwa weil der
neue Endpunkt ausfiel, darf ein Ersatz-Endpunkt für das Zielmodell eingetragen werden, geprüft gegen
dessen Referenzanker aus der Bestätigung. Danach setzt die Systemverwaltung den Lauf im Indexstatus
fort; die Seite nennt den Weg dorthin.
Während ein Lauf läuft, ist der Endpunktwechsel gesperrt.

Gespeichert wird in einer Transaktion, die auch die Adresse im Index-Datensatz nachzieht; die
Instanzen bauen ihren Modell-Client über den Invalidierungsbus neu. Ein abgelehnter Endpunktwechsel
nennt die gemessene Ähnlichkeit. Typischer Anlass ist der Ausfall: Ein Ersatzserver mit demselben
Modell wird eingetragen, ohne Neustart und ohne Neueinbettung.

---

## Generation

Ob Vektoren vergleichbar sind, entscheiden Generation, Modellname und Dimension zusammen: Weicht
eines davon ab, ist der Index veraltet.

- **Modellzeile** trägt die Generation des Modells, der **Index-Datensatz** die der Vektorspalte und
  während eines Wechsels die der Zielspalte.
- **Bestand.** Die Migration gibt Modellzeile und Vektorspalte die Generation 1. Steckt die
  Installation mitten in einer Neueinbettung, etwa nach dem bisherigen Eingriff in die Datenbank,
  bekommt die Zielspalte die Generation 2, und die Modellzeile bekommt die Generation der Spalte,
  deren Modellname und Dimension sie nennt. Passt die Modellzeile zu keiner Spalte, etwa nach einem
  Eingriff ohne Neustart, gilt die Regel des Startabgleichs unten. Die Folge der Generationen
  beginnt nach der höchsten vergebenen Nummer.
- **Vergabe.** Jede Bestätigung vergibt die nächste Generation aus dieser Folge, die nie eine Nummer
  zweimal ausgibt, auch nicht nach einem Verwerfen. Endpunktwechsel und Ersatz-Endpunkt behalten
  sie.
- **Startabgleich.** Stimmen Generation, Name und Dimension der Modellzeile mit der Vektorspalte
  überein, ist der Index aktuell; stimmen sie mit der Zielspalte überein, läuft ein Wechsel weiter.
  Sonst wurde die Modellzeile außerhalb des geführten Wegs geändert, etwa durch eine Rücksicherung
  oder einen Eingriff in die Datenbank; dann setzt der Startabgleich die Vektorsuche aus und legt
  eine Zielspalte an, wie gebaut. Passt die Modellzeile beim Start oder in der Migration zu keiner
  Spalte, bekommt sie eine neue Generation aus der Folge, und die Zielspalte bekommt dieselbe; auch
  ein Eingriff, der nur Name oder Dimension ändert und die Generation stehen lässt, endet so mit
  einer eigenen. Invariante: Vektorspalte und Zielspalte tragen nie dieselbe Generation. Ein Wechsel
  unter gleichem Namen und gleicher Dimension wird so beim Neustart nicht als „wieder das alte
  Modell" zurückgedreht, und ein Eingriff, der nur Name oder Dimension ändert, wird weiter erkannt.
- **Einbettungen** tragen die Generation des Clients, der sie erzeugt hat. Der Client-Cache ist nach
  Generation geschlüsselt; ein Client, dessen Bau eine Invalidierung überholt hat, wird nicht
  übernommen.

---

## Mehrinstanzbetrieb

- **Genau ein Wechsel.** Bestätigung und Verwerfen laufen in einer Transaktion unter dem Schemalock
  der Vektortabelle und entscheiden nach dem Index-Datensatz in der Datenbank, nicht nach dem Stand
  der Instanz. Die Änderungen an der Tabelle warten höchstens so lange auf Sperren wie das
  Umschalten; danach antworten sie `409` und lassen sich wiederholen. Ein zweiter Wechsel wird mit
  `409` abgelehnt.
- **Gleiche Version.** Bestätigung, Verwerfen und Endpunktwechsel verlangen, dass alle lebenden
  Instanzen dieselbe Version melden, sonst `409` mit dem Hinweis, das Rolling Update abzuschließen.
  Geprüft wird vor der Probe und noch einmal unter dem Schemalock.
  Verglichen wird wie im Instanzregister ohne Build-Metadaten; zwei Builds von `main` mit derselben
  Version gelten als gleich. Ein Release-Rollback ist während eines Wechsels nicht vorgesehen, erst
  nach Umschalten oder Verwerfen; das Handbuch sagt das. Eine ältere Instanz kennt die Generation
  nicht, ihr Startabgleich verglich nur den Modellnamen und könnte einen Wechsel unter gleichem
  Namen verwerfen. Schaltet sie um, bleibt die Zielgeneration ohne Zielmodell stehen; sie zählt
  nicht als Zielspalte. Sobald der Bus die Änderung meldet, spätestens beim Start, übernimmt der
  Abgleich der neuen Version sie als Generation der Vektorspalte, wenn Modell und Dimension passen
  und der Lauf abgeschlossen ist, ohne neue Neueinbettung. Hat der Abgleich einer älteren Instanz
  die Zielspalte verworfen, etwa nach einem Rollback, endet kein Lauf abgeschlossen; der Index
  bleibt dann veraltet.
- **Entscheidung gegen die Zeile unter dem Lock.** Bestätigung und Endpunktwechsel prüfen und
  vergleichen vor der Transaktion, weil die Probe auf das Netz wartet. Unter dem Schemalock muss
  die Modellzeile noch die sein, gegen die geprüft wurde (Generation, Modell, Adresse, Schlüssel,
  Eingabegrenze, Dimension); sonst antworten sie `409`, und die Probe ist zu wiederholen.
- **Lauf, Besitz, Übernahme** nach Art des Laufs, siehe „Zwei Arten von Läufen".
- **Schreiben.** Ein Schreiber bettet mit einem angehefteten Client ein und schreibt unter der
  Spaltensperre in die Spalte, die die Datenbank für die Generation seiner Einbettung nennt: die
  Vektorspalte oder die Zielspalte. Nennt sie keine, verwirft er die Einbettung, lässt die Sperre los
  und bettet mit dem neuen Client erneut ein, höchstens dreimal,
  sonst scheitert das Dokument. Ein gescheitertes Dokument einer Quelle versucht deren nächster Lauf
  erneut; ein Upload bleibt gescheitert, bis jemand ihn „erneut verarbeiten" lässt. Eine fehlende
  Zielspalte nach einem Verwerfen behandelt der Schreiber wie eine fremde Generation.
- **Prüfung in der Datenbank.** Die Schreibtransaktion nimmt den Schemalock der Vektortabelle
  geteilt, liest dann die Generationen beider Spalten und hält den Lock bis zum Commit.
  Bestätigung, Verwerfen, Umschalten und Startabgleich nehmen ihn exklusiv, bevor sie Datensatz
  oder Tabelle ändern, und warten deshalb auf laufende Schreiber; ein Schreiber schreibt nur in die
  Spalte, die zu seiner Generation gehört. Die Warteschlange ist fair: Ein Schreiber, der nach einem
  wartenden Wechsel kommt, wartet hinter ihm, sodass ein steter Strom von Schreibern den Wechsel
  nicht aushungert. Die Wartegrenze gilt je Warteschritt; ist sie in einem Schritt erreicht,
  antwortet der Wechsel `409`. Passt keine, rollt die ganze
  Transaktion zurück, die Zeile des Volltexts eingeschlossen. Eine Instanz, die Busereignisse verpasst hat, schreibt so
  auch über ein Verwerfen und eine erneute Bestätigung hinweg keinen Vektor in die Spalte einer
  anderen Generation. Das Umschalten wartet mit seiner Tabellensperre auf laufende Schreiber; was
  vorher geprüft und geschrieben wurde, wird mit der Spalte umgeschaltet.
- **Suchen.** Die Vektorsuche bettet die Frage ein und prüft unter der Spaltensperre, ob deren
  Generation die der Vektorspalte ist; die Abfrage prüft dasselbe noch einmal gegen den
  Index-Datensatz. Passt sie nicht, liefert die Vektorsuche nichts, und die Volltextsuche trägt die
  Frage allein.
- **Bus.** Modellzeile (`embedding-model`) und Index-Datensatz (`embedding-index`) melden jede
  Änderung. Eine Instanz liest bei beiden Ereignissen Modell und Spaltenstand neu; das konfigurierte
  Modell kommt aus der Modellzeile, nicht aus dem Stand beim Start.

---

## Datenhaltung

| Ort | Inhalt |
|---|---|
| Modellzeile | zusätzlich Generation und Referenzanker |
| Vorgänger | eigene Tabelle mit einer Zeile, Inhalt wie die Modellzeile samt Generation und Anker; der Schlüssel verschlüsselt mit `OPAA_SETTINGS_ENCRYPTION_KEY` wie in der Modellzeile, die nur eine Zeile zulässt |
| Index-Datensatz | zusätzlich Generation der Vektorspalte, Generation der Zielspalte, Vermerk „Indexbau läuft", Art des Laufs (Neueinbettung oder Nachfüllen) und Generation, für die der Lauf arbeitet |
| Folge der Generationen | fortlaufend, ohne Wiedervergabe, beginnend nach der höchsten vergebenen |

- **Umschalten.** Seine Transaktion tauscht die Spalten, macht die Generation der Zielspalte zur
  Generation der Vektorspalte, leert die Generation der Zielspalte, löscht den Vermerk zum Indexbau
  und den Vorgänger und beendet den Lauf.
- **Verwerfen** leert Generation der Zielspalte und Vermerk ebenso und vermerkt das Nachfüllen.
- **Migration.** Neue Tabelle und neue Spalten sind additiv (nullable oder mit bleibendem
  Vorgabewert) und brechen die Vorgängerversion nicht. Das Setzen der Generationen im Bestand ist
  eine Datenänderung und braucht einen Delta-Test, auch für eine Installation mitten in einer
  Neueinbettung. Ist die Art des Laufs ein persistierter Aufzählungswert, gilt für neue Werte das
  Verzeichnis der persistierten Aufzählungen.

---

## Prüfungen auf dem Server

Bestätigung, Endpunktwechsel und Ersatz-Endpunkt prüfen alles neu, unabhängig von der Probe:

- Adressprüfung der Modelle; ausgenommen nur eine Adresse, die denselben Endpunkt wie die
  gespeicherte nennt, wie in der Modellverwaltung.
- keine Zugangsdaten in der Adresse, Längen von Adresse, Kennung und Schlüssel, Eingabegrenze größer
  null.
- Dimension höchstens die Grenze des Vektorindex; bei der Bestätigung gleich der Vorschau.
- Schlüsselregel aus dem Abschnitt „Ablauf".
- Art des Vorgangs nach Kennung, Dimension und Ähnlichkeit: Ein Modellwechsel auf ein Modell, das
  als Endpunktwechsel gälte, und ein Endpunktwechsel, der die Ähnlichkeit verfehlt, werden
  abgelehnt.
- Zustand: kein laufender Wechsel für die Bestätigung (ein Nachfüllen beendet sie), ein angehaltener
  für den Ersatz-Endpunkt, kein laufender Lauf für den Endpunktwechsel; kein veralteter Index aus
  dem Startabgleich.
- gleiche Version aller lebenden Instanzen.

---

## Revisionsprotokoll

| Ereignis | Anlass | Inhalt |
|---|---|---|
| `EMBEDDING_MODEL_CHANGED` | Bestätigung eines Modellwechsels | Modell, Adresse ohne Zugangsdaten, Dimension, Eingabegrenze und Generation vorher und nachher; Schlüssel nur als „gesetzt", „geändert", „beibehalten" oder „entfernt"; Chunks der Vorschau |
| `EMBEDDING_MODEL_CHANGE_DISCARDED` | Verwerfen | zurückgesetztes und verworfenes Modell mit Generation; Status des beendeten Laufs; Zahl der nachzufüllenden Chunks |
| `EMBEDDING_MODEL_ENDPOINT_CHANGED` | Endpunktwechsel, Ersatz-Endpunkt | Adresse und Eingabegrenze vorher und nachher, Schlüsselstatus, kleinste gemessene Ähnlichkeit, ob während eines Wechsels |
| `EMBEDDING_REINDEX_STARTED`, `…_CANCELLED` | Fortsetzen, Abbrechen | wie gebaut |
| `EMBEDDING_REINDEX_COMPLETED` | Umschalten oder Fehler des Laufs | wie gebaut |

- Die Bestätigung schreibt kein zusätzliches `EMBEDDING_REINDEX_STARTED`; ihr Ereignis steht für den
  Start.
- Verwerfen beendet einen laufenden Lauf mit dem Status „abgebrochen" und der Meldung des
  Verwerfens, ohne eigenes `EMBEDDING_REINDEX_CANCELLED`.
- Beendet eine Bestätigung ein Nachfüllen, steht das im Ereignis der Bestätigung.
- Das Nachfüllen erzeugt kein Ereignis: Es ist ein Systemvorgang, der weder Zugriffe verändert noch
  Verwaltungshandeln ist, und schaltet nichts um; sein Stand steht im Indexstatus und im Log.
- Ein abgelehnter Vorgang oder eine fehlgeschlagene Probe erzeugt kein Ereignis; es ändert sich
  nichts.
- Die drei neuen Arten erfüllen den Maßstab des Ereignisvertrags als Verwaltungshandeln an
  Einstellungen. Sie brauchen je einen Eintrag in `AuditEventType`, im Verzeichnis der persistierten
  Aufzählungen (vor dem Release v0.1.0 lesbar, danach zuerst unter „pending"), in `audit-log.yaml`,
  die erlaubten Schlüssel in `AuditEventCatalog` und die Schlüssel der Selbstauskunft in
  `AuditSelfDisclosurePayload`. Die Zahl der Arten in ADR-0059 beschreibt dessen Prüfung zum
  Zeitpunkt der Entscheidung und bleibt; die geschlossene Liste ist `AuditEventType`.
- Kein Schlüssel erscheint im Protokoll oder im Log, auch nicht gekürzt.

---

## Schnittstelle

Im Thema `models`, nur für die Systemverwaltung (`SYSTEM_ADMIN`):

| Operation | Zweck |
|---|---|
| Probe des Embedding-Wechsels | Probeeinbettung und Durchsatzprobe eines Entwurfs; liefert Dimension, Art des Vorgangs (Modell- oder Endpunktwechsel, mit Ähnlichkeit) und die Vorschau |
| Modellwechsel starten | Bestätigung mit den Angaben des Entwurfs |
| Modellwechsel verwerfen | vor dem Umschalten, nicht während des Indexbaus |
| Endpunkt ändern | Endpunktwechsel und Ersatz-Endpunkt |

Fortschritt, Abbruch und Fortsetzen bleiben die bestehenden Operationen der Neueinbettung im Thema
`indexing`. Der Zugangsschlüssel kommt nur schreibend vor, wie in der Modellverwaltung.

**Grenzen der Aufrufe**, je installationsweit, darüber `429`:

| Operation | Grenze | Grund |
|---|---|---|
| Probe, Bestätigung | gemeinsam 30 je Stunde, wie der Einstellungsimport | Sie senden Probetexte und künstliche Texte an einen frei gewählten Endpunkt; die Grenze verhindert, dass er als Lastquelle dient |
| Endpunkt ändern | eigene Grenze, 30 je Stunde | Sie sendet ebenfalls Probetexte, darf aber im Ausfall nicht an verbrauchten Proben scheitern; sie prüft selbst und braucht keine vorherige Probe |
| Verwerfen | keine | Es sendet nichts nach außen, und der Zustand lässt es je Wechsel nur einmal zu |

Ist die Grenze der Probe erschöpft, bietet die Oberfläche den Endpunktwechsel direkt an.

---

## Was entfällt

- Der Datenbankeingriff für Modell- und Endpunktwechsel im Handbuch, samt dem Hinweis, der Schlüssel
  lasse sich so nicht ändern.
- „Nicht über die Oberfläche änderbar" in Installation, Modellverwaltung und Fakten-Manifest.

Der Startabgleich bleibt: Passt die Generation der Modellzeile beim Start zu keiner Spalte, etwa
nach einer Rücksicherung, setzt er die Vektorsuche wie gebaut aus und legt die Zielspalte an. Einen
Vorgänger gibt es dann nicht, Verwerfen also auch nicht.

---

## Umsetzungshinweise

Für den Umsetzungs-PR, der Backend, Oberfläche, Handbuch und e2e zusammen liefert.

**Handbuch** — nachzuziehen, der Datenbankeingriff entfällt:

- `betrieb/modelle.md`: Rollentabelle (Zeile Embedding), „Modell wechseln", Ausfalltabelle (Zeile
  Embedding), Fehlerbilder („Index veraltet")
- `betrieb/installation.md`: Schritttabelle (Embedding-Modell) und Abschnitt zum Abschluss
- `betrieb/aktualisierung.md`: Hinweis zu geänderten Vorgaben des Embedding-Modells, dazu: kein
  Release-Rollback während eines Wechsels
- `betrieb/kubernetes.md`: Hinweis zum späteren Wechsel und zur Neueinbettung
- `betrieb/konfiguration.md`: Zeile `OPAA_OPENAI_EMBEDDING_TIMEOUT`
- `spaces/suche.md`: Aussetzung der Vektorsuche nach einem Wechsel
- `betrieb/mehrere-instanzen.md`: Versionsbedingung

**Fakten-Manifest:** neue Tabelle und Spalten; `configuration.yaml` für `embedding_model.*`
(änderbar durch die Systemverwaltung); `outbound.yaml`, Datenfluss `embedding-model`: Ziel „den die
Installation oder die Systemverwaltung festlegt", Daten zusätzlich feste Probetexte und künstliche
Texte der Durchsatzprobe, Anlässe zusätzlich Modell- und Endpunktwechsel, Nachfüllen und Erhebung
des Referenzankers; `audit-log.yaml` für die drei Ereignisse. Neue prozesslokale Stellen (Client je
Generation) ins Register des prozesslokalen Zustands.

**Tests:**

- Bestätigung als eine Transaktion; Fehlschlag der Tabellenänderung lässt nichts zurück
- Doppelbestätigung auf zwei Instanzen: genau eine gelingt
- Race: mit Client A eingebettet, dann Commit einer Bestätigung auf Instanz B, dann Schreiben auf A
- Suche zwischen den beiden Busereignissen liefert keine Vektortreffer einer fremden Generation
- Verwerfen während des Laufs, während des Indexbaus (abgelehnt) und nach dem Umschalten (abgelehnt)
- Nachfüllen nach dem Verwerfen, mit Übernahme durch eine andere Instanz; Bestätigung während des
  Nachfüllens beendet es, auch dessen Worker auf derselben Instanz
- Vermerk „Indexbau läuft" nach Abbruch und nach Fehler beim Umschalten: Verwerfen gelingt nach dem
  Ende des Baus
- Schreibanweisung mit veraltetem Stand der Instanz nach Verwerfen und erneuter Bestätigung schreibt
  nichts
- Migration der Generationen, auch mitten in einer Neueinbettung nach einem Eingriff in die
  Datenbank
- Eingriff, der nur Name oder Dimension der Modellzeile ändert, wird beim Start erkannt
- Modellzeile, die beim Start oder in der Migration zu keiner Spalte passt: Modellzeile und
  Zielspalte bekommen eine neue, gemeinsame Generation, verschieden von der der Vektorspalte; eine
  Schreibanweisung mit der Generation der Vektorspalte schreibt nicht in die Zielspalte
- Neustart nach der Bestätigung; Wechsel unter gleichem Namen und gleicher Dimension über einen
  Neustart
- Endpunktwechsel mit und ohne Referenzanker, an der Grenze 0,99, Schlüssel bei anderem Host,
  Ersatz-Endpunkt bei angehaltenem Lauf
- Versionsbedingung für Bestätigung, Verwerfen und Endpunktwechsel
- Schlüssel weder im Protokoll noch im Log
- Grenzen der Aufrufe: erschöpfte Probe sperrt Endpunktwechsel und Verwerfen nicht
- e2e: Wechsel in der Oberfläche bis zum Umschalten, mit zwei Instanzen

**Hinweise für die Umsetzung:**

- Liefert die bedingte Schreibanweisung „nichts geschrieben", rollt die ganze Transaktion des
  Schreibers zurück, auch die Zeile des Volltexts; erst danach wird neu eingebettet und erneut
  geschrieben.
- Das Nachfüllen wählt seine Spalte nach der Art des Laufs, nicht danach, ob der Index veraltet ist;
  die heutige Spaltenwahl richtet sich allein nach dem Veraltet-Zustand.

**Veraltete Javadocs** (sagen „nicht über die Oberfläche" oder „Fingerabdruck hält die Identität
gleich"): `EmbeddingIndexReconciler`, `EmbeddingIndexStateRefresh`, `EmbeddingModelService`,
`InstalledEmbeddingModel`, `EmbeddingInfoService`.

---

## Integrationspunkte

- **Installation** ([installation-and-settings.md](./installation-and-settings.md)): gleiche Prüfung
  der Angaben, gleiche Probeeinbettung; der Referenzanker entsteht dort zuerst.
- **Modellverwaltung** ([llm-integration.md](./llm-integration.md)): Reiter Embedding wird
  bearbeitbar, mit den beiden Vorgängen; Schlüsselregel wie bei Rerank und Transkription.
- **Mehrinstanzbetrieb** ([ADR-0053](../decisions/0053-mehrinstanzbetrieb-aktiv-aktiv.md),
  Entscheidung 8): Lauf, Besitz und Übernahme der Neueinbettung.
- **Revisionsprotokoll**
  ([ADR-0059](../decisions/0059-ereignisvertrag-aufbewahrung-und-integritaet-des-revisionsprotokolls.md)):
  Ereignisvertrag.
- **Suche** ([hybrid-retrieval.md](./hybrid-retrieval.md)): Aussetzung der Vektorsuche wie gebaut.
- **Kostenvorschau** ([metadata-schema.md](./metadata-schema.md)): gleiche Durchsatzmessung je
  Embedding-Modell.

---

## Wirkung auf Behördenunterlagen

- [x] Neue oder geänderte personenbezogene Daten oder Protokollereignisse — drei neue
Revisionsereignisse (Z1)
- [ ] Neue Sichtbarkeit: Daten werden für weitere Rollen oder Personen sichtbar
- [ ] Neue Auswertung oder neuer Export, auch von bereits gespeicherten Daten
- [ ] Geänderte Aufbewahrungs- oder Löschfrist
- [x] Neuer externer Datenfluss (neuer Dienst, neues Ziel ausgehender Verbindungen) — die
Systemverwaltung kann das Ziel des Datenflusses `embedding-model` ändern, und die Durchsatzprobe
sendet künstliche Texte (Z5)
- [x] Geänderte Voreinstellung, geändertes Standardmodell oder geänderter System-Prompt — das
Embedding-Modell, und damit der Empfänger aller Textabschnitte und Suchanfragen, ist nach der
Installation änderbar (Z7)
- [ ] Geänderte KI-Einstufung (Zweck oder Einsatzbereich im Sinne der KI-Verordnung)
- [ ] Keine Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen

**Voraussichtliche Stufe:** Zustimmung (Z1, Z5, Z7 nach den Stufenregeln in
[behoerdenunterlagen.md](./behoerdenunterlagen.md#die-stufenregeln))

**Betroffene Vorlagen in `docs/behoerden/`:** Verzeichnis der Datenflüsse und Betriebsunterlagen
(Verzeichnis beim Umsetzen bestimmen)

---

## Offene Fragen / Zukünftige Erweiterungen

- Modelle mit mehr Dimensionen, als der Vektorindex aufnimmt (etwa über `halfvec` oder eine
  verkürzte Ausgabe des Modells), bleiben abgelehnt.
