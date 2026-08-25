package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * #914: deterministic unit tests for {@link MmrSelector}, using fixed chunk texts (and therefore
 * fixed lexical token overlap - see {@link MmrSelector}'s Javadoc for why similarity is lexical,
 * not vector-based) instead of a real embedding model, so every candidate's pairwise similarity is
 * known in advance.
 */
class MmrSelectorTest {

  private static Document chunk(String id, String text, double score) {
    return Document.builder().id(id).text(text).metadata(Map.of()).score(score).build();
  }

  /**
   * The #912 scenario in miniature: two near-duplicate high-scoring chunks restating the same
   * passage (one dominant topic) plus a lower-scoring but topically distinct third chunk (the
   * crowded-out second topic). At {@code mmrLambda = 0.5}, the second near-duplicate must lose to
   * the distinct chunk - proving redundancy is actually penalized, not merely that relevance order
   * is preserved.
   */
  @Test
  void redundantCandidateLosesToATopicallyDistinctLowerScoringOne() {
    Document mostRelevant =
        chunk("a", "Der Führerschein muss innerhalb von drei Monaten beantragt werden", 0.90);
    Document nearDuplicate =
        chunk("b", "Der Führerschein ist innerhalb von drei Monaten zu beantragen", 0.89);
    Document distinctTopic =
        chunk("c", "Der Personalausweis wird beim Einwohnermeldeamt beantragt", 0.70);

    List<Document> selected =
        MmrSelector.select(List.of(mostRelevant, nearDuplicate, distinctTopic), 2, 0.5);

    assertThat(selected).extracting(Document::getId).containsExactly("a", "c");
  }

  /** {@code mmrLambda = 1.0} disables the diversity term entirely - plain top-K by relevance. */
  @Test
  void lambdaOneReproducesPureTopKByRelevance() {
    Document mostRelevant =
        chunk("a", "Der Führerschein muss innerhalb von drei Monaten beantragt werden", 0.90);
    Document nearDuplicate =
        chunk("b", "Der Führerschein ist innerhalb von drei Monaten zu beantragen", 0.89);
    Document distinctTopic =
        chunk("c", "Der Personalausweis wird beim Einwohnermeldeamt beantragt", 0.70);

    List<Document> selected =
        MmrSelector.select(List.of(mostRelevant, nearDuplicate, distinctTopic), 2, 1.0);

    assertThat(selected).extracting(Document::getId).containsExactly("a", "b");
  }

  /** Fewer candidates than {@code topK}: every candidate is selected, none invented. */
  @Test
  void selectsAllCandidatesWhenFewerThanTopK() {
    Document a = chunk("a", "Erster Textabschnitt", 0.8);
    Document b = chunk("b", "Zweiter, völlig anderer Textabschnitt", 0.6);

    List<Document> selected = MmrSelector.select(List.of(a, b), 5, 0.7);

    assertThat(selected).extracting(Document::getId).containsExactly("a", "b");
  }

  @Test
  void selectsNothingFromAnEmptyCandidateList() {
    assertThat(MmrSelector.select(List.of(), 5, 0.7)).isEmpty();
  }

  @Test
  void selectsNothingWhenTopKIsZero() {
    Document a = chunk("a", "Text", 0.8);

    assertThat(MmrSelector.select(List.of(a), 0, 0.7)).isEmpty();
  }

  /**
   * The first pick is always the highest-relevance candidate regardless of its position in the
   * input list - the diversity term only ever affects picks after the first, since there is nothing
   * selected yet to be similar to.
   */
  @Test
  void firstPickIsAlwaysTheHighestRelevanceCandidate() {
    Document low = chunk("low", "Ein Text über Katzen", 0.4);
    Document high = chunk("high", "Ein völlig anderer Text über Hunde", 0.95);

    List<Document> selected = MmrSelector.select(List.of(low, high), 1, 0.5);

    assertThat(selected).extracting(Document::getId).containsExactly("high");
  }

  /** A candidate with no score at all (null) is treated as relevance 0, never as the best pick. */
  @Test
  void treatsANullScoreAsZeroRelevance() {
    Document scored = chunk("scored", "Ein Text mit Relevanzwert", 0.5);
    Document unscored =
        Document.builder()
            .id("unscored")
            .text("Ein Text ohne Relevanzwert")
            .metadata(Map.of())
            .build();

    List<Document> selected = MmrSelector.select(List.of(unscored, scored), 2, 1.0);

    assertThat(selected).extracting(Document::getId).containsExactly("scored", "unscored");
  }
}
