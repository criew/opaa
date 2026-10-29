package io.opaa.format.file.pdf;

import io.opaa.format.shared.ParserLimitExceededException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.BruteForceParser;
import org.apache.pdfbox.pdfparser.COSParser;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdfparser.XrefTrailerResolver;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.ProtectionPolicy;
import org.apache.pdfbox.pdmodel.encryption.SecurityHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a PDF like {@code Loader.loadPDF}, but every cross-reference and object stream PDFBox
 * decodes is measured against the stream limit of a {@link PdfReadBudget} first, and the objects it
 * registers - cross-reference entries, object-stream objects, brute-force finds - count against the
 * object limit. The brute-force search skips an encrypted file's object streams.
 */
final class PdfLoading {

  private static final Logger log = LoggerFactory.getLogger(PdfLoading.class);

  /** PDFBox creates its brute-force parser lazily into this field; it is replaced up front. */
  private static final Field BRUTE_FORCE_PARSER = bruteForceParserField();

  private static final int CLASSIC_ENTRY_BYTES = 20;

  private PdfLoading() {}

  /** The document in {@code file}, its loading charged to {@code budget}. */
  static PDDocument load(Path file, PdfReadBudget budget) throws IOException {
    RandomAccessRead source = new RandomAccessReadBufferedFile(file.toFile());
    try {
      MeasuringParser parser = new MeasuringParser(source, budget);
      PDDocument document = parser.parse();
      parser.loaded = true;
      return document;
    } catch (IOException | RuntimeException | Error e) {
      IOUtils.closeQuietly(source);
      throw e;
    }
  }

  /**
   * The pages of {@code doc} in reading order, found by walking its page tree, where every listing
   * of a page is one page. Throws {@link ParserLimitExceededException} past {@code maxPages}.
   * PDFBox queues a node's {@code /Kids} before the limit sees them (about 150 MB for millions of
   * kids), and a tree nested hundreds of thousands deep overflows its recursion: the read then
   * fails as a whole.
   */
  static List<PDPage> pages(PDDocument doc, long maxPages) {
    List<PDPage> pages = new ArrayList<>();
    for (PDPage page : doc.getPages()) {
      if (pages.size() >= maxPages) {
        throw new ParserLimitExceededException(
            "PDF exceeds the configured limit of " + maxPages + " pages");
      }
      pages.add(page);
    }
    return pages;
  }

  private static Field bruteForceParserField() {
    try {
      Field field = COSParser.class.getDeclaredField("bruteForceParser");
      field.setAccessible(true);
      return field;
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException("PDFBox no longer has COSParser#bruteForceParser", e);
    }
  }

  private static final class MeasuringParser extends PDFParser {

    private final PdfReadBudget budget;
    private final Set<COSStream> objectStreams = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean loaded;

    MeasuringParser(RandomAccessRead source, PdfReadBudget budget) throws IOException {
      super(source, "", null, null, IOUtils.createMemoryOnlyStreamCache());
      this.budget = budget;
      try {
        BRUTE_FORCE_PARSER.set(this, new MeasuringBruteForceParser(source, document, budget));
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("Cannot replace PDFBox's brute-force parser", e);
      }
    }

    @Override
    protected COSStream parseCOSStream(COSDictionary dic) throws IOException {
      COSStream stream = super.parseCOSStream(dic);
      if (!loaded) {
        long decoded = budget.measure(stream);
        if (COSName.XREF.equals(stream.getCOSName(COSName.TYPE))) {
          budget.chargeObjects(crossReferenceEntries(stream, decoded));
        }
      }
      return stream;
    }

    /** A classic table's entries are 20 bytes each, so its length bounds how many it held. */
    @Override
    protected boolean parseXrefTable(long startByteOffset) throws IOException {
      long start = source.getPosition();
      boolean parsed = super.parseXrefTable(startByteOffset);
      budget.chargeObjects((source.getPosition() - start) / CLASSIC_ENTRY_BYTES);
      return parsed;
    }

    @Override
    protected COSBase parseObjectStreamObject(long objstmObjNr, COSObjectKey key)
        throws IOException {
      budget.requireTime();
      COSBase container = document.getObjectFromPool(new COSObjectKey(objstmObjNr, 0)).getObject();
      if (container instanceof COSStream stream && objectStreams.add(stream)) {
        budget.chargeObjects(objectStreamObjects(stream, budget.remeasure(stream)));
      }
      return super.parseObjectStreamObject(objstmObjNr, key);
    }
  }

  /**
   * The entries PDFBox reads from a cross-reference stream: those its {@code /Index} (or {@code
   * /Size}) declares, but no more than its decoded bytes hold. A stream with valid {@code /W} whose
   * widths add up to zero would let PDFBox read entries without consuming a byte, so it is refused;
   * any other malformed {@code /W} is left to PDFBox to reject.
   */
  static long crossReferenceEntries(COSStream stream, long decodedBytes) {
    COSArray widths = stream.getCOSArray(COSName.W);
    if (widths == null || widths.size() != 3) {
      return 0;
    }
    long width = 0;
    for (int i = 0; i < 3; i++) {
      int w = widths.getInt(i, 0);
      if (w < 0) {
        return 0;
      }
      width += w;
    }
    if (width == 0) {
      throw new ParserLimitExceededException("PDF cross-reference stream has entries of no width");
    }
    long declared = 0;
    COSArray index = stream.getCOSArray(COSName.INDEX);
    if (index == null) {
      declared = Math.max(0, stream.getLong(COSName.SIZE, 0));
    } else {
      for (int i = 1; i < index.size(); i += 2) {
        if (index.getObject(i) instanceof COSNumber count) {
          declared = Math.min(Long.MAX_VALUE / 2, declared + Math.max(0, count.longValue()));
        }
      }
    }
    return Math.min(declared, decodedBytes / width);
  }

  /** The objects PDFBox parses from an object stream: its {@code /N}, at most one per byte. */
  static long objectStreamObjects(COSStream stream, long decodedBytes) {
    return Math.min(Math.max(0, stream.getLong(COSName.N, 0)), decodedBytes);
  }

  private static final class MeasuringBruteForceParser extends BruteForceParser {

    private final PdfReadBudget budget;
    private boolean searchingObjectStreams;
    private boolean objectsCharged;

    MeasuringBruteForceParser(RandomAccessRead source, COSDocument document, PdfReadBudget budget)
        throws IOException {
      super(source, document);
      this.budget = budget;
    }

    @Override
    protected void bfSearchForObjStreams(
        XrefTrailerResolver trailerResolver,
        SecurityHandler<? extends ProtectionPolicy> securityHandler)
        throws IOException {
      if (securityHandler != null) {
        log.debug("Skipping the brute-force search for object streams of an encrypted PDF");
        return;
      }
      searchingObjectStreams = true;
      try {
        super.bfSearchForObjStreams(trailerResolver, null);
      } finally {
        searchingObjectStreams = false;
      }
    }

    @Override
    protected Map<COSObjectKey, Long> getBFCOSObjectOffsets() throws IOException {
      Map<COSObjectKey, Long> offsets = super.getBFCOSObjectOffsets();
      if (!objectsCharged) {
        objectsCharged = true;
        budget.chargeObjects(offsets.size());
      }
      return offsets;
    }

    @Override
    protected COSStream parseCOSStream(COSDictionary dic) throws IOException {
      COSStream stream = super.parseCOSStream(dic);
      if (searchingObjectStreams) {
        budget.chargeObjects(objectStreamObjects(stream, budget.measure(stream)));
      }
      return stream;
    }
  }
}
