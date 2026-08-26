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
 * represents (#932, Lösungsrichtung 1 of #912's follow-up): once a document has a chunk in {@code
 * selection}, up to {@link QueryProperties#maxChunksPerDocument} of its chunks from {@code
 * candidatePool} are preferred over a second, third, ... chunk of a <em>different</em> document
 * that would otherwise fill the remaining budget - the failure mode where a document's true-but-
 * lower-ranked answer (e.g. a fee table) loses its slot to an unrelated document's chunk merely
 * because RRF/MMR spread the budget across topics before completeness within one document was
 * considered. {@code candidatePool} must already be permission- and threshold-filtered (the same
 * pool {@code similaritySearch} produced for {@code selection} itself) - this class only ever
 * reorders/replaces within that set, never searches or admits anything beyond it.
 *
 * <p>The set of documents represented in {@code selection} never shrinks: filling never grows
 * {@code selection} past {@code overallBudget}, and once full, a candidate can only be admitted by
 * evicting the weakest chunk of some <em>other, not-yet-completed</em> document that already holds
 * at least two chunks - a document is never evicted down to zero, and a document this same pass
 * already completed is never picked as an eviction source (see {@link #complete}), so a completion
 * can never undo an earlier one within the same call.
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
    for (int i = 0; i < selection.size(); i++) {
      originalRankByChunkId.put(selection.get(i).getId(), i);
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
        } else {
          // No eviction source is available for this document right now - trying its remaining
          // candidates would not change that. A later document may still succeed: a document that
          // failed to receive a chunk here (unlike one in completedDocumentKeys) stays a valid
          // eviction source for it.
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
   * (the same chunk can appear once per sub-query in a pooled multi-query candidate list) and
   * ordered by each chunk's own first-occurrence position in {@code candidatePool} - not {@link
   * Document#getScore()}, which is only comparable within the single search vector that produced it
   * (see this class's Javadoc) - so the strongest sibling by that pool's own rank is tried first.
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
