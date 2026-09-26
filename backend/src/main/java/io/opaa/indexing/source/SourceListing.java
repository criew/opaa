package io.opaa.indexing.source;

import java.util.List;

/**
 * What a {@link SourceBrowser} found.
 *
 * @param complete {@code false} when the source refused the listing itself - {@code message} then
 *     says what to do instead, and {@code entries} is empty
 * @param message German, user-facing; {@code null} for a complete listing
 */
public record SourceListing(boolean complete, List<Entry> entries, String message) {

  public SourceListing {
    entries = List.copyOf(entries);
  }

  /** One selectable item: the key the configuration stores, and a display name if there is one. */
  public record Entry(String key, String name) {}
}
