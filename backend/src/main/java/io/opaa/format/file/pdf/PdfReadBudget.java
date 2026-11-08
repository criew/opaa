package io.opaa.format.file.pdf;

import io.opaa.format.shared.ParserLimitExceededException;
import io.opaa.format.shared.ParserLimitProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.DecodeOptions;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;

/**
 * What one PDF may cost to read, counted over every pass (table rulings, table text, flow text) and
 * every nested form: the characters it yields, the content-stream operators processed (each array
 * operand element counting as one more), the decoded content-stream bytes parsed - a form's bytes
 * each time it is drawn, since PDFBox parses it anew on every {@code Do} - and the wall-clock time
 * since the budget was created. A single content stream may decode to no more than its own limit,
 * measured by streaming before PDFBox, which decodes a stream into memory as a whole, gets to it. A
 * page is charged with its finished text; while a page is still being stripped, its glyph count is
 * checked against what is left and against {@link #MAX_PAGE_GLYPHS}. The objects PDFBox registers
 * while it loads the file count against their own limit. An image the renderer draws is checked by
 * {@link #requireImage} and a font every pass selects by {@link #requireFont} before PDFBox decodes
 * it. Not thread-safe: one budget belongs to one read of one document or one rendered page.
 */
final class PdfReadBudget {

  /**
   * The filters a content stream may carry, full and abbreviated names. The other standard filters
   * are image codecs whose decoders allocate the whole raster a header claims before a byte is
   * written; a stream naming one, or an unknown filter, is refused before it is decoded.
   */
  private static final Set<COSName> CONTENT_FILTERS =
      Set.of(
          COSName.FLATE_DECODE,
          COSName.FLATE_DECODE_ABBREVIATION,
          COSName.LZW_DECODE,
          COSName.LZW_DECODE_ABBREVIATION,
          COSName.ASCII_HEX_DECODE,
          COSName.ASCII_HEX_DECODE_ABBREVIATION,
          COSName.ASCII85_DECODE,
          COSName.ASCII85_DECODE_ABBREVIATION,
          COSName.RUN_LENGTH_DECODE,
          COSName.RUN_LENGTH_DECODE_ABBREVIATION,
          COSName.CRYPT);

  /** The image codecs PDFBox decodes itself; an image may carry one, as its last filter. */
  private static final Set<COSName> IMAGE_CODECS =
      Set.of(
          COSName.DCT_DECODE,
          COSName.DCT_DECODE_ABBREVIATION,
          COSName.CCITTFAX_DECODE,
          COSName.CCITTFAX_DECODE_ABBREVIATION,
          COSName.JPX_DECODE,
          COSName.JBIG2_DECODE);

  /**
   * The most glyphs one page may yield in one pass. PDFBox holds every glyph of a page as a {@code
   * TextPosition} of roughly 200 bytes until the page is written, so this bounds the heap of a page
   * that draws one form thousands of times well below what the document's character limit allows.
   */
  static final long MAX_PAGE_GLYPHS = 200_000;

  /** The most descendant-font levels and chained CMaps of one font. */
  private static final int MAX_FONT_NESTING = 4;

  private final long maxCharacters;
  private final long maxOperators;
  private final long maxContentBytes;
  private final long maxStreamBytes;
  private final long maxSeconds;
  private final long maxObjects;
  private final long maxNanos;
  private long deadline;
  private final Map<COSStream, Long> decodedLengths = new IdentityHashMap<>();
  private final Map<COSStream, Long> imageBytes = new IdentityHashMap<>();
  private final Set<COSStream> checkedResources =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private final Set<COSDictionary> checkedFonts =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private final Set<COSStream> checkedFontStreams =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private long usedCharacters;
  private long operators;
  private long contentBytes;
  private long objects;

