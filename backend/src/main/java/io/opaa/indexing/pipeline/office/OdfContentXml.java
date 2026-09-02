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
 * Opens an ODF file's {@code content.xml} entry and parses it with a hardened {@link SAXParser} -
 * shared by {@link OdtDocumentPipeline}, {@link OdpDocumentPipeline} and {@code
 * TabularDocumentPipeline}'s own ODS reader (XXE hardening: no DOCTYPE, no external entities; a
 * byte ceiling on the decompressed stream against a zip bomb). Public solely so {@code
 * io.opaa.indexing.pipeline.tabular} can reuse this same hardened reader instead of carrying its
 * own copy (#1108) - the only type in this package made public purely for that cross-package reuse,
 * as opposed to the package's other public types, which are the pipeline beans themselves.
 */
public final class OdfContentXml {

  private OdfContentXml() {}

  /**
   * @return {@code false} when {@code file} has no {@code content.xml} entry at all (not a genuine
   *     ODF ZIP) - {@code handler} is left untouched in that case.
   * @throws IOException the file could not be opened as a ZIP, {@code content.xml} exceeds {@code
   *     maxContentXmlBytes}, or the XML itself is malformed / rejected by the hardening rules -
   *     including any limit {@code handler} itself enforces from a {@code SAXException} it raises
   *     (mirrors {@code TabularDocumentPipeline#readOds}'s own row-limit guard).
   */
  public static boolean parse(Path file, long maxContentXmlBytes, DefaultHandler handler)
      throws IOException {
    try (ZipFile zip = new ZipFile(file.toFile())) {
      ZipEntry entry = zip.getEntry("content.xml");
      if (entry == null) {
        return false;
      }
      try (InputStream in = boundedStream(zip.getInputStream(entry), maxContentXmlBytes)) {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // XXE hardening: content.xml originates from an uploaded/indexed file, never trusted input.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        SAXParser parser = factory.newSAXParser();
        parser.parse(in, handler);
      }
      return true;
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("Could not parse ODF content.xml of " + file.getFileName(), e);
    }
  }

  /** Wraps {@code in} so reading past {@code maxBytes} fails loudly instead of exhausting heap. */
  private static InputStream boundedStream(InputStream in, long maxBytes) {
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
              "ODF content.xml exceeds the configured size limit of " + maxBytes + " bytes");
        }
      }
    };
  }
}
