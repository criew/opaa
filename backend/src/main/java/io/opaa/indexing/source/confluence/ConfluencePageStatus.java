package io.opaa.indexing.source.confluence;

/**
 * What the instance says about a page's existence. Only {@link #CURRENT} pages are indexed; {@link
 * #TRASHED} is a positive finding that the page is gone from the reader's perspective (ADR-0023,
 * Entscheidung 4), {@link #OTHER} covers drafts, archived and historical versions, which are never
 * indexed either.
 */
public enum ConfluencePageStatus {
  CURRENT,
  TRASHED,
  OTHER;

  static ConfluencePageStatus fromApi(String status) {
    if (status == null) {
      return CURRENT;
    }
    return switch (status.toLowerCase()) {
      case "current" -> CURRENT;
      case "trashed", "deleted" -> TRASHED;
      default -> OTHER;
    };
  }
}