  PdfReadBudget(
      long maxCharacters,
      long maxOperators,
      long maxContentBytes,
      long maxStreamBytes,
      long maxSeconds,
      long maxObjects) {
    this.maxCharacters = maxCharacters;
    this.maxOperators = maxOperators;
    this.maxContentBytes = maxContentBytes;
    this.maxStreamBytes = maxStreamBytes;
    this.maxSeconds = maxSeconds;
    this.maxObjects = maxObjects;
    // capped so the difference in requireTime() cannot overflow
    this.maxNanos = Math.min(TimeUnit.SECONDS.toNanos(maxSeconds), Long.MAX_VALUE / 4);
    this.deadline = System.nanoTime() + maxNanos;
  }

  static PdfReadBudget of(ParserLimitProperties limits) {
    return new PdfReadBudget(
        limits.maxTextCharacters(),
        limits.maxPdfOperators(),
        limits.maxPdfContentBytes(),
        limits.maxPdfStreamBytes(),
        limits.maxPdfParseSeconds(),
        limits.maxPdfObjects());
  }

  static PdfReadBudget unbounded() {
    return new PdfReadBudget(
        Long.MAX_VALUE,
        Long.MAX_VALUE,
        Long.MAX_VALUE,
        Long.MAX_VALUE,
        Long.MAX_VALUE,
        Long.MAX_VALUE);
  }

  /** Throws when {@code pageGlyphs} more characters would exceed the budget. */
  private void requireRoomFor(long pageGlyphs) {
    if (pageGlyphs > maxCharacters - usedCharacters) {
      throw new ParserLimitExceededException(
          "PDF text exceeds the configured text character limit of " + maxCharacters);
    }
  }

  /**
   * Throws when a page has yielded {@code pageGlyphs} glyphs, past {@link #MAX_PAGE_GLYPHS} or past
   * what is left of the document's characters.
   */
  void requireRoomForPageGlyphs(long pageGlyphs) {
    if (pageGlyphs > MAX_PAGE_GLYPHS) {
      throw new ParserLimitExceededException(
          "PDF page exceeds the glyph limit of " + MAX_PAGE_GLYPHS + " per page");
    }
    requireRoomFor(pageGlyphs);
  }

  /** Adds a finished page's {@code characters}; throws when the budget is exceeded. */
  void charge(long characters) {
    requireRoomFor(characters);
    usedCharacters += characters;
  }

  /**
   * Counts one processed operator and the elements of its array operands; throws once the document
   * has used up its operators or its time.
   */
  void countOperator(List<COSBase> operands) {
    long units = 1;
    for (COSBase operand : operands) {
      if (operand instanceof COSArray array) {
        units += elements(array);
      }
    }
    operators += units;
    if (operators > maxOperators) {
      throw new ParserLimitExceededException(
          "PDF content exceeds the configured limit of " + maxOperators + " operators");
    }
    requireTime();
  }

  /**
   * Charges the decoded length of {@code stream}, which is about to be parsed; a stream is decoded
   * once to measure it and charged again on every later call. Throws when the stream alone decodes
   * to more than its limit, or once the document has used up its content bytes or its time.
   */
  void chargeContent(COSStream stream) throws IOException {
    contentBytes += measure(stream);
    if (contentBytes > maxContentBytes) {
      throw new ParserLimitExceededException(
          "PDF content exceeds the configured limit of " + maxContentBytes + " decoded bytes");
    }
  }

  /**
   * The decoded length of {@code stream}, decoded once to measure it and remembered after. Throws
   * when it decodes to more than the stream limit, carries a filter outside the content filters, or
   * once the document has used up its time; charges nothing.
   */
  long measure(COSStream stream) throws IOException {
    requireTime();
    Long length = decodedLengths.get(stream);
    if (length == null) {
      length = decodedLength(stream, maxStreamBytes, "content stream");
      decodedLengths.put(stream, length);
    }
    return length;
  }

  /**
   * Like {@link #measure}, but decodes {@code stream} again even when it was measured before: its
   * data may have changed since, as an object stream's does when PDFBox decrypts it.
   */
  long remeasure(COSStream stream) throws IOException {
    decodedLengths.remove(stream);
    return measure(stream);
  }

  /** {@link #chargeContent Charges} every content stream of {@code page}. */
  void chargePage(PDPage page) throws IOException {
    for (Iterator<PDStream> streams = page.getContentStreams(); streams.hasNext(); ) {
      chargeContent(streams.next().getCOSObject());
    }
  }

