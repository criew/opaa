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
