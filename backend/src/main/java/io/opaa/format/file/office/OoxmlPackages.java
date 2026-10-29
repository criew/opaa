package io.opaa.format.file.office;

import io.opaa.format.shared.ParserLimitProperties;
import io.opaa.sourceaccess.BoundedStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;

/**
 * Opens a DOCX/PPTX/XLSX package for POI only after its XML parts have been measured: POI builds a
 * DOM of every XML part it reads, and a small, highly compressed part can unpack to a DOM far
 * larger than the heap. Neither a part's name nor its declared content type decides whether POI
 * parses it - a relationship may point the main document at {@code word/document.png} - so every
 * entry is decompressed and counted against {@link ParserLimitProperties#maxOoxmlPartBytes()} and,
 * together with the others, {@link ParserLimitProperties#maxOoxmlTotalBytes()}: the bytes actually
 * inflated, not the sizes the archive declares. Only an entry whose first bytes cannot start an XML
 * document in any encoding an XML parser detects ({@link #mayBeXml}) is left uncounted - an image
 * or embedded file, which POI reads only on demand and no XML parser gets past the first byte of.
 *
 * <p>Public solely so {@code io.opaa.format.file.tabular} can open XLSX the same way.
 */
public final class OoxmlPackages {

  /** Leading bytes of an entry examined by {@link #mayBeXml}. */
  private static final int HEAD_BYTES = 64;

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
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (!entry.isDirectory()) {
          remaining -= inflatedXmlSize(zip, entry, Math.min(maxPart, remaining), maxPart);
        }
      }
    }
  }

  /** The inflated size of {@code entry}, or 0 when its first bytes rule out XML. */
  private static long inflatedXmlSize(ZipFile zip, ZipEntry entry, long budget, long maxPart)
      throws IOException {
    long total = 0;
    byte[] buffer = new byte[8192];
    try (InputStream in = BoundedStreams.input(zip.getInputStream(entry), budget)) {
      int head = in.readNBytes(buffer, 0, HEAD_BYTES);
      if (!mayBeXml(buffer, head)) {
        return 0;
      }
      total = head;
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

  /**
   * Whether {@code length} leading bytes can open an XML document: whitespace or {@code <} in an
   * ASCII-compatible encoding (which covers UTF-16LE and UCS-4LE without a byte order mark), a
   * UTF-8, UTF-16 or UCS-4 byte order mark, {@code <} or {@code <?} in UTF-16BE or UCS-4 without
   * one, or {@code <?xm} in EBCDIC. A leading zero byte counts only in these four-byte patterns, so
   * MP4/MOV media ({@code 00 00 00 xx ftyp}) and TrueType fonts ({@code 00 01 00 00}) stay
   * uncounted. In an ASCII-compatible encoding the whitespace must lead to a {@code <} followed by
   * {@code ?}, {@code !}, a name start or a zero byte, so binary data that merely opens with one of
   * these bytes, such as an obfuscated font, stays uncounted too; whitespace filling the whole head
   * counts. An entry shorter than four bytes counts, at its harmless size.
   */
  static boolean mayBeXml(byte[] head, int length) {
    if (length < 4) {
      return true;
    }
    int b0 = head[0] & 0xFF;
    int b1 = head[1] & 0xFF;
    int b2 = head[2] & 0xFF;
    int b3 = head[3] & 0xFF;
    return switch (b0) {
      case 0x00 ->
          (b1 == 0x3C && b2 == 0x00 && b3 == 0x3F) // UTF-16BE "<?"
              || (b1 == 0x3C && b2 == 0x00 && b3 == 0x00) // UCS-4 3412 "<"
              || (b1 == 0x00 && b2 == 0x00 && b3 == 0x3C) // UCS-4BE "<"
              || (b1 == 0x00 && b2 == 0x3C && b3 == 0x00) // UCS-4 2143 "<"
              || (b1 == 0x00 && b2 == 0xFE && b3 == 0xFF); // UCS-4BE byte order mark
      case 0x09, 0x0A, 0x0D, 0x20, 0x3C -> opensMarkup(head, length);
      case 0xEF -> b1 == 0xBB;
      case 0xFE -> b1 == 0xFF;
      case 0xFF -> b1 == 0xFE;
      case 0x4C -> b1 == 0x6F;
      default -> false;
    };
  }

  /** Whitespace, then {@code <} and a byte that can follow it at the start of a document. */
  private static boolean opensMarkup(byte[] head, int length) {
    int i = 0;
    while (i < length && isXmlWhitespace(head[i] & 0xFF)) {
      i++;
    }
    if (i == length) {
      return true;
    }
    if (head[i] != '<') {
      return false;
    }
    if (i + 1 == length) {
      return true;
    }
    int next = head[i + 1] & 0xFF;
    return next == '?'
        || next == '!'
        || next == 0x00 // UTF-16LE or UCS-4LE without a byte order mark
        || next == '_'
        || next == ':'
        || (next >= 'A' && next <= 'Z')
        || (next >= 'a' && next <= 'z')
        || next >= 0x80;
  }

  private static boolean isXmlWhitespace(int b) {
    return b == 0x09 || b == 0x0A || b == 0x0D || b == 0x20;
  }
}
