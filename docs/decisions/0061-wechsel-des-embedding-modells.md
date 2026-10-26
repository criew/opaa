# ADR-0061: Wechsel des Embedding-Modells

## Status

Akzeptiert · zuletzt geändert 2026-10-26 · ergänzt ADR-0060 (Entscheidung 10) und ADR-0053 (Entscheidung 8)

## Kontext

Das Embedding-Modell ist eine Zeile der Datenbank, die die Installation mit einer Probeeinbettung
festlegt (ADR-0060, Entscheidung 10). Danach gibt es in der Oberfläche keinen Wechsel. Das Handbuch
beschreibt als Übergang einen Eingriff in die Datenbank bei angehaltenen Instanzen: Modellname und
Dimension ändern, neu starten, die Neueinbettung in der Verwaltung starten. Der Zugangsschlüssel
lässt sich so nicht ändern, weil er verschlüsselt in der Zeile liegt, und ohne Probeeinbettung
bemerkt ein Tippfehler erst der Startabgleich.

Die Neueinbettung selbst ist gebaut: Sie füllt eine Zielspalte der Vektortabelle, gehört einer
Instanz, lässt sich auf jeder Instanz abbrechen, setzt nach einem Neustart fort, und eine lebende
Instanz übernimmt den Lauf einer toten (ADR-0053, Entscheidung 8). Sind alle Chunks neu eingebettet,
tauscht eine Transaktion unter dem Schemalock der Vektortabelle die Spalten. Solange der Index nicht
zum Modell passt, ist die Vektorsuche ausgesetzt, und die Volltextsuche antwortet allein. Ob zwei
Modelle vergleichbare Vektoren liefern, entscheiden Startabgleich und Neueinbettung nur nach
Modellname und Dimension; ein Schreiber bettet ein, bevor er die Spalte wählt, und die Vektorsuche
prüft den Stand der Spalten nicht gegen das Modell, mit dem sie die Frage eingebettet hat.

Ein Wechsel nur des Endpunkts, etwa auf einen Ersatzserver bei einem Ausfall, ist heute ebenfalls
ein Datenbankeingriff. Issue #3214, Spezifikation: [Wechsel des
Embedding-Modells](../features/embedding-model-change.md).

## Entscheidung

### 1. Geführter Wechsel mit angekündigter Unterbrechung der Vektorsuche

Die Systemverwaltung wechselt das Embedding-Modell unter Modelle → Embedding: neues Modell mit
Adresse, Schlüssel und Eingabegrenze, Probeeinbettung, Vorschau, Bestätigung. Ab der Bestätigung bis
zum Umschalten ist die Vektorsuche ausgesetzt; Suche und Chat antworten mit der Volltextsuche und
dem Hinweis „Eingeschränkte Suche“. Die Vorschau kündigt das an, mit Zahl der Chunks, Tokens
(geschätzt), Dauer und Speicherbedarf. Es gibt keinen parallelen Index mit zwei Modellen und kein
doppeltes Einbetten. Begründung: Der Wechsel ist selten und geplant; ein paralleler Index verlangte
zwei Modelle in jedem Schreibpfad und ein Umschalten des Abfragemodells auf allen Instanzen im
selben Augenblick, für eine Unterbrechung, die sich ankündigen und über Nacht legen lässt.

### 2. Die Generation entscheidet über Vergleichbarkeit

Modellzeile und Index tragen eine **Generation**: die Modellzeile die des Modells, der Index die der
Vektorspalte und während eines Wechsels die der Zielspalte. Jeder Modellwechsel vergibt eine neue,
ein Endpunktwechsel behält sie. Vergleichbar sind Vektoren nur bei gleicher Generation;
Startabgleich und Neueinbettung vergleichen sie zusätzlich zu Modellname und Dimension. Jede
Einbettung trägt die Generation des Modell-Clients, der sie erzeugt hat, und der Client-Cache ist
nach ihr geschlüsselt. Begründung: Ein Wechsel auf eine andere Fassung oder Quantisierung unter
gleichem Namen und gleicher Dimension sähe sonst beim nächsten Start wie das alte Modell aus und
würde still zurückgedreht.

