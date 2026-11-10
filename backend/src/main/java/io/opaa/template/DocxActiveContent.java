package io.opaa.template;

import io.opaa.common.ValidationException;
import io.opaa.format.file.office.OoxmlPackages;
import io.opaa.format.shared.ParserLimitProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Whether a Word package carries content Word may load or run: macros, ActiveX controls, embedded
 * objects, relationships to content outside the file but hyperlinks, and fields that pull in or
 * execute content (DDE, DDEAUTO, INCLUDETEXT, INCLUDEPICTURE). Field codes are read with an XML
 * parser, so neither a namespace prefix, a character reference nor a code split over several runs
 * hides one. Checks an uploaded template and every DOCX an agent run attaches.
 */
@Component
public class DocxActiveContent {

  private static final Logger log = LoggerFactory.getLogger(DocxActiveContent.class);

  private static final String MACRO_MAIN_CONTENT_TYPE =
      "application/vnd.ms-word.document.macroEnabled.main+xml";

  private static final String HYPERLINK =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";

  /** Relationship types that embed or pull in content Word may load or run. */
  private static final Set<String> ACTIVE_RELATIONSHIPS =
      Set.of(
          "attachedtemplate", "oleobject", "package", "subdocument", "afchunk", "frame", "control");

  /** A field instruction that loads or runs content; codes may be split over several runs. */
  private static final Pattern ACTIVE_FIELD =
      Pattern.compile(
          "INCLUDEPICTURE|INCLUDETEXT|DDEAUTO|(?<![A-Z])DDE(?![A-Z])", Pattern.CASE_INSENSITIVE);

  private final ParserLimitProperties limits;

  public DocxActiveContent(ParserLimitProperties limits) {
    this.limits = limits;
  }

  /**
   * Whether {@code content} is a Word package without active content; an unreadable package counts
   * as active.
   */
  public boolean isPassive(byte[] content) {
    Path file = null;
    try {
      file = Files.createTempFile("opaa-docx-check-", ".docx");
      Files.write(file, content);
      requireNoActiveEntries(file);
      OoxmlPackages.read(
          file,
          limits,
          pkg -> {
            requireNoActiveContent(pkg);
            return Boolean.TRUE;
          });
      return true;
    } catch (IOException | RuntimeException refused) {
      return false;
    } finally {
      if (file != null) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          log.warn("Temporary DOCX {} could not be deleted", file, e);
        }
      }
    }
  }

  /**
   * The archive's entry names, read before any XML is parsed: a macro project or an ActiveX control
   * is refused even where the package would not open as Word document at all.
   */
  static void requireNoActiveEntries(Path file) throws IOException {
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

  static void requireNoActiveContent(OPCPackage pkg) throws IOException {
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
          requireNoActiveField(content);
        }
      }
    }
    for (PackageRelationship relationship : pkg.getRelationships()) {
      requirePassive(relationship);
    }
  }

  /**
   * Reads every {@code instrText} of the part as one field code, as Word joins the runs of a field,
   * and every {@code instr} attribute of a simple field.
   */
  static void requireNoActiveField(InputStream xml) throws IOException {
    FieldCodes codes = new FieldCodes();
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      SAXParser parser = factory.newSAXParser();
      parser.parse(xml, codes);
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("Unreadable Word XML", e);
    }
    if (codes.active || ACTIVE_FIELD.matcher(codes.instructions).find()) {
      throw activeContent();
    }
  }

  static ValidationException activeContent() {
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

  private static void requirePassive(PackageRelationship relationship) {
    String type = relationship.getRelationshipType();
    String kind = type.substring(type.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    if (ACTIVE_RELATIONSHIPS.contains(kind)
        || (relationship.getTargetMode() == TargetMode.EXTERNAL && !HYPERLINK.equals(type))) {
      throw activeContent();
    }
  }

  /** Collects the field instructions of one part. */
  private static final class FieldCodes extends DefaultHandler {

    private final StringBuilder instructions = new StringBuilder();
    private int instrTextDepth;
    private boolean active;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      if ("instrText".equals(localName) || "delInstrText".equals(localName)) {
        instrTextDepth++;
      }
      for (int i = 0; i < attributes.getLength(); i++) {
        if ("instr".equals(attributes.getLocalName(i))
            && ACTIVE_FIELD.matcher(attributes.getValue(i)).find()) {
          active = true;
        }
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      if (("instrText".equals(localName) || "delInstrText".equals(localName))
          && instrTextDepth > 0) {
        instrTextDepth--;
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (instrTextDepth > 0) {
        instructions.append(ch, start, length);
      }
    }
  }
}
