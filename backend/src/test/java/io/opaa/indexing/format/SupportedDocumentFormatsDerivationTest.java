package io.opaa.indexing.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.test.ProductionDocumentFormats;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * How {@link SupportedDocumentFormats} arrives at what it accepts: the union of every registered
 * {@link DocumentFormat}'s {@link DocumentFormat#admittedFormats()}, and nothing else. {@link
 * SupportedDocumentFormatsTest} covers what those answers <em>are</em> for the registered formats;
 * this class covers that they come from the declarations and that a conflicting pair of
 * declarations is caught at construction rather than decided by bean order.
 */
class SupportedDocumentFormatsDerivationTest {

  /** A stand-in for a future format: everything it admits, and nothing else, is admitted. */
  private record FakeFormat(String id, Set<FormatAdmission> admittedFormats)
      implements DocumentFormat {

    @Override
    public short version() {
      return 1;
    }

    @Override
    public DocumentFormatResult run(DocumentFormatSource source) {
      return DocumentFormatResult.chunked(List.of());
    }
  }

  @Test
  void theAcceptedSetIsExactlyTheUnionOfTheRegisteredDeclarations() {
    List<DocumentFormat> formats = ProductionDocumentFormats.formats();

    Set<String> declared =
        formats.stream()
            .flatMap(format -> format.admittedFormats().stream())
            .map(FormatAdmission::extension)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    assertThat(new SupportedDocumentFormats(formats).extensions())
        .containsExactlyInAnyOrderElementsOf(declared);
  }

  @Test
  void aFormatDeclaringAnInventedExtensionIsAdmittedByThatDeclarationAlone() {
    DocumentFormat invented =
        new FakeFormat(
            "invented", Set.of(FormatAdmission.detectedAs(".opaa", "application/x-opaa-invented")));

    SupportedDocumentFormats supported = new SupportedDocumentFormats(List.of(invented));

    assertThat(supported.extensions()).containsExactly(".opaa");
    assertThat(supported.isSupported("bericht.opaa")).isTrue();
    assertThat(supported.contentTypeForExtension(".opaa")).isEqualTo("application/x-opaa-invented");
    assertThat(supported.extensionForContentType("application/x-opaa-invented")).isEqualTo(".opaa");
    assertThat(supported.extensionForDetectedContent("application/x-opaa-invented"))
        .isEqualTo(".opaa");
    assertThat(
            supported.decideForFileName("bericht.opaa", "application/x-opaa-invented").supported())
        .isTrue();
    // Nothing else comes along: an extension nobody declared is not admitted.
    assertThat(supported.isSupported("satzung.pdf")).isFalse();
  }

  @Test
  void aTextTolerantDeclarationAcceptsAnyTextContentUnderItsOwnExtensionOnly() {
    DocumentFormat notes =
        new FakeFormat("notes", Set.of(FormatAdmission.textTolerant(".note", "text/x-note")));

    SupportedDocumentFormats supported = new SupportedDocumentFormats(List.of(notes));

    assertThat(supported.contentMatchesExtension(".note", "text/plain")).isTrue();
    assertThat(supported.contentMatchesExtension(".note", "application/pdf")).isFalse();
    // Text-tolerant content is never admitted on content alone - the name has to claim it.
    assertThat(supported.extensionForDetectedContent("text/plain")).isNull();
    assertThat(supported.decideForFileName("notiz.note", "text/plain").supported()).isTrue();
    assertThat(supported.decideForFileName("notiz.andere", "text/plain").supported()).isFalse();
  }