### 3. Die Bestätigung setzt das Modell in einer Transaktion

Die Bestätigung prüft auf dem Server alle Angaben erneut und bettet die Probe erneut ein; weicht die
Dimension von der Vorschau ab, wird sie abgelehnt. Dann setzt eine Transaktion unter dem Schemalock
der Vektortabelle, mit derselben Wartegrenze auf Tabellensperren wie das Umschalten, die Modellzeile
samt Schlüssel und neuer Generation, bewahrt die bisherige Zeile verschlüsselt als **Vorgänger**,
legt die leere Zielspalte an und startet den Lauf als Lauf dieser Instanz. Was unter dem Lock
entschieden wird, liest sie aus der Datenbank, nicht aus dem Stand der Instanz. Läuft die
Wartegrenze ab, antwortet sie `409` und lässt sich wiederholen. Ein zweiter Wechsel ist erst nach
Umschalten oder Verwerfen möglich. Begründung: Ohne Entwurf auf dem Server gibt es keinen
Zwischenstand, der veralten könnte; was gilt, entscheidet die Transaktion.

### 4. Abbrechen, Verwerfen mit Nachfüllen, kein Rückweg nach dem Umschalten

„Abbrechen“ pausiert den Lauf; das Eingebettete bleibt. „Verwerfen“ ist vor dem Umschalten möglich,
nicht aber, sobald der Lauf den Index der Zielspalte baut. Es setzt in einer Transaktion wie in
Entscheidung 3 den Vorgänger zurück, entfernt die Zielspalte und beendet einen laufenden Lauf; die
Vektorsuche läuft sofort wieder mit dem alten Modell. Chunks, die seit der Bestätigung geschrieben
wurden und keinen alten Vektor haben, füllt ein anschließender Lauf mit dem wiederhergestellten
Modell nach. Das Umschalten verwirft alte Spalte und Vorgänger; danach führt nur ein neuer Wechsel
zurück, und die Bestätigung sagt das. Begründung: Bis zum Umschalten steht die alte Spalte ohnehin,
der Rückweg kostet nur die Zeile und das Nachfüllen weniger Chunks. Einen Indexbau abzubrechen, der
ohne Transaktion läuft, wäre aufwendiger als abzuwarten.

### 5. Endpunkt ohne Neueinbettung nur bei gleichem Modell

Adresse, Schlüssel und Eingabegrenze ändern sich ohne Neueinbettung nur, wenn Modellname und
Dimension gleich bleiben und die Vektoren mehrerer fester Probetexte am neuen Endpunkt denen des
**Referenzankers** nahezu gleichen: Gewertet wird die kleinste Kosinus-Ähnlichkeit, sie muss
mindestens 0,99 sein. Der Anker entsteht mit Installation oder Modellwechsel und bleibt über
Endpunktwechsel erhalten; fehlt er, erhebt ihn die erste gelungene Einbettung des Modells. Ein
gespeicherter Schlüssel bleibt nur bei derselben Adresse (Schema, Host, Port, Pfad), wie in der
Modellverwaltung. Bei angehaltenem Lauf eines Wechsels gilt dasselbe für das Zielmodell, als
Ersatz-Endpunkt. Begründung: Derselbe Name kann hinter einem anderen Server eine andere Fassung
bedeuten; ein fester Anker lässt keine Drift über viele Wechsel zu. Der Schutz zielt auf Versehen:
Ein Endpunkt, der die öffentlichen Probetexte gezielt vortäuscht, besteht ihn.

### 6. Mehrinstanzbetrieb: gleiche Version, Generation beim Schreiben und Lesen

