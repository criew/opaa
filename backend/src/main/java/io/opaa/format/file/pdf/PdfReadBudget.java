package io.opaa.format.file.pdf;

import io.opaa.format.shared.ParserLimitExceededException;
import io.opaa.format.shared.ParserLimitProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;

/**
 * What one PDF may cost to read, counted over every pass (table rulings, table text, flow text) and
 * every nested form: the characters it yields, the content-stream operators processed (each array
 * operand element counting as one more), the decoded content-stream bytes parsed - a form's bytes
 * each time it is drawn, since PDFBox parses it anew on every {@code Do} - and the wall-clock time
 * since the budget was created. A page is charged with its finished text; while a page is still
 * being stripped, its glyph count is checked against what is left. Not thread-safe: one budget
 * belongs to one read of one document.
 */
final class PdfReadBudget {

  private final long maxCharacters;
  private final long maxOperators;
  private final long maxContentBytes;
  private final long maxSeconds;
  private final long deadline;
  private final Map<COSStream, Long> decodedLengths = new IdentityHashMap<>();
  private long usedCharacters;
  private long operators;
  private long contentBytes;

  PdfReadBudget(long maxCharacters, long maxOperators, long maxContentBytes, long maxSeconds) {
    this.maxCharacters = maxCharacters;
    this.maxOperators = maxOperators;
    this.maxContentBytes = maxContentBytes;
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
        limits.maxPdfParseSeconds());
  }

  static PdfReadBudget unbounded() {
    return new PdfReadBudget(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
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
   * once to measure it and charged again on every later call. Throws once the document has used up
   * its content bytes or its time.
   */
  void chargeContent(COSStream stream) throws IOException {
    requireTime();
    Long length = decodedLengths.get(stream);
    if (length == null) {
      length = decodedLength(stream, maxContentBytes - contentBytes);
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

  /** Decoded bytes of {@code stream}, read no further than one byte past {@code remaining}. */
  private static long decodedLength(COSStream stream, long remaining) throws IOException {
    long total = 0;
    byte[] buffer = new byte[8192];
    try (InputStream in = stream.createInputStream()) {
      for (int n = in.read(buffer); n >= 0 && total <= remaining; n = in.read(buffer)) {
        total += n;
      }
    }
    return total;
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
