package io.opaa.searchadmin;

import java.util.List;

/** The whole read-only status display: model roles, search paths, per-library index state. */
public record SearchStatus(
    List<ModelRoleStatus> modelRoles,
    List<SearchPathStatus> searchPaths,
    List<LibrarySearchStatus> libraries) {

  public SearchStatus {
    modelRoles = List.copyOf(modelRoles);
    searchPaths = List.copyOf(searchPaths);
    libraries = List.copyOf(libraries);
  }
}
