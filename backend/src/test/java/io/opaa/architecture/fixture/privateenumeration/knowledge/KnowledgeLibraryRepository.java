package io.opaa.architecture.fixture.privateenumeration.knowledge;

import java.util.List;

/** The finders: the unfiltered ones with the private libraries, the shared one without. */
public interface KnowledgeLibraryRepository {
  List<Object> findByOrganizationId(String organizationId);

  List<Object> findByScheduleEnabledTrue();

  List<Object> findSharedByOrganizationId(String organizationId);
}
