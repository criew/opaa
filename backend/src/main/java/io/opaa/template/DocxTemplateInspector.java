package io.opaa.template;

import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ValidationException;
import io.opaa.format.file.office.OoxmlPackages;
import io.opaa.format.shared.ParserLimitProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
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
 * (DDE, INCLUDETEXT, INCLUDEPICTURE), packages whose XML parts exceed the parser limits, and
 * placeholders whose blocks are not closed in order.
 */
@Component
class DocxTemplateInspector {

  /** The largest template file accepted, in bytes. */
  static final long MAX_BYTES = 10L * 1024 * 1024;

  static final String DOCX_MEDIA_TYPE =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

  private static final String MAIN_CONTENT_TYPE =
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
  private static final String MACRO_MAIN_CONTENT_TYPE =
      "application/vnd.ms-word.document.macroEnabled.main+xml";
  private static final String NOT_A_DOCX =
      "Die Datei ist kein gültiges Word-Dokument. Zulässig sind Word-Dateien im Format .docx.";

  private static final String HYPERLINK =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";

  /** Relationship types that embed or pull in content Word may load or run. */
  private static final Set<String> ACTIVE_RELATIONSHIPS =
      Set.of(
          "attachedtemplate", "oleobject", "package", "subdocument", "afchunk", "frame", "control");

  private static final Pattern ACTIVE_FIELD =
      Pattern.compile(
          "(?:<w:instrText[^>]*>|w:instr=\")\\s*(?:DDE|DDEAUTO|INCLUDETEXT|INCLUDEPICTURE)\\b",
          Pattern.CASE_INSENSITIVE);

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
      requireNoActiveEntries(file);
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
    requireNoActiveContent(pkg);
    if (pkg.getPartsByContentType(MAIN_CONTENT_TYPE).size() != 1) {
      throw new ValidationException(NOT_A_DOCX);
    }
    XWPFDocument document = new XWPFDocument(pkg);
    return new XWPFWordExtractor(document).getText();
  }

  /**
   * The archive's entry names, read before any XML is parsed: a macro project or an ActiveX control
   * is refused even where the package would not open as Word document at all.
   */
  private static void requireNoActiveEntries(Path file) throws IOException {
    try (ZipFile zip = new ZipFile(file.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        String name = "/" + entries.nextElement().getName().toLowerCase(Locale.ROOT);
        if (name.contains("vbaproject") || name.contains("vbadata")) {
          throw macros();
        }
        if (name.startsWith("/word/activex/")) {
          throw activeX();
        }
        if (name.startsWith("/word/embeddings/")) {
          throw activeContent();
        }
      }
    }
  }

  private static ValidationException activeContent() {
    return new ValidationException(
        "Die Datei enthält eingebettete Objekte, verknüpfte Inhalte oder Felder, die Inhalte"
            + " nachladen. Vorlagen dürfen nur Text, Tabellen, Bilder und Links enthalten.");
  }

  private static ValidationException macros() {
    return new ValidationException(
        "Die Datei enthält Makros. Vorlagen dürfen keine Makros enthalten; speichern Sie sie als"
            + " Word-Dokument (.docx).");
  }

  private static ValidationException activeX() {
    return new ValidationException(
        "Die Datei enthält Steuerelemente (ActiveX). Vorlagen dürfen keine aktiven Inhalte"
            + " enthalten.");
  }

  private static void requireNoActiveContent(OPCPackage pkg) throws IOException {
    List<PackagePart> parts;
    try {
      parts = pkg.getParts();
    } catch (InvalidFormatException e) {
      throw new IOException("Unreadable package parts", e);
    }
    for (PackagePart part : parts) {
      String name = part.getPartName().getName().toLowerCase(Locale.ROOT);
      String contentType = part.getContentType().toLowerCase(Locale.ROOT);
      if (contentType.equals(MACRO_MAIN_CONTENT_TYPE.toLowerCase(Locale.ROOT))
          || contentType.contains("vbaproject")
          || contentType.contains("vbadata")
          || name.contains("vbaproject")
          || name.contains("vbadata")) {
        throw macros();
      }
      if (contentType.contains("activex") || name.startsWith("/word/activex/")) {
        throw activeX();
      }
      if (part.isRelationshipPart()) {
        continue;
      }
      try {
        for (PackageRelationship relationship : part.getRelationships()) {
          requirePassive(relationship);
        }
      } catch (InvalidFormatException e) {
        throw new IOException("Unreadable relationships", e);
      }
      if (contentType.contains("wordprocessingml") && contentType.endsWith("+xml")) {
        try (InputStream content = part.getInputStream()) {
          if (ACTIVE_FIELD
              .matcher(new String(content.readAllBytes(), StandardCharsets.UTF_8))
              .find()) {
            throw activeContent();
          }
        }
      }
    }
    for (PackageRelationship relationship : pkg.getRelationships()) {
      requirePassive(relationship);
    }
  }

  private static void requirePassive(PackageRelationship relationship) {
    String type = relationship.getRelationshipType();
    String kind = type.substring(type.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    if (ACTIVE_RELATIONSHIPS.contains(kind)
        || (relationship.getTargetMode() == TargetMode.EXTERNAL && !HYPERLINK.equals(type))) {
      throw activeContent();
    }
  }
}