  /**
   * Every lookup normalizes what it is asked about; a declaration that is not already in that form
   * would leave its extension admitted but unmatchable by any content, and would reach a document
   * row as its {@code content_type}. Rejected where it is written, not where it fails to match.
   */
  @Test
  void aMediaTypeThatIsNotDeclaredInItsNormalizedFormIsRejected() {
    assertThatThrownBy(() -> FormatAdmission.detectedAs(".foo", "Application/X-Foo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Application/X-Foo");
    assertThatThrownBy(() -> FormatAdmission.textTolerant(".foo", "TEXT/X-FOO"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FormatAdmission.detectedAs(".foo", "text/x-foo; charset=UTF-8"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FormatAdmission.detectedAs(".foo", "text/x-foo", "Text/X-Bar"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** The invariant the Javadoc promises, enforced instead of left to the factory methods. */
  @Test
  void aStrictAdmissionWhoseDetectedTypesOmitItsCanonicalTypeIsRejected() {
    assertThatThrownBy(
            () ->
                new FormatAdmission(
                    ".foo", "text/x-foo", Set.of("text/x-other"), false, true, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(".foo");
  }

  /**
   * Two spellings of one format ({@code .htm} beside {@code .html}) are one format's business, not
   * a collision: both are admitted and matched alike, and the declaration - not iteration order -
   * says which of them a detection is named after.
   */
  @Test
  void oneFormatMayAdmitTheSameMediaTypeUnderTwoExtensions() {
    DocumentFormat html =
        new FakeFormat(
            "html",
            Set.of(
                FormatAdmission.detectedAs(".html", "text/html"),
                FormatAdmission.detectedAs(".htm", "text/html").asAlternateSpelling()));

    SupportedDocumentFormats supported = new SupportedDocumentFormats(List.of(html));

    assertThat(supported.extensions()).containsExactly(".htm", ".html");
    assertThat(supported.isSupported("seite.htm")).isTrue();
    assertThat(supported.contentMatchesExtension(".htm", "text/html")).isTrue();
    assertThat(supported.contentTypeForExtension(".htm")).isEqualTo("text/html");
    // The alternate spelling is admitted, but never the answer to "which extension is this?".
    assertThat(supported.extensionForDetectedContent("text/html")).isEqualTo(".html");
    assertThat(supported.extensionForContentType("text/html")).isEqualTo(".html");
    // Its own name still decides its routing key, so a .htm file is not reported as a mismatch.
    assertThat(supported.decideForFileName("seite.htm", "text/html").extensionMismatch()).isFalse();
    assertThat(html.handledFormats()).containsExactlyInAnyOrder(".htm", ".html");
  }

  @Test
  void twoExtensionsOfOneFormatBothNamingTheSameMediaTypeFailFast() {
    DocumentFormat html =
        new FakeFormat(
            "html",
            Set.of(
                FormatAdmission.detectedAs(".html", "text/html"),
                FormatAdmission.detectedAs(".htm", "text/html")));

    assertThatThrownBy(() -> new SupportedDocumentFormats(List.of(html)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("text/html")
        .hasMessageContaining("alternate spelling");
  }

  /**
   * An admission a declared header may not name is still admitted and still detected - only {@link
   * SupportedDocumentFormats#extensionForContentType} keeps its hands off it.
   */
  @Test
  void anAdmissionCanRefuseToBeNamedByADeclaredContentTypeHeader() {
    DocumentFormat mailish =
        new FakeFormat(
            "mailish",
            Set.of(
                FormatAdmission.detectedAs(".fmt", "application/x-fmt")
                    .notNamedByDeclaredContentType()));

    SupportedDocumentFormats supported = new SupportedDocumentFormats(List.of(mailish));

    assertThat(supported.isSupported("datei.fmt")).isTrue();
    assertThat(supported.extensionForDetectedContent("application/x-fmt")).isEqualTo(".fmt");
    assertThat(supported.extensionForContentType("application/x-fmt")).isNull();
  }

  @Test
  void twoFormatsAdmittingTheSameExtensionFailFast() {
    DocumentFormat first =
        new FakeFormat("first", Set.of(FormatAdmission.detectedAs(".opaa", "application/x-first")));
    DocumentFormat second =
        new FakeFormat(
            "second", Set.of(FormatAdmission.detectedAs(".opaa", "application/x-second")));

    assertThatThrownBy(() -> new SupportedDocumentFormats(List.of(first, second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(".opaa")
        .hasMessageContaining("first")
        .hasMessageContaining("second");
  }

  @Test
  void twoFormatsAdmittingTheSameMediaTypeFailFast() {
    // Two extensions, one media type: routing would decide by whichever declaration was read last.
    DocumentFormat first =
        new FakeFormat("first", Set.of(FormatAdmission.detectedAs(".aaa", "application/x-thing")));
    DocumentFormat second =
        new FakeFormat("second", Set.of(FormatAdmission.detectedAs(".bbb", "application/x-thing")));

    assertThatThrownBy(() -> new SupportedDocumentFormats(List.of(first, second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("application/x-thing");
  }

  @Test
  void aFormatClaimsForRoutingWhatItAdmits() {
    DocumentFormat invented =
        new FakeFormat(
            "invented", Set.of(FormatAdmission.detectedAs(".opaa", "application/x-opaa-invented")));

    assertThat(invented.handledFormats()).containsExactly(".opaa");
  }
}
