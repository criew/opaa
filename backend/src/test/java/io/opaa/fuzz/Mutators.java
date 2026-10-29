package io.opaa.fuzz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The mutations of a campaign: byte-level changes, textual ones aimed at counts, lengths and
 * nesting (the shapes of amplification), and structural ones inside a ZIP container and around a
 * message. Every mutation is deterministic for its {@link SplittableRandom}; none grows an input
 * past {@link #MAX_BYTES}.
 */
final class Mutators {

  /** The largest input a mutation produces; amplification needs no large input. */
  static final int MAX_BYTES = 4 * 1024 * 1024;

  /** The most a ZIP part may unpack to while a mutation reads it. */
  private static final int MAX_PART_BYTES = 16 * 1024 * 1024;

  private static final byte[] INTERESTING_BYTES = {
    0, (byte) 0xFF, 0x7F, (byte) 0x80, '<', '>', '(', ')', '/', '\n', '"', '[', ']', '%'
  };

  private static final String[] INTERESTING_NUMBERS = {
    "0",
    "-1",
    "1",
    "2",
    "255",
    "65535",
    "65536",
    "1048576",
    "2147483647",
    "-2147483648",
    "4294967296",
    "9223372036854775807",
    "99999999999999999999",
    "1e308",
    "0.000001"
  };

  private static final String RUN_CHARACTERS = " \t\n\r-#<[|0aÜ=*.";

  private static final Pattern NUMBER = Pattern.compile("-?\\d{1,20}");

  private static final Pattern OPENING_TAG =
      Pattern.compile("<[A-Za-z][\\w:.-]{0,40}[^<>]{0,200}>");

  /** One mutation: its name for the record of a finding, and what it does. */
  record Mutation(String name, Operator operator) {}

  @FunctionalInterface
  interface Operator {
    byte[] apply(byte[] input, SplittableRandom random, List<byte[]> others) throws IOException;
  }

  /** Mutations of any input, also applied to one part inside a container. */
  static final List<Mutation> BASIC =
      List.of(
          new Mutation("flipBits", Mutators::flipBits),
          new Mutation("interestingBytes", Mutators::interestingBytes),
          new Mutation("deleteRange", Mutators::deleteRange),
          new Mutation("duplicateRange", Mutators::duplicateRange),
          new Mutation("insertRun", Mutators::insertRun),
          new Mutation("truncate", Mutators::truncate),
          new Mutation("splice", Mutators::splice),
          new Mutation("replaceNumber", Mutators::replaceNumber),
          new Mutation("repeatLine", Mutators::repeatLine),
          new Mutation("nestMarkup", Mutators::nestMarkup));

  /** {@link #BASIC} and the structural mutations of a container and a message. */
  static final List<Mutation> ALL =
      java.util.stream.Stream.concat(
              BASIC.stream(),
              java.util.stream.Stream.of(
                  new Mutation("mutateZipPart", Mutators::mutateZipPart),
                  new Mutation("wrapMessage", Mutators::wrapMessage)))
          .toList();

  private Mutators() {}

  static byte[] flipBits(byte[] input, SplittableRandom random, List<byte[]> others) {
    if (input.length == 0) {
      return input;
    }
    byte[] out = input.clone();
    for (int i = random.nextInt(1, 9); i > 0; i--) {
      int at = random.nextInt(out.length);
      out[at] ^= (byte) (1 << random.nextInt(8));
    }
    return out;
  }

  static byte[] interestingBytes(byte[] input, SplittableRandom random, List<byte[]> others) {
    if (input.length == 0) {
      return input;
    }
    byte[] out = input.clone();
    for (int i = random.nextInt(1, 5); i > 0; i--) {
      out[random.nextInt(out.length)] = INTERESTING_BYTES[random.nextInt(INTERESTING_BYTES.length)];
    }
    return out;
  }

  static byte[] deleteRange(byte[] input, SplittableRandom random, List<byte[]> others) {
    if (input.length < 2) {
      return input;
    }
    int from = random.nextInt(input.length);
    int to = Math.min(input.length, from + 1 + random.nextInt(Math.min(4096, input.length)));
    return concat(slice(input, 0, from), slice(input, to, input.length));
  }

  /** A block repeated in place: the shape of a repeated object, row, part or reference. */
  static byte[] duplicateRange(byte[] input, SplittableRandom random, List<byte[]> others) {
    if (input.length == 0) {
      return input;
    }
    int from = random.nextInt(input.length);
    int length = 1 + random.nextInt(Math.min(4096, input.length - from));
    int times = 1 + random.nextInt(random.nextBoolean() ? 8 : 2_000);
    times = Math.max(1, Math.min(times, (MAX_BYTES - input.length) / length));
    byte[] block = slice(input, from, from + length);
    ByteArrayOutputStream out = new ByteArrayOutputStream(input.length + block.length * times);
    out.write(input, 0, from + length);
    for (int i = 0; i < times; i++) {
      out.writeBytes(block);
    }
    out.write(input, from + length, input.length - from - length);
    return out.toByteArray();
  }

  /** A long run of one character: whitespace, a token, a marker, a heading or table border. */
  static byte[] insertRun(byte[] input, SplittableRandom random, List<byte[]> others) {
    int codePoint = RUN_CHARACTERS.codePointAt(random.nextInt(RUN_CHARACTERS.length()));
    int length = random.nextBoolean() ? random.nextInt(1, 256) : random.nextInt(1, 100_000);
    byte[] run = Character.toString(codePoint).repeat(length).getBytes(StandardCharsets.UTF_8);
    if (input.length + run.length > MAX_BYTES) {
      return input;
    }
    int at = random.nextInt(input.length + 1);
    return concat(slice(input, 0, at), run, slice(input, at, input.length));
  }

  static byte[] truncate(byte[] input, SplittableRandom random, List<byte[]> others) {
    return input.length < 2 ? input : slice(input, 0, random.nextInt(1, input.length));
  }

  /** The head of this input on the tail of another seed. */
  static byte[] splice(byte[] input, SplittableRandom random, List<byte[]> others) {
    byte[] other = others.get(random.nextInt(others.size()));
    if (input.length == 0 || other.length == 0) {
      return input;
    }
    byte[] spliced =
        concat(
            slice(input, 0, random.nextInt(input.length)),
            slice(other, random.nextInt(other.length), other.length));
    return spliced.length > MAX_BYTES ? input : spliced;
  }

  /** A count, length, width, offset or index replaced by a boundary value. */
  static byte[] replaceNumber(byte[] input, SplittableRandom random, List<byte[]> others) {
    String text = new String(input, StandardCharsets.ISO_8859_1);
    List<int[]> numbers = new ArrayList<>();
    Matcher matcher = NUMBER.matcher(text);
    while (matcher.find() && numbers.size() < 10_000) {
      numbers.add(new int[] {matcher.start(), matcher.end()});
    }
    if (numbers.isEmpty()) {
      return input;
    }
    int[] number = numbers.get(random.nextInt(numbers.size()));
    String replacement = INTERESTING_NUMBERS[random.nextInt(INTERESTING_NUMBERS.length)];
    return (text.substring(0, number[0]) + replacement + text.substring(number[1]))
        .getBytes(StandardCharsets.ISO_8859_1);
  }

  /** One line repeated: a heading, row, header line or cross-reference entry many times over. */
  static byte[] repeatLine(byte[] input, SplittableRandom random, List<byte[]> others) {
    if (input.length == 0) {
      return input;
    }
    int at = random.nextInt(input.length);
    int start = at;
    while (start > 0 && input[start - 1] != '\n') {
      start--;
    }
    int end = at;
    while (end < input.length && input[end] != '\n') {
      end++;
    }
    end = Math.min(input.length, end + 1);
    int length = Math.max(1, end - start);
    int times =
        Math.max(1, Math.min(random.nextInt(1, 5_000), (MAX_BYTES - input.length) / length));
    byte[] line = slice(input, start, end);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(input, 0, end);
    for (int i = 0; i < times; i++) {
      out.writeBytes(line);
    }
    out.write(input, end, input.length - end);
    return out.toByteArray();
  }

  /** An opening tag repeated without its closing ones: nesting as deep as the parser follows. */
  static byte[] nestMarkup(byte[] input, SplittableRandom random, List<byte[]> others) {
    String text = new String(input, StandardCharsets.UTF_8);
    List<int[]> tags = new ArrayList<>();
    Matcher matcher = OPENING_TAG.matcher(text);
    while (matcher.find() && tags.size() < 10_000) {
      if (text.charAt(matcher.end() - 2) != '/') {
        tags.add(new int[] {matcher.start(), matcher.end()});
      }
    }
    if (tags.isEmpty()) {
      return input;
    }
    int[] tag = tags.get(random.nextInt(tags.size()));
    String opening = text.substring(tag[0], tag[1]);
    int times = Math.max(1, Math.min(random.nextInt(1, 3_000), MAX_BYTES / 2 / opening.length()));
    byte[] nested =
        (text.substring(0, tag[1]) + opening.repeat(times) + text.substring(tag[1]))
            .getBytes(StandardCharsets.UTF_8);
    return nested.length > MAX_BYTES ? input : nested;
  }

  /**
   * One part of a ZIP container (OOXML, ODF) mutated by another mutation, or unpacked larger by
   * repetition, then packed again with the parts in their order and {@code mimetype} stored.
   */
  static byte[] mutateZipPart(byte[] input, SplittableRandom random, List<byte[]> others)
      throws IOException {
    Map<String, byte[]> parts = unzip(input);
    if (parts == null || parts.isEmpty()) {
      return input;
    }
    List<String> names = new ArrayList<>(parts.keySet());
    String name = names.get(random.nextInt(names.size()));
    byte[] part = parts.get(name);
    if (random.nextInt(4) == 0 && part.length > 0) {
      int times = Math.min(random.nextInt(2, 200), MAX_PART_BYTES / part.length);
      parts.put(name, repeatedBody(part, Math.max(1, times)));
    } else {
      Mutation inner = BASIC.get(random.nextInt(BASIC.size()));
      parts.put(name, inner.operator().apply(part, random, others));
    }
    byte[] zipped = zip(parts);
    return zipped.length > MAX_BYTES ? input : zipped;
  }

  /** The message as the attachment of another, one level of nesting deeper. */
  static byte[] wrapMessage(byte[] input, SplittableRandom random, List<byte[]> others) {
    String head = new String(input, 0, Math.min(input.length, 512), StandardCharsets.ISO_8859_1);
    if (!head.contains("From:") && !head.contains("Subject:") && !head.contains("MIME-Version")) {
      return input;
    }
    String boundary = "w" + random.nextInt(1_000_000);
    byte[] before =
        ("From: a@example.org\r\nSubject: Weitergeleitet\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\""
                + boundary
                + "\"\r\n\r\n--"
                + boundary
                + "\r\nContent-Type: message/rfc822\r\n"
                + "Content-Disposition: attachment; filename=\"weiter.eml\"\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
    byte[] after = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
    byte[] wrapped = concat(before, input, after);
    return wrapped.length > MAX_BYTES ? input : wrapped;
  }

  /** The XML body of a part with its children repeated: the root element keeps one copy. */
  private static byte[] repeatedBody(byte[] part, int times) {
    String text = new String(part, StandardCharsets.UTF_8);
    int open = text.indexOf('>', text.indexOf("<", text.startsWith("<?") ? 2 : 0) + 1);
    int close = text.lastIndexOf("</");
    if (open < 0 || close <= open) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      for (int i = 0; i < times; i++) {
        out.writeBytes(part);
      }
      return out.toByteArray();
    }
    String body = text.substring(open + 1, close);
    return (text.substring(0, open + 1) + body.repeat(times) + text.substring(close))
        .getBytes(StandardCharsets.UTF_8);
  }

  /** The parts of a ZIP in their order, or {@code null} for no readable ZIP within bounds. */
  static Map<String, byte[]> unzip(byte[] input) {
    if (input.length < 4 || input[0] != 'P' || input[1] != 'K') {
      return null;
    }
    Map<String, byte[]> parts = new LinkedHashMap<>();
    long total = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(input))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        byte[] data = zip.readNBytes(MAX_PART_BYTES + 1);
        total += data.length;
        if (data.length > MAX_PART_BYTES || total > 4L * MAX_PART_BYTES) {
          return null;
        }
        parts.putIfAbsent(entry.getName(), data);
      }
    } catch (IOException | IllegalArgumentException e) {
      return parts.isEmpty() ? null : parts;
    }
    return parts;
  }

  static byte[] zip(Map<String, byte[]> parts) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> part : parts.entrySet()) {
        ZipEntry entry = new ZipEntry(part.getKey());
        if (part.getKey().equals("mimetype")) {
          CRC32 crc = new CRC32();
          crc.update(part.getValue());
          entry.setMethod(ZipEntry.STORED);
          entry.setSize(part.getValue().length);
          entry.setCompressedSize(part.getValue().length);
          entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(part.getValue());
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private static byte[] slice(byte[] input, int from, int to) {
    byte[] out = new byte[to - from];
    System.arraycopy(input, from, out, 0, out.length);
    return out;
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }
}
