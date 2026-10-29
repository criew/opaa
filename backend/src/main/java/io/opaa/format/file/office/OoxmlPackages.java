package io.opaa.format.file.office;

import io.opaa.format.shared.ParserLimitProperties;
import io.opaa.sourceaccess.BoundedStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Opens a DOCX/PPTX/XLSX package for POI only after its XML parts have been measured: POI builds a
 * DOM of every XML part it reads, and a small, highly compressed part can unpack to a DOM far
 * larger than the heap. Every entry whose content type ({@code [Content_Types].xml}) is XML, or
 * which has no declared type, is decompressed once and counted against {@link
 * ParserLimitProperties#maxOoxmlPartBytes()} and, together with the others, {@link
 * ParserLimitProperties#maxOoxmlTotalBytes()} - the bytes actually inflated, not the sizes the
 * archive declares. Other parts (images, embedded objects) are not counted, because the package is
 * opened from the file read-only and POI reads them only on demand, which no format does.
 *
 * <p>Public solely so {@code io.opaa.format.file.tabular} can open XLSX the same way.
 */
public final class OoxmlPackages {

  private static final String CONTENT_TYPES = "[Content_Types].xml";

  private OoxmlPackages() {}

  /** Builds plain data from an opened package; the package is released afterwards. */
  @FunctionalInterface
  public interface PackageReader<T> {
    T read(OPCPackage pkg) throws IOException;
  }

  /**
   * Measures {@code file}, opens it read-only, hands it to {@code reader} and releases it on every
   * exit; whatever {@code reader} returns must not keep the package or a POI document of it.
   *
   * @throws IOException a part or the whole exceeds its limit, or the file is not a readable ZIP
   *     archive or OOXML package
   */
  public static <T> T read(Path file, ParserLimitProperties limits, PackageReader<T> reader)
      throws IOException {
    OPCPackage pkg = open(file, limits);
    try {
      return reader.read(pkg);
    } finally {
      pkg.revert();
    }
  }

  /**
   * Measures {@code file} and opens it read-only. The caller closes the package, or the POI
   * document built on it, which reverts rather than saves a read-only package.
   *
   * @throws IOException a part or the whole exceeds its limit, or the file is not a readable ZIP
   *     archive or OOXML package
   */
  public static OPCPackage open(Path file, ParserLimitProperties limits) throws IOException {
    requireWithinLimits(file, limits);
    try {
      return OPCPackage.open(file.toFile(), PackageAccess.READ);
    } catch (InvalidFormatException e) {
      throw new IOException("Not a readable OOXML package: " + file.getFileName(), e);
    }
  }

  /** The measuring step of {@link #open} alone. */
  static void requireWithinLimits(Path file, ParserLimitProperties limits) throws IOException {
    long maxPart = limits.maxOoxmlPartBytes();
    long remaining = limits.maxOoxmlTotalBytes();
    try (ZipFile zip = new ZipFile(file.toFile())) {
      ContentTypes types = ContentTypes.read(zip, maxPart);
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry.isDirectory() || !types.parsedAsXml(entry.getName())) {
          continue;
        }
        long size = inflatedSize(zip, entry, Math.min(maxPart, remaining), maxPart);
        remaining -= size;
      }
    }
  }

  private static long inflatedSize(ZipFile zip, ZipEntry entry, long budget, long maxPart)
      throws IOException {
    long total = 0;
    byte[] buffer = new byte[8192];
    try (InputStream in = BoundedStreams.input(zip.getInputStream(entry), budget)) {
      for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
        total += n;
      }
    } catch (BoundedStreams.LimitExceededException e) {
      throw new IOException(
          budget < maxPart
              ? "OOXML package exceeds the configured limit for all XML parts together"
              : "OOXML part "
                  + entry.getName()
                  + " exceeds the configured size limit of "
                  + maxPart
                  + " bytes",
          e);
    }
    return total;
  }

  /** The package's content types, by extension and by part name, both lower case. */
  private record ContentTypes(Map<String, String> byExtension, Map<String, String> byPartName) {

    static ContentTypes read(ZipFile zip, long maxBytes) throws IOException {
      ZipEntry entry = zip.getEntry(CONTENT_TYPES);
      if (entry == null) {
        throw new IOException("Not an OOXML package: no " + CONTENT_TYPES);
      }
      ContentTypes types = new ContentTypes(new HashMap<>(), new HashMap<>());
      try (InputStream in = BoundedStreams.input(zip.getInputStream(entry), maxBytes)) {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.newSAXParser().parse(in, types.handler());
      } catch (BoundedStreams.LimitExceededException e) {
        throw new IOException(CONTENT_TYPES + " exceeds the configured size limit", e);
      } catch (ParserConfigurationException | SAXException e) {
        throw new IOException("Could not parse " + CONTENT_TYPES, e);
      }
      return types;
    }

    private DefaultHandler handler() {
      return new DefaultHandler() {
        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
          String contentType = atts.getValue("ContentType");
          if (contentType == null) {
            return;
          }
          if ("Default".equals(localName) && atts.getValue("Extension") != null) {
            byExtension.put(lower(atts.getValue("Extension")), contentType);
          } else if ("Override".equals(localName) && atts.getValue("PartName") != null) {
            byPartName.put(lower(atts.getValue("PartName")), contentType);
          }
        }
      };
    }

    /**
     * Whether POI may read {@code entryName} into a DOM: an XML content type, or none at all, which
     * counts as XML so that an undeclared part cannot slip past the limits.
     */
    boolean parsedAsXml(String entryName) {
      if (CONTENT_TYPES.equals(entryName)) {
        return true;
      }
      String partName = lower("/" + entryName);
      String type = byPartName.get(partName);
      if (type == null) {
        int dot = partName.lastIndexOf('.');
        int slash = partName.lastIndexOf('/');
        type = dot > slash ? byExtension.get(partName.substring(dot + 1)) : null;
      }
      return type == null || lower(type).endsWith("xml");
    }

    private static String lower(String value) {
      return value.toLowerCase(Locale.ROOT);
    }
  }
}
