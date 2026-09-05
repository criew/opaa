package io.opaa.indexing;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.indexing.metadata.ModelMetadataExtractor;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.TikaFallbackPipeline;
import io.opaa.indexing.pipeline.confluence.ConfluenceDocumentPipeline;
import io.opaa.indexing.pipeline.html.HtmlDocumentPipeline;
import io.opaa.indexing.pipeline.mail.MailDocumentPipeline;
import io.opaa.indexing.pipeline.mail.MailProperties;
import io.opaa.indexing.pipeline.markdown.MarkdownDocumentPipeline;
import io.opaa.indexing.pipeline.office.DocxDocumentPipeline;
import io.opaa.indexing.pipeline.office.OdfProperties;
import io.opaa.indexing.pipeline.office.OdpDocumentPipeline;
import io.opaa.indexing.pipeline.office.OdtDocumentPipeline;
import io.opaa.indexing.pipeline.office.PptxDocumentPipeline;
import io.opaa.indexing.pipeline.pdf.PdfDocumentPipeline;
import io.opaa.indexing.pipeline.tabular.TabularDocumentPipeline;
import io.opaa.indexing.pipeline.tabular.TabularProperties;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.IndexingSourceExecutorRegistry;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.indexing.source.attachment.AttachmentLimits;
import io.opaa.indexing.source.attachment.AttachmentProperties;
import io.opaa.indexing.source.confluence.ConfluenceClientFactory;
import io.opaa.indexing.source.confluence.ConfluenceIndexingExecutor;
import io.opaa.indexing.source.confluence.ConfluenceProperties;
import io.opaa.indexing.source.confluence.ConfluenceSyncStateRepository;
import io.opaa.indexing.source.filesystem.AsyncIndexingExecutor;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.indexing.source.filesystem.FilesystemProperties;
import io.opaa.indexing.source.rss.RssFeedIndexingExecutor;
import io.opaa.indexing.source.rss.RssFeedParser;
import io.opaa.indexing.source.rss.RssFeedStateRepository;
import io.opaa.indexing.source.web.AutoindexCrawlerService;
import io.opaa.indexing.source.web.CrawlProperties;
import io.opaa.indexing.source.web.UrlIndexingExecutor;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.library.UploadProperties;
import io.opaa.observability.IndexingMetrics;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class IndexingConfiguration {

  @Bean
  DocumentService documentService() {
    return new DocumentService();
  }

  @Bean
  ChunkingService chunkingService(IndexingProperties properties) {
    return new ChunkingService(properties);
  }

  @Bean
  ChecksumService checksumService() {
    return new ChecksumService();
  }

  @Bean
  IndexingJobService indexingJobService(IndexingJobRepository indexingJobRepository) {
    return new IndexingJobService(indexingJobRepository);
  }

  @Bean
  IndexingMetrics indexingMetrics(MeterRegistry meterRegistry) {
    return new IndexingMetrics(meterRegistry);
  }

  /**
   * The fallback pipeline (docs/features/ingestion-pipelines.md, Teil 1) - declared as its concrete
   * type, not as {@link DocumentPipeline}, so {@link #documentPipelineRegistry} can ask for exactly
   * this one by type while still receiving every pipeline in its {@code List} parameter.
   */
  @Bean
  TikaFallbackPipeline tikaFallbackPipeline(
      DocumentService documentService, ChunkingService chunkingService) {
    return new TikaFallbackPipeline(documentService, chunkingService);
  }

  // Every pipeline below is an ordinary DocumentPipeline bean, picked up by
  // documentPipelineRegistry without that method changing shape - the open-closed criterion of
  // docs/features/ingestion-pipelines.md, Teil 1.

  /** XLSX/CSV/ODS pipeline (ingestion-pipelines.md, Teil 3, Punkt 3). */
  @Bean
  TabularDocumentPipeline tabularDocumentPipeline(TabularProperties tabularProperties) {
    return new TabularDocumentPipeline(tabularProperties);
  }

  /** HTML pipeline (ingestion-pipelines.md, Teil 3, Punkt 4). */
  @Bean
  HtmlDocumentPipeline htmlDocumentPipeline() {
    return new HtmlDocumentPipeline();
  }

  /**
   * Confluence page pipeline (ingestion-pipelines.md, Teil 3, Punkt 6) - claims no format, {@link
   * FileProcessingService#ingest} looks it up by id.
   */
  @Bean
  ConfluenceDocumentPipeline confluenceDocumentPipeline() {
    return new ConfluenceDocumentPipeline();
  }

  /**
   * Markdown pipeline (ingestion-pipelines.md, Teil 2). Its heading-aware cut changes the eval
   * measurement contract, because the eval corpus is entirely Markdown - see {@link
   * MarkdownDocumentPipeline}.
   */
  @Bean
  MarkdownDocumentPipeline markdownDocumentPipeline() {
    return new MarkdownDocumentPipeline();
  }

  /** DOCX pipeline (ingestion-pipelines.md, Teil 2). */
  @Bean
  DocxDocumentPipeline docxDocumentPipeline() {
    return new DocxDocumentPipeline();
  }

  /** PPTX pipeline (ingestion-pipelines.md, Teil 2). */
  @Bean
  PptxDocumentPipeline pptxDocumentPipeline() {
    return new PptxDocumentPipeline();
  }

  /** ODT pipeline (ingestion-pipelines.md, Teil 3, Punkt 2). */
  @Bean
  OdtDocumentPipeline odtDocumentPipeline(OdfProperties odfProperties) {
    return new OdtDocumentPipeline(odfProperties);
  }

  /** ODP pipeline (ingestion-pipelines.md, Teil 3, Punkt 2). */
  @Bean
  OdpDocumentPipeline odpDocumentPipeline(OdfProperties odfProperties) {
    return new OdpDocumentPipeline(odfProperties);
  }

  /**
   * PDF pipeline (ingestion-pipelines.md, Teil 1 and Teil 2). Answers the scan-detection guard from
   * its own PDFBox extraction rather than needing {@link DocumentService}.
   */
  @Bean
  PdfDocumentPipeline pdfDocumentPipeline() {
    return new PdfDocumentPipeline();
  }

  /**
   * EML/MSG pipeline (ingestion-pipelines.md, Teil 3, Punkt 5). It never recurses into a
   * sub-pipeline itself (ADR-0022, Entscheidung 10) and therefore needs no {@link
   * DocumentPipelineRegistry}. The {@code Clock} parameter resolves by type to this application's
   * single {@code @Primary} {@link Clock}, not to {@link #schedulingClock()} despite its name.
   */
  @Bean
  MailDocumentPipeline mailDocumentPipeline(
      ChunkingService chunkingService, MailProperties mailProperties, Clock schedulingClock) {
    return new MailDocumentPipeline(chunkingService, mailProperties, schedulingClock);
  }

  /**
   * Populated from every {@link DocumentPipeline} bean Spring finds - a new format becomes
   * reachable by adding one more pipeline bean, never by editing this method or {@link
   * FileProcessingService} (the open-closed criterion of docs/features/ingestion-pipelines.md, Teil
   * 1). Mirrors {@link #indexingSourceExecutorRegistry}'s own collection-injection pattern.
   */
  @Bean
  DocumentPipelineRegistry documentPipelineRegistry(
      List<DocumentPipeline> pipelines, TikaFallbackPipeline fallback) {
    return new DocumentPipelineRegistry(pipelines, fallback);
  }

  /**
   * The shared re-extraction of attachment bytes (ADR-0022) - attachments are never stored, so both
   * the selective re-index and "Im Dokument öffnen" re-derive them from their parent here.
   */
  @Bean
  AttachmentExtractor attachmentExtractor(DocumentPipelineRegistry documentPipelineRegistry) {
    return new AttachmentExtractor(documentPipelineRegistry);
  }

  /**
   * The one source-access instance both operator-triggered runs over the bestand share (pipeline
   * re-index, core-metadata backfill), so both read files under the same containment rules.
   */
  @Bean
  StoredDocumentSourceAccess storedDocumentSourceAccess(
      AttachmentExtractor attachmentExtractor,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      ChecksumService checksumService,
      FilesystemPathAllowlist filesystemPathAllowlist,
      UploadProperties uploadProperties) {
    return new StoredDocumentSourceAccess(
        attachmentExtractor,
        documentRepository,
        libraryRepository,
        checksumService,
        filesystemPathAllowlist,
        uploadProperties);
  }

  @Bean
  PipelineReindexService pipelineReindexService(
      JdbcTemplate jdbcTemplate,
      DocumentPipelineRegistry documentPipelineRegistry,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      FileProcessingService fileProcessingService,
      VectorChunkStore vectorChunkStore,
      StoredDocumentSourceAccess storedDocumentSourceAccess,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    return new PipelineReindexService(
        jdbcTemplate,
        documentPipelineRegistry,
        documentRepository,
        libraryRepository,
        fileProcessingService,
        vectorChunkStore,
        storedDocumentSourceAccess,
        schemaName,
        tableName);
  }

  /**
   * The generalized attachment path's shared indexer (ADR-0022, Entscheidung 8) - one instance
   * every caller shares, instead of each constructing its own.
   */
  @Bean
  AttachmentIndexer attachmentIndexer(
      BoundedDownloader boundedDownloader,
      FileProcessingService fileProcessingService,
      LibraryStorageQuotaService libraryStorageQuotaService,
      AttachmentProperties attachmentProperties) {
    return new AttachmentIndexer(
        boundedDownloader, fileProcessingService, libraryStorageQuotaService, attachmentProperties);
  }

  /**
   * The generalized attachment path's limits for a Mail attachment (ADR-0022, Entscheidung 6):
   * {@code maxAttachmentsPerMessage}/{@code maxAttachmentBytes} mirror {@code MailProperties}' own
   * parse-time ceilings. The nesting depth is {@link AttachmentIndexer}'s, one value for every
   * connector.
   */
  @Bean
  AttachmentLimits mailAttachmentLimits(MailProperties mailProperties) {
    return new AttachmentLimits(
        mailProperties.maxAttachmentsPerMessage(), mailProperties.maxAttachmentBytes());
  }

  @Bean
  FileProcessingService fileProcessingService(
      DocumentPipelineRegistry documentPipelineRegistry,
      DocumentRepository documentRepository,
      VectorChunkStore vectorChunkStore,
      ChecksumService checksumService,
      IndexingMetrics indexingMetrics,
      LibraryStorageQuotaService libraryStorageQuotaService,
      IndexingProperties indexingProperties,
      TaskExecutor embeddingTaskExecutor,
      ObjectProvider<AttachmentIndexer> attachmentIndexer,
      AttachmentLimits mailAttachmentLimits,
      DocumentMetadataService documentMetadataService,
      ModelMetadataExtractor modelMetadataExtractor) {
    return new FileProcessingService(
        documentPipelineRegistry,
        documentRepository,
        vectorChunkStore,
        checksumService,
        indexingMetrics,
        libraryStorageQuotaService,
        indexingProperties,
        embeddingTaskExecutor,
        attachmentIndexer,
        mailAttachmentLimits,
        documentMetadataService,
        modelMetadataExtractor);
  }

  @Bean
  FilesystemPathAllowlist filesystemPathAllowlist(FilesystemProperties properties) {
    return new FilesystemPathAllowlist(properties);
  }

  /**
   * Shared by every class fetching an {@code HTTP_DIRECTORY}/{@code RSS_FEED} target - a single
   * instance so the operator's configuration ({@code opaa.indexing.target-validation}) is applied
   * identically everywhere, mirroring {@link #filesystemPathAllowlist} above.
   */
  @Bean
  TargetAddressValidator targetAddressValidator(IndexingProperties properties) {
    return new TargetAddressValidator(
        properties.targetValidation().enabled(), properties.targetValidation().allowlist());
  }

  /**
   * What every request to a source OPAA does not operate carries and tolerates ({@code
   * opaa.indexing.http}) - one instance, so every connector identifies itself and waits out a
   * {@code 429} the same way.
   */
  @Bean
  SourceRequestPolicy sourceRequestPolicy(SourceHttpProperties sourceHttpProperties) {
    return sourceHttpProperties.toRequestPolicy();
  }

  /**
   * Builds per-library Confluence clients (ADR-0023); shares the target validation and the request
   * policy every other outbound source fetch uses.
   */
  @Bean
  ConfluenceClientFactory confluenceClientFactory(
      ConfluenceProperties confluenceProperties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy sourceRequestPolicy) {
    return new ConfluenceClientFactory(
        confluenceProperties, targetAddressValidator, sourceRequestPolicy);
  }

  @Bean
  StaleDocumentCleanupService staleDocumentCleanupService(
      DocumentRepository documentRepository, VectorChunkStore vectorChunkStore) {
    return new StaleDocumentCleanupService(documentRepository, vectorChunkStore);
  }

  /**
   * The run frame every {@link SourceIndexingExecutor} bean below runs inside: job bookkeeping,
   * protocol, result mapping, reconciliation and cost, once for all connectors.
   */
  @Bean
  IndexingRunTemplate indexingRunTemplate(
      IndexingJobService indexingJobService,
      IndexingRunEventRepository indexingRunEventRepository,
      StaleDocumentCleanupService staleDocumentCleanupService,
      DocumentRepository documentRepository,
      LibraryStorageQuotaService libraryStorageQuotaService) {
    return new IndexingRunTemplate(
        indexingJobService,
        indexingRunEventRepository,
        staleDocumentCleanupService,
        documentRepository,
        libraryStorageQuotaService);
  }

  // Declared as SourceIndexingExecutor, not the concrete executor type: all three beans below
  // carry @Async and are therefore wrapped in a JDK dynamic proxy at runtime, which only
  // implements the interfaces the target class declares. Every consumer
  // (IndexingSourceExecutorRegistry) depends on SourceIndexingExecutor already.
  @Bean
  SourceIndexingExecutor asyncIndexingExecutor(
      DocumentService documentService,
      FileProcessingService fileProcessingService,
      FilesystemPathAllowlist filesystemPathAllowlist,
      LibraryFolderService libraryFolderService,
      IndexingRunTemplate indexingRunTemplate) {
    return new AsyncIndexingExecutor(
        documentService,
        fileProcessingService,
        filesystemPathAllowlist,
        libraryFolderService,
        indexingRunTemplate);
  }

  @Bean
  AutoindexCrawlerService autoindexCrawlerService(
      TargetAddressValidator targetAddressValidator,
      CrawlProperties crawlProperties,
      SourceRequestPolicy sourceRequestPolicy) {
    return new AutoindexCrawlerService(
        targetAddressValidator, crawlProperties, sourceRequestPolicy);
  }

  @Bean
  BoundedDownloader boundedDownloader(
      TargetAddressValidator targetAddressValidator, SourceRequestPolicy sourceRequestPolicy) {
    return new BoundedDownloader(targetAddressValidator, sourceRequestPolicy);
  }

  @Bean
  SourceIndexingExecutor urlIndexingExecutor(
      AutoindexCrawlerService autoindexCrawlerService,
      BoundedDownloader boundedDownloader,
      FileProcessingService fileProcessingService,
      DocumentRepository documentRepository,
      CrawlProperties crawlProperties,
      LibraryFolderService libraryFolderService,
      IndexingRunTemplate indexingRunTemplate) {
    return new UrlIndexingExecutor(
        autoindexCrawlerService,
        boundedDownloader,
        fileProcessingService,
        documentRepository,
        crawlProperties,
        libraryFolderService,
        indexingRunTemplate);
  }

  @Bean
  RssFeedParser rssFeedParser() {
    return new RssFeedParser();
  }

  @Bean
  SourceIndexingExecutor rssFeedIndexingExecutor(
      RssFeedParser rssFeedParser,
      FileProcessingService fileProcessingService,
      DocumentRepository documentRepository,
      RssFeedStateRepository rssFeedStateRepository,
      AttachmentIndexer attachmentIndexer,
      IndexingProperties properties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy sourceRequestPolicy,
      IndexingRunTemplate indexingRunTemplate) {
    return new RssFeedIndexingExecutor(
        rssFeedParser,
        fileProcessingService,
        documentRepository,
        rssFeedStateRepository,
        attachmentIndexer,
        properties,
        targetAddressValidator,
        sourceRequestPolicy,
        indexingRunTemplate);
  }

  /**
   * Declared as the concrete type, not as {@link SourceIndexingExecutor} like its siblings: {@code
   * ConfluenceWebhookService} injects the executor directly for its targeted webhook run, and
   * Spring resolves an injection point by the bean method's declared type - the registry still
   * collects it through the interface it implements.
   */
  @Bean
  ConfluenceIndexingExecutor confluenceIndexingExecutor(
      ConfluenceClientFactory confluenceClientFactory,
      ConfluenceProperties confluenceProperties,
      FileProcessingService fileProcessingService,
      AttachmentIndexer attachmentIndexer,
      DocumentRepository documentRepository,
      ConfluenceSyncStateRepository confluenceSyncStateRepository,
      VectorChunkStore vectorChunkStore,
      IndexingRunTemplate indexingRunTemplate) {
    return new ConfluenceIndexingExecutor(
        confluenceClientFactory,
        confluenceProperties,
        fileProcessingService,
        attachmentIndexer,
        documentRepository,
        confluenceSyncStateRepository,
        vectorChunkStore,
        Clock.systemUTC(),
        indexingRunTemplate);
  }

  /**
   * Populated from every {@link SourceIndexingExecutor} bean Spring finds: a new source type
   * becomes reachable by adding one more bean here, never by editing this method or {@link
   * DocumentIndexingService}.
   */
  @Bean
  IndexingSourceExecutorRegistry indexingSourceExecutorRegistry(
      List<SourceIndexingExecutor> executors) {
    return new IndexingSourceExecutorRegistry(executors);
  }

  @Bean
  LowChunkDocumentAuditService lowChunkDocumentAuditService(
      DocumentRepository documentRepository, KnowledgeLibraryRepository libraryRepository) {
    return new LowChunkDocumentAuditService(documentRepository, libraryRepository);
  }

  @Bean
  DocumentIndexingService documentIndexingService(
      IndexingJobService indexingJobService,
      IndexingSourceExecutorRegistry indexingSourceExecutorRegistry,
      KnowledgeLibraryRepository libraryRepository,
      LibraryAccessService libraryAccessService,
      IndexingRunEventRepository indexingRunEventRepository) {
    return new DocumentIndexingService(
        indexingJobService,
        indexingSourceExecutorRegistry,
        libraryRepository,
        libraryAccessService,
        indexingRunEventRepository);
  }

  /**
   * Backs every {@link SourceIndexingExecutor}. Rejects a full queue with {@code AbortPolicy},
   * never {@code DiscardPolicy}: a silently discarded task would leave its {@code indexing_jobs}
   * row stuck at {@code RUNNING} forever, locking that library out of every future trigger. {@code
   * AbortPolicy} throws synchronously, so the trigger fails the job immediately.
   */
  @Bean
  TaskExecutor indexingTaskExecutor(IndexingProperties properties) {
    IndexingProperties.ThreadPool pool = properties.threadPool();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(pool.coreSize());
    executor.setMaxPoolSize(pool.maxSize());
    executor.setQueueCapacity(pool.queueCapacity());
    executor.setThreadNamePrefix("indexing-");
    executor.initialize();
    return executor;
  }

  /**
   * Backs {@link FileProcessingService}'s concurrent embedding calls, one fixed-size pool shared
   * across every indexing run in the process. It bounds the sub-batch fan-out of a splitting
   * document only - one that fits in a single sub-batch embeds on its caller's thread - so the
   * process-wide number of concurrent embedding calls is this pool plus the indexing and upload
   * pools. The queue is unbounded: only a document's own sub-batches are ever queued here.
   */
  @Bean
  TaskExecutor embeddingTaskExecutor(IndexingProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.embeddingConcurrency());
    executor.setMaxPoolSize(properties.embeddingConcurrency());
    executor.setQueueCapacity(Integer.MAX_VALUE);
    executor.setThreadNamePrefix("embedding-");
    executor.initialize();
    return executor;
  }

  /**
   * Backs the model step's one call per document (#1073) - deliberately not the common {@code
   * ForkJoinPool}: that pool is shared with everything else in the JVM, and a saturated one would
   * let the 30-second limit expire on a call that never started, counted as a model failure nobody
   * caused. Sized for every thread that can ingest at once (indexing plus upload pool), and a task
   * rejected all the same runs on its calling thread, so a call is always made; the timeout bounds
   * the ingest's waiting time, not the call.
   */
  @Bean
  TaskExecutor modelExtractionTaskExecutor(
      IndexingProperties indexingProperties, UploadProperties uploadProperties) {
    int concurrency =
        indexingProperties.threadPool().maxSize() + uploadProperties.threadPool().maxSize();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(concurrency);
    executor.setMaxPoolSize(concurrency);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("model-extraction-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }

  /**
   * Backs {@code FileProcessingService#processUploadedFileAsync} - deliberately its own pool with
   * its own {@link UploadProperties#threadPool} rather than a share of {@link
   * #indexingTaskExecutor}. Both use {@code AbortPolicy}, so a full queue throws synchronously back
   * to the caller, which turns it into an immediate {@code FAILED} document or job row.
   */
  @Bean
  TaskExecutor uploadTaskExecutor(UploadProperties properties) {
    UploadProperties.ThreadPool pool = properties.threadPool();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(pool.coreSize());
    executor.setMaxPoolSize(pool.maxSize());
    executor.setQueueCapacity(pool.queueCapacity());
    executor.setThreadNamePrefix("upload-");
    executor.initialize();
    return executor;
  }

  /**
   * Server local time - the same choice {@code io.opaa.audit.AuditRetentionScheduler}'s own
   * {@code @Scheduled(cron = ...)} already makes implicitly. A named {@link Clock} bean, rather
   * than {@code Clock.systemDefaultZone()} called directly in {@link LibraryIndexingScheduler}, so
   * a test can substitute a fixed clock.
   */
  @Bean
  Clock schedulingClock() {
    return Clock.systemDefaultZone();
  }

  @Bean
  LibraryIndexingScheduler libraryIndexingScheduler(
      KnowledgeLibraryRepository libraryRepository,
      DocumentIndexingService documentIndexingService,
      IndexingJobService indexingJobService,
      IndexingRunEventRepository indexingRunEventRepository,
      Clock schedulingClock) {
    return new LibraryIndexingScheduler(
        libraryRepository,
        documentIndexingService,
        indexingJobService,
        indexingRunEventRepository,
        schedulingClock);
  }
}
