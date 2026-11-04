package io.opaa.llm;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for {@link PersonalTranscriptionVocabulary}, always scoped to the organization. */
@Repository
public interface PersonalTranscriptionVocabularyRepository
    extends JpaRepository<PersonalTranscriptionVocabulary, UUID> {

  Optional<PersonalTranscriptionVocabulary> findByUserIdAndOrganizationId(
      UUID userId, UUID organizationId);
}
