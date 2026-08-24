package io.opaa.query;

import java.util.UUID;

/**
 * Domain counterpart of the generated {@code SearchedLibrary} (#860 Teil 4) - one library the
 * vector search actually ran against for a turn (#667), by id and name. A plain bean, not a record:
 * mirroring the generated DTO's {@code getId()}/{@code getName()} shape keeps every existing caller
 * of the old DTO-returning {@code QueryService#query} unchanged.
 */
public final class SearchedLibraryRef {

  private final UUID id;
  private final String name;

  public SearchedLibraryRef(UUID id, String name) {
    this.id = id;
    this.name = name;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }
}
