package io.opaa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    // Issue #1043, docs/features/retrieval-benchmark.md §5 "Zustandsfelder": the last deliberately
    // accepted state of this case, so a red case is distinguishable from a regression without
    // asking anyone's memory. Null for a domain whose dataset predates the fields
    // (comic-characters, city-landmarks); required for every case of a domain that declares them,
    // enforced by GoldenCaseCuration.
    @JsonProperty("expected_state") ExpectedState expectedState,
    @JsonProperty("expected_state_since") String expectedStateSince,
    @JsonProperty("expected_state_reason") String expectedStateReason,
    // Issue #1043: optional, committed reason why this case's *measured* state deviates from its
    // declared one on purpose — a known_gap case today's ranking happens to solve without the
    // mechanism it measures, or one solved on only one of the two measurement paths. Such a case is
    // reported separately from an unexplained deviation (see ExpectedStateAudit), so a permanently
    // expected finding does not train readers to ignore the section.
    @JsonProperty("expected_state_exception") String expectedStateException) {

  /**
   * The two states a curated case can be in (docs/features/retrieval-benchmark.md §5,
   * "Zustandsfelder"). A {@code KNOWN_GAP} case is not a defect of the dataset — it is the point of
   * the dataset: the measured, dated statement that a named building block is missing.
   *
   * <p><b>What "solved" means is one criterion for both measurement paths</b>, defined once in
   * {@link ExpectedStateAudit#isSolved}: every expected document inside the path's own window
   * <b>and</b> an expected document at rank 1. A case is only committed as {@code SOLVED} when
   * <b>both</b> paths reach it under that criterion; the audit in each path's report then names
   * every case whose measured state differs from the declared one. The rank-1 half is not
   * decoration: without it, a {@code metadata_filter} case would count as solved while the wrong
   * Fassung sits above the right one — the very capability that class measures.
   */
  public enum ExpectedState {
    @JsonProperty("solved")
    SOLVED,
    @JsonProperty("known_gap")
    KNOWN_GAP
  }
}
