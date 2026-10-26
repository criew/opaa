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
zum Modell passt, ist die Vektorsuche ausgesetzt, und die Volltextsuche antwortet allein.

Ein Wechsel nur des Endpunkts, etwa auf einen Ersatzserver bei einem Ausfall, ist heute ebenfalls
ein Datenbankeingriff. Issue #3214, Spezifikation:
[Wechsel des Embedding-Modells](../features/embedding-model-change.md).

## Entscheidung

### 1. Geführter Wechsel mit angekündigter Unterbrechung der Vektorsuche

Die Systemverwaltung wechselt das Embedding-Modell unter Modelle → Embedding: neues Modell mit
Adresse, Schlüssel und Eingabegrenze, Probeeinbettung, Vorschau, Bestätigung. Ab der Bestätigung
bis zum Umschalten ist die Vektorsuche ausgesetzt; Suche und Chat antworten mit der Volltextsuche
und dem Hinweis „Eingeschränkte Suche“. Die Vorschau kündigt das an, mit Zahl der Chunks, Tokens
(geschätzt), Dauer und Speicherbedarf. Es gibt keinen parallelen Index mit zwei Modellen und kein
doppeltes Einbetten. Begründung: Der Wechsel ist selten und geplant; ein paralleler Index verlangte
zwei Modelle in jedem Schreibpfad und ein Umschalten des Abfragemodells auf allen Instanzen im
selben Augenblick, für eine Unterbrechung, die sich ankündigen und über Nacht legen lässt.

### 2. Die Bestätigung setzt das Modell in einer Transaktion

Die Bestätigung bettet die Probe erneut ein; weicht die Dimension von der Vorschau ab, wird sie
abgelehnt. Dann setzt eine Transaktion unter dem Schemalock der Vektortabelle die Modellzeile samt
Schlüssel auf das neue Modell, bewahrt die bisherige Zeile als **Vorgänger**, legt die leere
Zielspalte mit der neuen Dimension an und startet den Lauf als Lauf dieser Instanz. Danach gelten
Lauf, Abbruch, Fortsetzen und Übernahme unverändert. Ein zweiter Wechsel ist erst nach Umschalten
oder Verwerfen möglich. Begründung: Ohne Entwurf auf dem Server gibt es keinen Zwischenstand, der
veralten könnte; was gilt, entscheidet die Transaktion.

### 3. Abbrechen, Verwerfen, kein Rückweg nach dem Umschalten

„Abbrechen“ pausiert den Lauf; das Eingebettete bleibt. „Verwerfen“ setzt vor dem Umschalten in
einer Transaktion unter dem Schemalock den Vorgänger zurück, entfernt die Zielspalte und beendet
einen laufenden Lauf; die Vektorsuche läuft sofort wieder mit dem alten Modell. Chunks, die seit
der Bestätigung neu oder mit geändertem Text geschrieben wurden, haben dann keinen Vektor und
findet nur die Volltextsuche, bis ihr Dokument neu indiziert wird; die Bestätigung des Verwerfens
nennt ihre Zahl. Das Umschalten verwirft alte Spalte und Vorgänger. Danach führt nur ein neuer
Wechsel zurück; die Bestätigung des Wechsels sagt das. Begründung: Bis zum Umschalten steht die alte
Spalte ohnehin, ein Rückweg kostet nur die Wiederherstellung der Zeile. Eine alte Spalte über das
Umschalten hinaus hielte den doppelten Speicher ohne Frist.

### 4. Endpunkt ohne Neueinbettung nur bei gleichem Modell

