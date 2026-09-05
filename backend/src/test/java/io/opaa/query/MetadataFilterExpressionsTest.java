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
            MetadataFilterExpressions.sqlPredicate(
                MetadataFilter.NONE, "v.metadata", VOCABULARY, parameters))
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
   * The rendered jsonpath must bracket the whole metadata condition under the permission filter:
   * jsonpath binds {@code &&} tighter than {@code ||}, so an unbracketed date window would tie the
   * permission filter to its first precision branch only. Asserted on the rendered string, because
   * the expression tree alone cannot show whether the converter emitted the brackets.
   */
  @Test
  void theRenderedVectorFilterBracketsTheMetadataConditionUnderThePermissionFilter() {
    MetadataFilter filter =
        new MetadataFilter(Set.of("VERMERK"), LocalDate.of(2024, 6, 15), LocalDate.of(2024, 8, 31));
    String rendered =
        jsonPath(
            MetadataFilterExpressions.subordinateTo(
                LIBRARY_FILTER, MetadataFilterExpressions.vectorExpression(filter, VOCABULARY)));

    String permission = jsonPath(LIBRARY_FILTER).replace("'::jsonpath", "");
    assertThat(rendered).startsWith(permission + " && (");
    // No "||" may sit at bracket depth zero of the metadata part: every OR is inside a group.
    String metadataPart = rendered.substring(permission.length());
    assertThat(topLevelOrCount(metadataPart)).isZero();
    // Inside: the Dokumentart condition AND the (bracketed) date condition.
    assertThat(metadataPart).contains(") && (");
  }

  /**
   * The bracket under the permission filter does not depend on a Dokumentart condition being
   * present: with a date-only filter the whole OR-composed date window is still one bracketed
   * operand, and the permission filter still binds to all of it - the group comes from {@link
   * MetadataFilterExpressions#subordinateTo} alone.
   */
  @Test
  void aDateOnlyFilterIsBracketedUnderThePermissionFilterAsAWhole() {
    MetadataFilter dateOnly =
        MetadataFilter.ofDateWindow(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31));
    Filter.Expression combined =
        MetadataFilterExpressions.subordinateTo(
            LIBRARY_FILTER, MetadataFilterExpressions.vectorExpression(dateOnly, VOCABULARY));
    String rendered = jsonPath(combined);

    assertThat(combined.right()).isInstanceOf(Filter.Group.class);
    String permission = jsonPath(LIBRARY_FILTER).replace("'::jsonpath", "");
    assertThat(rendered).startsWith(permission + " && (");
    String metadataPart = rendered.substring(permission.length());
    assertThat(topLevelOrCount(metadataPart)).isZero();
    // Every precision branch of the window sits inside that one bracket.
    assertThat(metadataPart).contains("DAY").contains("MONTH").contains("YEAR");
  }

  private static int topLevelOrCount(String jsonPath) {
    int depth = 0;
    int count = 0;
    for (int i = 0; i < jsonPath.length(); i++) {
      char c = jsonPath.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == '|'
          && depth == 0
          && i + 1 < jsonPath.length()
          && jsonPath.charAt(i + 1) == '|') {
        count++;
      }
    }
    return count;
  }

  /**
   * "No value" cannot be said as IS NULL in the vector path; both forms say NOT IN over every other
   * vocabulary code - true for a chunk without the key - so a value the closed set does not know (a
   * removed vocabulary code still on old chunks) is read as "no value" by both paths alike: the
   * paths cannot drift apart on it.
   */
  @Test
  void bothFormsSayNoValueAsNotInOverTheClosedValueSet() {
    MetadataFilter filter = MetadataFilter.ofDocumentTypes(List.of("VERMERK"));

    String vector = jsonPath(MetadataFilterExpressions.vectorExpression(filter, VOCABULARY));
    assertThat(vector).contains("!(").contains("SATZUNG_ORDNUNG").contains("DIENSTANWEISUNG");
    assertThat(vector).doesNotContain("\"VERMERK\"");

    List<Object> parameters = new ArrayList<>();
    String sql =
        MetadataFilterExpressions.sqlPredicate(filter, "v.metadata", VOCABULARY, parameters);
    assertThat(sql)
        .isEqualTo(" AND (v.metadata->>'doc_type' IS NULL OR v.metadata->>'doc_type' <> ALL(?))");
    assertThat(parameters).hasSize(1);
    assertThat((String[]) parameters.get(0))
        .containsExactly("SATZUNG_ORDNUNG", "DIENSTANWEISUNG")
        .doesNotContain("ALTCODE");
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
    String sql =
        MetadataFilterExpressions.sqlPredicate(filter, "v.metadata", VOCABULARY, parameters);
    assertThat(sql)
        .startsWith(
            " AND (v.metadata->>'doc_date_precision' IS NULL OR"
                + " v.metadata->>'doc_date_precision' <> ALL(?) OR (");
    assertThat((String[]) parameters.get(0)).containsExactly("DAY", "MONTH", "YEAR");
    assertThat(parameters.subList(1, parameters.size()))
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
    String sql = MetadataFilterExpressions.sqlPredicate(from, "v.metadata", VOCABULARY, parameters);
    assertThat(sql).contains(">= ?").doesNotContain("<= ?");
    assertThat(parameters.subList(1, parameters.size()))
        .containsExactly("DAY", "2024-01-01", "MONTH", "2024-01-01", "YEAR", "2024-01-01");
    assertThat(jsonPath(MetadataFilterExpressions.vectorExpression(from, VOCABULARY)))
        .doesNotContain("<=");
  }
}
