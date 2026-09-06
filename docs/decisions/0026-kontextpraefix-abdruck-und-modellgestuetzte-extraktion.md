# ADR-0026: Kontextpräfix-Abdruck je Dokument und modellgestützte Extraktion

## Status

Vorgeschlagen (05.09.2026, Issues #1072 und #1073, Epic #1065). Nachfolger von
[ADR-0024](0024-metadatenschema-kernfelder.md) für die beiden letzten Arbeitspakete desselben
Datenmodells; die dortigen Entscheidungen 1 bis 8 gelten unverändert weiter.

## Kontext

ADR-0024 hält fest, wie ein Metadatenwert entsteht, gespeichert wird und filtert. Zwei Fähigkeiten
des Epics #1065 sind darin nicht abgedeckt, obwohl beide Architekturentscheidungen tragen, die über
[docs/features/metadata-schema.md](../features/metadata-schema.md) hinausgehen:

- **Der Kontextpräfix (#1072)** stellt ausgewählte Metadatenwerte dem eingebetteten und
  volltextindizierten Text voran. Das wirkt ohne gesetzten Filter — und macht jede Schemaänderung an
  einem präfixwirksamen Feld zu einem Neu-Einbetten. Damit stellt sich die Frage, **welche Dokumente
  ein Nachlauf anfassen muss** und wie die Zusage „der angezeigte Preis ist der bezahlte Preis"
  technisch eingelöst wird.
- **Die modellgestützte Extraktion (#1073)** ist der erste Teil der Aufnahmestrecke, der Geld
  kostet, ausfallen kann und Dokumentinhalte an ein Sprachmodell übergibt. Die Spezifikation
  entscheidet die Fachregeln („unsicher bleibt leer", „je Bibliothek abschaltbar"), nicht die
  Betriebsmechanik. [ADR-0012](0012-messvertrag-retrieval-harness.md) deckt allein die
  Vorabfestlegung der Konfidenzschwelle ab.

Beide Fähigkeiten hängen am selben Datenmodell und an derselben Nachlauf-Mechanik; sie in einem ADR
zu führen hält die Abhängigkeit sichtbar, die zwischen ihnen tatsächlich besteht — ein vom Modell
vergebenes Schlagwort ändert den Kontextpräfix.

## Entscheidung

### 1. Die Auswahl des Präfix-Nachlaufs ist ein Abdruck je Dokument, keine Version je Bibliothek

`documents.context_prefix_stamp` (Migration 027) trägt den Fingerabdruck des Präfix, mit dem die
Chunks eines Dokuments zuletzt eingebettet wurden — gebildet über Titel und die präfixwirksamen
Werte (`ChunkContextPrefix#stampOf`); der Strukturkontext hängt am Chunk und kann sich ohne
Neu-Chunking nicht ändern. Eine präfixwirksame Schemaänderung leert diesen Abdruck bei **genau den
Dokumenten, deren Präfix sich dadurch ändert**.

Die naheliegende Alternative — eine `context_prefix_version` an der Bibliothek, analog zur
Pipeline-Version — ist bewusst **nicht** gebaut. Sie ließe die Folgekostenvorschau „12 Dokumente"
sagen und den Lauf danach 10.000 Dokumente neu einbetten. Genau diese Überraschung ist der Grund,
aus dem es die Anzeige gibt: **Die Zahl der Vorschau ist die Menge des Laufs**, und nur eine
dokumentgranulare Auswahl kann das einlösen. Der Preis ist ein Schreibvorgang je betroffenem
Dokument beim Speichern der Schemaänderung statt eines einzigen Zählers.

### 2. Ein Gate für beide Schreibwege des Präfix

`ChunkContextPrefix#forChunk` ist die einzige Stelle, an der ein Präfix entsteht — für den
Aufnahmeweg wie für den Nachlauf. Ein Dokument, das der Nachlauf neu einbettet, trägt damit
denselben indizierten Text wie ein frisch aufgenommenes; ohne dieses Gate wären zwei Formeln zu
pflegen, deren Auseinanderdriften erst an schwankenden Suchergebnissen auffiele. Die
Aufnahmeentscheidung „dieses Dokument bekommt überhaupt einen Präfix" (`context_prefix_eligible`)
wird dabei mitgeführt und vom Nachlauf **respektiert, nicht neu geraten** — er kennt die Quelle
nicht mehr, aus der sie stammt.

Der Präfix ist Teil der indizierten Darstellung, nie des gespeicherten Chunk-Textes: Der Auszug im
Beleg bleibt der Originalwortlaut.

### 3. Folgekosten sind eine Auswahl, keine Schätzformel

`changeImpact` beantwortet eine geplante Änderung mit betroffenen Dokumenten, betroffenen Chunks,
der Zahl der Einbettungsaufrufe und der erwarteten Laufzeit. Die Laufzeit stammt aus der
**gemessenen** mittleren Dauer je Chunk (`EmbeddingRateEstimator`); gemessen wird nur, was allein
lief, weil die Wandzeiten nebenläufiger Teilchargen überlappen und summiert eine zu pessimistische
Rate ergäben. Solange zu wenige Aufrufe gemessen sind, gilt der konfigurierte Schätzwert — und die
Antwort sagt, welcher der beiden gerade zählt. Eine geschätzte Zahl als gemessene auszugeben wäre
genau die Sorte Angabe, gegen die diese Anzeige gebaut ist.

### 4. Die modellgestützte Extraktion ist voreingestellt aus, je Bibliothek, mit zwei getrennten Marken

`knowledge_libraries.model_extraction_enabled` und `keywords_enabled` (Migration 028) stehen ab Werk
auf `false`, auch für jede vor der Migration angelegte Bibliothek. Eine Fähigkeit, die den Inhalt
jedes aufgenommenen Dokuments an ein Modell übergibt, wird nicht stillschweigend eingeschaltet.

Die Abtragsmarken des Bestandslaufs sind **zwei** (`documents.model_extraction_version` und
`documents.keyword_extraction_version`), nicht eine: Eine Bibliothek, die zuerst nur mit Schlagworten
lief, erreicht ihren Altbestand sonst nicht mehr, wenn die Modell-Extraktion später dazukommt. Aus
demselben Grund trägt der Modellschritt eine **eigene Versionsnummer** getrennt von
`CoreMetadataExtractor.EXTRACTION_VERSION`: Eine korrigierte reguläre Ausdrucksregel in Schritt 1
darf nicht jedes Dokument jeder eingeschalteten Bibliothek erneut zu einem bezahlten Modellaufruf
machen — und ein abgeleiteter Wert weist damit seinen tatsächlichen Erzeuger aus, was die
Herkunftsangabe von ADR-0024 überhaupt erst bewertbar hält.

### 5. Die Konfidenzschwelle ist vorab festgelegt und darf nur steigen

Freigegeben am 05.09.2026 nach der Regel von [ADR-0012](0012-messvertrag-retrieval-harness.md):
**0,80**, für alle modellbefüllten Felder. Ein Wert außerhalb der angebotenen Werteliste wird
unabhängig von der Konfidenz verworfen — das ist ein Vokabularverstoß, kein Schwellenfall, und die
serverseitige Prüfung gegen die Liste ist die einzige bindende Schranke gegen ein präpariertes
Dokument; die Kennzeichnung des Textes als Inhalt statt als Anweisung ist eine Minderung, keine
Zusicherung.

Die ursprüngliche Fassung dieser Entscheidung lautete „nach einer Messung nur senken, nie
stillschweigend erhöhen". Die erste Messung hat die Richtung umgekehrt: Die Handstichprobe vom
05.09.2026 weist 93,9 % falsche Werte oberhalb der Schwelle aus und verlangt über die
Kalibrierungsregel eine **Anhebung**. Die Regel lautet deshalb genauer: **Senken verlangt eine neue
Messung, Anheben nicht** — der Schaden ist asymmetrisch, ein halluzinierter Wert schlimmer als
keiner. Jede Änderung bleibt ein Commit mit Datum und gemessener Verteilung.

### 6. Ein Modellaufruf hat einen eigenen Pool, eine Ablehnungsregel und ein Client-Zeitlimit

Der Aufruf läuft auf einem eigenen, beschränkten Threadpool (`modelExtractionTaskExecutor`), nicht
auf dem gemeinsamen `ForkJoinPool`: Dessen Auslastung ließe das Zeitlimit an einem Aufruf ablaufen,
der nie gestartet ist — ein gezählter „Fehler", den kein Modell verursacht hat. Ist der Pool voll,
**unterbleibt der Aufruf** (eigener Zähler, eigene Ablehnungsregel), statt eingereiht oder auf dem
aufrufenden Faden ausgeführt zu werden; ein Inline-Aufruf kehrte erst nach der Antwort zurück, das
Zeitlimit griffe also gerade dann nicht, wenn es gebraucht wird.

Ein so übergangenes Dokument bekommt **keine Abtragsmarke** und bleibt in der Auswahl des
Bestandslaufs — anders als eine Zeitüberschreitung, die bezahlt wurde und deshalb als erledigt gilt.

Ein überschrittener Aufruf wird aufgegeben, nicht abgebrochen; ein blockierender HTTP-Lesevorgang
lässt sich nicht unterbrechen. Beendet wird er vom **Anfrage-Zeitlimit des Clients**, den dieser
Schritt eigens auflöst (`ActiveChatModelResolver#resolveChatClient(Duration)`, dieselben 30 s) —
sonst bräuchte ein hängendes Modell nur genug Fäden, um den Pool aufzubrauchen.

### 7. Freie Schlagworte leben in einer eigenen Tabelle

`document_keywords` statt einer weiteren Zeile in `document_metadata_values`: Jede Zeile dort ist ein
typisiertes Feld, das ein Filter benennen darf — genau das darf ein Schlagwort nie werden. Die
Trennung macht „Schlagworte filtern nie" zu einer Eigenschaft des Schemas statt zu einer Regel, an
die sich jeder künftige Filterpfad erinnern müsste. Schlagworte erreichen Einbettung und
Volltextindex ausschließlich als **ein Segment des Kontextpräfix** (Entscheidung 2) und tragen
keinen Chunk-Schlüssel.

## Konsequenzen

- **Einfacher:** Der Nachlauf braucht keinen eigenen Mechanismus — er benutzt dieselbe
  Chargen-Schleife wie Bestandslauf und Pipeline-Reindex, und seine Auswahl ist der eine Abdruck.
  Eine manuelle Korrektur eines präfixwirksamen Wertes bettet nicht sofort neu ein, sondern stellt
  genau ein Dokument in denselben Lauf.
- **Schwieriger:** Der Abdruck ist ein Hash über Titel und präfixwirksame Werte; jede Änderung an
  der Präfixbildung selbst ändert ihn für den ganzen Bestand. Wer die Formel anfasst, beauftragt
  damit implizit einen vollständigen Nachlauf und muss das ausweisen.
- **Offen und außerhalb dieses ADR:** Die Umschlüsselung einer Werteliste läuft als eine
  Transaktion und erfüllt die Nachlauf-Zusagen deshalb nicht (Issue #1361, mit Begründung in der
  Spezifikation). Die Nachkalibrierung der modellgestützten Extraktion nach der Handstichprobe —
  Schwelle 0,90, erweitertes Vokabular, Negativbeispiele im Prompt — ist Issue #1359; bis dahin ist
  die Fähigkeit gebaut, aber **nicht abgenommen**.
