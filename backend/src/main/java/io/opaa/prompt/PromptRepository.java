package io.opaa.prompt;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The prompts of the prompt libraries. */
public interface PromptRepository extends JpaRepository<Prompt, UUID> {

  List<Prompt> findByLibraryIdOrderBySortOrderAscNameAsc(UUID libraryId);

  Optional<Prompt> findByIdAndLibraryId(UUID id, UUID libraryId);

  boolean existsByLibraryIdAndName(UUID libraryId, String name);

  long countByLibraryId(UUID libraryId);

  /** How many prompts each of the libraries holds, in one query; an empty library is absent. */
  @Query(
      "select p.libraryId as libraryId, count(p) as promptCount from Prompt p"
          + " where p.libraryId in :libraryIds group by p.libraryId")
  List<LibraryPromptCount> countByLibraryIdIn(@Param("libraryIds") Collection<UUID> libraryIds);

  interface LibraryPromptCount {
    UUID getLibraryId();

    long getPromptCount();
  }
}
