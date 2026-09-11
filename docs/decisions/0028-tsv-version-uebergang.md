# ADR-0028: Übergang nach einer Anhebung von `content_tsv_version` — der lexikalische Suchpfad filtert nicht auf die Fassung

## Status

Akzeptiert (Maintainer-Entscheidung vom 08.09.2026, Issue #1346)

## Kontext

Jede Zeile in `chunk_full_text` trägt in `content_tsv_version` die Fassung der Lexembildung, unter der
ihr `tsvector` geschrieben wurde. `FullTextChunkStore.CURRENT_TSV_VERSION` wird angehoben, sobald
sich die gespeicherten Lexeme ändern — bisher dreimal, jedes Mal **additiv**:

| Anhebung | Änderung | Issue |
|---|---|---|
| 1 → 3 | unzerlegte Kennungslexeme (§-Angaben, Aktenzeichen) mit Gewicht `A` | #1097 |
| 3 → 4 | E-Mail-Adressen als Kennungslexem | #1166 |
| 4 → 5 | Kontextpräfix (Titel, Kernfelder) fließt in die `EMBED`-Form und damit in den Index | #1341 |

Die Konstante hat drei Verbraucher:

1. **Füllstand** (`FullTextIndexFillStateService`, `FullTextIndexFillState`): Eine Zeile alter Fassung
   zählt als Rückstand; die Bibliothek erscheint auf der Administrationsseite als `OUTDATED`
   („Nachzug ausstehend", seit #1429; zuvor `INCOMPLETE`).
2. **Reindex-Auswahl** (`PipelineReindexService`): Ein Dokument, dessen Zeilen unter der aktuellen
   Fassung liegen, wird vom Pipeline-Nachzug ausgewählt — unabhängig von der Pipeline-Version.
3. **Suchpfad** (`FullTextChunkSearch`): Die Abfrage las bis zu dieser Entscheidung ausschließlich
   Zeilen der aktuellen Fassung (`AND f.content_tsv_version = ?`).

Der dritte Verbraucher erzeugt den Widerspruch, den #1346 benennt: Mit dem Deployment einer Anhebung
ist **der gesamte Volltextindex jeder Installation** für den lexikalischen Pfad unsichtbar, bis der
Pipeline-Nachzug — der liest, zerlegt und neu einbettet, also bei großen Beständen Stunden läuft —
jede Bibliothek neu geschrieben hat. Zugleich sagt `docs/features/metadata-schema.md` unter
„Nachlauf im Betrieb" zu: „Die Suche bleibt während des gesamten Nachlaufs verfügbar. Ein bereits
indizierter Chunk bleibt gültig und auffindbar, bis seine Neufassung vorliegt." Eingelöst war das nur
für die Vektorhälfte. Die lexikalische Hälfte — gerade sie trägt die exakten Kennungen — war weg.

Zwei Eigenschaften des Bestands sind für die Entscheidung tragend:

- **Je Chunk gibt es genau eine Zeile.** `FullTextChunkStore#indexChunks` schreibt per Upsert auf
  `chunk_id`; eine Neufassung ersetzt die alte Zeile, sie tritt nicht neben sie. Der Versionsfilter
  dedupliziert also nichts — er schließt nur aus.
- **Der Rechtefilter ist versionsunabhängig.** `library_id = ANY(?)` steht neben dem Match-Prädikat
  in derselben `WHERE`-Klausel; ob eine Zeile alter oder neuer Fassung ist, ändert nichts daran,
  welche Bibliotheken der Aufrufer lesen darf.

## Erwogene Wege

### Weg 1: Untergrenze `MIN_SEARCHABLE_TSV_VERSION`

Der Suchpfad liest `content_tsv_version >= MIN_SEARCHABLE_TSV_VERSION` statt Gleichheit; eine zweite
Konstante benennt die älteste noch brauchbare Fassung. Bei einer additiven Anhebung bleibt die
Untergrenze stehen, bei einer brechenden wird sie auf die neue Fassung gezogen.

Vorteil: Die Kompatibilitätsaussage ist explizit und je Anhebung entscheidbar. Nachteil: Eine zweite
Konstante, die jede Anhebung mitpflegen muss, ohne dass ein Test sie erzwingt — sie wird vergessen,
und der Fehler zeigt sich nicht (die Suche läuft, nur mit Zeilen, die sie nicht lesen dürfte). Für den
heutigen Bestand, in dem jede Anhebung additiv war, wäre die Untergrenze dauerhaft `1`; die Konstante
trüge also nur eine hypothetische Bedingung.

### Weg 2 (gewählt): Kein Versionsfilter in der Suche

Der Suchpfad liest `chunk_full_text` ohne Bedingung an `content_tsv_version`. Die Konstante steuert
weiterhin Füllstand und Reindex-Auswahl. Eine Zeile alter Fassung bleibt lexikalisch findbar; ihr
fehlen nur die Lexeme, die die neuere Fassung hinzufügt, bis der Pipeline-Nachzug sie neu schreibt.

Vorteil: Die Zusage aus „Nachlauf im Betrieb" gilt für beide Hälften der Suche; ein Bump macht den
Index nicht schlagartig unsichtbar, sondern nur schrittweise besser. Kein neuer Zustand, keine zweite
Konstante. Nachteil: Die Zusage „additiv" ist eine Bedingung, die der Code nicht prüfen kann — sie
steht als Vertrag im Javadoc der Konstante und hier.

### Weg 3: Zusage in der Doku einschränken

Das Verhalten bleibt, die Dokumentation sagt ausdrücklich: „verfügbar" gilt für die Vektorhälfte; die
lexikalische Hälfte ist bis zum Nachzug weg, und die Zustandsübersicht benennt den Übergangszustand.

Vorteil: Keine Codeänderung. Nachteil: Die eingeschränkte Zusage wäre für den Betrieb die schlechtere
— ein Volltextindex, der nach jedem Deployment mit Lexemänderung für Stunden ausfällt, ist genau die
Überraschung, die die vier Zusagen des Nachlaufs verhindern sollen. Und der Preis wäre ohne Not
gezahlt: Für einen additiv gewachsenen Bestand gibt es keinen Grund, alte Zeilen auszuschließen.

## Entscheidung

**Weg 2.** `FullTextChunkSearch` liest `chunk_full_text` ohne Versionsfilter.
`CURRENT_TSV_VERSION` steuert ausschließlich den Füllstand (`FullTextIndexFillStateService`,
`FullTextIndexFillState`) und die Reindex-Auswahl (`PipelineReindexService`).

Damit bedeutet der Zähler `fullTextOutdatedChunks` in der Zustandsübersicht (bis #1429
`fullTextMissingChunks`): **Abschnitte mit einer `chunk_full_text`-Zeile alter Fassung — der
Pipeline-Nachzug steht aus.** Ein solcher Abschnitt ist lexikalisch weiterhin findbar, nur ohne die
jüngsten Lexeme. Seit #1429 zählt dieses Feld ausschließlich den Fassungs-Rückstand: Vektor- und
Volltextzeile eines Chunks entstehen in derselben Transaktion (#1047), ein Abschnitt ohne
`chunk_full_text`-Zeile ist kein Zustand, den das Modell noch kennt.

Keine Migration, kein Backfill, keine Sonderbehandlung von Altdaten: Es gibt heute keine Bestandszeilen
alter Fassung, die anders zu behandeln wären — jede Installation, die Volltext hatte, hat ihn seit
#1047 in derselben Transaktion wie den Vektor geschrieben und beim jeweiligen Bump nachgezogen.

## Konsequenzen

- **Die Zusage „Suche bleibt während des Nachlaufs verfügbar" gilt für beide Hälften.** Nach einer
  Anhebung liefert der lexikalische Pfad weiterhin Treffer; sie werden mit fortschreitendem Nachzug
  um die neuen Lexeme reicher. Der Mischzustand ist je Bibliothek am Füllstand erkennbar.
- **Bedingung für jede künftige Anhebung: Sie muss additiv sein.** Eine Änderung, die die Lexemform
  bestehender Zeilen **bricht** — etwa ein Wechsel der Textsuchkonfiguration
  (`FullTextChunkStore.TEXT_SEARCH_CONFIGURATION`, heute `german`), ein anderes Stemming oder eine
  andere Zerlegung —, erzeugt Zeilen, deren Lexeme mit einer nach neuer Regel gebauten `tsquery`
  nicht mehr übereinstimmen. Eine solche Anhebung **muss den Versionsfilter im Suchpfad wieder
  einführen** (als Gleichheit oder als Untergrenze nach Weg 1), bevor sie ausgeliefert wird. Die
  Bedingung steht im Javadoc der Konstante; der PR, der die Konstante anhebt, benennt, ob die
  Änderung additiv ist.
- **Der Füllstand meldet weiterhin jede Zeile alter Fassung als Rückstand.** Das ist gewollt: Er
  zeigt, was der Nachzug noch zu tun hat, nicht, was unauffindbar ist. Der Hinweis „Nachzug
  ausstehend" bleibt damit nach einem Bump aktiv, bis der Nachzug durch ist.
- **Der Test `FullTextChunkSearchIntegrationTest#aRowBelowTheCurrentTsvVersionIsStillFound`** hält
  das Verhalten fest; der frühere Test, der die Unsichtbarkeit alter Zeilen prüfte, ist durch ihn
  ersetzt.
