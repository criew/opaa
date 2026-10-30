package io.opaa.indexing.source.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jsoup.select.QueryParser;
import org.junit.jupiter.api.Test;

/**
 * Guards the reflective reads of Jsoup's evaluator tree: a Jsoup version that moves or renames the
 * fields makes every selector refused, and this test fails at once instead of the setting.
 */
class SelectorCostTest {

  @Test
  void readsCombinatorsAndGroupsFromTheParsedTree() {
    assertThat(SelectorCost.of(QueryParser.parse("main > div .text, #inhalt")))
        .isEqualTo(new SelectorCost.Shape(2, 2));
    assertThat(SelectorCost.of(QueryParser.parse("main, article, [role=main]")))
        .isEqualTo(new SelectorCost.Shape(0, 3));
  }

  @Test
  void refusesAnEvaluatorOutsideTheAllowedForms() {
    assertThatThrownBy(() -> SelectorCost.of(QueryParser.parse("p:has(q)")))
        .isInstanceOf(SelectorCost.Refused.class);
  }
}
