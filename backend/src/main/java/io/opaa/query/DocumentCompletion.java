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
 * Completes a fused chunk selection with sibling chunks of documents it already represents
 * (docs/handbuch/suche.md, Stufe 9): up to {@link QueryProperties#maxChunksPerDocument} chunks per
 * document, drawn only from the permission-scoped candidate pool the search stages filled, are
 * preferred over a chunk of a different document filling the remaining budget.
 *
 * <p>Tier 1 evicts the weakest chunk of another, not-yet-completed document already holding at
 * least two, so document diversity never drops below what fusion established. Tier 2 runs only when
 * tier 1 finds no source, is capped at {@code max(1, overallBudget / 4)} evictions per call, and
 * takes the lowest-ranked chunk of the whole selection - only if the completing document's own best
 * chunk ranks strictly better - which may drop a document from the answer.
 *
 * <p>A chunk either tier added is never a later victim, so a completion never undoes an earlier
 * one, and completion never grows the selection past {@code overallBudget}.
 */
final class DocumentCompletion {

  private DocumentCompletion() {}

  /**
   * One completion step, recorded for the explanation protocol: the chunk that was added, the
   * document it completes, and the chunk evicted to make room, if any.
   *
   * @param evicted {@code null} when the selection still had free budget and nothing had to go.
   * @param evictionTier {@code 0} when nothing was evicted, otherwise the tier that chose the
   *     victim - the difference between an eviction that preserved document diversity and one that
   *     may have dropped a document from the answer.
   */
  record CompletionEvent(
      Document added, String completedDocumentKey, Document evicted, int evictionTier) {}

  static List<Document> complete(
      List<Document> selection,
      List<Document> candidatePool,
      int maxChunksPerDocument,
      int overallBudget) {
    return complete(
        selection, candidatePool, maxChunksPerDocument, overallBudget, new ArrayList<>());
  }

  /**
   * The same completion, additionally recording one {@link CompletionEvent} per added chunk into
   * {@code trace}. Recording only - the selection this returns is identical either way, which is
   * what lets {@code DocumentCompletionStage} report what happened without a second implementation
   * of it.
   */
  static List<Document> complete(
      List<Document> selection,
      List<Document> candidatePool,
      int maxChunksPerDocument,
      int overallBudget,
      List<CompletionEvent> trace) {
    if (selection.isEmpty() || maxChunksPerDocument <= 1) {
      return selection;
    }

    // The authoritative rank is the selection's own order - never Document#getScore(), which is
    // only comparable within the single search that produced it (see ReciprocalRankFusion).
    Map<String, Integer> originalRankByChunkId = new HashMap<>();
    Map<String, Integer> bestOriginalRankByDocument = new HashMap<>();
    for (int i = 0; i < selection.size(); i++) {
      Document chunk = selection.get(i);
      originalRankByChunkId.put(chunk.getId(), i);
      bestOriginalRankByDocument.putIfAbsent(ChunkGroupingKey.of(chunk), i);
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

    // Tier 2's per-call cap: unbounded tier-2 eviction could otherwise shrink an eight-topic
    // answer down to a handful of documents in a single call.
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
          trace.add(new CompletionEvent(candidate, documentKey, null, 0));
          continue;
        }
        Document tier1Victim =
            evictWeakestFromAnOverrepresentedDocument(
                result, documentKey, completedDocumentKeys, originalRankByChunkId);
        if (tier1Victim != null) {
          result.add(candidate);
          completedDocumentKeys.add(documentKey);
          trace.add(new CompletionEvent(candidate, documentKey, tier1Victim, 1));
          continue;
        }
        Document tier2Victim =
            tier2EvictionsUsed < tier2EvictionCap
                ? evictLastRankedChunkOfSelection(
                    result,
                    documentKey,
                    bestOriginalRankByDocument.get(documentKey),
                    originalRankByChunkId)
                : null;
        if (tier2Victim != null) {
          result.add(candidate);
          completedDocumentKeys.add(documentKey);
          tier2EvictionsUsed++;
          trace.add(new CompletionEvent(candidate, documentKey, tier2Victim, 2));
          continue;
        }
        // Neither tier found an eviction source for this document right now (or tier 2's cap is
        // exhausted) - trying its remaining candidates would not change that. A later document
        // may still succeed: a document that failed to receive a chunk here (unlike one in
        // completedDocumentKeys) stays a valid tier-1 eviction source for it, and its own
        // original chunk stays a valid tier-2 one.
        break;
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
    return selection.stream().map(ChunkGroupingKey::of).distinct().collect(Collectors.toList());
  }

  private static long countForDocument(List<Document> selection, String documentKey) {
    return selection.stream().filter(d -> ChunkGroupingKey.of(d).equals(documentKey)).count();
  }

  /**
   * Groups every candidate not already in {@code selection} by document, deduplicated by chunk id
   * (the same chunk can appear once per sub-query and once per search path in a pooled candidate
   * list, kept at its first-occurring instance) and ordered by each chunk's own first-occurrence
   * position in {@code candidatePool} - not {@link Document#getScore()}, which is only comparable
   * within the single search that produced it. First-occurring, not highest-scoring, for the reason
   * {@link ReciprocalRankFusion} states for the same tie-break: two instances of one chunk can
   * carry a cosine similarity and a {@code ts_rank}, and the larger of the two means nothing.
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
      byChunkId.putIfAbsent(candidate.getId(), candidate);
    }
    Map<String, List<Document>> byDocument =
        byChunkId.values().stream()
            .collect(
                Collectors.groupingBy(
                    ChunkGroupingKey::of, LinkedHashMap::new, Collectors.toList()));
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
   * shrinking below what fusion/MMR already established (see this class's Javadoc). Returns the
   * evicted chunk, or {@code null} - leaving {@code result} unchanged - when no such document
   * exists.
   */
  private static Document evictWeakestFromAnOverrepresentedDocument(
      List<Document> result,
      String excludeDocumentKey,
      Set<String> protectedDocumentKeys,
      Map<String, Integer> originalRankByChunkId) {
    Map<String, List<Document>> byDocument =
        result.stream()
            .collect(
                Collectors.groupingBy(
                    ChunkGroupingKey::of, LinkedHashMap::new, Collectors.toList()));
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
      return null;
    }
    result.remove(weakest);
    return weakest;
  }

  /**
   * Tier 2: evicts the lowest-ranked chunk of the whole selection - the entry in {@code
   * originalRankByChunkId} with the highest rank, excluding {@code documentKey}'s own chunks - when
   * {@code documentKey}'s own best original rank beats it strictly. Only chunks with an {@code
   * originalRankByChunkId} entry are eligible at all, which structurally excludes any chunk a
   * completion already added this call - a chunk from {@code candidatePool} never carries one -
   * mirroring tier 1's {@code protectedDocumentKeys} exclusion without needing a second set.
   * Returns the evicted chunk, or {@code null} - leaving {@code result} unchanged - when no
   * eligible victim exists or the strict-rank condition fails. The caller enforces the per-call
   * tier-2 cap (see this class's Javadoc); this method has no cap awareness of its own.
   */
  private static Document evictLastRankedChunkOfSelection(
      List<Document> result,
      String documentKey,
      int documentBestRank,
      Map<String, Integer> originalRankByChunkId) {
    Document weakest = null;
    int weakestRank = -1;
    for (Document candidate : result) {
      Integer rank = originalRankByChunkId.get(candidate.getId());
      if (rank == null || ChunkGroupingKey.of(candidate).equals(documentKey)) {
        continue;
      }
      if (rank > weakestRank) {
        weakestRank = rank;
        weakest = candidate;
      }
    }
    if (weakest == null || documentBestRank >= weakestRank) {
      return null;
    }
    result.remove(weakest);
    return weakest;
  }
}
