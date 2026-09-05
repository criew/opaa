package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DatePrecision;
import io.opaa.indexing.metadata.LibraryFieldCondition;
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
 * A library-field condition (#1071) says the same thing in both search paths, and it says it
 * bracketed and under the permission filter: {@code (fremde Bibliothek) OR (Wert passt) OR (kein
 * Wert)}. The "kein Wert" branch reads the presence marker, never the value key - see {@code
 * LibraryMetadataFieldKeys} for why the value key cannot carry it.
 */
class LibraryFieldFilterExpressionsTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final List<String> VOCABULARY = List.of("VERMERK");
  private static final Filter.Expression LIBRARY_FILTER =
      SearchScopeStage.libraryFilter(Set.of(LIBRARY_ID));

  private static String jsonPath(Filter.Expression expression) {
    return new PgVectorFilterExpressionConverter().convertExpression(expression);
  }

  @Test
  void aSelectConditionNamesTheValuesTheLibraryAndTheLeerwertRuleInBothPaths() {
    MetadataFilter filter =
        MetadataFilter.NONE.withLibraryFields(
            List.of(LibraryFieldCondition.ofCodes(LIBRARY_ID, "fassung", List.of("A", "B"))));

    String rendered = jsonPath(MetadataFilterExpressions.vectorExpression(filter, VOCABULARY));
    assertThat(rendered).contains("lf_fassung").contains("lfs_fassung").contains("library_id");

    List<Object> parameters = new ArrayList<>();
    String sql =
        MetadataFilterExpressions.sqlPredicate(filter, "v.metadata", VOCABULARY, parameters);
    assertThat(sql)
        .startsWith(" AND (")
        .contains("v.metadata->>'library_id'")
        .contains("v.metadata->>'lfs_fassung'")
        .contains("v.metadata->>'lf_fassung' = ANY(?)");
    assertThat(parameters).hasSize(3);
  }

  @Test
  void aPatternConditionIsAnExactValueNeverAPrefix() {
    MetadataFilter filter =
        MetadataFilter.NONE.withLibraryFields(
            List.of(LibraryFieldCondition.ofValue(LIBRARY_ID, "paragraf", "§ 7")));

    List<Object> parameters = new ArrayList<>();
    String sql =
        MetadataFilterExpressions.sqlPredicate(filter, "v.metadata", VOCABULARY, parameters);

    assertThat(sql).contains("v.metadata->>'lf_paragraf' = ?").doesNotContain("LIKE");
    assertThat(parameters).contains("§ 7");
    assertThat(jsonPath(MetadataFilterExpressions.vectorExpression(filter, VOCABULARY)))
        .contains("\"§ 7\"");
  }

  @Test
  void aDateConditionCoversEveryPrecisionSpanExactlyLikeTheKernfeld() {
    MetadataFilter filter =
        MetadataFilter.NONE.withLibraryFields(
            List.of(
                LibraryFieldCondition.ofDateWindow(
                    LIBRARY_ID, "fassung", LocalDate.of(2024, 6, 15), LocalDate.of(2024, 8, 31))));

    List<Object> parameters = new ArrayList<>();
    String sql =
        MetadataFilterExpressions.sqlPredicate(filter, "v.metadata", VOCABULARY, parameters);

    assertThat(sql).contains("v.metadata->>'lfp_fassung' = ?");
    // The lower bound is the first day of the span the precision leaves open - "Fassung 2024" is in
    // a window starting 15.06.2024, the deliberately wide reading of the Kernfeld Datum/Stand.
    assertThat(parameters)
        .contains(DatePrecision.YEAR.name(), "2024-01-01", "2024-06-15", "2024-08-31");
  }

  /**
   * jsonpath binds {@code &&} tighter than {@code ||}. A library-field condition sits beside other
   * conditions, so its own OR-chain must be bracketed as a whole: unbracketed, {@code (typ) && a ||
   * b || c} would let a document without a value for the library field slip past the Dokumentart
   * condition. Asserted with a second condition beside it, because {@code subordinateTo} brackets
   * the metadata expression as a whole and would hide a missing inner group on its own.
   */
  @Test
  void aLibraryFieldConditionIsBracketedBesideAnotherCondition() {
    MetadataFilter filter =
        MetadataFilter.ofDocumentTypes(List.of("VERMERK"))
            .withLibraryFields(
                List.of(LibraryFieldCondition.ofCodes(LIBRARY_ID, "fassung", List.of("A"))));
    Filter.Expression combined =
        MetadataFilterExpressions.subordinateTo(
            LIBRARY_FILTER,
            MetadataFilterExpressions.vectorExpression(
                filter, List.of("VERMERK", "PROTOKOLL", "SATZUNG_ORDNUNG")));

    assertThat(combined.type()).isEqualTo(Filter.ExpressionType.AND);
    assertThat(combined.left()).isSameAs(LIBRARY_FILTER);
    String rendered = jsonPath(combined);
    // The library-field OR-chain follows the Dokumentart term behind a "&& (" that opens its own
    // group. Without the inner group the converter would render "… && !($.library_id == …) || …",
    // and a document without a value for the library field would slip past the Dokumentart
    // condition entirely.
    assertThat(rendered)
        .as("the whole library-field condition is one bracketed operand: %s", rendered)
        .contains("&& (!(")
        .doesNotContain("&& !(");
  }

  /** Two conditions on the same field would only ever contradict each other - rejected (400). */
  @Test
  void twoConditionsOnTheSameFieldAreRejected() {
    assertThatThrownBy(
            () ->
                MetadataFilter.NONE.withLibraryFields(
                    List.of(
                        LibraryFieldCondition.ofCodes(LIBRARY_ID, "fassung", List.of("A")),
                        LibraryFieldCondition.ofCodes(LIBRARY_ID, "fassung", List.of("B")))))
        .isInstanceOf(io.opaa.common.ValidationException.class);
  }
}