Bestätigung, Verwerfen und Endpunktwechsel gelingen nur, wenn alle lebenden Instanzen dieselbe
Version melden, verglichen ohne Build-Metadaten. Ein Release-Rollback ist während eines Wechsels
nicht vorgesehen, erst nach Umschalten oder Verwerfen. Modellzeile und Spaltenstand erfahren die
Instanzen über den Invalidierungsbus (ADR-0053, Entscheidung 5). Ein Schreiber prüft beim Schreiben,
ob die Generation seiner Einbettung die der gewählten Spalte ist; sonst verwirft er sie und bettet
neu ein. Die Vektorsuche prüft dasselbe für die Frage und liefert sonst nichts, dann antwortet die
Volltextsuche. Nachläufe (Kontextpräfix, Nachzug, Änderung eines Metadatenschemas) bleiben während
eines Wechsels erlaubt: Sie schreiben über denselben Weg. Begründung: Zwischen Commit und
Busereignis darf kein Vektor einer Generation in die Spalte einer anderen geraten und keine Frage
gegen Vektoren eines anderen Modells laufen; eine ältere Instanz kennt die Generation nicht.

### 7. Vorschau ohne Inhalte und ohne Preis, mit festen Grenzen

Chunks zählt die Vorschau wie der Fortschritt der Neueinbettung, private Bibliotheken nur ab der
Mindestgruppengröße. Tokens schätzt sie aus der Textlänge, die Dauer aus einer Durchsatzprobe mit
künstlichen Texten, ohne Probe aus `opaa.indexing.embedding-rate-estimate`, den Speicher als Spanne,
die neue Tupel und Indexeinträge der Neueinbettung einschließt. Einen Preis nennt sie nicht. Probe
und Bestätigung zählen gegen eine installationsweite Grenze wie der Einstellungsimport; Zahl und
Größe der Probeaufrufe sind fest begrenzt. Begründung: Vor der Bestätigung geht kein Inhalt an den
neuen Empfänger, OPAA kennt die Tarife der Anbieter nicht, und eine Probe darf den Endpunkt nicht
als Lastquelle missbrauchen lassen.

## Konsequenzen

- Der Datenbankeingriff für Modell- und Endpunktwechsel entfällt im Handbuch. Der Startabgleich
  bleibt als Schutz, etwa nach einer Rücksicherung mit abweichender Modellzeile.
- Während eines Wechsels findet die Suche nur über die Volltextsuche; die Antwortqualität sinkt für
  die angekündigte Dauer.
- Die Neueinbettung braucht freien Platz deutlich über der Größe der neuen Vektoren; PostgreSQL gibt
  den Platz der alten Spalte erst frei, wenn die Zeilen neu geschrieben werden.
- Ein Release-Rollback wartet, bis ein Wechsel umgeschaltet oder verworfen ist; zwei Builds
  derselben Version gelten als gleich.

## Verworfene Alternativen

- Paralleler Index mit altem Modell für die Suche bis zum Umschalten — doppeltes Einbetten neuer
  Dokumente und ein instanzübergreifend gleichzeitiges Umschalten des Abfragemodells.
- Vergleichbarkeit nur nach Modellname und Dimension — dreht einen Wechsel unter gleichem Namen beim
  Neustart zurück (Entscheidung 2).
- Alte Spalte nach dem Umschalten eine Frist lang als Sofort-Rückweg — doppelter Speicher und ein
  Aufräumlauf für einen Fall, den Probe und Vorschau vorher abfangen.
- Verwerfen ohne Nachfüllen — ließe die seit der Bestätigung geschriebenen Chunks bis zur nächsten
  Indexierung ihres Dokuments ohne Vektor.
- Indexbau beim Verwerfen abbrechen — braucht das Beenden einer fremden Datenbanksitzung.
- Anker bei jedem Endpunktwechsel neu setzen — ließe eine schrittweise Drift zu (Entscheidung 5).
- Sperre der Nachläufe während eines Wechsels — sie schreiben bereits in die Zielspalte, die Sperre
  schützte nichts.
- Kostenvorschau in Euro — OPAA kennt keine Tarife; Tokens lassen sich mit dem eigenen Tarif
  rechnen.
