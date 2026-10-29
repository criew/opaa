package io.opaa.format.file.pdf;

import io.opaa.format.shared.ParserLimitExceededException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
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
 * Loads a PDF the way {@code Loader.loadPDF} does, but measures every stream PDFBox decodes into
 * memory while loading - the cross-reference streams and the object streams - against the stream
 * limit of a {@link PdfReadBudget} before it decodes them, and checks the budget's time on every
 * such stream. Measured are: every stream the parser reads before loading has finished (a
 * cross-reference stream is decoded right after it is read; object streams are among them, still
 * encrypted where the file is), every object stream before an object is taken from it, decrypted by
 * then, and every object stream the brute-force search for a broken cross-reference decodes. On
 * that search an encrypted file's object streams are not searched at all, since they could only be
 * measured after a decryption the search does itself; objects only they hold stay missing.
 *
 * <p>The pages are read by walking the page tree ({@link #pages}), never through the {@code /Count}
 * the file declares.
 */
final class PdfLoading {

  private static final Logger log = LoggerFactory.getLogger(PdfLoading.class);

  /** PDFBox creates its brute-force parser lazily into this field; it is replaced up front. */
  private static final Field BRUTE_FORCE_PARSER = bruteForceParserField();

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
        budget.measure(stream);
      }
      return stream;
    }

    @Override
    protected COSBase parseObjectStreamObject(long objstmObjNr, COSObjectKey key)
        throws IOException {
      budget.requireTime();
      COSBase container = document.getObjectFromPool(new COSObjectKey(objstmObjNr, 0)).getObject();
      if (container instanceof COSStream stream && objectStreams.add(stream)) {
        budget.remeasure(stream);
      }
      return super.parseObjectStreamObject(objstmObjNr, key);
    }
  }

  private static final class MeasuringBruteForceParser extends BruteForceParser {

    private final PdfReadBudget budget;
    private boolean searchingObjectStreams;

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
    protected COSStream parseCOSStream(COSDictionary dic) throws IOException {
      COSStream stream = super.parseCOSStream(dic);
      if (searchingObjectStreams) {
        budget.measure(stream);
      }
      return stream;
    }
  }
}
