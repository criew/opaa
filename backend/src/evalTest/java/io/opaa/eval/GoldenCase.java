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
    String type) {}
