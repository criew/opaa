package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.FullTextBackfillService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The lexical search path against a real PostgreSQL (#1048, docs/features/hybrid-retrieval.md,
 * Arbeitspaket 2). Nothing here is mocked away that the assertions are about: the {@code tsvector}
 * is built by the production write path, the query is the production query, and the permission
 * filter is enforced by the database - the specification demands exactly that ("Der Filter wird in
 * einem Test abgesichert, der ihn tatsächlich ausführt. Ein Test, der den Volltextpfad mockt, prüft
 * den Filter nicht").
 *
 * <p>Chunks are written via {@link VectorStore#add} and then indexed by {@link
 * FullTextBackfillService}, which uses the same {@code FullTextChunkStore#indexChunks} the ingest
 * path uses - so what is searched here is byte-identical to what production stores.
 */
@OpaaIndexingIntegrationTest
class FullTextChunkSearchIntegrationTest {

  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FullTextBackfillService backfillService;
  @Autowired private FullTextChunkSearch fullTextChunkSearch;

  private final UUID readableLibrary = UUID.randomUUID();
  private final UUID forbiddenLibrary = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text, chunk_full_text_skip");
  }

  /**
   * ADR-0008 §5: the readable-library filter is part of the query. The forbidden chunk is the
   * <em>better</em> match for the question, so a path that filtered afterwards - or not at all -
   * would return it here.
   */
  @Test
  void aChunkOutsideTheReadableLibrariesIsNeverReturned() {
    UUID readable = seed(readableLibrary, "Die Gebührenbefreiung ist auf Antrag zu gewähren.");
    seed(
        forbiddenLibrary,
        "Gebührenbefreiung wegen Bedürftigkeit: Gebührenbefreiung nach Antrag, Gebührenbefreiung"
            + " im Einzelfall.");
    backfillService.backfillBatch(100);

    List<Document> hits =
        fullTextChunkSearch.search("Gebührenbefreiung", Set.of(readableLibrary), 25);

    assertThat(hits).extracting(Document::getId).containsExactly(readable.toString());
  }

  /** An empty set of readable libraries means nothing is searchable, never "everything". */
  @Test
  void anEmptyReadableSetReturnsNothing() {
    seed(readableLibrary, "Die Gebührenbefreiung ist auf Antrag zu gewähren.");
    backfillService.backfillBatch(100);

    assertThat(fullTextChunkSearch.search("Gebührenbefreiung", Set.of(), 25)).isEmpty();
  }

  /**
   * The identifier protection against the known weakness of {@code ts_rank}: it counts term
   * frequency and knows no inverse document frequency, so a chunk that merely repeats the
   * question's words often outranks the one chunk that actually carries the paragraph. The
   * undecomposed identifier lexeme carries weight {@code A} against the body text's {@code D} and
   * settles that - which is what "§ 34 und § 35 bleiben unterscheidbar" means in a ranked list with
   * competitors, not merely in a match.
   */
  @Test
  void anExactParagraphMatchOutranksAChunkThatOnlyRepeatsTheQuestionsWords() {
    UUID paragraph = seed(readableLibrary, "Für Vorhaben im Außenbereich gilt § 35 BauGB.");
    UUID repetitive =
        seed(
            readableLibrary,
            "Vorhaben und wieder Vorhaben: das BauGB regelt Vorhaben im Außenbereich, Vorhaben im"
                + " Außenbereich werden geprüft, und die Frist für Vorhaben beträgt 35 Werktage im"
                + " Außenbereich.");
    backfillService.backfillBatch(100);

    List<Document> hits =
        fullTextChunkSearch.search(
            "Vorhaben im Außenbereich nach § 35 BauGB", Set.of(readableLibrary), 25);

    assertThat(hits)
        .extracting(Document::getId)
        .containsExactly(paragraph.toString(), repetitive.toString());
    assertThat(hits.get(0).getScore()).isGreaterThan(hits.get(1).getScore());
  }

  /**
   * The same for a file number, which the German analysis chain damages far worse than a paragraph:
   * it reads {@code 1023/24.NW} as one token of its own, so a question spelling the number
   * differently, or a chunk repeating the surrounding words, wins on sheer frequency. Carried as an
   * undecomposed lexeme the number separates two proceedings of the same court in the same year.
   */
  @Test
  void anExactFileNumberOutranksAChunkThatOnlyRepeatsTheQuestionsWords() {
    UUID wanted = seed(readableLibrary, "Beschluss im Verfahren 4 K 1023/24.NW.");
    UUID repetitive =
        seed(
            readableLibrary,
            "Verfahren, Verfahren und nochmals Verfahren: im Verfahren 4 K 1024/24.NW und in"
                + " weiteren Verfahren des Jahres 24 wurde im Verfahren entschieden.");
    backfillService.backfillBatch(100);

    List<Document> hits =
        fullTextChunkSearch.search("Verfahren 4 K 1023/24.NW", Set.of(readableLibrary), 25);

    assertThat(hits).extracting(Document::getId).first().isEqualTo(wanted.toString());
    assertThat(hits).extracting(Document::getId).contains(repetitive.toString());
  }

  /** The path answers the #938 case it exists for: a literal term the vector path ranks away. */
  @Test
  void aLiteralTermIsFoundWhereverItStands() {
    UUID chunkId =
        seed(readableLibrary, "Von der Gebühr befreit ist, wer seine Bedürftigkeit nachweist.");
    backfillService.backfillBatch(100);

    List<Document> hits = fullTextChunkSearch.search("Bedürftigkeit", Set.of(readableLibrary), 25);

    assertThat(hits).extracting(Document::getId).containsExactly(chunkId.toString());
    assertThat(hits.get(0).getMetadata())
        .containsEntry(VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString());
  }

  /** German stemming still applies to ordinary words - the identifier lexemes sit next to it. */
  @Test
  void germanStemmingStillApplies() {
    UUID chunkId = seed(readableLibrary, "Die Satzung regelt die Befreiungen von der Gebühr.");
    backfillService.backfillBatch(100);

    assertThat(fullTextChunkSearch.search("Befreiung", Set.of(readableLibrary), 25))
        .extracting(Document::getId)
        .containsExactly(chunkId.toString());
  }

  /** A question without a single usable token must not become a query that matches everything. */
  @Test
  void aQuestionWithoutUsableTokensReturnsNothing() {
    seed(readableLibrary, "Die Satzung regelt die Befreiungen von der Gebühr.");
    backfillService.backfillBatch(100);

    assertThat(fullTextChunkSearch.search("...", Set.of(readableLibrary), 25)).isEmpty();
    assertThat(fullTextChunkSearch.search("Gebühr", Set.of(readableLibrary), 0)).isEmpty();
  }

  /**
   * The asymmetry the identifier protection lives or dies by, end to end: the document names its
   * file number behind a keyword ("mit dem Aktenzeichen BAU-DA-2/2024"), the question names it bare
   * ("Was regelt die Dienstanweisung BAU-DA-2/2024?"). Both sides must produce the same lexeme, or
   * the protection is inert on exactly the questions it exists for - and the competitor here, which
   * repeats the question's words and carries the neighbouring number, would win.
   */
  @Test
  void anAdministrativeFileNumberIsFoundThoughOnlyTheChunkNamesTheKeyword() {
    UUID wanted =
        seed(
            readableLibrary,
            "Diese Dienstanweisung mit dem Aktenzeichen BAU-DA-2/2024 regelt die Bearbeitung.");
    UUID neighbour =
        seed(
            readableLibrary,
            "Diese Dienstanweisung mit dem Aktenzeichen BAU-DA-1/2023 regelt die Bearbeitung;"
                + " die Dienstanweisung regelt ferner die Vertretung, und diese Dienstanweisung"
                + " regelt die Fristen.");
    backfillService.backfillBatch(100);

    List<Document> hits =
        fullTextChunkSearch.search(
            "Was regelt die Dienstanweisung BAU-DA-2/2024?", Set.of(readableLibrary), 25);

    assertThat(hits).extracting(Document::getId).first().isEqualTo(wanted.toString());
    assertThat(hits).extracting(Document::getId).contains(neighbour.toString());
  }

  /**
   * A question is user input and reaches {@code to_tsquery}, whose own syntax has operators. The
   * tokens are reduced to letters and digits before they get there, so no character of the question
   * can be read as one - neither to raise a syntax error nor to change what is matched.
   */
  @Test
  void tsqueryOperatorCharactersInTheQuestionAreHarmless() {
    UUID chunkId = seed(readableLibrary, "Die Satzung regelt die Gebühr.");
    backfillService.backfillBatch(100);

    List<Document> hits =
        fullTextChunkSearch.search(
            "Gebühr & ! ( ) : * <-> 'satzung' | 1=1 --", Set.of(readableLibrary), 25);

    assertThat(hits).extracting(Document::getId).containsExactly(chunkId.toString());
  }

  /**
   * Ties in {@code ts_rank} are the normal case, not an edge case: identically structured documents
   * of one office score the same for a question that names none of them. The order among them must
   * come from the chunk's content, never from its id - a chunk id is a fresh UUID per indexing run,
   * so an id-based tie-break reshuffles the tail of every such result between two runs over the
   * same corpus, which is exactly what costs the retrieval benchmark its run-to-run reproducibility
   * (#1049, ADR-0013).
   */
  @Test
  void chunksWithTheSameRankAreOrderedByTheirContentAndNotByTheirId() {
    // Same text, therefore the same ts_rank; inserted in the reverse of the expected order, so an
    // insertion- or id-ordered result would show it.
    seed(readableLibrary, "Die Satzung regelt die Gebühr.", "z-satzung.md", 1);
    seed(readableLibrary, "Die Satzung regelt die Gebühr.", "a-satzung.md", 0);
    backfillService.backfillBatch(100);

    List<Document> hits = fullTextChunkSearch.search("Satzung Gebühr", Set.of(readableLibrary), 25);

    assertThat(hits)
        .extracting(hit -> hit.getMetadata().get("file_name"))
        .containsExactly("a-satzung.md", "z-satzung.md");
  }

  /**
   * Within one document the tie-break is the chunk index, and it is compared as a number: as text,
   * chunk 10 would sort ahead of chunk 2 and the protocol would report an order the document does
   * not have.
   */
  @Test
  void chunksOfOneDocumentWithTheSameRankAreOrderedNumericallyByTheirIndex() {
    seed(readableLibrary, "Die Satzung regelt die Gebühr.", "satzung.md", 10);
    seed(readableLibrary, "Die Satzung regelt die Gebühr.", "satzung.md", 2);
    backfillService.backfillBatch(100);

    List<Document> hits = fullTextChunkSearch.search("Satzung Gebühr", Set.of(readableLibrary), 25);

    assertThat(hits).extracting(hit -> hit.getMetadata().get("chunk_index")).containsExactly(2, 10);
  }

  private UUID seed(UUID libraryId, String text) {
    Document chunk =
        new Document(
            text,
            Map.of(
                VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString(),
                VectorChunkStore.LIBRARY_ID_METADATA_KEY, libraryId.toString()));
    vectorStore.add(List.of(chunk));
    return UUID.fromString(chunk.getId());
  }

  private void seed(UUID libraryId, String text, String fileName, int chunkIndex) {
    vectorStore.add(
        List.of(
            new Document(
                text,
                Map.of(
                    VectorChunkStore.DOCUMENT_ID_METADATA_KEY,
                    documentId.toString(),
                    VectorChunkStore.LIBRARY_ID_METADATA_KEY,
                    libraryId.toString(),
                    "file_name",
                    fileName,
                    "chunk_index",
                    chunkIndex))));
  }
}
