package io.opaa.indexing.source.confluence;

/**
 * A space as both editions describe it. {@code id} is the instance's numeric identifier as a string
 * (Cloud v2 addresses pages by space id, Data Center by key); {@code key} is the stable,
 * user-visible selector a library stores; {@code personal} marks a person's own space, which the
 * scope "Alle Spaces" leaves out.
 */
public record ConfluenceSpace(String id, String key, String name, boolean personal) {

  /** A space that is not a personal one. */
  public ConfluenceSpace(String id, String key, String name) {
    this(id, key, name, false);
  }

  /** As an edition describes it: {@code type} {@code personal}, or a key opening with "~". */
  static ConfluenceSpace of(String id, String key, String name, String type) {
    return new ConfluenceSpace(
        id, key, name, "personal".equalsIgnoreCase(type) || (key != null && key.startsWith("~")));
  }
}
