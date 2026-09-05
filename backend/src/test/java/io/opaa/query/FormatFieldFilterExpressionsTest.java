package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.indexing.metadata.FormatFieldCondition;
import io.opaa.indexing.metadata.FormatMetadataField;
import io.opaa.indexing.metadata.MetadataFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;

/**
 * A format-field condition (#1242) says the same thing in both search paths - {@code (Wert passt)
 * OR (kein Wert)} - and stays subordinate to the permission filter. Unlike a library field it
 * carries no library guard: a format field means the same everywhere, and a document that is not of
 * the format simply has no value.
 */
class FormatFieldFilterExpressionsTest {

  private static final List<String> VOCABULARY = List.of("VERMERK");
  private static final Filter.Expression LIBRARY_FILTER =
      SearchScopeStage.libraryFilter(Set.of(UUID.randomUUID()));

  private static MetadataFilter senderFilter(String... addresses) {
    return MetadataFilter.NONE.withFormatFields(
        List.of(FormatFieldCondition.parse("mail_sender", List.of(addresses))));
  }

  private static String jsonPath(Filter.Expression expression) {
    return new PgVectorFilterExpressionConverter().convertExpression(expression);
  }

  @Test
  void theSenderConditionNamesTheValuesAndTheLeerwertRuleInBothPaths() {
    MetadataFilter filter = senderFilter("max@stadt.de", "schmidt@kreis.de");

    String rendered = jsonPath(MetadataFilterExpressions.vectorExpression(filter, VOCABULARY));
    assertThat(rendered).contains("ff_mail_sender").contains("ffs_mail_sender");

    List<Object> parameters = new ArrayList<>();
    String sql =
        MetadataFilterExpressions.sqlPredicate(filter, "v.metadata", VOCABULARY, parameters);
    assertThat(sql)
        .startsWith(" AND (")
        .contains("v.metadata->>'ffs_mail_sender' IS NULL")
        .contains("v.metadata->>'ff_mail_sender' = ANY(?)");
    assertThat(parameters).hasSize(2);
  }

  /**
   * The converter renders a nested expression without parentheses, so the OR-composed condition
   * must be a group - otherwise the permission filter would bind to its first branch only, and a
   * document without a sender would slip past the Dokumentart condition beside it.
   */
  @Test
  void theConditionIsBracketedBesideAnotherConditionAndStaysUnderTheRightsFilter() {
    MetadataFilter filter =
        MetadataFilter.ofDocumentTypes(List.of("VERMERK"))
            .withFormatFields(
                List.of(FormatFieldCondition.parse("mail_sender", List.of("max@stadt.de"))));
    Filter.Expression combined =
        MetadataFilterExpressions.subordinateTo(
            LIBRARY_FILTER,
            MetadataFilterExpressions.vectorExpression(
                filter, List.of("VERMERK", "PROTOKOLL", "SATZUNG_ORDNUNG")));

    assertThat(combined.type()).isEqualTo(Filter.ExpressionType.AND);
    assertThat(combined.left()).isSameAs(LIBRARY_FILTER);
    String rendered = jsonPath(combined);
    assertThat(rendered)
        .as("the whole format-field condition is one bracketed operand: %s", rendered)
        .contains("&& (")
        .contains("ff_mail_sender")
        .contains("ffs_mail_sender");
  }

  /** A chunk without the presence marker was kept by the Leerwert rule, not by a match. */
  @Test
  void aChunkWithoutTheSenderMarkerCountsAsKeptWithoutValue() {
    MetadataFilter filter = senderFilter("max@stadt.de");
    Document withSender =
        new Document(
            "text",
            Map.of(
                FormatMetadataField.MAIL_SENDER.chunkKey(),
                "max@stadt.de",
                FormatMetadataField.MAIL_SENDER.presenceChunkKey(),
                FormatMetadataField.PRESENCE_VALUE));

    assertThat(MetadataFilterExpressions.keptWithoutValue(filter, withSender)).isFalse();
    assertThat(MetadataFilterExpressions.keptWithoutValue(filter, new Document("text", Map.of())))
        .isTrue();
  }

  /**
   * The Betreff shows in the Beleg and never filters, an unknown key names no field, and a value
   * the pattern rejects is a caller error - none of the three is silently dropped.
   */
  @Test
  void aNonFilterableFieldAnUnknownFieldAndAnInvalidValueAreAllRejected() {
    assertThatThrownBy(() -> FormatFieldCondition.parse("mail_subject", List.of("Bebauungsplan")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Betreff");
    assertThatThrownBy(() -> FormatFieldCondition.parse("mail_zeichen", List.of("X")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("mail_zeichen");
    assertThatThrownBy(() -> FormatFieldCondition.parse("mail_sender", List.of("Max Mueller")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Absender");
  }

  /** An exact match, never a substring: the field exists so an address is checkable. */
  @Test
  void theConditionMatchesTheWholeAddressOnly() {
    FormatFieldCondition condition =
        FormatFieldCondition.parse("mail_sender", List.of("max@stadt.de"));

    assertThat(condition.matches("max@stadt.de")).isTrue();
    assertThat(condition.matches("max@stadt.de.example.org")).isFalse();
    assertThat(condition.matches(null)).isTrue();
  }
}
