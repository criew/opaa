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
 * <p>Eviction has two tiers, tried in order (#932 Zuschnitt v2 - v1 was a no-op whenever every
 * document in {@code selection} held exactly one chunk, its own live-verification failure mode).
 * Tier 1: the weakest chunk of some <em>other, not-yet-completed</em> document that already holds
 * at least two chunks - that document is never evicted down to zero, and document diversity never
 * drops below what fusion/MMR established. Tier 2, only tried when tier 1 finds no source: the
 * auswahlrang-last chunk of the whole selection, evicted only when the completing document's own
 * best chunk ranks strictly better than that victim, the victim is not itself a chunk this same
 * call already added, and it does not belong to the completing document. Tier 2 may therefore drop
 * a document out of the selection entirely - an accepted trade-off, since a well-ranked document's
 * second chunk is worth more than the single chunk of the table's tail.
 *
 * <p>A document this same pass already completed is never picked as a tier-1 eviction source (see
 * {@link #complete}), and a chunk either tier just added is never eligible as a later document's
 * victim in either tier, so a completion can never undo an earlier one within the same call.
 * Filling never grows {@code selection} past {@code overallBudget}.
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
        } else if (evictSelectionsAuswahlrangLastChunk(
            result,
            documentKey,
            bestOriginalRankByDocument.get(documentKey),
            originalRankByChunkId)) {
          result.add(candidate);
          completedDocumentKeys.add(documentKey);
        } else {
          // Neither tier found an eviction source for this document right now - trying its
          // remaining candidates would not change that. A later document may still succeed: a
          // document that failed to receive a chunk here (unlike one in completedDocumentKeys)
          // stays a valid tier-1 eviction source for it, and its own original chunk stays a valid
          // tier-2 one.
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
   * (see this class's Javadoc). On the single-query path this position <em>is</em> that search's
   * own rank, so the strongest sibling is tried first. On the multi-sub-query path {@code
   * candidatePool} is the flat concatenation of every sub-query's own candidates (see {@code
   * QueryService#retrieveRelevantChunks}), so this position is only rank-fair <em>within</em> one
   * sub-query - a later-processed sub-query's own rank-1 candidate still sorts behind an
   * earlier-processed sub-query's weaker one. That is a tie-break over an already
   * permission/threshold-filtered, budget-capped set of alternatives, not a ranking decision that
   * feeds {@link #evictWeakestFromAnOverrepresentedDocument}'s document-diversity guarantee (which
   * uses {@code selection}'s own authoritative fused rank, see {@link #complete}) - trying a
   * slightly less relevant sibling first before falling through to a stronger one is an accepted,
   * minor imprecision, not a correctness or safety gap.
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
   * Tier 2 (#932 Zuschnitt v2): evicts the auswahlrang-last chunk of the whole selection - the
   * entry in {@code originalRankByChunkId} with the highest rank, excluding {@code documentKey}'s
   * own chunks - when {@code documentKey}'s own best original rank beats it strictly. Only chunks
   * with an {@code originalRankByChunkId} entry are eligible at all, which structurally excludes
   * any chunk a completion already added this call - a chunk from {@code candidatePool} never
   * carries one - mirroring tier 1's {@code protectedDocumentKeys} exclusion without needing a
   * second set. Returns {@code false}, leaving {@code result} unchanged, when no eligible victim
   * exists or the strict-rank condition fails.
   */
  private static boolean evictSelectionsAuswahlrangLastChunk(
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
