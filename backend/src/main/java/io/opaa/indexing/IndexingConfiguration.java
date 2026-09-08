package io.opaa.indexing;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.AttachmentExtractor;
import io.opaa.indexing.document.ChecksumService;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.document.DocumentService;
import io.opaa.indexing.document.StoredDocumentSourceAccess;
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
import io.opaa.indexing.job.DocumentIndexingService;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.job.LibraryIndexingScheduler;
import io.opaa.indexing.maintenance.LowChunkDocumentAuditService;
import io.opaa.indexing.maintenance.PipelineReindexService;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.indexing.metadata.ModelMetadataExtractor;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.IndexingSourceExecutorRegistry;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.indexing.source.attachment.AttachmentLimits;
import io.opaa.indexing.source.attachment.AttachmentProperties;
import io.opaa.indexing.source.confluence.ConfluenceClientFactory;
import io.opaa.indexing.source.confluence.ConfluenceIndexingExecutor;
import io.opaa.indexing.source.confluence.ConfluenceProperties;
import io.opaa.indexing.source.filesystem.AsyncIndexingExecutor;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.indexing.source.filesystem.FilesystemProperties;
import io.opaa.indexing.source.rss.RssFeedIndexingExecutor;
import io.opaa.indexing.source.rss.RssFeedParser;
import io.opaa.indexing.source.rss.RssFeedStateRepository;
import io.opaa.indexing.source.s3.S3ClientFactory;
import io.opaa.indexing.source.s3.S3IndexingExecutor;
import io.opaa.indexing.source.s3.S3Properties;
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
   * Confluence page pipeline (ingestion-pipelines.md, Teil 3, Punkt 6) - claims no format, {@link
   * DocumentIngestService#ingest} looks it up by id.
   */
  @Bean
  ConfluenceStorageFormat confluenceDocumentPipeline() {
    return new ConfluenceStorageFormat();
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
   * single {@code @Primary} {@link Clock}, not to {@link #schedulingClock()} despite its name.
   */
  @Bean
  MailDocumentFormat mailDocumentPipeline(
      ChunkingService chunkingService, MailProperties mailProperties, Clock schedulingClock) {
    return new MailDocumentFormat(chunkingService, mailProperties, schedulingClock);
  }

  /**
   * Populated from every {@link DocumentFormat} bean Spring finds - a new format becomes reachable
   * by adding one more pipeline bean, never by editing this method or {@link DocumentIngestService}
   * (the open-closed criterion of docs/features/ingestion-pipelines.md, Teil 1). Mirrors {@link
   * #indexingSourceExecutorRegistry}'s own collection-injection pattern.
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

  /**
   * The shared re-extraction of attachment bytes (ADR-0022) - attachments are never stored, so both
   * the selective re-index and "Im Dokument öffnen" re-derive them from their parent here.
   */
  @Bean
  AttachmentExtractor attachmentExtractor(DocumentFormatRegistry documentPipelineRegistry) {
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
      DocumentFormatRegistry documentPipelineRegistry,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      DocumentIngestService documentIngestService,
      VectorChunkStore vectorChunkStore,
      StoredDocumentSourceAccess storedDocumentSourceAccess,
      @Value("${spring.ai.vectorstore.pgvector.schema-name:public}") String schemaName,
      @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
    return new PipelineReindexService(
        jdbcTemplate,
        documentPipelineRegistry,
        documentRepository,
        libraryRepository,
        documentIngestService,
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
      DocumentIngestService documentIngestService,
      LibraryStorageQuotaService libraryStorageQuotaService,
      AttachmentProperties attachmentProperties,
      SupportedDocumentFormats supportedDocumentFormats) {
    return new AttachmentIndexer(
        boundedDownloader,
        documentIngestService,
        libraryStorageQuotaService,
        attachmentProperties,
        supportedDocumentFormats);
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
  DocumentIngestService documentIngestService(
      DocumentFormatRegistry documentPipelineRegistry,
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
    return new DocumentIngestService(
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

  /**
   * Builds per-library S3 stores (ADR-0027); shares the target validation every other outbound
   * source fetch uses - the SDK's own HTTP client bypasses SourceHttpClientFactory, so the
   * validation is applied on the client and on every request instead.
   */
  @Bean
  S3ClientFactory s3ClientFactory(
      S3Properties s3Properties, TargetAddressValidator targetAddressValidator) {
    return new S3ClientFactory(s3Properties, targetAddressValidator);
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
      DocumentIngestService documentIngestService,
      FilesystemPathAllowlist filesystemPathAllowlist,
      LibraryFolderService libraryFolderService,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats) {
    return new AsyncIndexingExecutor(
        documentService,
        documentIngestService,
        filesystemPathAllowlist,
        libraryFolderService,
        indexingRunTemplate,
        supportedDocumentFormats);
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
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      SourceRequestPolicy sourceRequestPolicy,
      CrawlProperties crawlProperties,
      LibraryFolderService libraryFolderService,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats) {
    return new UrlIndexingExecutor(
        autoindexCrawlerService,
        boundedDownloader,
        documentIngestService,
        documentRepository,
        crawlProperties,
        libraryFolderService,
        sourceRequestPolicy,
        indexingRunTemplate,
        supportedDocumentFormats);
  }

  @Bean
  RssFeedParser rssFeedParser() {
    return new RssFeedParser();
  }

  @Bean
  SourceIndexingExecutor rssFeedIndexingExecutor(
      RssFeedParser rssFeedParser,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      RssFeedStateRepository rssFeedStateRepository,
      AttachmentIndexer attachmentIndexer,
      IndexingProperties properties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy sourceRequestPolicy,
      IndexingRunTemplate indexingRunTemplate) {
    return new RssFeedIndexingExecutor(
        rssFeedParser,
        documentIngestService,
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
      DocumentIngestService documentIngestService,
      AttachmentIndexer attachmentIndexer,
      DocumentRepository documentRepository,
      SourceSyncStateRepository sourceSyncStateRepository,
      StaleDocumentCleanupService staleDocumentCleanupService,
      IndexingRunTemplate indexingRunTemplate) {
    return new ConfluenceIndexingExecutor(
        confluenceClientFactory,
        confluenceProperties,
        documentIngestService,
        attachmentIndexer,
        documentRepository,
        sourceSyncStateRepository,
        staleDocumentCleanupService,
        Clock.systemUTC(),
        indexingRunTemplate);
  }

  /**
   * Declared as the concrete type, like the Confluence executor: the event adapter ({@code
   * S3EventService}) calls {@code refreshObjects}, which the interface does not carry.
   */
  @Bean
  S3IndexingExecutor s3IndexingExecutor(
      S3ClientFactory s3ClientFactory,
      S3Properties s3Properties,
      DocumentIngestService documentIngestService,
      DocumentRepository documentRepository,
      LibraryFolderService libraryFolderService,
      StaleDocumentCleanupService staleDocumentCleanupService,
      SourceSyncStateRepository sourceSyncStateRepository,
      IndexingRunTemplate indexingRunTemplate,
      SupportedDocumentFormats supportedDocumentFormats) {
    return new S3IndexingExecutor(
        s3ClientFactory,
        s3Properties,
        documentIngestService,
        documentRepository,
        libraryFolderService,
        staleDocumentCleanupService,
        sourceSyncStateRepository,
        Clock.systemUTC(),
        indexingRunTemplate,
        supportedDocumentFormats);
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
   * Backs {@link DocumentIngestService}'s concurrent embedding calls, one fixed-size pool shared
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
   * caused. Sized for every thread that can ingest at once (indexing plus upload pool), so a
   * rejection means every ingest thread is already inside a model call. {@code AbortPolicy}, never
   * caller-runs: an inline call would return only after the model answered, which is exactly the
   * unbounded wait the limit exists to prevent - a rejected call is counted and skipped instead.
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
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.initialize();
    return executor;
  }

  /**
   * Backs {@code DocumentIngestService#processUploadedFileAsync} - deliberately its own pool with
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
