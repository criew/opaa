package io.opaa.library;

import io.opaa.indexing.source.confluence.ConfluenceEdition;
import io.opaa.indexing.source.confluence.ConfluenceSpaceSelection;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.knowledge.SourceType;
import java.net.URI;
import java.util.List;

/**
 * Test-only fluent builder for {@link LibraryUpdate} - production code (in particular {@code
 * LibraryResponseMapper}) only ever needs the canonical all-args constructor, so this convenience
 * lives under {@code src/test} rather than on the record itself.
 */
public final class LibraryUpdateBuilder {

  private final String name;
  private String description;
  private SourceType sourceType;
  private String sourcePath;
  private URI sourceUrl;
  private String sourceCredentials;
  private LibraryScheduleUpdate schedule;
  private ConfluenceEdition confluenceEdition;
  private List<ConfluenceSpaceSelection> confluenceSpaces;
  private Integer confluenceFullSyncIntervalDays;
  private S3SourceSettings s3Settings;
  private ConnectorData sourceSettings;

  private LibraryUpdateBuilder(String name) {
    this.name = name;
  }

  public static LibraryUpdateBuilder libraryUpdate(String name) {
    return new LibraryUpdateBuilder(name);
  }

  public LibraryUpdateBuilder description(String description) {
    this.description = description;
    return this;
  }

  public LibraryUpdateBuilder sourceType(SourceType sourceType) {
    this.sourceType = sourceType;
    return this;
  }

  public LibraryUpdateBuilder sourcePath(String sourcePath) {
    this.sourcePath = sourcePath;
    return this;
  }

  public LibraryUpdateBuilder sourceUrl(URI sourceUrl) {
    this.sourceUrl = sourceUrl;
    return this;
  }

  public LibraryUpdateBuilder sourceCredentials(String sourceCredentials) {
    this.sourceCredentials = sourceCredentials;
    return this;
  }

  public LibraryUpdateBuilder schedule(LibraryScheduleUpdate schedule) {
    this.schedule = schedule;
    return this;
  }

  public LibraryUpdateBuilder confluenceEdition(ConfluenceEdition confluenceEdition) {
    this.confluenceEdition = confluenceEdition;
    return this;
  }

  public LibraryUpdateBuilder confluenceSpaces(List<ConfluenceSpaceSelection> confluenceSpaces) {
    this.confluenceSpaces = confluenceSpaces;
    return this;
  }

  public LibraryUpdateBuilder confluenceFullSyncIntervalDays(Integer days) {
    this.confluenceFullSyncIntervalDays = days;
    return this;
  }

  public LibraryUpdateBuilder s3Settings(S3SourceSettings s3Settings) {
    this.s3Settings = s3Settings;
    return this;
  }

  /** Connector settings sent as given, in place of the per-connector fields above. */
  public LibraryUpdateBuilder sourceSettings(ConnectorData sourceSettings) {
    this.sourceSettings = sourceSettings;
    return this;
  }

  public LibraryUpdate build() {
    return new LibraryUpdate(
        name,
        description,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceCredentials,
        schedule,
        sourceSettings != null
            ? sourceSettings
            : TestSourceSettings.of(
                confluenceEdition, confluenceSpaces, confluenceFullSyncIntervalDays, s3Settings));
  }
}
