package io.opaa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.opaa.indexing.metadata.MetadataFilter;
import java.util.List;

/**
 * A single golden-dataset case, as defined in {@code eval/golden/comic-characters.json} (see
 * docs/features/search-quality-evaluation.md, "Golden Dataset"). Deliberately mirrors only the
 * fields the harness needs; unknown fields are ignored so a future field addition to the dataset
 * does not break deserialization here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenCase(
    String id,
    String domain,
    String query,
    @JsonProperty("expected_documents") List<String> expectedDocuments,
    String category,
    String difficulty,
    String language,
    String type,
    // Issue #721: optional, frozen literal text excerpt the answer is known to sit in — the
    // chunk-level ground truth for multi-chunk domains. Deliberately a literal span, not a chunk
    // index (see ChunkAnswerSpanMetrics' class Javadoc for why): absent for every comic-characters
    // case (Ein-Chunk-Invariante makes a chunk-level metric meaningless there), so this field
    // defaults to null on the unmodified comic-characters.json — the schema extension this issue
    // requires.
    //
    // Issue #1043 settles this field's open question for cases with more than one expected
    // document (docs/features/retrieval-benchmark.md, "Offene Punkte" 4): it stays one span per
    // *case*, and a case with several expected documents carries none at all. See
    // GoldenCaseCuration#SINGLE_DOCUMENT_ANSWER_SPAN_RULE for the reasoning and the enforcement.
    @JsonProperty("answer_span") String answerSpan,
    // docs/features/retrieval-benchmark.md §5 "Zustandsfelder": the last deliberately accepted
    // state of this case, separately for each measurement path, so a red case is distinguishable
    // from a regression without asking anyone's memory. Required on every case of every committed
    // dataset (GoldenCaseCurationTest); null only in synthetic test cases.
    @JsonProperty("expected_state") ExpectedStateByPath expectedState,
    // Issue #1070 (Teil 2): the core-field filter both measurement paths apply for this case -
    // null for a case that is asked without one. The Dokumentart codes are production vocabulary
    // codes, never the corpus' frontmatter raw values.
    @JsonProperty("filter") Filter filter,
    // Issue #1070: the committed reason why a metadata_filter case carries no filter although its
    // class exists to measure one (e.g. a validity question no core field can express).
    @JsonProperty("filter_note") String filterNote,
    // Issue #1070: the document the filter exists to keep out - the other Fassung, the wrong
    // Dokumentart. MetadataFilterAudit reports "Filter greift nicht" when it is still in the
    // window.
    @JsonProperty("confusable_document") String confusableDocument,
    // Issue #1070: names the filtered field the expected document has no value for
    // ("documentType" or "documentDate"), marking a Leerwert-Regel case: the filter must keep the
    // document by that rule, and MetadataFilterAudit reports "Filter greift zu stark" when it does
    // not.
    @JsonProperty("no_value_field") String noValueField) {

  /** The constructor of a case without the #1070 filter fields, as every earlier dataset has. */
  public GoldenCase(
      String id,
      String domain,
      String query,
      List<String> expectedDocuments,
      String category,
      String difficulty,
      String language,
      String type,
      String answerSpan,
      ExpectedStateByPath expectedState) {
    this(
        id,
        domain,
        query,
        expectedDocuments,
        category,
        difficulty,
        language,
        type,
        answerSpan,
        expectedState,
        null,
        null,
        null,
        null);
  }

  /**
   * The case's filter as the domain type both search paths take; {@link MetadataFilter#NONE} if the
   * case declares none.
   */
  public MetadataFilter metadataFilter() {
    return filter == null ? MetadataFilter.NONE : filter.toMetadataFilter();
  }

  /** Whether this case is measured with a filter at all. */
  public boolean isFiltered() {
    return filter != null && !metadataFilter().isEmpty();
  }

  /**
   * The JSON shape of a case's filter (issue #1070): a list of Dokumentart codes and an inclusive
   * ISO date window, either half optional. Mirrors the API's {@code MetadataFilter} schema so a
   * golden case says exactly what a person would set in the filter popover. A misspelled key is not
   * rejected here but by {@link GoldenCaseCuration}: it leaves the filter without a condition,
   * which that check refuses.
   */
  public record Filter(
      @JsonProperty("documentType") List<String> documentType,
      @JsonProperty("documentDateFrom") String documentDateFrom,
      @JsonProperty("documentDateTo") String documentDateTo) {

    public MetadataFilter toMetadataFilter() {
      return MetadataFilter.parse(documentType, documentDateFrom, documentDateTo);
    }
  }

  /**
   * The declared state of a case on each measurement path ({@code expected_state.raw_vector},
   * {@code expected_state.pipeline}). Each path's audit compares its measurement against its own
   * entry only, so a lasting path asymmetry is two declared states, not an excused deviation.
   */
  public record ExpectedStateByPath(
      @JsonProperty("raw_vector") PathState rawVector,
      @JsonProperty("pipeline") PathState pipeline) {}

  /**
   * One path's declared state, the date it was last deliberately set and the reason: for {@code
   * known_gap} the missing building block, for {@code solved} the change that solved it.
   */
  public record PathState(
      @JsonProperty("state") ExpectedState state,
      @JsonProperty("since") String since,
      @JsonProperty("reason") String reason) {}

  /**
   * The two states a curated case can be in (docs/features/retrieval-benchmark.md §5,
   * "Zustandsfelder"). A {@code KNOWN_GAP} case is not a defect of the dataset — it is the point of
   * the dataset: the measured, dated statement that a named building block is missing.
   *
   * <p>"Solved" is one criterion, applied to each path's own window ({@link
   * ExpectedStateAudit#isSolved}): every expected document inside the window <b>and</b> an expected
   * document at rank 1. Without the rank-1 half a {@code metadata_filter} case would count as
   * solved while the wrong Fassung sits above the right one.
   */
  public enum ExpectedState {
    @JsonProperty("solved")
    SOLVED,
    @JsonProperty("known_gap")
    KNOWN_GAP
  }
}
