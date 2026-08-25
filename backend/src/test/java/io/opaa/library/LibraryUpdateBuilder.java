package io.opaa.library;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import java.net.URI;

/**
 * Test-only fluent builder for {@link LibraryUpdate} - production code (in particular {@code
 * LibraryResponseMapper}) only ever needs the canonical all-args constructor, so this convenience
 * lives under {@code src/test} rather than on the record itself.
 */
public final class LibraryUpdateBuilder {

  private final String name;
  private String description;
  private LibraryVisibility visibility;
  private Boolean listed;
  private DocumentSourceType sourceType;
  private String sourcePath;
  private URI sourceUrl;
  private String sourceProxy;
  private String sourceCredentials;
  private Boolean sourceInsecureSsl;
  private LibraryScheduleUpdate schedule;

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

  public LibraryUpdateBuilder visibility(LibraryVisibility visibility) {
    this.visibility = visibility;
    return this;
  }

  public LibraryUpdateBuilder listed(Boolean listed) {
    this.listed = listed;
    return this;
  }

  public LibraryUpdateBuilder sourceType(DocumentSourceType sourceType) {
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

  public LibraryUpdateBuilder sourceProxy(String sourceProxy) {
    this.sourceProxy = sourceProxy;
    return this;
  }

  public LibraryUpdateBuilder sourceCredentials(String sourceCredentials) {
    this.sourceCredentials = sourceCredentials;
    return this;
  }

  public LibraryUpdateBuilder sourceInsecureSsl(Boolean sourceInsecureSsl) {
    this.sourceInsecureSsl = sourceInsecureSsl;
    return this;
  }

  public LibraryUpdateBuilder schedule(LibraryScheduleUpdate schedule) {
    this.schedule = schedule;
    return this;
  }

  public LibraryUpdate build() {
    return new LibraryUpdate(
        name,
        description,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl,
        schedule);
  }
}
