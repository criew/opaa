package io.opaa.test;

import io.opaa.indexing.format.DocumentFormat;
import io.opaa.indexing.format.DocumentFormatRegistry;
import io.opaa.indexing.format.SupportedDocumentFormats;
import io.opaa.indexing.format.file.fallback.TikaFallbackFormat;
import io.opaa.indexing.format.file.html.HtmlDocumentFormat;
import io.opaa.indexing.format.file.mail.MailDocumentFormat;
import io.opaa.indexing.format.file.mail.MailProperties;
import io.opaa.indexing.format.file.markdown.MarkdownDocumentFormat;
import io.opaa.indexing.format.file.office.DocxDocumentFormat;
import io.opaa.indexing.format.file.office.OdfProperties;
import io.opaa.indexing.format.file.office.OdpDocumentFormat;
import io.opaa.indexing.format.file.office.OdtDocumentFormat;
import io.opaa.indexing.format.file.office.PptxDocumentFormat;
import io.opaa.indexing.format.file.pdf.PdfDocumentFormat;
import io.opaa.indexing.format.file.tabular.TabularDocumentFormat;
import io.opaa.indexing.format.file.tabular.TabularProperties;
import io.opaa.indexing.format.stream.confluencestorage.ConfluenceStorageFormat;
import java.time.Clock;
import java.util.List;

/**
 * The set of {@link DocumentFormat} beans {@code IndexingConfiguration} wires, built without a
 * Spring context - for a unit test that needs the admission set the application actually applies
 * ({@link #supportedFormats()}) rather than a hand-picked one.
 *
 * <p>Constructed with {@code null}/zero collaborators: nothing here is ever run, only asked what it
 * admits and declares. {@code DocumentFormatRegistryRoutingIntegrationTest} asserts this list
 * against the wired context, so a format added there and forgotten here fails loudly.
 */
public final class ProductionDocumentFormats {

  private ProductionDocumentFormats() {}

  /** Every registered format, the fallback first, exactly as the application registers them. */
  public static List<DocumentFormat> formats() {
    return List.of(
        new TikaFallbackFormat(null, null),
        new TabularDocumentFormat(new TabularProperties(0, 0, 0, 0)),
        new HtmlDocumentFormat(),
        new ConfluenceStorageFormat(),
        new MarkdownDocumentFormat(),
        new DocxDocumentFormat(),
        new PptxDocumentFormat(),
        new OdtDocumentFormat(new OdfProperties(0, 0, 0, 0, 0)),
        new OdpDocumentFormat(new OdfProperties(0, 0, 0, 0, 0)),
        new PdfDocumentFormat(),
        new MailDocumentFormat(null, new MailProperties(0, 0, 0), Clock.systemUTC()));
  }

  /** The admission the application derives from {@link #formats()}. */
  public static SupportedDocumentFormats supportedFormats() {
    return new SupportedDocumentFormats(formats());
  }

  /** The registry the application derives from {@link #formats()}. */
  public static DocumentFormatRegistry registry() {
    List<DocumentFormat> formats = formats();
    return new DocumentFormatRegistry(formats, formats.getFirst());
  }
}
