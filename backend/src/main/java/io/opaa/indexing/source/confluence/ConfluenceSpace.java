package io.opaa.indexing.source.confluence;

/**
 * A space as both editions describe it. {@code id} is the instance's numeric identifier as a string
 * (Cloud v2 addresses pages by space id, Data Center by key); {@code key} is the stable,
 * user-visible selector a library stores.
 */
public record ConfluenceSpace(String id, String key, String name) {}
