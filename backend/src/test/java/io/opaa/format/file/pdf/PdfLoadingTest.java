package io.opaa.format.file.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.format.shared.ParserLimitExceededException;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.junit.jupiter.api.Test;

/**
 * {@link PdfLoading} reaches into PDFBox: it replaces the private {@code
 * COSParser#bruteForceParser} by reflection, which a PDFBox update must keep, and counts the
 * entries of a cross-reference stream the way PDFBox reads them.
 */
class PdfLoadingTest {

  @Test
  void theClassFindsThePdfBoxFieldItReplaces() {
    assertThatCode(
            () ->
                Class.forName(PdfLoading.class.getName(), true, PdfLoading.class.getClassLoader()))
        .doesNotThrowAnyException();
  }

  @Test
  void crossReferenceEntriesAreBoundedByTheDecodedBytes() {
    COSStream stream = xrefStream(1, 4, 2);
    stream.setItem(COSName.INDEX, numbers(0, 10, 100, 2_000_000_000));

    assertThat(PdfLoading.crossReferenceEntries(stream, 70)).isEqualTo(10);
    assertThat(PdfLoading.crossReferenceEntries(stream, 7_000_000)).isEqualTo(1_000_000);
  }

  @Test
  void crossReferenceEntriesWithoutIndexFollowTheSize() {
    COSStream stream = xrefStream(1, 2, 0);
    stream.setInt(COSName.SIZE, 5);

    assertThat(PdfLoading.crossReferenceEntries(stream, 1_000)).isEqualTo(5);
  }

  @Test
  void entriesOfNoWidthAreRefused() {
    assertThatThrownBy(() -> PdfLoading.crossReferenceEntries(xrefStream(0, 0, 0), 1))
        .isInstanceOf(ParserLimitExceededException.class);
  }

  @Test
  void aMalformedWidthIsLeftToPdfBox() {
    COSStream stream = new COSStream();
    stream.setItem(COSName.W, numbers(1, 2));

    assertThat(PdfLoading.crossReferenceEntries(stream, 1_000)).isZero();
  }

  @Test
  void objectStreamObjectsAreBoundedByTheDecodedBytes() {
    COSStream stream = new COSStream();
    stream.setInt(COSName.N, 1_000_000);

    assertThat(PdfLoading.objectStreamObjects(stream, 300)).isEqualTo(300);
    assertThat(PdfLoading.objectStreamObjects(stream, 3_000_000)).isEqualTo(1_000_000);
  }

  private static COSStream xrefStream(int... widths) {
    COSStream stream = new COSStream();
    stream.setItem(COSName.TYPE, COSName.XREF);
    stream.setItem(COSName.W, numbers(widths));
    return stream;
  }

  private static COSArray numbers(int... values) {
    COSArray array = new COSArray();
    for (int value : values) {
      array.add(COSInteger.get(value));
    }
    return array;
  }
}
