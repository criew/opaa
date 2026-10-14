package io.opaa.library;

import io.opaa.indexing.source.confluence.ConfluenceEdition;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.knowledge.SourceType;
import java.net.URI;
import java.util.UUID;

/**
 * Test-only fluent builder for {@link SourceConnectionTest} - mirrors {@code
 * LibraryCreationBuilder}'s reasoning: production code only ever needs the canonical all-args
 * constructor ({@code SourceConnectionTestResponseMapper}).
 */
public final class SourceConnectionTestBuilder {

  private SourceType sourceType;
  private String sourcePath;
  private URI sourceUrl;
  private String sourceCredentials;
  private UUID libraryId;
  private ConfluenceEdition confluenceEdition;
  private S3SourceSettings s3Settings;

  private SourceConnectionTestBuilder() {}

  public static SourceConnectionTestBuilder sourceConnectionTest() {
    return new SourceConnectionTestBuilder();
  }

  public SourceConnectionTestBuilder sourceType(SourceType sourceType) {
    this.sourceType = sourceType;
    return this;
  }

  public SourceConnectionTestBuilder sourcePath(String sourcePath) {
    this.sourcePath = sourcePath;
    return this;
  }

  public SourceConnectionTestBuilder sourceUrl(URI sourceUrl) {
    this.sourceUrl = sourceUrl;
    return this;
  }

  public SourceConnectionTestBuilder sourceCredentials(String sourceCredentials) {
    this.sourceCredentials = sourceCredentials;
    return this;
  }

  public SourceConnectionTestBuilder libraryId(UUID libraryId) {
    this.libraryId = libraryId;
    return this;
  }

  public SourceConnectionTestBuilder confluenceEdition(ConfluenceEdition confluenceEdition) {
    this.confluenceEdition = confluenceEdition;
    return this;
  }

  public SourceConnectionTestBuilder s3Settings(S3SourceSettings s3Settings) {
    this.s3Settings = s3Settings;
    return this;
  }

  public SourceConnectionTest build() {
    return new SourceConnectionTest(
        sourceType,
        sourcePath,
        sourceUrl,
        sourceCredentials,
        libraryId,
        TestConnectorSettings.of(confluenceEdition, s3Settings));
  }
}
