package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link AttachmentProfile} in isolation, with fictional {@code example.gov}-style
 * addresses only (#468 acceptance criteria: no test references a real address, and the GSB profile
 * is checked against a nachgebildete page, not a real institution's).
 */
class AttachmentProfileTest {

  private static Element contentArea(String html) {
    return Jsoup.parse(html, "https://example.gov/artikel/mein-artikel").body();
  }

  @Test
  void genericFindsALinkWithASupportedExtensionOnTheSameHost() {
    Element content =
        contentArea("<main><a href=\"https://example.gov/downloads/anlage.pdf\">Anlage</a></main>");

    List<AttachmentCandidate> attachments =
        AttachmentProfile.GENERIC.findAttachments(
            content, URI.create("https://example.gov/artikel/mein-artikel"));

    assertThat(attachments)
        .containsExactly(
            new AttachmentCandidate("https://example.gov/downloads/anlage.pdf", "anlage.pdf"));
  }

  @Test
  void genericIgnoresALinkWithoutASupportedExtension() {
    Element content =
        contentArea(
            "<main><a href=\"https://example.gov/artikel/anderer-artikel\">Weiter</a></main>");

    List<AttachmentCandidate> attachments =
        AttachmentProfile.GENERIC.findAttachments(
            content, URI.create("https://example.gov/artikel/mein-artikel"));

    assertThat(attachments).isEmpty();
  }

  @Test
  void genericIgnoresALinkToAForeignHost() {
    // #468 acceptance criteria: "Verweise, die aus der Seite hinausführen, gelten nicht als
    // Anlage" - a link to a foreign host is never an attachment, even with a supported extension.
    Element content =
        contentArea("<main><a href=\"https://anderes-beispiel.gov/anlage.pdf\">Fremd</a></main>");

    List<AttachmentCandidate> attachments =
        AttachmentProfile.GENERIC.findAttachments(
            content, URI.create("https://example.gov/artikel/mein-artikel"));

    assertThat(attachments).isEmpty();
  }

  @Test
  void gsbFindsAQueryParameterAttachmentWithoutAFileExtension() {
    // Generic reproduction of the Government Site Builder pattern (#468): the file is served
    // through __blob=publicationFile on the page's own address, and the URL itself carries no
    // file extension.
    Element content =
        contentArea(
            "<main><a href=\"https://example.gov/service/mein-dokument?__blob=publicationFile\">"
                + "Herunterladen</a></main>");

    List<AttachmentCandidate> attachments =
        AttachmentProfile.GSB.findAttachments(
            content, URI.create("https://example.gov/artikel/mein-artikel"));

    assertThat(attachments)
        .containsExactly(
            new AttachmentCandidate(
                "https://example.gov/service/mein-dokument?__blob=publicationFile",
                "mein-dokument"));
  }

  @Test
  void gsbIgnoresAnOrdinaryLinkWithoutTheBlobQueryParameter() {
    Element content =
        contentArea(
            "<main><a href=\"https://example.gov/artikel/anderer-artikel\">Weiter</a></main>");

    List<AttachmentCandidate> attachments =
        AttachmentProfile.GSB.findAttachments(
            content, URI.create("https://example.gov/artikel/mein-artikel"));

    assertThat(attachments).isEmpty();
  }

  @Test
  void gsbIgnoresAQueryParameterAttachmentOnAForeignHost() {
    Element content =
        contentArea(
            "<main><a href=\"https://anderes-beispiel.gov/service/dok?__blob=publicationFile\">"
                + "Fremd</a></main>");

    List<AttachmentCandidate> attachments =
        AttachmentProfile.GSB.findAttachments(
            content, URI.create("https://example.gov/artikel/mein-artikel"));

    assertThat(attachments).isEmpty();
  }

  @Test
  void genericDoesNotConsiderTheGsbQueryParameterAnAttachment() {
    // GENERIC decides purely on the URL's extension - the GSB pattern (no extension, query
    // parameter) is invisible to it, confirming the two profiles are independent.
    Element content =
        contentArea(
            "<main><a href=\"https://example.gov/service/mein-dokument?__blob=publicationFile\">"
                + "Herunterladen</a></main>");

    List<AttachmentCandidate> attachments =
        AttachmentProfile.GENERIC.findAttachments(
            content, URI.create("https://example.gov/artikel/mein-artikel"));

    assertThat(attachments).isEmpty();
  }
}
