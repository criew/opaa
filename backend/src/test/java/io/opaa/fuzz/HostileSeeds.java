package io.opaa.fuzz;

import io.opaa.format.hostile.HostileFiles;
import io.opaa.format.hostile.HostileFiles.HostileFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Supplier;

/**
 * The crafted files of the regression suite ({@link HostileFiles}) as fuzzing seeds, generated at
 * load instead of committed, each scaled down so that one run takes a moment: the mutators grow
 * them, the regression suite holds their full size. Each passes {@link Mutators#reproducible} -
 * fixed PDF trailer IDs, ZIP entry times and package dates, also of embedded packages - so on the
 * same JDK the same campaign seed mutates the same bytes; another zlib may compress differently.
 */
final class HostileSeeds {

  /** The prefix of every seed name, which tells it from a committed one. */
  static final String PREFIX = "hostile-";

  private static final List<Supplier<HostileFile>> FILES =
      List.of(
          HostileFiles::pdfWithCyclicOutline,
          () -> HostileFiles.pdfDrawingOneFormOverAndOver(200),
          () -> HostileFiles.docxCompressionBomb(256 * 1024),
          () -> HostileFiles.emlWithNestedMessages(8, 2),
          () -> HostileFiles.msgWithSharedAttachmentSectors(8 * 1024, 10, 512),
          () -> HostileFiles.htmlWithBacktrackingTitle(2_000),
          () -> HostileFiles.markdownWithBacktrackingHeading(5_000),
          () -> HostileFiles.htmlWithNestedTables(60, 3),
          () -> HostileFiles.htmlWithManyContentRoots(2_000),
          () -> HostileFiles.htmlWithBacktrackingParagraph(2_000),
          () -> HostileFiles.pptxEmbeddingAWordDocumentPerSlide(3),
          () -> HostileFiles.docWithPackagedTextInItsObjectPool(3),
          () -> HostileFiles.textWithoutWhitespace(60_000));

  private HostileSeeds() {}

  /** Every crafted file as a seed named {@link #PREFIX} and its file name. */
  static List<FuzzCorpus.Seed> seeds() {
    return FILES.stream().map(Supplier::get).map(HostileSeeds::seed).toList();
  }

  private static FuzzCorpus.Seed seed(HostileFile file) {
    try {
      String name = PREFIX + file.fileName();
      return new FuzzCorpus.Seed(
          name, FuzzCorpus.extensionOf(name), Mutators.reproducible(file.bytes()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
