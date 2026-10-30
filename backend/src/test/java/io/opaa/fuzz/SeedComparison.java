package io.opaa.fuzz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
 * implementation does not decide; another zlib may compress the same content to other bytes.
 *
 * <ul>
 *   <li>A ZIP entry by entry: name, order, method, time and uncompressed content, nested packages
 *       likewise. Only the local entries count - central directory, extra fields and comments are
 *       not compared, the generator crafts nothing there.
 *   <li>A PDF with a Flate stream with every Flate stream inflated (bytes after the deflate end and
 *       a stream that does not inflate compared raw), its {@code /Length} masked, and an xref
 *       offset or {@code startxref} masked only where it points where it should in its own file.
 *   <li>Anything else, a PDF without Flate stream included, byte for byte.
 * </ul>
 */
final class SeedComparison {

  private static final Pattern STREAM_START = Pattern.compile("/Length (\\d++) >>\nstream\n");
  private static final Pattern XREF_SECTION = Pattern.compile("(?m)^xref\n");
  private static final Pattern SUBSECTION = Pattern.compile("(\\d++) (\\d++)\n");
  private static final Pattern XREF_ENTRY = Pattern.compile("(\\d{10}) (\\d{5}) ([nf]) ?\r?\n");
  private static final Pattern START_XREF = Pattern.compile("startxref\n(\\d++)");
  private static final Pattern OBJECT_HEADER = Pattern.compile("\\d++ \\d++ obj\\b");
  private static final String END_STREAM = "\nendstream";
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
    if (isPdf(expected) && isPdf(actual) && hasFlateStream(expected) && hasFlateStream(actual)) {
      return byteDifference(
          name + " (PDF, Flate streams inflated)", canonical(expected), canonical(actual));
    }
    return byteDifference(name, expected, actual);
  }

  // --- ZIP ---------------------------------------------------------------------------------------

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

  // --- PDF ---------------------------------------------------------------------------------------

  /** A part of the file replaced in its canonical form; parts never overlap. */
  private record Replacement(int start, int end, String text) {}

  private record Stream(int dataStart, int dataEnd, boolean flate) {}

  private static boolean hasFlateStream(byte[] pdf) {
    String text = latin1(pdf);
    return streams(text).stream().anyMatch(Stream::flate);
  }

  /** Every stream whose {@code /Length} ends right before {@code endstream}. */
  private static List<Stream> streams(String text) {
    List<Stream> streams = new ArrayList<>();
    Matcher start = STREAM_START.matcher(text);
    int from = 0;
    while (start.find(from)) {
      long length = Long.parseLong(start.group(1));
      int dataStart = start.end();
      if (dataStart + length > text.length()
          || !text.startsWith(END_STREAM, dataStart + (int) length)) {
        from = dataStart;
        continue;
      }
      int dataEnd = dataStart + (int) length;
      int object = text.lastIndexOf(" obj", start.start());
      boolean flate = object >= 0 && text.substring(object, start.start()).contains("/FlateDecode");
      streams.add(new Stream(dataStart, dataEnd, flate));
      from = dataEnd;
    }
    return streams;
  }

  /**
   * The PDF with every Flate stream inflated and its length masked, and every xref offset and
   * {@code startxref} that points where it should masked; an offset pointing elsewhere stays.
   */
  private static byte[] canonical(byte[] pdf) {
    String text = latin1(pdf);
    List<Stream> streams = streams(text);
    List<Replacement> replacements = new ArrayList<>();
    for (Stream stream : streams) {
      if (stream.flate()) {
        int lengthStart = text.lastIndexOf("/Length ", stream.dataStart());
        replacements.add(
            new Replacement(lengthStart, stream.dataStart(), "/Length _ >>\nstream\n"));
        replacements.add(
            new Replacement(
                stream.dataStart(),
                stream.dataEnd(),
                inflated(text.substring(stream.dataStart(), stream.dataEnd()))));
      }
    }
    xrefOffsets(text, streams, replacements);
    startXrefs(text, streams, replacements);

    replacements.sort(Comparator.comparingInt(Replacement::start));
    StringBuilder out = new StringBuilder(text.length());
    int from = 0;
    for (Replacement replacement : replacements) {
      out.append(text, from, replacement.start()).append(replacement.text());
      from = replacement.end();
    }
    out.append(text, from, text.length());
    return out.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  /** Masks the offset of each in-use xref entry that points to its own object. */
  private static void xrefOffsets(String text, List<Stream> streams, List<Replacement> out) {
    Matcher section = XREF_SECTION.matcher(text);
    while (section.find()) {
      if (insideStream(section.start(), streams)) {
        continue;
      }
      int at = section.end();
      Matcher subsection = SUBSECTION.matcher(text);
      while (subsection.region(at, text.length()).lookingAt()) {
        long number = Long.parseLong(subsection.group(1));
        long count = Long.parseLong(subsection.group(2));
        at = subsection.end();
        Matcher entry = XREF_ENTRY.matcher(text);
        for (long i = 0; i < count && entry.region(at, text.length()).lookingAt(); i++) {
          long offset = Long.parseLong(entry.group(1));
          int generation = Integer.parseInt(entry.group(2));
          String header = (number + i) + " " + generation + " obj";
          if (entry.group(3).equals("n")
              && offset < text.length()
              && text.startsWith(header, (int) offset)) {
            out.add(new Replacement(entry.start(1), entry.end(1), "_".repeat(10)));
          }
          at = entry.end();
        }
      }
    }
  }

  /** Masks a {@code startxref} that points to an xref table or to an XRef stream object. */
  private static void startXrefs(String text, List<Stream> streams, List<Replacement> out) {
    Matcher start = START_XREF.matcher(text);
    while (start.find()) {
      if (insideStream(start.start(), streams)) {
        continue;
      }
      long offset = Long.parseLong(start.group(1));
      if (offset >= text.length()) {
        continue;
      }
      int at = (int) offset;
      boolean table = text.startsWith("xref", at);
      Matcher object = OBJECT_HEADER.matcher(text).region(at, text.length());
      boolean xrefStream = false;
      if (!table && object.lookingAt()) {
        int dictionaryEnd = text.indexOf("stream", object.end());
        xrefStream =
            dictionaryEnd > 0
                && text.substring(object.end(), dictionaryEnd).contains("/Type /XRef");
      }
      if (table || xrefStream) {
        out.add(new Replacement(start.start(1), start.end(1), "_"));
      }
    }
  }

  private static boolean insideStream(int position, List<Stream> streams) {
    return streams.stream().anyMatch(s -> position >= s.dataStart() && position < s.dataEnd());
  }

  /**
   * The inflated stream, then any bytes after the deflate end; a stream that does not inflate to
   * its end comes back raw, marked as such, so its bytes are compared as they are.
   */
  private static String inflated(String data) {
    byte[] raw = data.getBytes(StandardCharsets.ISO_8859_1);
    Inflater inflater = new Inflater();
    try {
      inflater.setInput(raw);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      while (!inflater.finished() && out.size() <= MAX_INFLATED_BYTES) {
        int n = inflater.inflate(buffer);
        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
          break;
        }
        out.write(buffer, 0, n);
      }
      if (!inflater.finished()) {
        return "[raw]" + data;
      }
      int trailing = inflater.getRemaining();
      return "[inflated]"
          + out.toString(StandardCharsets.ISO_8859_1)
          + "[after the deflate end]"
          + data.substring(data.length() - trailing);
    } catch (DataFormatException e) {
      return "[raw]" + data;
    } finally {
      inflater.end();
    }
  }

  // --- Bytes -------------------------------------------------------------------------------------

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

  private static String latin1(byte[] bytes) {
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }

  private static boolean isZip(byte[] bytes) {
    return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K';
  }

  private static boolean isPdf(byte[] bytes) {
    return bytes.length >= 5 && latin1(Arrays.copyOf(bytes, 5)).equals("%PDF-");
  }
}
