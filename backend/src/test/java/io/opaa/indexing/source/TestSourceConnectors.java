package io.opaa.indexing.source;

import static org.mockito.Mockito.mock;

import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.document.DocumentService;
import io.opaa.indexing.source.confluence.ConfluenceConnectionService;
import io.opaa.indexing.source.confluence.ConfluenceProperties;
import io.opaa.indexing.source.confluence.ConfluenceSourceConnector;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService;
import io.opaa.indexing.source.filesystem.FilesystemSourceConnector;
import io.opaa.indexing.source.rss.RssFeedParser;
import io.opaa.indexing.source.rss.RssFeedSourceConnector;
import io.opaa.indexing.source.rss.RssFeedStateRepository;
import io.opaa.indexing.source.s3.S3ClientFactory;
import io.opaa.indexing.source.s3.S3ConnectionService;
import io.opaa.indexing.source.s3.S3OriginalAccess;
import io.opaa.indexing.source.s3.S3Properties;
import io.opaa.indexing.source.s3.S3SourceConnector;
import io.opaa.indexing.source.s3.events.S3EventService;
import io.opaa.indexing.source.upload.UploadSourceConnector;
import io.opaa.indexing.source.web.AutoindexCrawlerService;
import io.opaa.indexing.source.web.HttpDirectorySourceConnector;
import io.opaa.knowledge.UploadedOriginalStore;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.test.ProductionDocumentFormats;
import java.util.List;

/**
 * The production connectors, wired for a unit test without a Spring context: every collaborator has
 * a neutral stand-in - disabled target validation, mocked repositories and remote services - that a
 * test replaces where it is the subject.
 */
public final class TestSourceConnectors {

  private FilesystemPathAllowlist filesystemAllowlist = mock(FilesystemPathAllowlist.class);
  private IndexingProperties indexingProperties =
      new IndexingProperties(1000, 0, 50, null, null, null, null, 0);
  private RssFeedStateRepository rssFeedStateRepository = mock(RssFeedStateRepository.class);
  private SourceSyncStateRepository sourceSyncStateRepository =
      mock(SourceSyncStateRepository.class);
  private ConfluenceConnectionService confluenceConnectionService =
      mock(ConfluenceConnectionService.class);
  private ConfluenceProperties confluenceProperties =
      new ConfluenceProperties(0, null, null, 0, null, 0, 0, 0, null, null, 0);
  private S3ConnectionService s3ConnectionService = mock(S3ConnectionService.class);
  private UploadedOriginalStore uploadedOriginalStore = mock(UploadedOriginalStore.class);
  private TargetAddressValidator targetAddressValidator = TargetAddressValidator.disabled();
  private RemoteContentProperties remoteContentProperties = new RemoteContentProperties(0, 0);
  private S3OriginalAccess s3OriginalAccess = mock(S3OriginalAccess.class);
  private ConfluenceWebhookService confluenceWebhookService = mock(ConfluenceWebhookService.class);
  private S3EventService s3EventService = mock(S3EventService.class);

  private TestSourceConnectors() {}

  public static TestSourceConnectors connectors() {
    return new TestSourceConnectors();
  }

  public TestSourceConnectors filesystemAllowlist(FilesystemPathAllowlist allowlist) {
    this.filesystemAllowlist = allowlist;
    return this;
  }

  public TestSourceConnectors indexingProperties(IndexingProperties properties) {
    this.indexingProperties = properties;
    return this;
  }

  public TestSourceConnectors rssFeedStateRepository(RssFeedStateRepository repository) {
    this.rssFeedStateRepository = repository;
    return this;
  }

  public TestSourceConnectors sourceSyncStateRepository(SourceSyncStateRepository repository) {
    this.sourceSyncStateRepository = repository;
    return this;
  }

  public TestSourceConnectors confluenceConnectionService(ConfluenceConnectionService service) {
    this.confluenceConnectionService = service;
    return this;
  }

  public TestSourceConnectors confluenceProperties(ConfluenceProperties properties) {
    this.confluenceProperties = properties;
    return this;
  }

  public TestSourceConnectors s3ConnectionService(S3ConnectionService service) {
    this.s3ConnectionService = service;
    return this;
  }

  public TestSourceConnectors uploadedOriginalStore(UploadedOriginalStore store) {
    this.uploadedOriginalStore = store;
    return this;
  }

  /** The validator the original download applies; the connection tests stay unvalidated. */
  public TestSourceConnectors originalTargetValidator(TargetAddressValidator validator) {
    this.targetAddressValidator = validator;
    return this;
  }

  public TestSourceConnectors remoteContentProperties(RemoteContentProperties properties) {
    this.remoteContentProperties = properties;
    return this;
  }

  public TestSourceConnectors s3OriginalAccess(S3OriginalAccess access) {
    this.s3OriginalAccess = access;
    return this;
  }

  public TestSourceConnectors confluenceWebhookService(ConfluenceWebhookService service) {
    this.confluenceWebhookService = service;
    return this;
  }

  public TestSourceConnectors s3EventService(S3EventService service) {
    this.s3EventService = service;
    return this;
  }

  public SourceConnectorRegistry registry() {
    TargetAddressValidator validator = TargetAddressValidator.disabled();
    SourceRequestPolicy policy = SourceRequestPolicy.defaults();
    RemoteOriginalAccess remoteOriginals =
        new RemoteOriginalAccess(
            new BoundedDownloader(targetAddressValidator),
            targetAddressValidator,
            remoteContentProperties);
    return new SourceConnectorRegistry(
        List.of(
            new UploadSourceConnector(uploadedOriginalStore),
            new FilesystemSourceConnector(
                filesystemAllowlist,
                new DocumentService(),
                ProductionDocumentFormats.supportedFormats()),
            new HttpDirectorySourceConnector(
                new AutoindexCrawlerService(validator),
                validator,
                policy,
                ProductionDocumentFormats.supportedFormats(),
                indexingProperties,
                remoteOriginals),
            new RssFeedSourceConnector(
                new RssFeedParser(),
                rssFeedStateRepository,
                validator,
                policy,
                indexingProperties,
                remoteOriginals),
            new ConfluenceSourceConnector(
                confluenceConnectionService,
                confluenceProperties,
                sourceSyncStateRepository,
                confluenceWebhookService),
            new S3SourceConnector(
                s3ConnectionService,
                new S3ClientFactory(S3Properties.defaults(), validator),
                sourceSyncStateRepository,
                s3OriginalAccess,
                s3EventService)));
  }
}
