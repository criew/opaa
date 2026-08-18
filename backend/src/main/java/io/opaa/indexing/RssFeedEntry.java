package io.opaa.indexing;

import java.time.Instant;
import java.util.Optional;

/**
 * A single {@code <item>} of a parsed RSS 2.0 feed ({@link RssFeedParser}).
 *
 * <p>{@code link} is guaranteed non-blank - {@link RssFeedParser} skips any item without one, since
 * the link is the only handle the future indexing run (#467) has on the detail page an entry points
 * to. {@code title} and {@code description} are {@code null}, not blank, when the feed omits the
 * corresponding element entirely. {@code publishedAt} is empty both when {@code pubDate} is absent
 * and when it could not be parsed - an unreadable date must not invalidate the entry (ADR-0017,
 * #466).
 */
public record RssFeedEntry(
    String title, String link, String description, Optional<Instant> publishedAt) {}
