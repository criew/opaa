package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.metadata.MetadataFilter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;

/**
 * The two query forms of a {@link MetadataFilter} state the same rule (#1070), and the metadata
 * filter is subordinate to the permission filter by construction - the AND with the library filter
 * is the outer operator, and an empty filter leaves the permission filter untouched.
 */
class MetadataFilterExpressionsTest {

  private static final List<String> VOCABULARY =
      List.of("SATZUNG_ORDNUNG", "DIENSTANWEISUNG", "VERMERK");
  private static final Filter.Expression LIBRARY_FILTER =
      SearchScopeStage.libraryFilter(Set.of(UUID.randomUUID()));

  private static String jsonPath(Filter.Expression expression) {
    return new PgVectorFilterExpressionConverter().convertExpression(expression);
  }

  @Test
  void anEmptyFilterIsNoConditionInEitherPath() {
    assertThat(MetadataFilterExpressions.vectorExpression(MetadataFilter.NONE, VOCABULARY))
        .isNull();
    assertThat(MetadataFilterExpressions.subordinateTo(LIBRARY_FILTER, null))
        .isSameAs(LIBRARY_FILTER);
    List<Object> parameters = new ArrayList<>();
    assertThat(
            MetadataFilterExpressions.sqlPredicate(MetadataFilter.NONE, "v.metadata", parameters))
        .isEmpty();
    assertThat(parameters).isEmpty();
  }

  /**
   * The permission filter stays the outer operand: whatever the metadata filter says, the
   * expression a search runs is {@code libraryFilter AND (...)} - it can remove, never add.
   */
  @Test
  void theMetadataFilterIsAndedUnderThePermissionFilter() {
    MetadataFilter filter = MetadataFilter.ofDocumentTypes(List.of("VERMERK"));
    Filter.Expression combined =
        MetadataFilterExpressions.subordinateTo(
            LIBRARY_FILTER, MetadataFilterExpressions.vectorExpression(filter, VOCABULARY));

    assertThat(combined.type()).isEqualTo(Filter.ExpressionType.AND);
    assertThat(combined.left()).isSameAs(LIBRARY_FILTER);
    assertThat(jsonPath(combined)).startsWith(jsonPath(LIBRARY_FILTER).replace("'::jsonpath", ""));
  }

  /**
   * "No value" cannot be said as IS NULL in the vector path; it is said as NOT IN over every other
   * vocabulary code - true exactly for a chunk without the key - so the vector form reads "not one
   * of the unselected codes" while the SQL form reads "IS NULL OR one of the selected".
   */
  @Test
  void theDocumentTypeConditionKeepsTheSelectedCodesAndTheMissingKey() {
    MetadataFilter filter = MetadataFilter.ofDocumentTypes(List.of("VERMERK"));

    String vector = jsonPath(MetadataFilterExpressions.vectorExpression(filter, VOCABULARY));
    assertThat(vector).contains("!(").contains("SATZUNG_ORDNUNG").contains("DIENSTANWEISUNG");
    assertThat(vector).doesNotContain("\"VERMERK\"");

    List<Object> parameters = new ArrayList<>();
    String sql = MetadataFilterExpressions.sqlPredicate(filter, "v.metadata", parameters);
    assertThat(sql)
        .isEqualTo(" AND (v.metadata->>'doc_type' IS NULL OR v.metadata->>'doc_type' = ANY(?))");
    assertThat(parameters).hasSize(1);
    assertThat((String[]) parameters.get(0)).containsExactly("VERMERK");
  }

  /** Selecting every code constrains nothing: every document carries one of them or none. */
  @Test
  void selectingTheWholeVocabularyIsNoCondition() {
    assertThat(
            MetadataFilterExpressions.vectorExpression(
                MetadataFilter.ofDocumentTypes(VOCABULARY), VOCABULARY))
        .isNull();
  }

  /**
   * The window is widened per precision to the first day of the month/year the window starts in.
   */
  @Test
  void theDateConditionWidensTheLowerBoundPerPrecisionInBothPaths() {
    MetadataFilter filter =
        MetadataFilter.ofDateWindow(LocalDate.of(2024, 6, 15), LocalDate.of(2024, 8, 31));

    String vector = jsonPath(MetadataFilterExpressions.vectorExpression(filter, VOCABULARY));
    assertThat(vector)
        .contains("\"DAY\" && $.\"doc_date\" >= \"2024-06-15\"")
        .contains("\"MONTH\" && $.\"doc_date\" >= \"2024-06-01\"")
        .contains("\"YEAR\" && $.\"doc_date\" >= \"2024-01-01\"")
        .contains("<= \"2024-08-31\"")
        .contains("!($.\"doc_date_precision\" == \"DAY\"");

    List<Object> parameters = new ArrayList<>();
    String sql = MetadataFilterExpressions.sqlPredicate(filter, "v.metadata", parameters);
    assertThat(sql).startsWith(" AND (v.metadata->>'doc_date' IS NULL OR (");
    assertThat(parameters)
        .containsExactly(
            "DAY",
            "2024-06-15",
            "2024-08-31",
            "MONTH",
            "2024-06-01",
            "2024-08-31",
            "YEAR",
            "2024-01-01",
            "2024-08-31");
  }

  @Test
  void anOpenEndedWindowOmitsTheMissingBound() {
    MetadataFilter from = MetadataFilter.ofDateWindow(LocalDate.of(2024, 1, 1), null);
    List<Object> parameters = new ArrayList<>();
    String sql = MetadataFilterExpressions.sqlPredicate(from, "v.metadata", parameters);
    assertThat(sql).contains(">= ?").doesNotContain("<= ?");
    assertThat(parameters)
        .containsExactly("DAY", "2024-01-01", "MONTH", "2024-01-01", "YEAR", "2024-01-01");
    assertThat(jsonPath(MetadataFilterExpressions.vectorExpression(from, VOCABULARY)))
        .doesNotContain("<=");
  }
}