Adresse, Schlüssel und Eingabegrenze ändern sich ohne Neueinbettung nur, wenn Modellname und
Dimension gleich bleiben und der Probevektor des neuen Endpunkts dem **Referenzvektor** nahezu
gleicht (Kosinus-Ähnlichkeit mindestens 0,99). Der Referenzvektor ist der Probevektor, den
Installation, Bestätigung eines Wechsels und Endpunktwechsel mit der Modellzeile speichern; fehlt er, bettet die Prüfung die Probe mit
dem bisherigen Endpunkt ein, und ist dieser nicht erreichbar, lehnt sie ab und verweist auf den
Modellwechsel. Während eines Wechsels ist der Endpunktwechsel gesperrt. Begründung: Derselbe Name
kann hinter einem anderen Server eine andere Fassung oder Quantisierung bedeuten; erst der Vergleich
zeigt, ob die gespeicherten Vektoren vergleichbar bleiben.

### 5. Mehrinstanzbetrieb

Ein Wechsel startet nur, wenn alle lebenden Instanzen dieselbe Version melden. Die Instanzen
erfahren Modellzeile und Spaltenstand über den Invalidierungsbus (ADR-0053, Entscheidung 5). Ein
Schreiber bettet nur mit dem Modell ein, das sein Spaltenstand für die gewählte Spalte nennt; passt
sein Modell-Client nicht dazu, liest er beides neu. Nachläufe (Kontextpräfix, Nachzug, Änderung
eines Metadatenschemas) sind während des Wechsels nicht gesperrt: Sie schreiben wie jede Indexierung
in die Zielspalte. Begründung: Zwischen Commit und Busereignis darf kein Vektor des einen Modells in
die Spalte des anderen geraten; die gleiche Version schließt eine Instanz aus, die diese Regel nicht
kennt.

### 6. Vorschau ohne Inhalte und ohne Preis

Chunks zählt die Vorschau wie der Fortschritt der Neueinbettung, private Bibliotheken nur ab der
Mindestgruppengröße. Tokens schätzt sie aus der Textlänge der Chunks, die Dauer aus einer
Durchsatzprobe des neuen Endpunkts mit künstlichen Texten in der Zielgröße der Chunks, ohne Probe
aus `opaa.indexing.embedding-rate-estimate`. Der Speicherbedarf folgt aus Chunks, Dimension und
Vektorindex. Einen Preis nennt sie nicht. Begründung: Vor der Bestätigung geht kein Inhalt an den
neuen Empfänger, und OPAA kennt die Tarife der Anbieter nicht.

## Konsequenzen

- Der Datenbankeingriff für Modell- und Endpunktwechsel entfällt im Handbuch. Der Startabgleich
  bleibt als Schutz, etwa nach einer Rücksicherung mit abweichender Modellzeile.
- Während eines Wechsels findet die Suche nur über die Volltextsuche; die Antwortqualität sinkt für
  die angekündigte Dauer.
- Neu eingebettete Chunks belegen bis zum Umschalten zusätzlichen Speicher; den Platz der alten
  Spalte gibt PostgreSQL erst frei, wenn die Zeilen neu geschrieben werden.
- Ein Release-Rollback während eines Wechsels bleibt möglich, aber ein neuer Wechsel startet erst,
  wenn alle Instanzen wieder dieselbe Version melden.

## Verworfene Alternativen

- Paralleler Index mit altem Modell für die Suche bis zum Umschalten — doppeltes Einbetten neuer
  Dokumente und ein instanzübergreifend gleichzeitiges Umschalten des Abfragemodells (Entscheidung 1).
- Alte Spalte nach dem Umschalten eine Frist lang als Sofort-Rückweg — doppelter Speicher und ein
  Aufräumlauf für einen Fall, den der Probevektor und die Vorschau vorher abfangen.
- Endpunktwechsel nur nach Modellname und Dimension — erkennt eine andere Fassung unter demselben
  Namen nicht (Entscheidung 4).
- Sperre der Nachläufe während eines Wechsels — sie schreiben bereits in die Zielspalte, die Sperre
  schützte nichts.
- Kostenvorschau in Euro — OPAA kennt keine Tarife; Tokens lassen sich mit dem eigenen Tarif rechnen.
