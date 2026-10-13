package io.opaa.retrieval.ranking;

import java.util.List;
import java.util.function.Predicate;

/**
 * The selection rule of the guaranteed top candidates ({@code
 * opaa.query.fusion.guaranteed-per-list}): every guaranteed candidate is taken, the remaining
 * places go to the others in the given order, and the budget stays the cap. Should the guaranteed
 * candidates alone exceed the budget, the earliest of them win. The selection keeps the given
 * order. Without a guaranteed candidate it is the head of the list - the rule is the identity when
 * the guarantee is off.
 */
final class GuaranteedSelection {

  private GuaranteedSelection() {}

  /** Which positions of {@code ordered} the selection takes, as a mask over the list. */
  static <T> boolean[] select(List<T> ordered, Predicate<T> guaranteed, int budget) {
    boolean[] taken = new boolean[ordered.size()];
    int guaranteedCount = 0;
    for (T candidate : ordered) {
      if (guaranteed.test(candidate)) {
        guaranteedCount++;
      }
    }
    int guaranteedPlaces = Math.min(guaranteedCount, budget);
    int freePlaces = budget - guaranteedPlaces;
    for (int i = 0; i < ordered.size(); i++) {
      if (guaranteed.test(ordered.get(i))) {
        if (guaranteedPlaces > 0) {
          taken[i] = true;
          guaranteedPlaces--;
        }
      } else if (freePlaces > 0) {
        taken[i] = true;
        freePlaces--;
      }
    }
    return taken;
  }
}
