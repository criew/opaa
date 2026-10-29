package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.FakeEmbeddingModel;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.TwoInstanceRace;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two instances taking over the embedding model of an existing installation at once, on the real
 * vector table behind the schema lock {@code 213}: both succeed, one row is written, and the table
 * keeps the size of its column.
 */
@OpaaIntegrationTest
class EmbeddingModelTakeoverTwoInstanceIntegrationTest {

  @Autowired private EmbeddingModelService embeddingModels;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  private List<Map<String, Object>> before;

  @BeforeEach
  void removeTheInstalledModel() {
    before = jdbc.queryForList("SELECT * FROM embedding_model");
    jdbc.update("DELETE FROM embedding_model");
  }

  /** Every column of the row, so the generation the index record names comes back as well. */
  @AfterEach
  void restoreTheInstalledModel() {
    jdbc.update("DELETE FROM embedding_model");
    for (Map<String, Object> row : before) {
      List<String> columns = List.copyOf(row.keySet());
      jdbc.update(
          "INSERT INTO embedding_model ("
              + String.join(", ", columns)
              + ") VALUES ("
              + String.join(", ", Collections.nCopies(columns.size(), "?"))
              + ")",
          columns.stream().map(row::get).toArray());
    }
  }

  @Test
  void twoInstancesTakeOverTheModelOnceWithTheSizeOfTheExistingColumn() throws Exception {
    List<Boolean> outcomes = new CopyOnWriteArrayList<>();
    Runnable takeover = () -> outcomes.add(embeddingModels.adoptIfMissing());

    new TwoInstanceRace(dataSource).run(takeover, takeover, "SELECT pg_advisory_xact_lock(213, 0)");

    assertThat(outcomes).containsExactly(true, true);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_model", Integer.class)).isOne();
    assertThat(jdbc.queryForObject("SELECT dimensions FROM embedding_model", Integer.class))
        .isEqualTo(FakeEmbeddingModel.DIMENSIONS);
  }
}
