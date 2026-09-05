package io.opaa.indexing.pipeline.office;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Opens an entry inside an ODF ZIP archive (typically {@code content.xml} or {@code styles.xml})
 * and parses it with a hardened {@link SAXParser} - shared by {@link OdtDocumentPipeline}, {@link
 * OdpDocumentPipeline} and {@code TabularDocumentPipeline}'s own ODS reader (XXE hardening: no
 * DOCTYPE, no external entities; a byte ceiling on the decompressed stream against a zip bomb).
 * Public solely so {@code io.opaa.indexing.pipeline.tabular} can reuse this same hardened reader
 * instead of carrying its own copy - the only type in this package made public purely for that
 * cross-package reuse, as opposed to the package's other public types, which are the pipeline beans
 * themselves.
 */
public final class OdfContentXml {

  private OdfContentXml() {}

  /** Convenience for the common case of reading {@code content.xml} itself. */
  public static boolean parse(Path file, long maxEntryBytes, DefaultHandler handler)
      throws IOException {
    return parse(file, "content.xml", maxEntryBytes, handler);
  }

  /**
   * @return {@code false} when {@code file} has no {@code entryName} entry at all (not a genuine
   *     ODF ZIP, or an entry a given document simply does not carry, e.g. {@code styles.xml}
   *     without any header/footer) - {@code handler} is left untouched in that case.
   * @throws IOException the file could not be opened as a ZIP, {@code entryName} exceeds {@code
   *     maxEntryBytes}, or the XML itself is malformed / rejected by the hardening rules -
   *     including any limit {@code handler} itself enforces from a {@code SAXException} it raises
   *     (mirrors {@code TabularDocumentPipeline#readOds}'s own row-limit guard).
   */
  public static boolean parse(
      Path file, String entryName, long maxEntryBytes, DefaultHandler handler) throws IOException {
    try (ZipFile zip = new ZipFile(file.toFile())) {
      ZipEntry entry = zip.getEntry(entryName);
      if (entry == null) {
        return false;
      }
      try (InputStream in = boundedStream(zip.getInputStream(entry), entryName, maxEntryBytes)) {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // XXE hardening: entryName originates from an uploaded/indexed file, never trusted input.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        SAXParser parser = factory.newSAXParser();
        parser.parse(in, handler);
      }
      return true;
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("Could not parse ODF " + entryName + " of " + file.getFileName(), e);
    }
  }

  /** Wraps {@code in} so reading past {@code maxBytes} fails loudly instead of exhausting heap. */
  private static InputStream boundedStream(InputStream in, String entryName, long maxBytes) {
    return new FilterInputStream(in) {
      private long total;

      @Override
      public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
          checkLimit(++total);
        }
        return b;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
          total += n;
          checkLimit(total);
        }
        return n;
      }

      private void checkLimit(long readSoFar) throws IOException {
        if (readSoFar > maxBytes) {
          throw new IOException(
              "ODF " + entryName + " exceeds the configured size limit of " + maxBytes + " bytes");
        }
      }
    };
  }
}
