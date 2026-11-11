package io.opaa.template;

import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ValidationException;
import io.opaa.format.file.office.OoxmlPackages;
import io.opaa.format.shared.ParserLimitProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

/**
 * Admits a DOCX file as document template and reads its placeholders. It reads the package and its
 * text, it never fills or renders it: the tags are text here, and filling happens in the sandbox
 * alone, so no tag can run code in the backend.
 *
 * <p>Refused are files above {@link #MAX_BYTES}, anything but a Word document ({@code .docx}, the
 * plain WordprocessingML main part), packages with macros, ActiveX controls, embedded objects,
 * relationships that load content from outside the file (an attached template, a linked frame or
 * sub-document, any external target but a hyperlink) and fields that pull in or execute content
 * (DDE, INCLUDETEXT, INCLUDEPICTURE; {@link DocxActiveContent}), packages whose XML parts exceed
 * the parser limits, and placeholders whose blocks are not closed in order.
 */
@Component
class DocxTemplateInspector {

  /** The largest template file accepted, in bytes. */
  static final long MAX_BYTES = 10L * 1024 * 1024;

  static final String DOCX_MEDIA_TYPE =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  private static final String MAIN_CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
  private static final String NOT_A_DOCX =
      "Die Datei ist kein gültiges Word-Dokument. Zulässig sind Word-Dateien im Format .docx.";

  private final ParserLimitProperties limits;

  DocxTemplateInspector(ParserLimitProperties limits) {
    this.limits = limits;
  }

  /** The placeholders of the admitted file at {@code file}, named {@code fileName} on upload. */
  List<TemplatePlaceholder> inspect(Path file, String fileName, long sizeBytes) {
    if (sizeBytes > MAX_BYTES) {
      throw new PayloadTooLargeException("Die Vorlage ist größer als 10 MB.");
    }
    if (sizeBytes <= 0) {
      throw new ValidationException("Die Datei ist leer.");
    }
    if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".docx")) {
      throw new ValidationException(NOT_A_DOCX);
    }
    String text;
    try {
      DocxActiveContent.requireNoActiveEntries(file);
      text = OoxmlPackages.read(file, limits, DocxTemplateInspector::admittedText);
    } catch (ValidationException refused) {
      throw refused;
    } catch (IOException | RuntimeException unreadable) {
      throw new ValidationException(NOT_A_DOCX);
    }
    return TemplateTagScanner.scan(text);
  }

  /** The text of body, tables, headers and footers - where {@code docxtpl} fills tags. */
  private static String admittedText(OPCPackage pkg) throws IOException {
    DocxActiveContent.requireNoActiveContent(pkg);
    if (pkg.getPartsByContentType(MAIN_CONTENT_TYPE).size() != 1) {
      throw new ValidationException(NOT_A_DOCX);
    }
    XWPFDocument document = new XWPFDocument(pkg);
    return new XWPFWordExtractor(document).getText();
  }
}
