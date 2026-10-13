package io.opaa.retrieval.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Widens the selection the answer prompt is built from by the neighbours of every selected chunk -
 * {@code chunk_index ± radius} of the same document - so a passage cut at a chunk boundary reaches
 * the model whole (docs/handbuch/suche.md, Abschnitt 6). Runs after the pipeline and only for the
 * answer: the selection, its ranking and the search hits stay what the pipeline decided.
 *
 * <p>{@link NeighborChunkProperties#maxAddedCharacters} is spent in two rounds. First every
 * selected chunk in rank order gets one direct neighbour - the following one, else the preceding
 * one - unless a direct neighbour of it is already in the context. Then the rest goes nearest first
 * and in rank order, each neighbour only next to a chunk already present. A neighbour that no
 * longer fits is skipped. The result is ordered as continuous excerpts: every run of adjoining
 * chunks of one document in index order, the runs in the rank order of their best selected chunk.
 * Every chunk - neighbour or not - stays a chunk of its own with its own citation target, so a
 * citation of a neighbour validates like any other.
 */
@Component
public class NeighborChunkExpansion {

  private final NeighborChunkLookup lookup;
  private final NeighborChunkProperties properties;

  public NeighborChunkExpansion(NeighborChunkLookup lookup, NeighborChunkProperties properties) {
    this.lookup = lookup;
    this.properties = properties;
  }

  /**
   * {@code selection} widened by its neighbours within {@code searchScope}; {@code selection}
   * itself when the expansion is switched off, nothing is selected or nothing could be added.
   */
  public List<Document> expand(List<Document> selection, Set<UUID> searchScope) {
    if (!properties.enabled() || selection.isEmpty() || searchScope.isEmpty()) {
      return selection;
    }
    Set<ChunkPosition> present = new HashSet<>();
    for (Document chunk : selection) {
      ChunkPosition.of(chunk).ifPresent(present::add);
    }

    // Candidate -> the position one step closer to the selected chunk it extends. Distance-major
    // order: every nearer neighbour of the whole selection is offered before any farther one.
    Map<ChunkPosition, ChunkPosition> wanted = new LinkedHashMap<>();
    for (int distance = 1; distance <= properties.radius(); distance++) {
      for (Document chunk : selection) {
        Optional<ChunkPosition> position = ChunkPosition.of(chunk);
        if (position.isEmpty()) {
          continue;
        }
        for (int side : new int[] {-1, 1}) {
          ChunkPosition candidate = position.get().shifted(side * distance);
          if (candidate.chunkIndex() >= 0 && !present.contains(candidate)) {
            wanted.putIfAbsent(candidate, position.get().shifted(side * (distance - 1)));
          }
        }
      }
    }
    if (wanted.isEmpty()) {
      return selection;
    }

    Budget budget =
        new Budget(
            lookup.find(wanted.keySet(), searchScope),
            new HashSet<>(present),
            properties.maxAddedCharacters());

    // First round: one direct neighbour per selected chunk, the following one if it can be added.
    for (Document chunk : selection) {
      Optional<ChunkPosition> position = ChunkPosition.of(chunk);
      if (position.isEmpty()
          || budget.includes(position.get().shifted(1))
          || budget.includes(position.get().shifted(-1))) {
        continue;
      }
      if (!budget.add(position.get().shifted(1))) {
        budget.add(position.get().shifted(-1));
      }
    }
    // Second round: the rest of the budget, nearest first and in rank order.
    for (Map.Entry<ChunkPosition, ChunkPosition> entry : wanted.entrySet()) {
      if (budget.includes(entry.getValue())) {
        budget.add(entry.getKey());
      }
    }
    if (budget.added.isEmpty()) {
      return selection;
    }
    return asExcerpts(selection, budget.added);
  }

  /** The neighbours added so far and the characters still free for further ones. */
  private static final class Budget {
    private final Map<ChunkPosition, Document> loaded;
    private final Set<ChunkPosition> included;
    private final List<Document> added = new ArrayList<>();
    private int remainingCharacters;

    private Budget(
        Map<ChunkPosition, Document> loaded, Set<ChunkPosition> included, int characters) {
      this.loaded = loaded;
      this.included = included;
      this.remainingCharacters = characters;
    }

    private boolean includes(ChunkPosition position) {
      return included.contains(position);
    }

    /**
     * Adds the neighbour at {@code position} if it was loaded, is not in the context yet and still
     * fits; returns whether it was added.
     */
    private boolean add(ChunkPosition position) {
      Document neighbour = loaded.get(position);
      if (neighbour == null || included.contains(position)) {
        return false;
      }
      int length = neighbour.getText() == null ? 0 : neighbour.getText().length();
      if (length > remainingCharacters) {
        return false;
      }
      remainingCharacters -= length;
      included.add(position);
      added.add(neighbour);
      return true;
    }
  }

  /**
   * Orders {@code selection} and {@code added} as continuous excerpts. Every neighbour adjoins a
   * selected chunk, so every run holds at least one, and its rank is that of its best one. A
   * selected chunk without a position is a run of its own.
   */
  private static List<Document> asExcerpts(List<Document> selection, List<Document> added) {
    Map<String, TreeMap<Integer, Document>> byDocument = new HashMap<>();
    Map<ChunkPosition, Integer> rankOf = new HashMap<>();
    List<Excerpt> excerpts = new ArrayList<>();
    for (int rank = 0; rank < selection.size(); rank++) {
      Document chunk = selection.get(rank);
      Optional<ChunkPosition> position = ChunkPosition.of(chunk);
      if (position.isEmpty()) {
        excerpts.add(new Excerpt(rank, List.of(chunk)));
        continue;
      }
      rankOf.putIfAbsent(position.get(), rank);
      byDocument
          .computeIfAbsent(position.get().documentId(), id -> new TreeMap<>())
          .putIfAbsent(position.get().chunkIndex(), chunk);
    }
    for (Document neighbour : added) {
      ChunkPosition position = ChunkPosition.of(neighbour).orElseThrow();
      byDocument
          .computeIfAbsent(position.documentId(), id -> new TreeMap<>())
          .putIfAbsent(position.chunkIndex(), neighbour);
    }
    for (Map.Entry<String, TreeMap<Integer, Document>> document : byDocument.entrySet()) {
      List<Document> run = new ArrayList<>();
      int runRank = Integer.MAX_VALUE;
      Integer previousIndex = null;
      for (Map.Entry<Integer, Document> chunk : document.getValue().entrySet()) {
        if (previousIndex != null && chunk.getKey() != previousIndex + 1) {
          excerpts.add(new Excerpt(runRank, run));
          run = new ArrayList<>();
          runRank = Integer.MAX_VALUE;
        }
        run.add(chunk.getValue());
        Integer rank = rankOf.get(new ChunkPosition(document.getKey(), chunk.getKey()));
        if (rank != null) {
          runRank = Math.min(runRank, rank);
        }
        previousIndex = chunk.getKey();
      }
      excerpts.add(new Excerpt(runRank, run));
    }
    excerpts.sort(Comparator.comparingInt(Excerpt::rank));
    return excerpts.stream().flatMap(excerpt -> excerpt.chunks().stream()).toList();
  }

  private record Excerpt(int rank, List<Document> chunks) {}
}
