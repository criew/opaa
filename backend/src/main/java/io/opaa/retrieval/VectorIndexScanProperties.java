package io.opaa.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How far one vector search may walk the HNSW index to fill fetch-k candidates within its filter
 * (docs/handbuch/suche.md Abschnitt 10.3). An Ebene-1 value like {@link QueryProperties}.
 *
 * @param maxScanTuples upper bound on the index entries one search visits ({@code
 *     hnsw.max_scan_tuples}). A search whose filter keeps too few of them ends with a short list,
 *     which the explanation protocol names. Default 20000, pgvector's own default; pgvector's scan
 *     memory limit ({@code hnsw.scan_mem_multiplier}) can end a scan earlier.
 */
@ConfigurationProperties(prefix = "opaa.query.vector-index")
public record VectorIndexScanProperties(@DefaultValue("20000") int maxScanTuples) {

  public VectorIndexScanProperties {
    if (maxScanTuples <= 0) {
      throw new IllegalArgumentException("maxScanTuples must be positive, got " + maxScanTuples);
    }
  }
}
