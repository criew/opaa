package io.opaa.indexing;

/**
 * Where inside its source a connector document sits (ADR-0023, "Identität und Metadaten"): the
 * container it came from - a Confluence space key - and its hierarchy path, the ancestor titles
 * root first joined with {@value #HIERARCHY_SEPARATOR}. Persisted on {@link Document} so the run
 * protocol, the citation and the chunk context can name them without asking the instance; {@code
 * null} fields for every source type without such a notion.
 */
public record SourceDocumentContext(String containerKey, String hierarchyPath) {

  public static final String HIERARCHY_SEPARATOR = " / ";

  public static final SourceDocumentContext NONE = new SourceDocumentContext(null, null);

  /** {@code hierarchyPath} extended by one more level, or just {@code title} at the root. */
  public SourceDocumentContext descend(String title) {
    String path =
        hierarchyPath == null || hierarchyPath.isBlank()
            ? title
            : hierarchyPath + HIERARCHY_SEPARATOR + title;
    return new SourceDocumentContext(containerKey, path);
  }
}
