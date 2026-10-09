package io.opaa.retrieval.search;

import com.pgvector.PGvector;
import io.opaa.retrieval.VectorIndexScanProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The vector half of the hybrid search (docs/handbuch/suche.md, Stufe 4): the nearest chunks to the
 * query within the request's filter, best first, in the {@link Document} shape of {@code
 * PgVectorStore#similaritySearch} (same filter translation, distance, score and threshold).
 *
 * <p>The filter sits in the query, never on its result. With an HNSW index it is evaluated per
 * index entry, so the search runs as an iterative index scan ({@code hnsw.iterative_scan =
 * relaxed_order}, pgvector 0.8 or later) that keeps walking until {@code topK} rows pass or {@link
 * VectorIndexScanProperties#maxScanTuples} entries were visited; the outer query restores the exact
 * distance order the relaxed scan gives up. The settings are set after the query embedding exists,
 * so no connection is held during the embedding call. They last until the transaction ends: this
 * search's own, or a caller's it joins.
 */
@Component
public class VectorChunkSearch {

  /** pgvector's default {@code hnsw.ef_search}; the candidate list is never narrower. */
  static final int DEFAULT_EF_SEARCH = 40;

  private static final String SCAN_SETTINGS_SQL =
      "SELECT set_config('hnsw.iterative_scan', 'relaxed_order', true),"
          + " set_config('hnsw.ef_search', ?, true),"
          + " set_config('hnsw.max_scan_tuples', ?, true)";

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final EmbeddingModel embeddingModel;
  private final ObjectMapper objectMapper;
  private final VectorIndexScanProperties scanProperties;
  private final PgDistanceType distanceType;
  private final String qualifiedTableName;
  private final PgVectorFilterExpressionConverter filterConverter =
      new PgVectorFilterExpressionConverter();

  VectorChunkSearch(
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      EmbeddingModel embeddingModel,
      ObjectMapper objectMapper,
      VectorIndexScanProperties scanProperties,
      @Value("${spring.ai.vectorstore.pgvector.distance-type:cosine_distance}")
          PgDistanceType distanceType,
      @Value("${opaa.database.schema}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setReadOnly(true);
    this.embeddingModel = embeddingModel;
    this.objectMapper = objectMapper;
    this.scanProperties = scanProperties;
    this.distanceType = distanceType;
    this.qualifiedTableName = schemaName + "." + tableName;
  }

  /**
   * Up to {@code request.getTopK()} chunks within the request's filter whose similarity reaches its
   * threshold, nearest first. Fewer when fewer qualify, when the index scan reached {@link
   * VectorIndexScanProperties#maxScanTuples} or pgvector's scan memory limit first, or when the
   * HNSW graph leaves matching nodes unreachable from its entry point (approximate; rare with real
   * embeddings).
   */
  public List<Document> similaritySearch(SearchRequest request) {
    PGvector queryEmbedding = new PGvector(embeddingModel.embed(request.getQuery()));
    String sql = searchSql(request);
    double maxDistance = 1 - request.getSimilarityThreshold();
    int efSearch = Math.max(DEFAULT_EF_SEARCH, request.getTopK());
    return transactionTemplate.execute(
        status -> {
          jdbcTemplate.query(
              SCAN_SETTINGS_SQL,
              rs -> {},
              String.valueOf(efSearch),
              String.valueOf(scanProperties.maxScanTuples()));
          return jdbcTemplate.query(
              sql,
              (rs, rowNum) -> {
                float distance = rs.getFloat("distance");
                Map<String, Object> metadata =
                    new LinkedHashMap<>(
                        FullTextChunkSearch.readMetadata(objectMapper, rs.getString("metadata")));
                metadata.put(DocumentMetadata.DISTANCE.value(), distance);
                return Document.builder()
                    .id(rs.getString("id"))
                    .text(rs.getString("content"))
                    .metadata(metadata)
                    .score(1.0 - distance)
                    .build();
              },
              queryEmbedding,
              request.getTopK(),
              maxDistance);
        });
  }

  /**
   * The nearest rows within the filter, materialized so the index scan stops at {@code LIMIT}; the
   * threshold and the exact order apply outside it. Cutting the k nearest at the threshold yields
   * the same rows as {@code PgVectorStore}'s threshold inside the scan, without letting a strict
   * threshold drive the scan to its tuple limit.
   */
  private String searchSql(SearchRequest request) {
    String filter =
        request.getFilterExpression() == null
            ? ""
            : " WHERE " + filterConverter.convertExpression(request.getFilterExpression());
    // PgVectorStore reports inner-product distance shifted by one; ordering by the raw operator
    // keeps the index usable and the order unchanged.
    String distance =
        distanceType == PgDistanceType.NEGATIVE_INNER_PRODUCT
            ? "(1 + raw_distance)"
            : "raw_distance";
    return "WITH nearest AS MATERIALIZED ("
        + "SELECT id, content, metadata, embedding "
        + distanceType.operator
        + " ? AS raw_distance FROM "
        + qualifiedTableName
        + filter
        + " ORDER BY raw_distance LIMIT ?) "
        + "SELECT id, content, metadata, "
        + distance
        + " AS distance FROM nearest WHERE "
        + distance
        // "+ 0": PostgreSQL 17+ would otherwise take the CTE's ORDER BY as already satisfied and
        // skip this sort, keeping the relaxed scan's only approximate order.
        + " < ? ORDER BY "
        + distance
        + " + 0";
  }
}
