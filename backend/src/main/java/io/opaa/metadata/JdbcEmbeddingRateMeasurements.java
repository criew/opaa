package io.opaa.metadata;

import io.opaa.llm.EmbeddingInfoService;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

/**
 * {@link EmbeddingRateMeasurements} in {@code embedding_rate_measurements}, one row per embedding
 * model: a measurement of another model says nothing about the configured one, so a model change
 * starts from the configured estimate again. The model is read at each use, since it may be
 * installed after this bean is created.
 */
@Component
public class JdbcEmbeddingRateMeasurements implements EmbeddingRateMeasurements {

  private final JdbcOperations jdbc;
  private final Supplier<String> embeddingModel;

  @Autowired
  public JdbcEmbeddingRateMeasurements(
      JdbcOperations jdbc, EmbeddingInfoService embeddingInfoService) {
    this(jdbc, () -> embeddingInfoService.getEmbeddingInfo().model());
  }

  JdbcEmbeddingRateMeasurements(JdbcOperations jdbc, String embeddingModel) {
    this(jdbc, () -> embeddingModel);
  }

  private JdbcEmbeddingRateMeasurements(JdbcOperations jdbc, Supplier<String> embeddingModel) {
    this.jdbc = jdbc;
    this.embeddingModel =
        () -> {
          String model = embeddingModel.get();
          return model == null ? "" : model;
        };
  }

  @Override
  public void add(long chunks, long nanos) {
    jdbc.update(
        "INSERT INTO embedding_rate_measurements AS m (embedding_model, measured_chunks,"
            + " measured_nanos, updated_at) VALUES (?, ?, ?, clock_timestamp()) ON CONFLICT"
            + " (embedding_model) DO UPDATE SET measured_chunks = m.measured_chunks +"
            + " EXCLUDED.measured_chunks, measured_nanos = m.measured_nanos +"
            + " EXCLUDED.measured_nanos, updated_at = EXCLUDED.updated_at",
        embeddingModel.get(),
        chunks,
        nanos);
  }

  @Override
  public Totals totals() {
    List<Totals> rows =
        jdbc.query(
            "SELECT measured_chunks, measured_nanos FROM embedding_rate_measurements WHERE"
                + " embedding_model = ?",
            (rs, row) -> new Totals(rs.getLong(1), rs.getLong(2)),
            embeddingModel.get());
    return rows.isEmpty() ? Totals.NONE : rows.getFirst();
  }
}
