package io.opaa.retrieval.ranking;

import io.opaa.retrieval.CandidateList;
import io.opaa.retrieval.FusionContribution;
import io.opaa.retrieval.FusionMethod;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.SearchPath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.ai.document.Document;

/**
 * Fuses the candidate lists of one retrieval run into one ordered list, uncapped - the budget is
 * {@link RankFusionStage}'s decision.
 *
 * <p>Each list contributes per candidate: {@code 1 / (60 + rank)} under {@link FusionMethod#RRF};
 * under {@link FusionMethod#SCORE} the vector weight times the cosine similarity rescaled from the
 * similarity threshold to 1, or the remaining weight times the {@code ts_rank} relative to the best
 * of its list. A candidate's fused score is the sum over the lists of its group. Pooled, all lists
 * form one group; with the sub-query quota each sub-query is a group, and the order is filled
 * round-robin - every round takes each group's best candidate not yet taken, best fused score
 * first. With one sub-query both are the same.
 *
 * <p>Deduplicated by {@link Document#getId()}; the instance from the <b>earliest</b> list is kept,
 * positionally rather than by score, because a cosine similarity and a {@code ts_rank} of the same
 * chunk are not comparable.
 */
final class CandidateFusion {

  /** The standard RRF damping constant (Cormack et al., 2009). */
  static final double RANK_DAMPING_CONSTANT = 60.0;

  private CandidateFusion() {}

  /**
   * One fused candidate: its fused score within the group that placed it, that group's sub-query
   * ({@code 0} when pooled), and the contributions that make up exactly that score - the lists of
   * the placing group the candidate appeared in, so the shares always add up to the fused score.
   */
  record FusedCandidate(
      Document document, double fusedScore, int subQuery, List<FusionContribution> contributions) {}

  static List<FusedCandidate> fuse(
      List<CandidateList> lists, QueryProperties.Fusion fusion, double similarityThreshold) {
    Map<String, Document> documentById = new LinkedHashMap<>();
    Map<Integer, Map<String, List<FusionContribution>>> contributionsByGroup = new TreeMap<>();
    Map<Integer, Map<String, Double>> scoresByGroup = new TreeMap<>();

    for (CandidateList list : lists) {
      if (list.searchPath() == null) {
        throw new IllegalArgumentException(
            "the fusion takes searched lists only, but '"
                + list.label()
                + "' names no search path and sub-query - a stage before the fusion dropped the"
                + " list's origin, which would silently merge every sub-query into one pool");
      }
      int group = fusion.subQueryQuota() ? list.subQuery() : 0;
      Map<String, List<FusionContribution>> groupContributions =
          contributionsByGroup.computeIfAbsent(group, key -> new LinkedHashMap<>());
      Map<String, Double> groupScores =
          scoresByGroup.computeIfAbsent(group, key -> new LinkedHashMap<>());
      double best = bestScore(list);
      for (int i = 0; i < list.documents().size(); i++) {
        Document document = list.documents().get(i);
        int rank = i + 1;
        double value = contribution(list, document, rank, best, fusion, similarityThreshold);
        documentById.putIfAbsent(document.getId(), document);
        groupContributions
            .computeIfAbsent(document.getId(), key -> new ArrayList<>())
            .add(new FusionContribution(list.label(), rank, value));
        groupScores.merge(document.getId(), value, Double::sum);
      }
    }

    List<List<Map.Entry<String, Double>>> groupOrders = new ArrayList<>();
    List<Integer> groupKeys = new ArrayList<>(scoresByGroup.keySet());
    for (Map<String, Double> scores : scoresByGroup.values()) {
      groupOrders.add(
          scores.entrySet().stream()
              .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
              .toList());
    }

    List<FusedCandidate> fused = new ArrayList<>(documentById.size());
    Set<String> taken = new HashSet<>();
    int[] cursor = new int[groupOrders.size()];
    while (taken.size() < documentById.size()) {
      List<FusedCandidate> round = new ArrayList<>(groupOrders.size());
      for (int g = 0; g < groupOrders.size(); g++) {
        List<Map.Entry<String, Double>> order = groupOrders.get(g);
        while (cursor[g] < order.size() && taken.contains(order.get(cursor[g]).getKey())) {
          cursor[g]++;
        }
        if (cursor[g] < order.size()) {
          Map.Entry<String, Double> next = order.get(cursor[g]++);
          taken.add(next.getKey());
          round.add(
              new FusedCandidate(
                  documentById.get(next.getKey()),
                  next.getValue(),
                  groupKeys.get(g),
                  List.copyOf(contributionsByGroup.get(groupKeys.get(g)).get(next.getKey()))));
        }
      }
      round.sort(Comparator.comparingDouble(FusedCandidate::fusedScore).reversed());
      fused.addAll(round);
    }
    return List.copyOf(fused);
  }

  private static double contribution(
      CandidateList list,
      Document document,
      int rank,
      double bestInList,
      QueryProperties.Fusion fusion,
      double similarityThreshold) {
    if (fusion.method() == FusionMethod.RRF) {
      return 1.0 / (RANK_DAMPING_CONSTANT + rank);
    }
    double score = document.getScore() != null ? document.getScore() : 0.0;
    if (list.searchPath() == SearchPath.VECTOR) {
      double span = 1.0 - similarityThreshold;
      double rescaled = span <= 0.0 ? 1.0 : (score - similarityThreshold) / span;
      return fusion.vectorWeight() * Math.clamp(rescaled, 0.0, 1.0);
    }
    double relative = bestInList > 0.0 ? score / bestInList : 0.0;
    return (1.0 - fusion.vectorWeight()) * Math.clamp(relative, 0.0, 1.0);
  }

  private static double bestScore(CandidateList list) {
    return list.documents().stream()
        .map(Document::getScore)
        .filter(score -> score != null)
        .mapToDouble(Double::doubleValue)
        .max()
        .orElse(0.0);
  }
}
