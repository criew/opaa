package io.opaa.retrieval.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.knowledge.VectorTable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class NeighborChunkExpansionTest {

  private static final Set<UUID> SCOPE = Set.of(UUID.randomUUID());

  /** Every stored chunk of the fake index, by position; text length is set per chunk. */
  private final Map<ChunkPosition, Document> index = new HashMap<>();

  private final List<Collection<ChunkPosition>> lookups = new ArrayList<>();

  private final NeighborChunkLookup lookup =
      new NeighborChunkLookup(null, null, new VectorTable("public", "vector_store")) {
        @Override
        public Map<ChunkPosition, Document> find(
            Collection<ChunkPosition> positions, Set<UUID> libraryIds) {
          lookups.add(List.copyOf(positions));
          Map<ChunkPosition, Document> found = new HashMap<>();
          positions.forEach(
              position -> {
                if (index.containsKey(position)) {
                  found.put(position, index.get(position));
                }
              });
          return found;
        }
      };

  @Test
  void switchedOffReturnsTheSelectionWithoutLookingUpAnything() {
    store("a", 0, 1, 2);
    List<Document> selection = List.of(chunk("a", 1));

    List<Document> result = expansion(0, 40_000).expand(selection, SCOPE);

    assertThat(result).isSameAs(selection);
    assertThat(lookups).isEmpty();
  }

  @Test
  void addsBothNeighboursAndOrdersTheExcerptByIndex() {
    store("a", 0, 1, 2);

    List<Document> result = expansion(1, 40_000).expand(List.of(index(chunk("a", 1))), SCOPE);

    assertThat(positions(result)).containsExactly("a#0", "a#1", "a#2");
  }

  @Test
  void neverLoadsANegativeIndexOrAPositionAlreadySelected() {
    store("a", 0, 1, 2);

    expansion(1, 40_000).expand(List.of(index(chunk("a", 0)), index(chunk("a", 1))), SCOPE);

    assertThat(lookups).hasSize(1);
    assertThat(lookups.get(0)).extracting(ChunkPosition::key).containsExactly("a#2");
  }

  @Test
  void ordersExcerptsByTheirBestSelectedChunk() {
    store("a", 3, 4, 5);
    store("b", 0, 1);

    List<Document> result =
        expansion(1, 40_000).expand(List.of(index(chunk("b", 0)), index(chunk("a", 4))), SCOPE);

    assertThat(positions(result)).containsExactly("b#0", "b#1", "a#3", "a#4", "a#5");
  }

  @Test
  void keepsSeparateExcerptsOfOneDocumentApartAtTheirOwnRanks() {
    store("a", 0, 1, 2, 7, 8, 9);
    store("b", 5);

    List<Document> result =
        expansion(1, 40_000)
            .expand(
                List.of(index(chunk("a", 8)), index(chunk("b", 5)), index(chunk("a", 1))), SCOPE);

    assertThat(positions(result)).containsExactly("a#7", "a#8", "a#9", "b#5", "a#0", "a#1", "a#2");
  }

  @Test
  void stopsAddingOnceTheCharacterBudgetIsSpent() {
    store("a", 0, 1, 2);
    store("b", 0, 1, 2);
    // 100 characters each: the budget of 250 takes the following neighbour of both selected
    // chunks, and then nothing more fits.
    List<Document> result =
        expansion(1, 250).expand(List.of(index(chunk("a", 1)), index(chunk("b", 1))), SCOPE);

    assertThat(positions(result)).containsExactly("a#1", "a#2", "b#1", "b#2");
  }

  @Test
  void aTightBudgetStillGivesTheLastSelectedChunkANeighbour() {
    store("a", 0, 1, 2);
    store("b", 0, 1, 2);
    store("c", 0, 1, 2);

    List<Document> result =
        expansion(1, 300)
            .expand(
                List.of(index(chunk("a", 1)), index(chunk("b", 1)), index(chunk("c", 1))), SCOPE);

    assertThat(positions(result)).containsExactly("a#1", "a#2", "b#1", "b#2", "c#1", "c#2");
  }

  @Test
  void spendsTheRestOfTheBudgetInRankOrderAfterEverySelectedChunkHasOneNeighbour() {
    store("a", 0, 1, 2);
    store("b", 0, 1, 2);
    store("c", 0, 1, 2);

    List<Document> result =
        expansion(1, 400)
            .expand(
                List.of(index(chunk("a", 1)), index(chunk("b", 1)), index(chunk("c", 1))), SCOPE);

    assertThat(positions(result)).containsExactly("a#0", "a#1", "a#2", "b#1", "b#2", "c#1", "c#2");
  }

  @Test
  void takesThePrecedingNeighbourFirstWhenNoFollowingOneExists() {
    // a#2 is the last chunk of its document; b#1 comes after it in rank.
    store("a", 0, 1, 2);
    store("b", 0, 1, 2);

    List<Document> result =
        expansion(1, 200).expand(List.of(index(chunk("a", 2)), index(chunk("b", 1))), SCOPE);

    assertThat(positions(result)).containsExactly("a#1", "a#2", "b#1", "b#2");
  }

  @Test
  void takesTheOtherSideFirstWhenTheFollowingNeighbourDoesNotFit() {
    store("a", 0, 1);
    store("b", 0, 1, 2);
    index.put(new ChunkPosition("a", 2), chunk("a", 2, 500));

    List<Document> result =
        expansion(1, 200).expand(List.of(index(chunk("a", 1)), index(chunk("b", 1))), SCOPE);

    assertThat(positions(result)).containsExactly("a#0", "a#1", "b#1", "b#2");
  }

  @Test
  void givesNoFirstRoundNeighbourToAChunkThatAlreadyAdjoinsTheContext() {
    // a#1 and a#2 are both selected and adjoin each other; c#1 still gets its neighbour.
    store("a", 0, 1, 2, 3);
    store("c", 0, 1, 2);

    List<Document> result =
        expansion(1, 100)
            .expand(
                List.of(index(chunk("a", 1)), index(chunk("a", 2)), index(chunk("c", 1))), SCOPE);

    assertThat(positions(result)).containsExactly("a#1", "a#2", "c#1", "c#2");
  }

  @Test
  void offersEveryNearerNeighbourBeforeAnyFartherOne() {
    store("a", 0, 1, 2, 3, 4);
    store("b", 0, 1, 2);

    List<Document> result =
        expansion(2, 400).expand(List.of(index(chunk("a", 2)), index(chunk("b", 1))), SCOPE);

    assertThat(positions(result)).containsExactly("a#1", "a#2", "a#3", "b#0", "b#1", "b#2");
  }

  @Test
  void neverAddsAFartherNeighbourAcrossAGap() {
    // a#1 is missing from the index: a#0 would not adjoin anything selected.
    store("a", 0, 2);

    List<Document> result = expansion(2, 40_000).expand(List.of(index(chunk("a", 2))), SCOPE);

    assertThat(positions(result)).containsExactly("a#2");
  }

  @Test
  void keepsAChunkWithoutPositionAtItsRank() {
    store("a", 0, 1);
    Document withoutIndex =
        Document.builder().id("loose").text("x").metadata(Map.of("document_id", "c")).build();

    List<Document> result =
        expansion(1, 40_000).expand(List.of(withoutIndex, index(chunk("a", 1))), SCOPE);

    assertThat(result.get(0)).isSameAs(withoutIndex);
    assertThat(positions(result.subList(1, result.size()))).containsExactly("a#0", "a#1");
  }

  @Test
  void returnsTheSelectionUnchangedWhenNoNeighbourExists() {
    store("a", 0);
    List<Document> selection = List.of(index(chunk("a", 0)));

    assertThat(expansion(1, 40_000).expand(selection, SCOPE)).isSameAs(selection);
  }

  @Test
  void aBudgetOfZeroSwitchesTheExpansionOffWithoutLookingUpAnything() {
    store("a", 0, 1, 2);
    List<Document> selection = List.of(index(chunk("a", 1)));

    assertThat(new NeighborChunkProperties(1, 0).enabled()).isFalse();
    assertThat(expansion(1, 0).expand(selection, SCOPE)).isSameAs(selection);
    assertThat(lookups).isEmpty();
  }

  @Test
  void acceptsTheBudgetBoundsAndRejectsValuesOutsideThem() {
    assertThat(new NeighborChunkProperties(1, 1_000_000).maxAddedCharacters()).isEqualTo(1_000_000);
    assertThatThrownBy(() -> new NeighborChunkProperties(1, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NeighborChunkProperties(1, 1_000_001))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsARadiusOutsideItsBounds() {
    assertThatThrownBy(() -> new NeighborChunkProperties(4, 1000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NeighborChunkProperties(-1, 1000))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private NeighborChunkExpansion expansion(int radius, int maxAddedCharacters) {
    return new NeighborChunkExpansion(
        lookup, new NeighborChunkProperties(radius, maxAddedCharacters));
  }

  private void store(String documentId, int... chunkIndexes) {
    for (int chunkIndex : chunkIndexes) {
      Document chunk = chunk(documentId, chunkIndex);
      index.put(new ChunkPosition(documentId, chunkIndex), chunk);
    }
  }

  /** The stored instance of {@code chunk}, so selection and index share identities. */
  private Document index(Document chunk) {
    return index.getOrDefault(ChunkPosition.of(chunk).orElseThrow(), chunk);
  }

  private static Document chunk(String documentId, int chunkIndex) {
    return chunk(documentId, chunkIndex, 100);
  }

  private static Document chunk(String documentId, int chunkIndex, int length) {
    return Document.builder()
        .id(documentId + "-" + chunkIndex)
        .text("x".repeat(length))
        .metadata(Map.of("document_id", documentId, "chunk_index", chunkIndex))
        .build();
  }

  private static List<String> positions(List<Document> chunks) {
    return chunks.stream().map(c -> ChunkPosition.of(c).orElseThrow().key()).toList();
  }
}
