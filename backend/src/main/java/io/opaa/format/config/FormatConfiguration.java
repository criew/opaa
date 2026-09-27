package io.opaa.format.config;

import io.opaa.format.DocumentFormat;
import io.opaa.format.DocumentFormatRegistry;
import io.opaa.format.DocumentService;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.format.chunk.ChunkSizing;
import io.opaa.format.chunk.ChunkingService;
import io.opaa.format.file.fallback.TikaFallbackFormat;
import io.opaa.format.file.html.HtmlDocumentFormat;
import io.opaa.format.file.mail.MailDocumentFormat;
import io.opaa.format.file.mail.MailProperties;
import io.opaa.format.file.markdown.MarkdownDocumentFormat;
import io.opaa.format.file.office.DocxDocumentFormat;
import io.opaa.format.file.office.OdfProperties;
import io.opaa.format.file.office.OdpDocumentFormat;
import io.opaa.format.file.office.OdtDocumentFormat;
import io.opaa.format.file.office.PptxDocumentFormat;
import io.opaa.format.file.pdf.PdfDocumentFormat;
import io.opaa.format.file.tabular.TabularDocumentFormat;
import io.opaa.format.file.tabular.TabularProperties;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the file formats, their registry and admission, the Tika reader and the generic cut. Bean
 * names are part of the contract: other configurations and tests ask for them by name. What the
 * module needs from above arrives as a bean of an interface it declares ({@link ChunkSizing}); a
 * data format a single source delivers is registered by that source's configuration.
 */
@Configuration
@EnableConfigurationProperties({MailProperties.class, OdfProperties.class, TabularProperties.class})
public class FormatConfiguration {

  @Bean
  DocumentService documentService() {
    return new DocumentService();
  }

  @Bean
  ChunkingService chunkingService(ChunkSizing properties) {
    return new ChunkingService(properties);
  }

  /**
   * The fallback pipeline (docs/features/ingestion-pipelines.md, Teil 1) - declared as its concrete
   * type, not as {@link DocumentFormat}, so {@link #documentPipelineRegistry} can ask for exactly
   * this one by type while still receiving every pipeline in its {@code List} parameter.
   */
  @Bean
  TikaFallbackFormat tikaFallbackPipeline(
      DocumentService documentService, ChunkingService chunkingService) {
    return new TikaFallbackFormat(documentService, chunkingService);
  }

  // Every pipeline below is an ordinary DocumentFormat bean, picked up by
  // documentPipelineRegistry without that method changing shape - the open-closed criterion of
  // docs/features/ingestion-pipelines.md, Teil 1.

  /** XLSX/CSV/ODS pipeline (ingestion-pipelines.md, Teil 3, Punkt 3). */
  @Bean
  TabularDocumentFormat tabularDocumentPipeline(TabularProperties tabularProperties) {
    return new TabularDocumentFormat(tabularProperties);
  }

  /** HTML pipeline (ingestion-pipelines.md, Teil 3, Punkt 4). */
  @Bean
  HtmlDocumentFormat htmlDocumentPipeline() {
    return new HtmlDocumentFormat();
  }

  /**
   * Markdown pipeline (ingestion-pipelines.md, Teil 2). Its heading-aware cut changes the eval
   * measurement contract, because the eval corpus is entirely Markdown - see {@link
   * MarkdownDocumentFormat}.
   */
  @Bean
  MarkdownDocumentFormat markdownDocumentPipeline() {
    return new MarkdownDocumentFormat();
  }

  /** DOCX pipeline (ingestion-pipelines.md, Teil 2). */
  @Bean
  DocxDocumentFormat docxDocumentPipeline() {
    return new DocxDocumentFormat();
  }

  /** PPTX pipeline (ingestion-pipelines.md, Teil 2). */
  @Bean
  PptxDocumentFormat pptxDocumentPipeline() {
    return new PptxDocumentFormat();
  }

  /** ODT pipeline (ingestion-pipelines.md, Teil 3, Punkt 2). */
  @Bean
  OdtDocumentFormat odtDocumentPipeline(OdfProperties odfProperties) {
    return new OdtDocumentFormat(odfProperties);
  }

  /** ODP pipeline (ingestion-pipelines.md, Teil 3, Punkt 2). */
  @Bean
  OdpDocumentFormat odpDocumentPipeline(OdfProperties odfProperties) {
    return new OdpDocumentFormat(odfProperties);
  }

  /**
   * PDF pipeline (ingestion-pipelines.md, Teil 1 and Teil 2). Answers the scan-detection guard from
   * its own PDFBox extraction rather than needing {@link DocumentService}.
   */
  @Bean
  PdfDocumentFormat pdfDocumentPipeline() {
    return new PdfDocumentFormat();
  }

  /**
   * EML/MSG pipeline (ingestion-pipelines.md, Teil 3, Punkt 5). It never recurses into a
   * sub-pipeline itself (ADR-0022, Entscheidung 10) and therefore needs no {@link
   * DocumentFormatRegistry}. The {@code Clock} parameter resolves by type to this application's
   * single {@code @Primary} {@link Clock}, not to {@code IndexingConfiguration#schedulingClock()}
   * despite its name.
   */
  @Bean
  MailDocumentFormat mailDocumentPipeline(
      ChunkingService chunkingService, MailProperties mailProperties, Clock schedulingClock) {
    return new MailDocumentFormat(chunkingService, mailProperties, schedulingClock);
  }

  /**
   * Populated from every {@link DocumentFormat} bean Spring finds - a new format becomes reachable
   * by adding one more pipeline bean, never by editing this method or {@code DocumentIngestService}
   * (the open-closed criterion of docs/features/ingestion-pipelines.md, Teil 1).
   */
  @Bean
  DocumentFormatRegistry documentPipelineRegistry(
      List<DocumentFormat> pipelines, TikaFallbackFormat fallback) {
    return new DocumentFormatRegistry(pipelines, fallback);
  }

  /**
   * What this deployment accepts for indexing - the union of every registered format's {@link
   * DocumentFormat#admittedFormats()}, derived by the registry itself so admission and routing can
   * never disagree. A new format changes this set by being a bean, not by being listed anywhere.
   */
  @Bean
  SupportedDocumentFormats supportedDocumentFormats(
      DocumentFormatRegistry documentPipelineRegistry) {
    return documentPipelineRegistry.supportedFormats();
  }
}
