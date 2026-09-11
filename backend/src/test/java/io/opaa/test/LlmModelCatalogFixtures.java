package io.opaa.test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Hands a test class the systemwide model catalogue for the duration of one test method and puts
 * back exactly the rows it found.
 *
 * <p>{@code llm_models} has no owner column and at most one active row ({@code
 * ux_llm_models_single_active}), so a class that needs a known catalogue state cannot scope its
 * cleanup by id the way every other table allows. It also holds a row no test class created: {@code
 * LlmModelSeeder} takes the environment configuration over once per fresh application context, and
 * that row decides whether {@code ActiveChatModelResolver} finds an active model at all. Taking a
 * snapshot and restoring it is therefore what "remove only your own rows" means here.
 */
public final class LlmModelCatalogFixtures {

  private final JdbcTemplate jdbcTemplate;

  LlmModelCatalogFixtures(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Empties the catalogue for a {@code @BeforeEach} and returns its previous content.
   *
   * @return the rows to hand back to {@link #restoreCatalog(List)} in {@code @AfterEach}
   */
  public List<Map<String, Object>> takeOverCatalog() {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM llm_models");
    jdbcTemplate.update("DELETE FROM llm_models");
    return rows;
  }

  /**
   * @param rows the snapshot from {@link #takeOverCatalog()}; {@code null} is refused, because an
   *     accidentally missing snapshot would leave the catalogue emptied for every following class
   */
  public void restoreCatalog(List<Map<String, Object>> rows) {
    Objects.requireNonNull(rows, "call takeOverCatalog() in @BeforeEach and keep its result");
    jdbcTemplate.update("DELETE FROM llm_models");
    for (Map<String, Object> row : rows) {
      String columns = String.join(", ", row.keySet());
      String placeholders =
          row.keySet().stream().map(column -> "?").collect(Collectors.joining(", "));
      jdbcTemplate.update(
          "INSERT INTO llm_models (" + columns + ") VALUES (" + placeholders + ")",
          row.values().toArray());
    }
  }
}
