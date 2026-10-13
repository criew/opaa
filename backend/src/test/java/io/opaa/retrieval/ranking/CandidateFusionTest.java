package io.opaa.retrieval.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.opaa.retrieval.CandidateList;
import io.opaa.retrieval.FusionContribution;
import io.opaa.retrieval.FusionMethod;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RetrievalListLabel;
import io.opaa.retrieval.SearchPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * {@link CandidateFusion}: rank or score fusion, pooled over all sub-queries or with a quota per
 * sub-query (docs/features/retrieval-algorithm.md, step 5). The budget on top belongs to {@link
 * RankFusionStage}.
 */
class CandidateFusionTest {

  private static final double THRESHOLD = 0.3;
  private static final QueryProperties.Fusion POOLED_RRF =
      new QueryProperties.Fusion(FusionMethod.RRF, false, 0.7);
  private static final QueryProperties.Fusion QUOTA_RRF =
      new QueryProperties.Fusion(FusionMethod.RRF, true, 0.7);
  private static final QueryProperties.Fusion POOLED_SCORE =
      new QueryProperties.Fusion(FusionMethod.SCORE, false, 0.7);
  private static final QueryProperties.Fusion QUOTA_SCORE =
      new QueryProperties.Fusion(FusionMethod.SCORE, true, 0.7);

  private static Document chunk(String id, double score) {
    return Document.builder().id(id).text(id).metadata(Map.of()).score(score).build();
  }

  private static Document chunk(String id, String text, double score) {
    return Document.builder().id(id).text(text).metadata(Map.of()).score(score).build();
  }

  private static CandidateList vector(int subQuery, Document... documents) {
    return new CandidateList(
        RetrievalListLabel.VECTOR_SEARCH.format(subQuery),
        List.of(documents),
        SearchPath.VECTOR,
        subQuery);
  }

  private static CandidateList fullText(int subQuery, Document... documents) {
    return new CandidateList(
        RetrievalListLabel.FULL_TEXT_SEARCH.format(subQuery),
        List.of(documents),
        SearchPath.FULL_TEXT,
        subQuery);
  }

  private static List<String> order(List<CandidateList> lists, QueryProperties.Fusion fusion) {
    return CandidateFusion.fuse(lists, fusion, THRESHOLD).stream()
        .map(candidate -> candidate.document().getId())
        .toList();
  }

  @Test
  void pooledRrfRanksRankOneOfEverySubQueryAboveRankTwoEvenAtAMuchHigherScore() {
    List<CandidateList> lists =
        List.of(
            vector(1, chunk("a", 0.31), chunk("b1", 0.99)),
            vector(2, chunk("c", 0.31), chunk("b2", 0.99)));

    assertThat(order(lists, POOLED_RRF)).containsExactly("a", "c", "b1", "b2");
  }

  @Test
  void pooledRrfRanksAChunkAppearingInEveryListAboveOneAppearingInOnlyOne() {
    Document shared = chunk("shared", 0.5);
    List<CandidateList> lists =
        List.of(
            vector(1, chunk("only-first", 0.5), shared),
            vector(2, chunk("only-second", 0.5), shared));

    assertThat(order(lists, POOLED_RRF)).containsExactly("shared", "only-first", "only-second");
  }

  /**
   * A chunk both paths found is one candidate, and the earliest list's instance survives - the
   * vector path's, whose score is a cosine similarity, never "the higher score" across scales.
   */
  @Test
  void dedupesByChunkIdKeepingTheEarliestListsInstance() {
    List<CandidateList> lists =
        List.of(
            vector(1, chunk("dup", "vector path's copy", 0.42)),
            fullText(1, chunk("dup", "lexical path's copy", 0.09)));

    for (QueryProperties.Fusion fusion : List.of(POOLED_RRF, QUOTA_RRF, POOLED_SCORE)) {
      List<CandidateFusion.FusedCandidate> fused = CandidateFusion.fuse(lists, fusion, THRESHOLD);
      assertThat(fused).hasSize(1);
      assertThat(fused.getFirst().document().getText()).isEqualTo("vector path's copy");
    }
  }

  @Test
  void aChunkBothPathsFoundOutranksOneASinglePathFound() {
    Document both = chunk("both", 0.5);
    List<CandidateList> lists =
        List.of(
            vector(1, chunk("vector-top", 0.8), both),
            fullText(1, chunk("lexical-top", 0.07), both));

    assertThat(order(lists, POOLED_RRF)).containsExactly("both", "vector-top", "lexical-top");
  }

  @Test
  void emptyInputYieldsAnEmptyList() {
    assertThat(CandidateFusion.fuse(List.of(), QUOTA_RRF, THRESHOLD)).isEmpty();
  }

