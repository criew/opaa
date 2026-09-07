package io.opaa.indexing.pipeline.office;

import io.opaa.sourceaccess.BoundedStreams;
import java.io.Closeable;
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
 * One opening of an ODF ZIP package, from which any of its entries ({@code meta.xml}, {@code
 * content.xml}, {@code styles.xml}) is parsed with a hardened {@link SAXParser} - shared by {@link
 * OdtDocumentPipeline}, {@link OdpDocumentPipeline} and {@code TabularDocumentPipeline}'s ODS
 * reader (XXE hardening: no DOCTYPE, no external entities; a byte ceiling per decompressed entry
 * against a zip bomb). A pipeline opens the package once per ingest and reads every entry it needs
 * from that one opening; the entries fail independently, a rejected entry leaves the package usable
 * for the next. Public solely so {@code io.opaa.indexing.pipeline.tabular} can reuse this same
 * hardened reader - the only type in this package made public purely for that cross-package reuse.
 */
public final class OdfPackage implements Closeable {

  /** How a pipeline opens a package - {@link OdfPackage#open} in production, countable in tests. */
  @FunctionalInterface
  public interface Opener {
    OdfPackage open(Path file) throws IOException;
  }

  private final Path file;
  private final ZipFile zip;

  private OdfPackage(Path file, ZipFile zip) {
    this.file = file;
    this.zip = zip;
  }

  /**
   * @throws IOException {@code file} could not be opened as a ZIP archive
   */
  public static OdfPackage open(Path file) throws IOException {
    return new OdfPackage(file, new ZipFile(file.toFile()));
  }

  /** One-shot convenience: opens {@code file}, parses {@code content.xml}, closes. */
  public static boolean parse(Path file, long maxEntryBytes, DefaultHandler handler)
      throws IOException {
    return parse(file, "content.xml", maxEntryBytes, handler);
  }

  /** One-shot convenience: opens {@code file}, parses {@code entryName}, closes. */
  public static boolean parse(
      Path file, String entryName, long maxEntryBytes, DefaultHandler handler) throws IOException {
    try (OdfPackage odf = open(file)) {
      return odf.parse(entryName, maxEntryBytes, handler);
    }
  }

  /**
   * @return {@code false} when the package has no {@code entryName} entry at all (not a genuine ODF
   *     ZIP, or an entry a given document simply does not carry, e.g. {@code styles.xml} without
   *     any header/footer) - {@code handler} is left untouched in that case.
   * @throws IOException {@code entryName} exceeds {@code maxEntryBytes}, or the XML itself is
   *     malformed / rejected by the hardening rules - including any limit {@code handler} itself
   *     enforces from a {@code SAXException} it raises (mirrors {@code
   *     TabularDocumentPipeline#readOds}'s own row-limit guard).
   */
  public boolean parse(String entryName, long maxEntryBytes, DefaultHandler handler)
      throws IOException {
    ZipEntry entry = zip.getEntry(entryName);
    if (entry == null) {
      return false;
    }
    try (InputStream in = BoundedStreams.input(zip.getInputStream(entry), maxEntryBytes)) {
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
    } catch (BoundedStreams.LimitExceededException e) {
      // the decompressed entry is capped while it streams - a zip bomb never reaches the heap
      throw new IOException(
          "ODF " + entryName + " exceeds the configured size limit of " + maxEntryBytes + " bytes",
          e);
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("Could not parse ODF " + entryName + " of " + file.getFileName(), e);
    }
    return true;
  }

  @Override
  public void close() throws IOException {
    zip.close();
  }
}
