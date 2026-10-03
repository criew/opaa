package io.opaa.indexing.source.filesystem;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Matches document keys against the entries a walk could not read: a key lies in an unreadable area
 * when its normalized path equals such an entry or sits below it. The comparison is per path
 * element, so {@code /a/b} never covers {@code /a/bc}, and follows the platform's own path rules
 * (case-insensitive on Windows). A key that is not a path of this platform matches nothing.
 */
final class UnreadableAreas implements Predicate<String> {

  private final List<Path> areas;

  UnreadableAreas(List<Path> unreadable) {
    this.areas = unreadable.stream().map(p -> p.toAbsolutePath().normalize()).toList();
  }

  @Override
  public boolean test(String documentKey) {
    Path key;
    try {
      key = Path.of(documentKey).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      return false;
    }
    return areas.stream().anyMatch(key::startsWith);
  }
}
