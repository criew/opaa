package io.opaa.indexing.source.smb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The file ids of the folders a walk has met, as a short text for its checkpoint: sorted as
 * unsigned numbers, each the distance to the one before, in base 36, separated by dots.
 */
final class VisitedFolders {

  private VisitedFolders() {}

  static String encode(Set<Long> ids) {
    List<Long> sorted = new ArrayList<>(ids);
    sorted.sort(Long::compareUnsigned);
    StringBuilder text = new StringBuilder();
    long previous = 0;
    for (long id : sorted) {
      if (!text.isEmpty()) {
        text.append('.');
      }
      text.append(Long.toUnsignedString(id - previous, 36));
      previous = id;
    }
    return text.toString();
  }

  /**
   * @throws IllegalArgumentException when {@code text} is not what {@link #encode} wrote
   */
  static Set<Long> decode(String text) {
    Set<Long> ids = new HashSet<>();
    if (text == null || text.isEmpty()) {
      return ids;
    }
    long previous = 0;
    for (String part : text.split("\\.", -1)) {
      previous += Long.parseUnsignedLong(part, 36);
      ids.add(previous);
    }
    return ids;
  }
}