  /**
   * Counts {@code count} more objects PDFBox registers while loading or reading the document;
   * throws once the document has used up its objects.
   */
  void chargeObjects(long count) {
    objects += Math.max(0, count);
    if (objects > maxObjects) {
      throw new ParserLimitExceededException(
          "PDF exceeds the configured limit of " + maxObjects + " objects");
    }
  }

  /**
   * Gives the budget its whole time again from now; what it counted stays counted. For work that
   * resumes after a pause outside the read, as rendering the next page does.
   */
  void restartClock() {
    deadline = System.nanoTime() + maxNanos;
  }

  /** Throws once the document has used up its time. */
  void requireTime() {
    if (System.nanoTime() - deadline > 0) {
      throw new ParserLimitExceededException(
          "PDF reading exceeds the configured limit of " + maxSeconds + " seconds");
    }
  }

  /**
   * Checks an image stream before PDFBox decodes it, which it does into memory as a whole, and
   * returns the bytes that decoding holds: the content filters ahead of an image codec, or all of
   * them without one, measured by streaming; a JPEG's raster as its frame header claims it, a CCITT
   * stream's as its columns and rows do. Throws past {@code maxBytes}, or for a filter ahead of the
   * codec outside the content filters. A stream is decoded once per budget.
   */
  long requireImage(COSStream stream, long maxBytes) throws IOException {
    requireTime();
    Long known = imageBytes.get(stream);
    if (known != null) {
      return known;
    }
    List<COSName> filters = filterNames(stream.getFilters());
    COSName codec =
        !filters.isEmpty() && IMAGE_CODECS.contains(filters.getLast()) ? filters.getLast() : null;
    List<COSName> leading = codec == null ? filters : filters.subList(0, filters.size() - 1);
    for (COSName filter : leading) {
      if (!CONTENT_FILTERS.contains(filter)) {
        throw new ParserLimitExceededException(
            "PDF image uses the filter " + filter.getName() + " ahead of its codec");
      }
    }
    boolean jpeg =
        COSName.DCT_DECODE.equals(codec) || COSName.DCT_DECODE_ABBREVIATION.equals(codec);
    Decoded decoded = decode(stream, leading, maxBytes, jpeg, "image stream");
    long bytes = decoded.count();
    if (jpeg) {
      bytes = jpegBytes(decoded.output());
    } else if (COSName.CCITTFAX_DECODE.equals(codec)
        || COSName.CCITTFAX_DECODE_ABBREVIATION.equals(codec)) {
      bytes = ccittBytes(stream, filters.size() - 1);
    }
    if (bytes > maxBytes) {
      throw new ParserLimitExceededException(
          "PDF image codec claims " + bytes + " bytes, more than the limit of " + maxBytes);
    }
    imageBytes.put(stream, bytes);
    return bytes;
  }

  /**
   * Throws when {@code stream}, which PDFBox reads into memory as a whole (shading data, a sampled
   * function, a colour profile), decodes to more than {@code maxBytes} or carries a filter outside
   * the content filters. A stream is decoded once per budget.
   */
  void requireResourceStream(COSStream stream, long maxBytes) throws IOException {
    requireTime();
    if (checkedResources.contains(stream)) {
      return;
    }
    decodedLength(stream, maxBytes, "resource stream");
    checkedResources.add(stream);
  }

  /**
   * Checks the font {@code operator} selects when it is {@code Tf}, by {@link #requireFont}, before
   * PDFBox loads it from {@code resources}.
   */
  void requireFontOperator(Operator operator, List<COSBase> operands, PDResources resources)
      throws IOException {
    if (!OperatorName.SET_FONT_AND_SIZE.equals(operator.getName())
        || resources == null
        || operands.isEmpty()
        || !(operands.getFirst() instanceof COSName name)) {
      return;
    }
    COSDictionary fonts = resources.getCOSObject().getCOSDictionary(COSName.FONT);
    if (fonts != null) {
      requireFont(fonts.getDictionaryObject(name));
    }
  }

