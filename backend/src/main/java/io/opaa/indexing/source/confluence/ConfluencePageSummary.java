package io.opaa.indexing.source.confluence;

/**
 * A page as a listing returns it - identity, title, version and parent, deliberately <em>no
 * body</em>: listings and searches yield identifiers, content is fetched one page at a time
 * (ADR-0023; CQL with body expansion silently caps at 50 hits).
 *
 * @param parentId {@code null} for a space's root page
 * @param version the page's version number - the change marker compared before any body is fetched
 */
public record ConfluencePageSummary(
    String id, String spaceKey, String title, int version, String parentId) {}
