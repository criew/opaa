package io.opaa.architecture.fixture.privateenumeration.searchadmin;

import io.opaa.architecture.fixture.privateenumeration.knowledge.KnowledgeLibraryRepository;
import java.util.List;

/** A view of the administration: one list names every library, the other the shared ones. */
public class IndexStatus {
  KnowledgeLibraryRepository libraries;

  public List<Object> everyLibrary(String organizationId) {
    return libraries.findByOrganizationId(organizationId);
  }

  public List<Object> sharedLibraries(String organizationId) {
    return libraries.findSharedByOrganizationId(organizationId);
  }
}
