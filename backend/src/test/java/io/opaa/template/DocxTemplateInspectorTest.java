package io.opaa.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ValidationException;
import io.opaa.format.shared.ParserLimitProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxTemplateInspectorTest {

  private final DocxTemplateInspector inspector =
      new DocxTemplateInspector(ParserLimitProperties.DEFAULTS);

  @TempDir Path directory;

  @Test
  void readsThePlaceholdersOfAWordDocument() throws IOException {
    Path file =
        write(TestDocx.withParagraphs("Betreff: {{ betreff }}", "{% if anlage %}x{% endif %}"));

    assertThat(inspector.inspect(file, "vermerk.docx", Files.size(file)))
        .containsExactly(
            new TemplatePlaceholder("betreff", PlaceholderKind.FIELD, List.of()),
            new TemplatePlaceholder("anlage", PlaceholderKind.CONDITION, List.of()));
  }

  @Test
  void aFileThatIsNoWordDocumentIsRefused() throws IOException {
    Path file = write("kein Word".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> inspector.inspect(file, "vorlage.docx", Files.size(file)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("kein gültiges Word-Dokument");
  }

  @Test
  void anotherExtensionIsRefused() throws IOException {
    Path file = write(TestDocx.withParagraphs("{{ a }}"));

    assertThatThrownBy(() -> inspector.inspect(file, "vorlage.docm", Files.size(file)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void aDocumentWithMacrosIsRefused() throws IOException {
    Path file =
        write(withEntry(TestDocx.withParagraphs("{{ a }}"), "word/vbaProject.bin", new byte[16]));

    assertThatThrownBy(() -> inspector.inspect(file, "vorlage.docx", Files.size(file)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Makros");
  }

  @Test
  void aDocumentWithActiveXControlsIsRefused() throws IOException {
    Path file =
        write(
            withEntry(
                TestDocx.withParagraphs("{{ a }}"),
                "word/activeX/activeX1.xml",
                "<ax:ocx xmlns:ax=\"http://schemas.microsoft.com/office/2006/activeX\"/>"
                    .getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(() -> inspector.inspect(file, "vorlage.docx", Files.size(file)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("ActiveX");
  }

  @Test
  void aFileAboveTenMegabytesIsRefused() throws IOException {
    Path file = write(TestDocx.withParagraphs("{{ a }}"));

    assertThatThrownBy(
            () -> inspector.inspect(file, "vorlage.docx", DocxTemplateInspector.MAX_BYTES + 1))
        .isInstanceOf(PayloadTooLargeException.class);
  }

  private Path write(byte[] content) throws IOException {
    Path file = Files.createTempFile(directory, "template", ".docx");
    Files.write(file, content);
    return file;
  }

  /** {@code docx} with one more archive entry, the way a macro-enabled save adds its project. */
  private static byte[] withEntry(byte[] docx, String name, byte[] content) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx));
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        zip.putNextEntry(new ZipEntry(entry.getName()));
        in.transferTo(zip);
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry(name));
      zip.write(content);
      zip.closeEntry();
    }
    return out.toByteArray();
  }
}
