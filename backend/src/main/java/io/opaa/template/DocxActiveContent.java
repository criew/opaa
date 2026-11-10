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
 * Whether a Word package carries content Word may load or run: macros, also as entry or exit macro
 * of a form field, ActiveX controls, embedded objects, relationships to content outside the file
 * but hyperlinks, mail merge settings with their data source and query, and any field but those of
 * {@link #PASSIVE_FIELDS}. A field is passive only when the first word of its code is on that list
 * and no other field stands inside its code, as Word would evaluate such an inner field (QUOTE, a
 * nested field name) to build the outer one. Field codes are read with an XML parser, so neither a
 * namespace prefix, a character reference nor a code split over several runs hides one. Checks an
 * uploaded template and every DOCX an agent run attaches.
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

  /**
   * Fields that only show what the document itself holds: page and section numbers, dates, document
   * properties, references, tables of contents and indexes, numbering, symbols, links, merge fields
   * and legacy form fields.
   */
  static final Set<String> PASSIVE_FIELDS =
      Set.of(
          "PAGE",
          "NUMPAGES",
          "SECTIONPAGES",
          "SECTION",
          "DATE",
          "TIME",
          "CREATEDATE",
          "SAVEDATE",
          "PRINTDATE",
          "EDITTIME",
          "HYPERLINK",
          "TOC",
          "TOA",
          "TC",
          "TA",
          "XE",
          "INDEX",
          "REF",
          "PAGEREF",
          "NOTEREF",
          "SEQ",
          "STYLEREF",
          "AUTHOR",
          "TITLE",
          "SUBJECT",
          "KEYWORDS",
          "COMMENTS",
          "LASTSAVEDBY",
          "NUMWORDS",
          "NUMCHARS",
          "MERGEFIELD",
          "LISTNUM",
          "AUTONUM",
          "AUTONUMLGL",
          "AUTONUMOUT",
          "SYMBOL",
          "ADVANCE",
          "FORMTEXT",
          "FORMCHECKBOX",
          "FORMDROPDOWN",
          "BIBLIOGRAPHY",
          "CITATION");

  /** The first word of a field code: the field's name. */
  private static final Pattern FIELD_NAME =
      Pattern.compile("^\\s*([^\\s\\\\]*)", Pattern.UNICODE_CHARACTER_CLASS);

  /**
   * Elements that make Word run a macro or open a data source: the entry and exit macro of a legacy
   * form field and the mail merge settings with connection string and query.
   */
  private static final Set<String> ACTIVE_ELEMENTS = Set.of("entryMacro", "exitMacro", "mailMerge");

  /** Field names that load or run content, also searched across all codes of a part. */
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
   * Reads the fields of the part: a complex field from {@code fldChar begin} over its {@code
   * instrText} runs to {@code separate} or {@code end}, a simple field from its {@code instr}
   * attribute; {@code instrText} outside a field is read per paragraph.
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

  /** Whether {@code code} names a field of {@link #PASSIVE_FIELDS}; an empty code is passive. */
  static boolean passiveField(CharSequence code) {
    java.util.regex.Matcher name = FIELD_NAME.matcher(code);
    String field = name.find() ? name.group(1).toUpperCase(Locale.ROOT) : "";
    return field.isEmpty() || PASSIVE_FIELDS.contains(field);
  }

  /** Reads the fields of one part and notes whether any of them is active. */
  private static final class FieldCodes extends DefaultHandler {

    /** A complex field; while {@code inCode}, its code is still being read. */
    private static final class Field {
      private final StringBuilder code = new StringBuilder();
      private boolean inCode = true;
    }

    private final StringBuilder instructions = new StringBuilder();
    private final java.util.Deque<Field> fields = new java.util.ArrayDeque<>();
    private final StringBuilder loose = new StringBuilder();
    private int instrTextDepth;
    private boolean active;

    private boolean readingCode() {
      return !fields.isEmpty() && fields.peek().inCode;
    }

    private void check(CharSequence code) {
      if (!passiveField(code)) {
        active = true;
      }
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) {
      if ("instrText".equals(localName) || "delInstrText".equals(localName)) {
        instrTextDepth++;
      }
      if (ACTIVE_ELEMENTS.contains(localName)) {
        active = true;
      }
      if ("fldChar".equals(localName)) {
        fieldChar(attribute(attributes, "fldCharType"));
      }
      String instr = attribute(attributes, "instr");
      if (instr != null) {
        // A simple field inside the code of another field is a nested field as well.
        active |= readingCode() || ACTIVE_FIELD.matcher(instr).find();
        check(instr);
      }
    }

    private void fieldChar(String type) {
      if ("begin".equals(type)) {
        active |= readingCode();
        fields.push(new Field());
      } else if ("separate".equals(type) && readingCode()) {
        check(fields.peek().code);
        fields.peek().inCode = false;
      } else if ("end".equals(type) && !fields.isEmpty()) {
        Field field = fields.pop();
        if (field.inCode) {
          check(field.code);
        }
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      if (("instrText".equals(localName) || "delInstrText".equals(localName))
          && instrTextDepth > 0) {
        instrTextDepth--;
      }
      if ("p".equals(localName) && !loose.isEmpty()) {
        check(loose);
        loose.setLength(0);
      }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (instrTextDepth > 0) {
        instructions.append(ch, start, length);
        (readingCode() ? fields.peek().code : loose).append(ch, start, length);
      }
    }

    @Override
    public void endDocument() {
      for (Field field : fields) {
        if (field.inCode) {
          check(field.code);
        }
      }
      check(loose);
    }

    private static String attribute(Attributes attributes, String localName) {
      for (int i = 0; i < attributes.getLength(); i++) {
        if (localName.equals(attributes.getLocalName(i))) {
          return attributes.getValue(i);
        }
      }
      return null;
    }
  }
}
