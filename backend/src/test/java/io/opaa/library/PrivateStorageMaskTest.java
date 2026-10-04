package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.library.PrivateStorageSummary.MaskedNumber;
import io.opaa.permission.GroupSizeProperties;
import io.opaa.permission.PersonThreshold;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Numbers about private libraries with a minimum group size of 5: no number, and no difference of
 * two numbers of one answer, rests on one to four persons.
 */
class PrivateStorageMaskTest {

  private final PrivateStorageMask mask =
      new PrivateStorageMask(new PersonThreshold(new GroupSizeProperties(5)));

  @Test
  void aTotalIsExactFromTheMinimumOn() {
    assertThat(mask.total(1234, 5)).isEqualTo(MaskedNumber.exact(1234));
    assertThat(mask.total(1234, 4)).isEqualTo(MaskedNumber.fewerThan(5));
    assertThat(mask.total(0, 0)).isEqualTo(MaskedNumber.fewerThan(5));
  }

  @Test
  void aPartIsExactOnlyWhereItAndItsRestReachTheMinimum() {
    assertThat(mask.part(10, 5, 5)).isEqualTo(MaskedNumber.exact(10));
    assertThat(mask.part(10, 4, 9)).isEqualTo(MaskedNumber.fewerThan(5));
    assertThat(mask.part(10, 0, 9)).isEqualTo(MaskedNumber.fewerThan(5));
    assertThat(mask.part(10, 9, 4)).isEqualTo(MaskedNumber.untold());
    assertThat(mask.part(10, 9, 0)).isEqualTo(MaskedNumber.untold());
  }

  /**
   * Profiles A and B rest on five persons each, C on one: each of A and B qualifies alone, but
   * together they would leave C's single person as the difference to the total - one drops out.
   */
  @Test
  void partsThatTogetherLeaveFewPersonsOverAreNotAllTold() {
    Map<String, Set<String>> owners =
        Map.of(
            "A", Set.of("a1", "a2", "a3", "a4", "a5"),
            "B", Set.of("b1", "b2", "b3", "b4", "b5"),
            "C", Set.of("c1"));

    Set<String> disclosed =
        mask.disclosedParts(
            Map.of("A", 5L, "B", 5L, "C", 1L), parts -> personsOutside(owners, parts));

    assertThat(disclosed).containsExactly("B");
  }

  /**
   * Regression guard: whether nobody or one person keeps a private library outside the two parts
   * must not change the answer - a rest of none counts as few, like a rest of one.
   */
  @Test
  void aRestOfNoneAnswersLikeARestOfOne() {
    Map<String, Set<String>> withoutOutsider =
        Map.of(
            "A", Set.of("a1", "a2", "a3", "a4", "a5"),
            "B", Set.of("b1", "b2", "b3", "b4", "b5"));
    Map<String, Set<String>> withOutsider =
        Map.of(
            "A", Set.of("a1", "a2", "a3", "a4", "a5"),
            "B", Set.of("b1", "b2", "b3", "b4", "b5"),
            "outside", Set.of("x"));
    Map<String, Long> parts = Map.of("A", 5L, "B", 5L);

    Set<String> restOfNone =
        mask.disclosedParts(parts, given -> personsOutside(withoutOutsider, given));
    Set<String> restOfOne =
        mask.disclosedParts(parts, given -> personsOutside(withOutsider, given));

    assertThat(restOfNone).isEqualTo(restOfOne).containsExactly("B");
  }

  @Test
  void partsLeavingEnoughPersonsOverAreAllTold() {
    Map<String, Set<String>> owners =
        Map.of(
            "A", Set.of("a1", "a2", "a3", "a4", "a5"),
            "B", Set.of("b1", "b2", "b3", "b4", "b5"),
            "C", Set.of("c1", "c2", "c3", "c4", "c5"),
            "outside", Set.of("o1", "o2", "o3", "o4", "o5"));

    assertThat(
            mask.disclosedParts(
                Map.of("A", 5L, "B", 5L, "C", 5L), parts -> personsOutside(owners, parts)))
        .containsExactlyInAnyOrder("A", "B", "C");
  }

  /**
   * A person on two profiles counts in both: the rest of each part alone still rests on five, but
   * both together leave nobody over, so only one is told.
   */
  @Test
  void aPersonOnSeveralPartsCountsInEach() {
    Map<String, Set<String>> owners =
        Map.of(
            "A", Set.of("p1", "p2", "p3", "p4", "x"),
            "B", Set.of("q1", "q2", "q3", "q4", "x"));

    assertThat(
            mask.disclosedParts(Map.of("A", 5L, "B", 5L), parts -> personsOutside(owners, parts)))
        .containsExactly("B");
  }

  @Test
  void fewOwnersOverallTellNoPart() {
    Map<String, Set<String>> owners = Map.of("A", Set.of("a1", "a2"), "B", Set.of("b1"));

    assertThat(
            mask.disclosedParts(Map.of("A", 2L, "B", 1L), parts -> personsOutside(owners, parts)))
        .isEmpty();
  }

  /** The owners of every part outside {@code parts}, as the libraries outside them rest on. */
  private static long personsOutside(Map<String, Set<String>> owners, Set<String> parts) {
    Set<String> outside = new HashSet<>();
    owners.forEach(
        (part, persons) -> {
          if (!parts.contains(part)) {
            outside.addAll(persons);
          }
        });
    return outside.size();
  }
}
