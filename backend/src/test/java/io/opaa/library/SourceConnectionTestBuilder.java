package io.opaa.library;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import java.net.URI;
import java.util.UUID;

/**
 * Test-only fluent builder for {@link SourceConnectionTest} - mirrors {@code
 * LibraryCreationBuilder}'s reasoning (#860): production code only ever needs the canonical
 * all-args constructor ({@code SourceConnectionTestResponseMapper}).
 */
public final class SourceConnectionTestBuilder {

  private DocumentSourceType sourceType;
  private String sourcePath;
  private URI sourceUrl;
  private String sourceProxy;
  private String sourceCredentials;
  private Boolean sourceInsecureSsl;
  private UUID libraryId;
  private ConfluenceEdition confluenceEdition;

  private SourceConnectionTestBuilder() {}

  public static SourceConnectionTestBuilder sourceConnectionTest() {
    return new SourceConnectionTestBuilder();
  }

  public SourceConnectionTestBuilder sourceType(DocumentSourceType sourceType) {
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

  public SourceConnectionTestBuilder sourceProxy(String sourceProxy) {
    this.sourceProxy = sourceProxy;
    return this;
  }

  public SourceConnectionTestBuilder sourceCredentials(String sourceCredentials) {
    this.sourceCredentials = sourceCredentials;
    return this;
  }

  public SourceConnectionTestBuilder sourceInsecureSsl(Boolean sourceInsecureSsl) {
    this.sourceInsecureSsl = sourceInsecureSsl;
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

  public SourceConnectionTest build() {
    return new SourceConnectionTest(
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        libraryId,
        confluenceEdition);
  }
}