  /**
   * Checks the streams PDFBox reads into memory as a whole when it loads {@code font}: the embedded
   * font programs of its descriptor and of its descendant fonts, an embedded CMap with its {@code
   * /UseCMap} chain, {@code /CIDToGIDMap} and {@code /ToUnicode}. Each may decode to no more than
   * the stream limit. A Type 3 font's glyphs are content, charged when drawn. A font is checked
   * once per budget.
   */
  void requireFont(COSBase font) throws IOException {
    requireFont(font, 0);
  }

  private void requireFont(COSBase font, int depth) throws IOException {
    if (!(font instanceof COSDictionary dictionary) || !checkedFonts.add(dictionary)) {
      return;
    }
    requireTime();
    requireFontStream(dictionary.getDictionaryObject(COSName.TO_UNICODE));
    requireFontStream(dictionary.getDictionaryObject(COSName.CID_TO_GID_MAP));
    COSBase encoding = dictionary.getDictionaryObject(COSName.ENCODING);
    for (int cmaps = 0; encoding instanceof COSStream cmap; cmaps++) {
      if (cmaps >= MAX_FONT_NESTING) {
        throw new ParserLimitExceededException(
            "PDF font chains more than " + MAX_FONT_NESTING + " CMaps");
      }
      requireFontStream(cmap);
      encoding = cmap.getDictionaryObject(COSName.USE_CMAP);
    }
    if (dictionary.getDictionaryObject(COSName.FONT_DESC) instanceof COSDictionary descriptor) {
      requireFontStream(descriptor.getDictionaryObject(COSName.FONT_FILE));
      requireFontStream(descriptor.getDictionaryObject(COSName.FONT_FILE2));
      requireFontStream(descriptor.getDictionaryObject(COSName.FONT_FILE3));
    }
    if (dictionary.getDictionaryObject(COSName.DESCENDANT_FONTS) instanceof COSArray descendants) {
      if (depth >= MAX_FONT_NESTING) {
        throw new ParserLimitExceededException(
            "PDF font nests descendant fonts deeper than " + MAX_FONT_NESTING);
      }
      for (int i = 0; i < Math.min(descendants.size(), MAX_FONT_NESTING); i++) {
        requireFont(descendants.getObject(i), depth + 1);
      }
    }
  }

  private void requireFontStream(COSBase base) throws IOException {
    if (base instanceof COSStream stream && !checkedFontStreams.contains(stream)) {
      decodedLength(stream, maxStreamBytes, "font stream");
      checkedFontStreams.add(stream);
    }
  }

