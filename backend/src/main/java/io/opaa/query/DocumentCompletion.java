package io.opaa.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;

/**
 * Completes a post-fusion/post-MMR chunk selection with sibling chunks of documents it already
 * represents (#932): up to {@link QueryProperties#maxChunksPerDocument} chunks per document from
 * the already permission/threshold-filtered {@code candidatePool} (the same pool {@code
 * similaritySearch} produced for {@code selection}) are preferred over a chunk of a different
 * document filling the remaining budget.
 *
 * <p>Tier 1 evicts the weakest chunk of some other, not-yet-completed document already holding at
 * least two chunks - document diversity never drops below what fusion/MMR established.
 *
 * <p>Tier 2, tried only when tier 1 finds no source and capped at {@code max(1, overallBudget / 4)}
 * evictions per call (#932 scope v2 - v1's tier-1-only rule was a no-op whenever every document
 * held exactly one chunk, its own live-verification failure mode): evicts the lowest-ranked chunk
 * of the whole selection, but only when the completing document's own best chunk ranks strictly
 * better than that victim, the victim was not itself added this call, and it does not belong to the
 * completing document. May drop a document out of the selection entirely - diversity is not
 * protected here.
 *
 * <p>A document already completed this call is never a tier-1 source; a chunk either tier added is
 * never a later victim in either tier, so a completion never undoes an earlier one. A completed
 * document's own original chunk, unlike its just-added completion, stays eligible as a later tier-2
 * victim. Filling never grows {@code selection} past {@code overallBudget}.
 */
final class DocumentCompletion {

  private DocumentCompletion() {}

