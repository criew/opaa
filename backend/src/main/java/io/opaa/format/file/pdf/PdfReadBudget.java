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
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.DecodeOptions;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;

/**
 * What one PDF may cost to read, counted over every pass (table rulings, table text, flow text) and
 * every nested form: the characters it yields, the content-stream operators processed (each array
 * operand element counting as one more), the decoded content-stream bytes parsed - a form's bytes
 * each time it is drawn, since PDFBox parses it anew on every {@code Do} - and the wall-clock time
 * since the budget was created. A single content stream may decode to no more than its own limit,
 * measured by streaming before PDFBox, which decodes a stream into memory as a whole, gets to it. A
 * page is charged with its finished text; while a page is still being stripped, its glyph count is
 * checked against what is left. Not thread-safe: one budget belongs to one read of one document.
 */
final class PdfReadBudget {

  private final long maxCharacters;
  private final long maxOperators;
  private final long maxContentBytes;
  private final long maxStreamBytes;
  private final long maxSeconds;
  private final long deadline;
  private final Map<COSStream, Long> decodedLengths = new IdentityHashMap<>();
  private long usedCharacters;
  private long operators;
  private long contentBytes;

  PdfReadBudget(
      long maxCharacters,
      long maxOperators,
      long maxContentBytes,
      long maxStreamBytes,
      long maxSeconds) {
    this.maxCharacters = maxCharacters;
    this.maxOperators = maxOperators;
    this.maxContentBytes = maxContentBytes;
    this.maxStreamBytes = maxStreamBytes;
    this.maxSeconds = maxSeconds;
    // capped so the difference in requireTime() cannot overflow
    this.deadline =
        System.nanoTime() + Math.min(TimeUnit.SECONDS.toNanos(maxSeconds), Long.MAX_VALUE / 4);
  }

  static PdfReadBudget of(ParserLimitProperties limits) {
    return new PdfReadBudget(
        limits.maxTextCharacters(),
        limits.maxPdfOperators(),
        limits.maxPdfContentBytes(),
        limits.maxPdfStreamBytes(),
        limits.maxPdfParseSeconds());
  }

  static PdfReadBudget unbounded() {
    return new PdfReadBudget(
        Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
  }

  /** Throws when {@code pageGlyphs} more characters would exceed the budget. */
  void requireRoomFor(long pageGlyphs) {
    if (pageGlyphs > maxCharacters - usedCharacters) {
      throw new ParserLimitExceededException(
          "PDF text exceeds the configured text character limit of " + maxCharacters);
    }
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
    requireTime();
    Long length = decodedLengths.get(stream);
    if (length == null) {
      length = decodedLength(stream, maxStreamBytes);
      decodedLengths.put(stream, length);
    }
    contentBytes += length;
    if (contentBytes > maxContentBytes) {
      throw new ParserLimitExceededException(
          "PDF content exceeds the configured limit of " + maxContentBytes + " decoded bytes");
    }
  }

  /** {@link #chargeContent Charges} every content stream of {@code page}. */
  void chargePage(PDPage page) throws IOException {
    for (Iterator<PDStream> streams = page.getContentStreams(); streams.hasNext(); ) {
      chargeContent(streams.next().getCOSObject());
    }
  }

  private void requireTime() {
    if (System.nanoTime() - deadline > 0) {
      throw new ParserLimitExceededException(
          "PDF reading exceeds the configured limit of " + maxSeconds + " seconds");
    }
  }

  /**
   * Decoded bytes of {@code stream}, run through its filters here rather than through {@link
   * COSStream#createInputStream()}, which would decode the whole stream into memory first. The last
   * filter's output is counted and dropped; an earlier filter's, which the next one reads, is kept
   * up to {@code limit}. Throws as soon as either passes {@code limit}. Data a filter cannot decode
   * ends the measurement with what was counted, as PDFBox's own decoding ends at the same byte.
   */
  private static long decodedLength(COSStream stream, long limit) throws IOException {
    List<COSName> filters = filterNames(stream.getFilters());
    try (InputStream raw = stream.createRawInputStream()) {
      if (filters.isEmpty()) {
        CountingSink sink = new CountingSink(limit, false);
        raw.transferTo(sink);
        return sink.count;
      }
      InputStream input = raw;
      for (int i = 0; i < filters.size(); i++) {
        CountingSink sink = new CountingSink(limit, i < filters.size() - 1);
        try {
          FilterFactory.INSTANCE
              .getFilter(filters.get(i))
              .decode(input, sink, stream, i, DecodeOptions.DEFAULT);
        } catch (IOException e) {
          sink.requireWithinLimit();
          return sink.count;
        }
        sink.requireWithinLimit();
        if (i == filters.size() - 1) {
          return sink.count;
        }
        input = new ByteArrayInputStream(sink.kept.toByteArray());
      }
      return 0;
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
    private long count;
    private boolean exceeded;

    CountingSink(long limit, boolean keep) {
      this.limit = limit;
      this.kept = keep ? new ByteArrayOutputStream() : null;
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
            "PDF content stream exceeds the configured limit of " + limit + " decoded bytes");
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