  /** The bytes of the raster a JPEG's frame header claims, read without decoding its scan. */
  private static long jpegBytes(byte[] jpeg) throws IOException {
    Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
    if (!readers.hasNext()) {
      throw new IOException("No JPEG reader available");
    }
    ImageReader reader = readers.next();
    try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg))) {
      reader.setInput(input, true, true);
      ImageTypeSpecifier type = reader.getRawImageType(0);
      long bands = type == null ? 4 : type.getNumBands();
      return (long) reader.getWidth(0) * reader.getHeight(0) * bands;
    } finally {
      reader.dispose();
    }
  }

  /** The bytes PDFBox's CCITT decoder allocates, sized by columns and rows. */
  private static long ccittBytes(COSStream stream, int index) {
    COSBase parameters = stream.getDictionaryObject(COSName.DECODE_PARMS, COSName.DP);
    if (parameters instanceof COSArray array) {
      parameters = index < array.size() ? array.getObject(index) : null;
    }
    COSDictionary decodeParms =
        parameters instanceof COSDictionary dictionary ? dictionary : new COSDictionary();
    long columns = Math.max(0, decodeParms.getInt(COSName.COLUMNS, 1728));
    long rows = decodeParms.getInt(COSName.ROWS, 0);
    long height = stream.getInt(COSName.HEIGHT, COSName.H, 0);
    rows = rows > 0 && height > 0 ? height : Math.max(Math.max(rows, height), 0);
    return (columns + 7) / 8 * rows;
  }

  /**
   * Decoded bytes of {@code stream}, run through its filters here rather than through {@link
   * COSStream#createInputStream()}, which would decode the whole stream into memory first. A filter
   * outside {@link #CONTENT_FILTERS} is refused before anything is decoded.
   */
  private static long decodedLength(COSStream stream, long limit, String subject)
      throws IOException {
    List<COSName> filters = filterNames(stream.getFilters());
    for (COSName filter : filters) {
      if (!CONTENT_FILTERS.contains(filter)) {
        throw new ParserLimitExceededException(
            "PDF " + subject + " uses the filter " + filter.getName() + ", which it may not carry");
      }
    }
    return decode(stream, filters, limit, false, subject).count();
  }

  /** What {@link #decode} counted, and the last filter's output if it was kept. */
  private record Decoded(long count, byte[] output) {}

  /**
   * Runs the raw bytes of {@code stream} through {@code filters}, the first filters of the stream.
   * The last filter's output is counted, and kept only with {@code keepLast}; an earlier filter's,
   * which the next one reads, is kept. Throws as soon as any output passes {@code limit}. Data a
   * filter cannot decode ends the decoding with what was counted, as PDFBox's own decoding ends at
   * the same byte.
   */
  private static Decoded decode(
      COSStream stream, List<COSName> filters, long limit, boolean keepLast, String subject)
      throws IOException {
    try (InputStream raw = stream.createRawInputStream()) {
      if (filters.isEmpty()) {
        CountingSink sink = new CountingSink(limit, keepLast, subject);
        raw.transferTo(sink);
        return sink.result();
      }
      InputStream input = raw;
      for (int i = 0; ; i++) {
        boolean last = i == filters.size() - 1;
        CountingSink sink = new CountingSink(limit, !last || keepLast, subject);
        try {
          FilterFactory.INSTANCE
              .getFilter(filters.get(i))
              .decode(input, sink, stream, i, DecodeOptions.DEFAULT);
        } catch (IOException e) {
          sink.requireWithinLimit();
          return sink.result();
        }
        sink.requireWithinLimit();
        if (last) {
          return sink.result();
        }
        input = new ByteArrayInputStream(sink.kept.toByteArray());
      }
    }
  }

  private static List<COSName> filterNames(COSBase filters) {
    if (filters instanceof COSName name) {
      return List.of(name);
    }
    List<COSName> names = new ArrayList<>();
    if (filters instanceof COSArray array) {
      for (int i = 0; i < array.size(); i++) {
        if (array.getObject(i) instanceof COSName name) {
          names.add(name);
        }
      }
    }
    return names;
  }

  /**
   * Counts what a filter writes and throws past {@code limit}; the flag survives a filter that
   * catches the exception, so the caller still sees the overrun.
   */
  private static final class CountingSink extends OutputStream {

    private final long limit;
    private final ByteArrayOutputStream kept;
    private final String subject;
    private long count;
    private boolean exceeded;

    CountingSink(long limit, boolean keep, String subject) {
      this.limit = limit;
      this.kept = keep ? new ByteArrayOutputStream() : null;
      this.subject = subject;
    }

    Decoded result() {
      return new Decoded(count, kept == null ? null : kept.toByteArray());
    }

    @Override
    public void write(int b) {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      count += length;
      requireWithinLimit();
      if (kept != null) {
        kept.write(bytes, offset, length);
      }
    }

    void requireWithinLimit() {
      if (exceeded || count > limit) {
        exceeded = true;
        throw new ParserLimitExceededException(
            "PDF " + subject + " exceeds the configured limit of " + limit + " decoded bytes");
      }
    }
  }

  /** Elements of {@code array} and every array nested in it, counted without recursion. */
  private static long elements(COSArray array) {
    long count = 0;
    Deque<COSArray> pending = new ArrayDeque<>();
    pending.push(array);
    while (!pending.isEmpty()) {
      COSArray next = pending.pop();
      count += next.size();
      for (int i = 0; i < next.size(); i++) {
        if (next.get(i) instanceof COSArray nested) {
          pending.push(nested);
        }
      }
    }
    return count;
  }
}
