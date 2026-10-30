# ADR-0062: Fundstellen-Anker je Chunk

## Status

Akzeptiert · zuletzt geändert 2026-10-30

## Kontext

„Dokument öffnen“ im Belegfenster soll an die zitierte Stelle springen und sie markieren (Epic
#3260). Ein Chunk kannte seine Position bisher nur als `location`, einen deutschen Anzeigetext
(„S. 2–4 · Abschn. 4.2 …“), den Leser per String-Vergleich wieder zerlegten. Je nach Format fehlte
dabei Wesentliches: PDF mit Outline verlor die Seite, der Tika-Rückfall kürzte den Abschnittspfad
auf zwei Ebenen, eine Einzelmail trug gar nichts. Offsets oder Bounding-Boxes speicherte kein
Format.

Zwei Eigenschaften des Bestands bestimmen, was ein Anker leisten muss. Der Chunk-Text ist nicht
der Originaltext: Eine Überschriftenzeile oder ein Folientitel steht vorn, Tabellen sind geplättet,
der Sanitizer normalisiert, Chunks überlappen. Und Originale werden live von der Quelle geholt,
können sich also seit der Indexierung geändert haben. Ein Zeichen-Offset in die eigene Extraktion
passt deshalb weder zum Textlayer eines PDF-Viewers noch zu gerendertem Markdown.

## Entscheidung

### 1. Ein strukturierter Anker je Chunk

Jeder Chunk trägt unter dem Metadatum `anchor` ein dünn besetztes Objekt (`ChunkAnchor`): die
Struktureinheiten, auf die ein Viewer springt — `pageFrom`/`pageTo`, `path` (voller
Überschriftenpfad), `slide`, `sheet` mit `rowFrom`/`rowTo`, `message` (Nachricht im Thread) —
und zwei Zitate `quoteStart`/`quoteEnd`. Ein Format setzt nur die Einheiten, die es kennt; leere
Felder werden nicht gespeichert. Abschnittspfad und Blattname durchlaufen wie `location` den
Sanitizer von `DocumentFormatRunner`. Das Muster folgt dem Fragment- und dem TextQuote-Selector der W3C
Web Annotation: grob springen über die Struktur, fein markieren über den Text.

### 2. Zitate statt Offsets

`quoteStart` und `quoteEnd` sind höchstens 60 Zeichen vom Anfang und Ende des eigenen Textes des
Chunks, Leerraum zu einem Leerzeichen gefaltet, an Wortgrenzen geschnitten. Text, der nicht zur
Passage gehört (wiederholte Überschriftenzeile, Folientitel, Tabellenkopf, Überlappung mit dem
Vorgänger), meldet das Format als Vorspann; `DocumentFormatRunner` nimmt die Zitate aus dem
bereinigten Text ohne diesen Vorspann. Ein Viewer sucht die Zitate im ebenso normalisierten Text.

### 3. Seiten auch entlang der Outline

Ein PDF mit Outline wird abschnittsweise geschnitten; die Seiten des Chunks findet
`PdfPageLocator`, indem er den Anfang und das Ende der Passage in den normalisierten Seitentexten
sucht. Wird die Passage nicht wörtlich gefunden, bleibt der Anker ohne Seiten.

### 4. Der volle Abschnittspfad

`path` und der Abschnittsteil von `location` tragen den vollen Überschriftenpfad, in jedem Format.

### 5. Momentaufnahme im Chat

Ein Beleg speichert den Anker des zitierten Chunks zum Zeitpunkt der Antwort mit
(`chat_messages.sources`, `ChunkLocation.anchor` der API). `chunkIndex` ist nach einer
Neu-Zerlegung nicht stabil; der gespeicherte Anker benennt die Stelle weiter.

### 6. Kein Einfluss auf das Embedding

Der Anker ist Metadatum und nie Teil des Embedding-Inputs; Chunk-Text und Kontextpräfix bleiben
unverändert. Der Re-Index nach der Versionserhöhung der Formate verwendet die Vektoren deshalb
wieder. Einzige Ausnahme ist der volle Pfad im Tika-Rückfall (Entscheidung 4), der dort das
Kontextpräfix verlängern kann.

### 7. `location` bleibt gespeicherter Anzeigetext

`location` wird weiter je Chunk gespeichert und nicht aus dem Anker abgeleitet. Es trägt
Anzeigedaten, die der Anker bewusst nicht kennt (Folientitel, „Nachricht 1 von 2 · Teil 2 von 3“,
„Kopf-/Fußzeile“, „Masterfolie“). Kontextpräfix und Passagenleser lesen den Abschnittspfad weiter
aus `location`, sodass der Embedding-Input unverändert bleibt.

## Konsequenzen

- Jeder Viewer braucht nur den Anker, keine Kenntnis des Schnitts.
- Für Formate ohne Viewer im Browser zeigt die Textfassung die Chunks selbst in Lesereihenfolge;
  sie markiert die zitierte Passage über ihren Chunk-Index und bildet ihre Gliederung aus den
  Ankern (Folie, Blatt, Nachricht, Abschnittspfad, Seite).
- Ein geändertes Original kann die Zitate unauffindbar machen; der Viewer meldet das, statt still
  daneben zu markieren.
- Seiten, Folie und Abschnittspfad stehen doppelt im Chunk: maschinenlesbar im Anker, als
  Anzeigetext in `location`. Das kostet wenige Dutzend Byte je Chunk.

## Verworfene Alternativen

- Zeichen-Offsets in die Extraktion — passen nicht zum Text, den ein Viewer rendert.
- Bounding-Boxes aus PDFBox — für die Markierung reicht der Textlayer des Viewers; Aufwand und
  Datenmenge ohne Bedarf.
- `location` weiter als einzige Angabe — nicht maschinenlesbar, je Format lückenhaft.
- `location` beim Lesen aus dem Anker bilden — der Anker müsste reine Anzeigedaten mittragen,
  Alt-Chunks bräuchten bis zum Re-Index einen Rückfall, und Kontextpräfix-Regeln, die heute am Fundort hängen
  (etwa PDF-Outline mit Seiten und Pfad), müssten nachgebaut werden, um das Embedding unverändert zu
  halten. Ersparnis: wenige Dutzend Byte je Chunk.