  /**
   * The s3-06 shape: chunks several near-identical sub-queries share collect a contribution from
   * each, and the candidate leading only its own sub-query falls behind all of them. With the quota
   * every sub-query places its own best first.
   */
  @Test
  void theQuotaPutsTheLeaderOfEverySubQueryAheadOfCandidatesTheSubQueriesShare() {
    List<CandidateList> lists =
        List.of(
            vector(
                1,
                chunk("ginster", 0.7),
                chunk("s1", 0.6),
                chunk("s2", 0.6),
                chunk("s3", 0.6),
                chunk("s4", 0.6)),
            vector(
                2,
                chunk("elfe", 0.7),
                chunk("s1", 0.6),
                chunk("s2", 0.6),
                chunk("s3", 0.6),
                chunk("s4", 0.6)));

    assertThat(order(lists, POOLED_RRF).subList(0, 3)).containsExactly("s1", "s2", "s3");
    assertThat(order(lists, QUOTA_RRF)).startsWith("ginster", "elfe", "s1", "s2");
  }

  /** With one search query there is nothing to share out: the quota is the identity. */
  @Test
  void withOneSubQueryTheQuotaIsTheIdentity() {
    List<CandidateList> lists =
        List.of(
            vector(1, chunk("a", 0.8), chunk("b", 0.7), chunk("c", 0.6), chunk("d", 0.5)),
            fullText(1, chunk("d", 0.09), chunk("e", 0.08), chunk("a", 0.02)));

    assertThat(order(lists, QUOTA_RRF)).isEqualTo(order(lists, POOLED_RRF));
    assertThat(order(lists, QUOTA_SCORE)).isEqualTo(order(lists, POOLED_SCORE));
    assertThat(CandidateFusion.fuse(lists, QUOTA_RRF, THRESHOLD))
        .extracting(CandidateFusion.FusedCandidate::fusedScore)
        .isEqualTo(
            CandidateFusion.fuse(lists, POOLED_RRF, THRESHOLD).stream()
                .map(CandidateFusion.FusedCandidate::fusedScore)
                .toList());
  }

  /**
   * Score fusion keeps what rank fusion drops: a second rank with cosine 0.89 beats a first rank
   * with cosine 0.46 of another sub-query.
   */
  @Test
  void scoreFusionLetsAStrongSecondRankBeatAWeakFirstRank() {
    List<CandidateList> lists =
        List.of(
            vector(1, chunk("weak-top", 0.46), chunk("weak-second", 0.45)),
            vector(2, chunk("strong-top", 0.90), chunk("strong-second", 0.89)));

    assertThat(order(lists, POOLED_RRF))
        .containsExactly("weak-top", "strong-top", "weak-second", "strong-second");
    assertThat(order(lists, POOLED_SCORE))
        .containsExactly("strong-top", "strong-second", "weak-top", "weak-second");
  }

  /**
   * The score scale: a cosine is rescaled from the threshold (0) to 1 (1) and weighted by the
   * vector weight, a {@code ts_rank} is taken relative to its list's best and weighted by the rest.
   */
  @Test
  void scoreFusionContributionsFollowTheDocumentedScale() {
    List<CandidateList> lists =
        List.of(
            vector(1, chunk("a", 0.65), chunk("b", 0.475)),
            fullText(1, chunk("b", 0.08), chunk("c", 0.04)));

    List<CandidateFusion.FusedCandidate> fused =
        CandidateFusion.fuse(lists, POOLED_SCORE, THRESHOLD);

    CandidateFusion.FusedCandidate b =
        fused.stream().filter(c -> c.document().getId().equals("b")).findFirst().orElseThrow();
    // vector: 0.7 * (0.475 - 0.3) / 0.7 = 0.175; lexical: 0.3 * 0.08 / 0.08 = 0.3
    assertThat(b.contributions())
        .extracting(FusionContribution::listLabel, FusionContribution::rank)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("vector search · sub-query 1", 2),
            org.assertj.core.groups.Tuple.tuple("full-text search · sub-query 1", 1));
    assertThat(b.contributions().get(0).value()).isCloseTo(0.175, within(1e-9));
    assertThat(b.contributions().get(1).value()).isCloseTo(0.3, within(1e-9));
    assertThat(b.fusedScore()).isCloseTo(0.475, within(1e-9));
    assertThat(fused).extracting(c -> c.document().getId()).containsExactly("b", "a", "c");
  }

  /** Every list a candidate appeared in is named with its rank and its share of the score. */
  @Test
  void rrfContributionsNameEveryListWithRankAndShare() {
    Document both = chunk("both", 0.5);
    List<CandidateList> lists =
        List.of(
            vector(1, chunk("x", 0.8), both),
            fullText(1, chunk("y", 0.09), chunk("z", 0.08), both));

    CandidateFusion.FusedCandidate fused =
        CandidateFusion.fuse(lists, POOLED_RRF, THRESHOLD).stream()
            .filter(c -> c.document().getId().equals("both"))
            .findFirst()
            .orElseThrow();

    assertThat(fused.contributions())
        .containsExactly(
            new FusionContribution("vector search · sub-query 1", 2, 1.0 / 62),
            new FusionContribution("full-text search · sub-query 1", 3, 1.0 / 63));
    assertThat(fused.fusedScore()).isCloseTo(1.0 / 62 + 1.0 / 63, within(1e-12));
  }
}
