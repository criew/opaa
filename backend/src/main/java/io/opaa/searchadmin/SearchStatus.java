package io.opaa.searchadmin;

import java.util.List;

/**
 * The whole read-only status display: model roles, search paths, per-library index state of the
 * shared libraries and the private ones as one line.
 */
public record SearchStatus(
    List<ModelRoleStatus> modelRoles,
    List<SearchPathStatus> searchPaths,
    List<LibrarySearchStatus> libraries,
    PrivateLibrarySummary privateLibraries) {

  public SearchStatus {
    modelRoles = List.copyOf(modelRoles);
    searchPaths = List.copyOf(searchPaths);
    libraries = List.copyOf(libraries);
  }
}
