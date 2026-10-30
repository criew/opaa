package io.opaa.fuzz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Compares a seed as the generator writes it with the committed file by what the deflate
 * implementation does not decide: a ZIP entry by entry (name, order, method, time and uncompressed
 * content, nested packages likewise), a PDF with every Flate stream inflated and the lengths and
 * offsets that follow from the compressed size masked, anything else byte for byte. Another zlib
 * may compress the same content to other bytes; that is no difference here.
 */
final class SeedComparison {

  private static final Pattern STREAM_START = Pattern.compile("/Length (\\d++) >>\nstream\n");
  private static final Pattern XREF_OFFSET = Pattern.compile("(?m)^\\d{10}( \\d{5} [nf] )$");
  private static final Pattern START_XREF = Pattern.compile("startxref\n\\d++");
  private static final int MAX_INFLATED_BYTES = 64 * 1024 * 1024;

  private SeedComparison() {}

  /** The first difference between {@code expected} and {@code actual}, named by its path. */
  static Optional<String> difference(String name, byte[] expected, byte[] actual) {
    if (Arrays.equals(expected, actual)) {
      return Optional.empty();
    }
    if (isZip(expected) && isZip(actual)) {
      return zipDifference(name, expected, actual);
    }
    if (isPdf(expected) && isPdf(actual)) {
      return byteDifference(
          name + " (PDF, Flate streams inflated)", canonical(expected), canonical(actual));
    }
    return byteDifference(name, expected, actual);
  }

  private record Part(String name, int method, LocalDateTime time, byte[] data) {}

  private static Optional<String> zipDifference(String name, byte[] expected, byte[] actual) {
    List<Part> want = parts(expected);
    List<Part> got = parts(actual);
    for (int i = 0; i < Math.min(want.size(), got.size()); i++) {
      Part a = want.get(i);
      Part b = got.get(i);
      String path = name + "!" + a.name();
      if (!a.name().equals(b.name())) {
        return Optional.of(name + ": entry " + i + " is " + b.name() + ", expected " + a.name());
      }
      if (a.method() != b.method()) {
        return Optional.of(path + ": method " + b.method() + ", expected " + a.method());
      }
      if (!a.time().equals(b.time())) {
        return Optional.of(path + ": time " + b.time() + ", expected " + a.time());
      }
      Optional<String> content = difference(path, a.data(), b.data());
      if (content.isPresent()) {
        return content;
      }
    }
    if (want.size() != got.size()) {
      return Optional.of(name + ": " + got.size() + " entries, expected " + want.size());
    }
    return Optional.empty();
  }

  private static List<Part> parts(byte[] zipped) {
    List<Part> parts = new ArrayList<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        parts.add(
            new Part(entry.getName(), entry.getMethod(), entry.getTimeLocal(), zip.readAllBytes()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return parts;
  }

  /** The PDF with Flate streams inflated, their lengths, xref offsets and startxref masked. */
  private static byte[] canonical(byte[] pdf) {
    String text = new String(pdf, StandardCharsets.ISO_8859_1);
    StringBuilder out = new StringBuilder(text.length());
    Matcher stream = STREAM_START.matcher(text);
    int from = 0;
    while (stream.find(from)) {
      int start = stream.end();
      int end = start + Integer.parseInt(stream.group(1));
      if (end > text.length()) {
        break;
      }
      String dictionary =
          text.substring(Math.max(0, text.lastIndexOf(" obj", stream.start())), stream.start());
      String data = text.substring(start, end);
      out.append(text, from, stream.start()).append("/Length _ >>\nstream\n");
      out.append(dictionary.contains("/FlateDecode") ? inflated(data) : data);
      from = end;
    }
    out.append(text, from, text.length());
    String masked = XREF_OFFSET.matcher(out).replaceAll("__________$1");
    masked = START_XREF.matcher(masked).replaceAll("startxref\n_");
    return masked.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static String inflated(String data) {
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(data.getBytes(StandardCharsets.ISO_8859_1));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      while (!inflater.finished() && out.size() <= MAX_INFLATED_BYTES) {
        int n = inflater.inflate(buffer);
        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
          break;
        }
        out.write(buffer, 0, n);
      }
      return out.toString(StandardCharsets.ISO_8859_1);
    } catch (DataFormatException e) {
      return data;
    } finally {
      inflater.end();
    }
  }

  private static Optional<String> byteDifference(String name, byte[] expected, byte[] actual) {
    int at = Arrays.mismatch(expected, actual);
    if (at < 0) {
      return Optional.empty();
    }
    return Optional.of(
        name
            + ": first difference at byte "
            + at
            + " (expected "
            + expected.length
            + " bytes, generated "
            + actual.length
            + ")");
  }

  private static boolean isZip(byte[] bytes) {
    return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K';
  }

  private static boolean isPdf(byte[] bytes) {
    return bytes.length >= 5
        && new String(bytes, 0, 5, StandardCharsets.ISO_8859_1).equals("%PDF-");
  }
}
