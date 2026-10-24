package io.opaa.indexing.spi;

import java.util.List;

/**
 * What a {@link SourceBrowser} found.
 *
 * @param complete {@code false} when the source refused the listing itself - {@code message} then
 *     says what to do instead, and {@code entries} is empty - or when the listing stopped at a
 *     limit of the connector, {@code entries} then holding the first ones found (see {@link
 *     #cutAt})
 * @param message German, user-facing; {@code null} for a complete listing
 */
public record SourceListing(boolean complete, List<Entry> entries, String message) {

  public SourceListing {
    entries = List.copyOf(entries);
  }

  /**
   * An incomplete listing of the first {@code entries} found before a limit of the connector,
   * possibly none when the part read held nothing selectable; {@code noun} names them in the plural
   * ({@code "Ordner"}), {@code hint} says how to reach the others.
   */
  public static SourceListing cutAt(List<Entry> entries, String noun, String hint) {
    String shown =
        entries.isEmpty()
            ? "Im gelesenen Teil der Liste sind keine " + noun + "."
            : "Es werden die ersten " + entries.size() + " " + noun + " gezeigt.";
    return new SourceListing(false, entries, shown + " " + hint);
  }

  /** One selectable item: the key the configuration stores, and a display name if there is one. */
  public record Entry(String key, String name) {}
}
