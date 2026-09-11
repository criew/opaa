package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The {@code s3://bucket/key} identity of a document (ADR-0027, Entscheidung 5) both ways. */
class S3ObjectRefTest {

  @Test
  void readsBackWhatItWrote() {
    String filePath = S3ObjectRef.filePath("protokolle", "2025/rat/sitzung 01.pdf");

    assertThat(filePath).isEqualTo("s3://protokolle/2025/rat/sitzung 01.pdf");
    assertThat(S3ObjectRef.parse(filePath))
        .contains(new S3ObjectRef("protokolle", "2025/rat/sitzung 01.pdf"));
  }

  @Test
  void keepsTheKeyExactlyAsItIs() {
    // Not URL-encoded, and a key may contain anything a path segment may - including what looks
    // like another scheme.
    assertThat(S3ObjectRef.parse("s3://b/a+b %2F c/s3://x.pdf"))
        .contains(new S3ObjectRef("b", "a+b %2F c/s3://x.pdf"));
  }

  @Test
  void isEmptyForEverythingThatIsNoObjectLocator() {
    assertThat(S3ObjectRef.parse(null)).isEmpty();
    assertThat(S3ObjectRef.parse("")).isEmpty();
    assertThat(S3ObjectRef.parse("/var/opaa/uploads/a.pdf")).isEmpty();
    assertThat(S3ObjectRef.parse("https://example.org/a.pdf")).isEmpty();
    assertThat(S3ObjectRef.parse("s3://bucket")).isEmpty();
    assertThat(S3ObjectRef.parse("s3://bucket/")).isEmpty();
    assertThat(S3ObjectRef.parse("s3:///key")).isEmpty();
  }

  @Test
  void theSyntheticPathOfAnAttachmentParsesIntoAKeyOfItsOwn() {
    // ADR-0022: an attachment's file_path embeds its parent's. It parses, and the key it yields
    // names no object - which is why the download path routes an attachment to the re-extraction
    // before it ever reaches this locator.
    assertThat(S3ObjectRef.parse("s3://b/post/nachricht.eml/0/anlage.pdf"))
        .contains(new S3ObjectRef("b", "post/nachricht.eml/0/anlage.pdf"));
  }
}
