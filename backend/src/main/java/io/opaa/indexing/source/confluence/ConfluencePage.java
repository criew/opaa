package io.opaa.indexing.source.confluence;

import java.time.Instant;
import java.util.List;

/**
 * A fully fetched page, edition-independent. Both adapters deliver the body in Confluence's {@code
 * storage} representation (XHTML with macro elements), so content preparation runs the same rules
 * for both editions.
 *
 * @param ancestorTitles titles from the space's top-level ancestor down to the direct parent - the
 *     Gliederungspfad; empty for a root page
 * @param pageUrl the title-free URL that identifies this page in a library and opens it in the
 *     citation (ADR-0023, Entscheidung 4): Cloud {@code /wiki/spaces/<key>/pages/<id>}, Data Center
 *     {@code /pages/viewpage.action?pageId=<id>}
 * @param lastModified when this version was created; {@code null} if the instance did not say
 */
public record ConfluencePage(
    String id,
    String spaceKey,
    String title,
    int version,
    ConfluencePageStatus status,
    List<String> ancestorTitles,
    String storageBody,
    String pageUrl,
    Instant lastModified) {

  public ConfluencePage {
    ancestorTitles = List.copyOf(ancestorTitles);
  }
}
