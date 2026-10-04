package io.opaa.library;

import io.opaa.library.PrivateStorageSummary.MaskedNumber;
import io.opaa.permission.PersonThreshold;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * How numbers about private libraries are masked for the administration ({@link PersonThreshold}):
 * a total exactly only from N persons on; a part exactly only where its persons and those of the
 * rest each reach N; and several parts of one total only as long as what they leave over together
 * rests on at least N persons - a rest of none counts as few - so no difference of two numbers
 * points at fewer than N, nor tells whether anyone is outside.
 */
final class PrivateStorageMask {

  private final PersonThreshold threshold;

  PrivateStorageMask(PersonThreshold threshold) {
    this.threshold = threshold;
  }

  /** A total resting on {@code persons}. */
  MaskedNumber total(long value, long persons) {
    return threshold.discloses(persons)
        ? MaskedNumber.exact(value)
        : MaskedNumber.fewerThan(threshold.minimum());
  }

  /** A part resting on {@code persons}, the rest of its total on {@code rest} persons. */
  MaskedNumber part(long value, long persons, long rest) {
    return threshold.disclosesPart(persons, rest) ? MaskedNumber.exact(value) : withheld(persons);
  }

  /** A part not told exactly: "fewer than N" below N persons, else not told at all. */
  MaskedNumber withheld(long persons) {
    return persons < threshold.minimum()
        ? MaskedNumber.fewerThan(threshold.minimum())
        : MaskedNumber.untold();
  }

  /**
   * Which of several parts of one total may be told exactly. {@code personsOn} holds the persons of
   * each part; {@code personsOutside} counts the persons of everything outside the given parts. A
   * part qualifies on its own as {@link #part}; while the qualifying parts leave over fewer than N
   * persons, none included, the one resting on the fewest drops out.
   */
  <K extends Comparable<K>> Set<K> disclosedParts(
      Map<K, Long> personsOn, ToLongFunction<Set<K>> personsOutside) {
    Set<K> disclosed = new HashSet<>();
    for (Map.Entry<K, Long> part : personsOn.entrySet()) {
      if (threshold.disclosesPart(
          part.getValue(), personsOutside.applyAsLong(Set.of(part.getKey())))) {
        disclosed.add(part.getKey());
      }
    }
    while (!disclosed.isEmpty()) {
      long leftOver = personsOutside.applyAsLong(disclosed);
      if (threshold.discloses(leftOver)) {
        break;
      }
      disclosed.remove(
          disclosed.stream()
              .min(Comparator.comparing((K key) -> personsOn.get(key)).thenComparing(key -> key))
              .orElseThrow());
    }
    return disclosed;
  }
}