  static List<Document> complete(
      List<Document> selection,
      List<Document> candidatePool,
      int maxChunksPerDocument,
      int overallBudget) {
    if (selection.isEmpty() || maxChunksPerDocument <= 1) {
      return selection;
    }

    // The authoritative rank both paths agree on: selection's own order (fused-score descending
    // on the multi-sub-query path, plain relevance descending on the single-query path) - never
    // Document#getScore() directly, which is only comparable within a single search vector and is
    // exactly the cross-sub-query comparison ReciprocalRankFusion's Javadoc documents as invalid.
    Map<String, Integer> originalRankByChunkId = new HashMap<>();
    Map<String, Integer> bestOriginalRankByDocument = new HashMap<>();
    for (int i = 0; i < selection.size(); i++) {
      Document chunk = selection.get(i);
      originalRankByChunkId.put(chunk.getId(), i);
      bestOriginalRankByDocument.putIfAbsent(QueryService.chunkGroupingKey(chunk), i);
    }

    List<Document> result = new ArrayList<>(selection);
    Set<String> selectedChunkIds =
        result.stream().map(Document::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    Map<String, List<Document>> unusedCandidatesByDocument =
        unusedCandidatesByDocument(candidatePool, selectedChunkIds);
    List<String> documentOrder = distinctDocumentOrder(result);

    // Documents that already received a completion chunk in this call - excluded from being an
    // eviction source for any later document's completion, so a completion can never be undone by
    // a subsequent one within the same call (see this class's Javadoc).
    Set<String> completedDocumentKeys = new HashSet<>();

    // Tier 2's per-call cap (#932 scope v2, Maintainer decision): unbounded tier-2 eviction could
    // otherwise shrink an eight-topic answer down to a handful of documents in a single call.
    int tier2EvictionCap = Math.max(1, overallBudget / 4);
    int tier2EvictionsUsed = 0;

    for (String documentKey : documentOrder) {
      List<Document> unused = unusedCandidatesByDocument.get(documentKey);
      if (unused == null) {
        continue;
      }
      for (Document candidate : unused) {
        if (countForDocument(result, documentKey) >= maxChunksPerDocument) {
          break;
        }
        if (result.size() < overallBudget) {
          result.add(candidate);
          completedDocumentKeys.add(documentKey);
        } else if (evictWeakestFromAnOverrepresentedDocument(
            result, documentKey, completedDocumentKeys, originalRankByChunkId)) {
          result.add(candidate);
          completedDocumentKeys.add(documentKey);
        } else if (tier2EvictionsUsed < tier2EvictionCap
            && evictLastRankedChunkOfSelection(
                result,
                documentKey,
                bestOriginalRankByDocument.get(documentKey),
                originalRankByChunkId)) {
          result.add(candidate);
          completedDocumentKeys.add(documentKey);
          tier2EvictionsUsed++;
        } else {
          // Neither tier found an eviction source for this document right now (or tier 2's cap is
          // exhausted) - trying its remaining candidates would not change that. A later document
          // may still succeed: a document that failed to receive a chunk here (unlike one in
          // completedDocumentKeys) stays a valid tier-1 eviction source for it, and its own
          // original chunk stays a valid tier-2 one.
          break;
        }
      }
    }
    return result;
  }

  /**
   * The distinct document keys represented in {@code selection}, in first-appearance order - the
   * order completion attempts documents in, so an earlier-ranked document's completeness is
   * preferred over a later-ranked one's when the budget runs out.
   */
  private static List<String> distinctDocumentOrder(List<Document> selection) {
    return selection.stream()
        .map(QueryService::chunkGroupingKey)
        .distinct()
        .collect(Collectors.toList());
  }

  private static long countForDocument(List<Document> selection, String documentKey) {
    return selection.stream()
        .filter(d -> QueryService.chunkGroupingKey(d).equals(documentKey))
        .count();
  }

  /**
   * Groups every candidate not already in {@code selection} by document, deduplicated by chunk id
   * (the same chunk can appear once per sub-query in a pooled multi-query candidate list, kept at
   * its higher-scoring instance) and ordered by each chunk's own first-occurrence position in
   * {@code candidatePool} - not {@link Document#getScore()}, which is only comparable within the
   * single search vector that produced it (see this class's Javadoc).
   */
  private static Map<String, List<Document>> unusedCandidatesByDocument(
      List<Document> candidatePool, Set<String> selectedChunkIds) {
    Map<String, Document> byChunkId = new LinkedHashMap<>();
    Map<String, Integer> poolRankByChunkId = new HashMap<>();
    int index = 0;
    for (Document candidate : candidatePool) {
      poolRankByChunkId.putIfAbsent(candidate.getId(), index++);
      if (selectedChunkIds.contains(candidate.getId())) {
        continue;
      }
      byChunkId.merge(candidate.getId(), candidate, DocumentCompletion::preferHigherScore);
    }
    Map<String, List<Document>> byDocument =
        byChunkId.values().stream()
            .collect(
                Collectors.groupingBy(
                    QueryService::chunkGroupingKey, LinkedHashMap::new, Collectors.toList()));
    byDocument.replaceAll(
        (documentKey, chunks) ->
            chunks.stream()
                .sorted(Comparator.comparingInt(c -> poolRankByChunkId.get(c.getId())))
                .toList());
    return byDocument;
  }

  /**
   * Removes the weakest (highest {@code originalRankByChunkId}) chunk of some document other than
   * {@code excludeDocumentKey} or any key in {@code protectedDocumentKeys} that currently holds at
   * least two chunks in {@code result} - the eviction rule that keeps document diversity from ever
   * shrinking below what fusion/MMR already established (see this class's Javadoc). Returns {@code
   * false}, leaving {@code result} unchanged, when no such document exists.
   */
  private static boolean evictWeakestFromAnOverrepresentedDocument(
      List<Document> result,
      String excludeDocumentKey,
      Set<String> protectedDocumentKeys,
      Map<String, Integer> originalRankByChunkId) {
    Map<String, List<Document>> byDocument =
        result.stream()
            .collect(
                Collectors.groupingBy(
                    QueryService::chunkGroupingKey, LinkedHashMap::new, Collectors.toList()));
    Document weakest = null;
    int weakestRank = -1;
    for (Map.Entry<String, List<Document>> entry : byDocument.entrySet()) {
      if (entry.getKey().equals(excludeDocumentKey)
          || protectedDocumentKeys.contains(entry.getKey())
          || entry.getValue().size() < 2) {
        continue;
      }
      for (Document candidate : entry.getValue()) {
        int rank = originalRankByChunkId.getOrDefault(candidate.getId(), Integer.MAX_VALUE);
        if (rank > weakestRank) {
          weakestRank = rank;
          weakest = candidate;
        }
      }
    }
    if (weakest == null) {
      return false;
    }
    result.remove(weakest);
    return true;
  }

  /**
   * Tier 2: evicts the lowest-ranked chunk of the whole selection - the entry in {@code
   * originalRankByChunkId} with the highest rank, excluding {@code documentKey}'s own chunks - when
   * {@code documentKey}'s own best original rank beats it strictly. Only chunks with an {@code
   * originalRankByChunkId} entry are eligible at all, which structurally excludes any chunk a
   * completion already added this call - a chunk from {@code candidatePool} never carries one -
   * mirroring tier 1's {@code protectedDocumentKeys} exclusion without needing a second set.
   * Returns {@code false}, leaving {@code result} unchanged, when no eligible victim exists or the
   * strict-rank condition fails. The caller enforces the per-call tier-2 cap (see this class's
   * Javadoc); this method has no cap awareness of its own.
   */
  private static boolean evictLastRankedChunkOfSelection(
      List<Document> result,
      String documentKey,
      int documentBestRank,
      Map<String, Integer> originalRankByChunkId) {
    Document weakest = null;
    int weakestRank = -1;
    for (Document candidate : result) {
      Integer rank = originalRankByChunkId.get(candidate.getId());
      if (rank == null || QueryService.chunkGroupingKey(candidate).equals(documentKey)) {
        continue;
      }
      if (rank > weakestRank) {
        weakestRank = rank;
        weakest = candidate;
      }
    }
    if (weakest == null || documentBestRank >= weakestRank) {
      return false;
    }
    result.remove(weakest);
    return true;
  }

  /** {@code null} scores lose to any non-null one; between two non-null scores, the higher wins. */
  private static Document preferHigherScore(Document a, Document b) {
    Double scoreA = a.getScore();
    Double scoreB = b.getScore();
    if (scoreA == null) {
      return b;
    }
    if (scoreB == null) {
      return a;
    }
    return scoreA >= scoreB ? a : b;
  }
}
