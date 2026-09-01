package io.opaa.query;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The lexical search path's own switch, an Ebene-1 value in the sense of
 * docs/features/hybrid-retrieval.md#konfigurations-ebenenmodell: overridable for development and
 * for a deliberate experiment, absent from every administration surface.
 *
 * <p>Separate from {@link QueryProperties} rather than a field on it: the path's candidate count is
 * {@link QueryProperties#fetchK}, identical to the vector path's, and this switch is not a measured
 * dimension of the retrieval benchmark's run configuration. Adding it to the measured record would
 * claim it is one.
 *
 * @param enabled whether {@link RetrievalStageName#FULL_TEXT_SEARCH} runs its query at all. Default
 *     {@code true}: the path is built to be exercised, and until #1049 wires it into the fusion its
 *     results reach the explanation protocol only, where they are what makes the path diagnosable
 *     before it can influence an answer. Set to {@code false} to spare the query entirely - the
 *     stage then behaves exactly as if it were switched off in {@link
 *     RetrievalPipelineProperties#disabledStages()}, except that it still appears in the protocol
 *     saying so.
 */
@ConfigurationProperties(prefix = "opaa.query.full-text-search")
public record FullTextSearchProperties(@DefaultValue("true") boolean enabled) {

  /** The shipped configuration: the lexical path runs. */
  public static FullTextSearchProperties shipped() {
    return new FullTextSearchProperties(true);
  }
}
