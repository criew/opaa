# Migration 016: Bibliothekszuordnung in den Chunk-Metadaten nachtragen

Trägt `library_id` und `organization_id` in die Metadaten der Chunks im `vector_store` nach — die
Filterachse, auf der die rechtebewusste Vektorsuche (#202) arbeitet. Betroffen sind Bestände, die
vor deren Einführung indiziert wurden.

- **Changeset:** [`016-backfill-vector-store-library-metadata.yaml`](../../backend/src/main/resources/db/changelog/changes/016-backfill-vector-store-library-metadata.yaml)
- **Test:** `Migration016VectorStoreLibraryMetadataTest`
- **Issue:** [#408](https://github.com/criew/opaa/issues/408)

## Warum die Lücke entstanden ist

[Migration 012](./012-knowledge-library.md) hat `documents.library_id` per Backfill befüllt. Die
Chunks liegen aber nicht in `documents`, sondern als eigene Zeilen im `vector_store`, und ihre
Zuordnung steht dort in einer JSON-Spalte. Diese Spalte hat die Migration nicht angefasst.

Seither schreibt `FileProcessingService#storeChunks` beide Felder auf jeden neuen Chunk. Für alles,
was vorher indiziert wurde, fehlen sie — dauerhaft, denn nichts schreibt einen bestehenden Chunk
neu.

## Warum das ohne diese Migration niemandem auffällt

`QueryService#libraryFilter` filtert genau auf `library_id`. Ein Chunk ohne das Feld wird von jeder
Suche verworfen, unabhängig davon, welche Rechte die fragende Person hat. Sichtbar ist das nirgends:

- Die Dokumente stehen weiter als `INDEXED` in der Übersicht, mit korrekter Chunk-Zahl.
- Die Zeile in `documents` trägt ihre Bibliothek, ist also unauffällig.
- Eine Antwort ohne Fundstellen ist von „dazu gibt es im Bestand nichts" nicht zu unterscheiden.

Auf der Testinstanz hat genau das einen vollständig indizierten Korpus aus 1449 Dokumenten
unbrauchbar gemacht, ohne eine einzige Fehlermeldung.

## Was das Changeset tut

Ein `UPDATE`, verbunden über `metadata->>'document_id'` — das Feld hat schon die erste Fassung von
`storeChunks` geschrieben. Übernommen werden `library_id` und `organization_id` aus der Zeile in
`documents`, und zwar nur für Chunks, die noch kein `library_id` führen.

Zwei Fälle bleiben bewusst unberührt:

| Fall | Verhalten | Grund |
| --- | --- | --- |
| Chunk führt bereits ein `library_id` | unverändert | Ein Überschreiben würde den Chunk in eine andere Bibliothek verschieben — eine Rechteänderung, keine Reparatur |
| `document_id` zeigt auf kein Dokument mehr | unverändert | Es gibt keine Bibliothek, die man eintragen könnte; eine zu raten hieße, Zugriff auf Verdacht zu gewähren |

## Warum eine Precondition nötig ist

`vector_store` steht nicht unter Liquibase-Kontrolle, sondern wird von Spring AI beim Start angelegt
(`PgVectorStore#initializeSchema`, `initialize-schema: true`). Auf einer frischen Installation läuft
Liquibase, **bevor** es die Tabelle gibt — ohne `tableExists`-Precondition mit `onFail: MARK_RAN`
würde das Changeset dort den gesamten Anwendungsstart scheitern lassen.

`MARK_RAN` ist in diesem Fall auch inhaltlich richtig: Auf einer frischen Installation gibt es
keinen Altbestand, und jeder danach geschriebene Chunk trägt die Felder ohnehin.

Aus derselben Quelle stammt eine zweite Eigenheit: `metadata` ist vom Typ `json`, nicht `jsonb` —
Spring AI legt die Spalte fest so an. Daher der Umweg über `::jsonb` für die Verkettung und zurück.

## Trockenlauf mit Mengengerüst

Verfahren wie in [Migration 012](./012-knowledge-library.md#trockenlauf-mit-mengengerüst): Kopie der
Zieldatenbank in einen Wegwerf-Container, Anwendung dagegen starten, Zahlen vergleichen.

Vor der Migration:

```sql
SELECT count(*) FROM vector_store;
SELECT count(*) FILTER (WHERE NOT jsonb_exists(metadata::jsonb, 'library_id')) FROM vector_store;
```

Nach der Migration:

```sql
SELECT count(*) FROM vector_store;  -- muss unverändert sein
SELECT count(*) FILTER (WHERE NOT jsonb_exists(metadata::jsonb, 'library_id')) FROM vector_store;
```

Die zweite Zahl muss nach dem Lauf auf die Zahl der Waisen gesunken sein — im Regelfall `0`. Welche
das sind, zeigt:

```sql
SELECT v.metadata->>'document_id', count(*)
FROM vector_store v
LEFT JOIN documents d ON d.id::text = v.metadata->>'document_id'
WHERE d.id IS NULL
GROUP BY 1;
```

Die entscheidende Prüfung ist aber nicht die Zahl, sondern der Filter selbst — dass die Suche die
Chunks jetzt findet:

```sql
SELECT count(*) FROM vector_store
WHERE metadata::jsonb @@ '($."library_id" == "<bibliotheks-uuid>")'::jsonpath;
```

Das ist genau das Prädikat, das `PgVectorFilterExpressionConverter` aus dem Ausdruck von
`QueryService#libraryFilter` erzeugt. `Migration016VectorStoreLibraryMetadataTest` prüft dagegen,
nicht gegen das bloße Vorhandensein des Feldes: Die Chunks trugen ja schon vorher Metadaten, jede
schwächer formulierte Zusicherung wäre auch auf dem kaputten Stand grün gewesen.

**Einschränkung dieses PRs:** Wie bei den Migrationen 008 und 012 war kein Abgleich gegen einen
produktiven Datenbestand möglich (Projekt vor 1.0). Der Nachweis stammt aus zwei Quellen: dem
Testcontainer-Lauf mit synthetischen Alt-Zeilen und dem realen Bestand der Testinstanz
`opaa.ewerlin.com`, auf dem dieselbe Anweisung 1449 Chunks nachgetragen und den Korpus wieder
auffindbar gemacht hat.

## Rollback

Bewusst ein No-op. Die Felder wieder zu entfernen träfe auch jeden Chunk, den `storeChunks` seither
regulär mit ihnen geschrieben hat — an den Metadaten ist beides nicht zu unterscheiden. Das Ergebnis
wäre schlechter als der Ausgangszustand, weil dann auch der neue Bestand unauffindbar würde.

Dieselbe Begründung wie bei `012-backfill-document-library-id`: Ein No-op, damit der Rollback der
umgebenden, verlustfreien Changesets nicht blockiert wird.

## Was danach noch offen ist

Die Migration repariert einen bestehenden Bestand. Sie ändert nichts daran, dass die Indexierung
ihre Dokumente in die System-Bibliothek legt, die für die Suche niemandem gehört — das ist
[#406](https://github.com/criew/opaa/issues/406).
