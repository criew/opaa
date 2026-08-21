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
    @JsonProperty("answer_span") String answerSpan) {}
