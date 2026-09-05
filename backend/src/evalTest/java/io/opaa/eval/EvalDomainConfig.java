package io.opaa.eval;

/**
 * Per-domain configuration for the retrieval harness (issue #721, ADR-0011/ADR-0012 Nachtrag).
 * {@code RetrievalEvaluationHarnessTest} and {@code checkRetrievalBaseline} used to hardcode
 * `comic-characters` at four places (corpus directory, manifest, golden dataset, baseline file) —
 * this record is the single place those four names and the domain's chunk-count expectation live,
 * so a future domain (#234) supplies its own instance instead of a second hardcoded copy.
 *
 * @param name the domain name, matching {@code GoldenCase#domain()} and the corpus directory under
 *     {@code eval/corpus/<name>/}.
 * @param goldenDatasetFileName file name under {@code eval/golden/}.
 * @param baselineFileName file name under {@code eval/baseline/}.
 * @param chunkCountExpectation what this domain's corpus is expected to look like after the real
 *     splitter runs (ADR-0010 Nachtrag) — {@code comic-characters} keeps the original, unchanged
 *     Ein-Chunk-Invariante.
 * @param documentTopK the document-bound search window (ADR-0012 Nachtrag) — the number of
 *     *distinct documents* the ranking metrics are computed over, always 10 for both domains today
 *     (unchanged from the pre-#721 chunk-bound window, which is why it keeps the same name and
 *     value the class Javadoc of {@link RetrievalMetrics} already documented).
 * @param maxChunksPerDocument a per-domain upper-bound estimate of chunks per document, used only
 *     to size the chunk-bound search ({@link DocumentRanking#documentTopKWindowSize}) so that
 *     deduplication has enough chunks to reach {@code documentTopK} distinct documents. For {@code
 *     comic-characters} this is 1 (the Ein-Chunk-Invariante guarantees it), which makes {@code
 *     chunkTopK == documentTopK} — the mechanism behind this PR's bit-identical baseline claim.
 */
public record EvalDomainConfig(
    String name,
    String goldenDatasetFileName,
    String baselineFileName,
    ChunkCountExpectation chunkCountExpectation,
    int documentTopK,
    int maxChunksPerDocument) {

  public EvalDomainConfig {
    if (documentTopK <= 0) {
      throw new IllegalArgumentException("documentTopK must be positive, got " + documentTopK);
    }
    if (maxChunksPerDocument <= 0) {
      throw new IllegalArgumentException(
          "maxChunksPerDocument must be positive, got " + maxChunksPerDocument);
    }
  }

  /** The chunk-bound search window this domain needs — see the class Javadoc. */
  public int chunkTopK() {
    return DocumentRanking.documentTopKWindowSize(documentTopK, maxChunksPerDocument);
  }

  /**
   * This domain's pipeline-path baseline file under {@code eval/baseline/} (issue #1040). Derived
   * from {@link #baselineFileName()} with a fixed prefix rather than declared separately: the two
   * paths are not interconvertible, so a pipeline baseline must never be able to land on a
   * raw-vector baseline's name — deriving it makes that collision impossible to introduce by a typo
   * in a future domain declaration. Guarded by {@code PipelinePathIsolationTest}.
   */
  public String pipelineBaselineFileName() {
    return "pipeline-" + baselineFileName;
  }

  /**
   * The frozen comic-characters domain (issues #225–#228): one chunk per document, unchanged from
   * before #721 in every observable way.
   */
  public static final EvalDomainConfig COMIC_CHARACTERS =
      new EvalDomainConfig(
          "comic-characters",
          "comic-characters.json",
          "comic-characters.json",
          ChunkCountExpectation.exactlyOneChunk(),
          10,
          1);

  /**
   * The city-landmarks domain (issue #234): deliberately multi-chunk documents (200 European
   * cities, minimum 3 chunks each at the application's default {@code chunk-size=1000}).
   *
   * <p>Chunk-count distribution measured by {@code CityLandmarksChunkSizeDryRunTest}: minimum 3,
   * median 8, maximum 11 through the naive, heading-agnostic {@code TikaFallbackPipeline}/{@code
   * ChunkingService} cut every {@code .md} document ran through before #1103. Since #1103, Markdown
   * is routed to the heading-aware {@code MarkdownDocumentPipeline} instead — every corpus document
   * starts with a YAML frontmatter block the splitter now drops rather than turning into a
   * headingless leading chunk (see that pipeline's own Javadoc), and each remaining {@code
   * #}/{@code ##}/{@code ###} heading now cuts a new chunk instead of folding into a larger one.
   * Re-measured after that change: minimum 5, median 15, maximum 17. {@code
   * maxChunksPerDocument=20} is a deliberately conservative upper bound above the re-measured
   * maximum (17), consistent with {@link RetrievalEvaluationHarnessTest}'s own runtime check that
   * this value is never undersized (issue #721 review, Nit 4).
   */
  public static final EvalDomainConfig CITY_LANDMARKS =
      new EvalDomainConfig(
          "city-landmarks",
          "city-landmarks.json",
          "city-landmarks.json",
          ChunkCountExpectation.atLeast(3),
          10,
          20);

  /**
   * The verwaltung domain (issues #1042/#1043): 70 German-language administrative documents of the
   * fictional municipality "Kalkstadt", multi-chunk like {@code city-landmarks}.
   *
   * <p>Chunk-count distribution measured by {@code VerwaltungChunkSizeDryRunTest}: minimum 3,
   * median 3, maximum 4 through the naive, heading-agnostic {@code TikaFallbackPipeline}/{@code
   * ChunkingService} cut every {@code .md} document ran through before #1103. Since #1103, Markdown
   * is routed to the heading-aware {@code MarkdownDocumentPipeline} instead — see {@link
   * #CITY_LANDMARKS}'s Javadoc for why. Re-measured after that change: minimum 10, median 15,
   * maximum 16. {@code maxChunksPerDocument=19} is a deliberately conservative upper bound above
   * the re-measured maximum (16), checked at runtime by the harness like every domain's.
   *
   * <p>Unlike the other two domains, this corpus is smaller than one might expect a search window
   * to need: 72 documents against {@code documentTopK=10} is still comfortably above the window, so
   * the harness's "every query reached the full document window" assertion holds here too.
   */
  public static final EvalDomainConfig VERWALTUNG =
      new EvalDomainConfig(
          "verwaltung",
          "verwaltung.json",
          "verwaltung.json",
          ChunkCountExpectation.atLeast(3),
          10,
          19);
}
