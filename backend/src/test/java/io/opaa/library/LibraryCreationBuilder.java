package io.opaa.library;

import io.opaa.indexing.DocumentSourceType;
import java.net.URI;
import java.util.UUID;

/**
 * Test-only fluent builder for {@link LibraryCreation} - production code (in particular {@code
 * LibraryResponseMapper}) only ever needs the canonical all-args constructor, so this convenience
 * lives under {@code src/test} rather than on the record itself.
 */
public final class LibraryCreationBuilder {

  private final String name;
  private String description;
  private LibraryOwnerType ownerType;
  private UUID ownerId;
  private LibraryVisibility visibility;
  private Boolean listed;
  private final DocumentSourceType sourceType;
  private String sourcePath;
  private URI sourceUrl;
  private String sourceProxy;
  private String sourceCredentials;
  private Boolean sourceInsecureSsl;

  private LibraryCreationBuilder(String name, DocumentSourceType sourceType) {
    this.name = name;
    this.sourceType = sourceType;
  }

  public static LibraryCreationBuilder libraryCreation(String name, DocumentSourceType sourceType) {
    return new LibraryCreationBuilder(name, sourceType);
  }

  public LibraryCreationBuilder description(String description) {
    this.description = description;
    return this;
  }

  public LibraryCreationBuilder ownerType(LibraryOwnerType ownerType) {
    this.ownerType = ownerType;
    return this;
  }

  public LibraryCreationBuilder ownerId(UUID ownerId) {
    this.ownerId = ownerId;
    return this;
  }

  public LibraryCreationBuilder visibility(LibraryVisibility visibility) {
    this.visibility = visibility;
    return this;
  }

  public LibraryCreationBuilder listed(Boolean listed) {
    this.listed = listed;
    return this;
  }

  public LibraryCreationBuilder sourcePath(String sourcePath) {
    this.sourcePath = sourcePath;
    return this;
  }

  public LibraryCreationBuilder sourceUrl(URI sourceUrl) {
    this.sourceUrl = sourceUrl;
    return this;
  }

  public LibraryCreationBuilder sourceProxy(String sourceProxy) {
    this.sourceProxy = sourceProxy;
    return this;
  }

  public LibraryCreationBuilder sourceCredentials(String sourceCredentials) {
    this.sourceCredentials = sourceCredentials;
    return this;
  }

  public LibraryCreationBuilder sourceInsecureSsl(Boolean sourceInsecureSsl) {
    this.sourceInsecureSsl = sourceInsecureSsl;
    return this;
  }

  public LibraryCreation build() {
    return new LibraryCreation(
        name,
        description,
        ownerType,
        ownerId,
        visibility,
        listed,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }
}
