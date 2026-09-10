package io.opaa.query;

import java.util.UUID;

/** One library the retrieval actually searched for a turn, by id and name. */
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
