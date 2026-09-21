package io.opaa.succession;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SuccessionReviewRepository extends JpaRepository<SuccessionReview, UUID> {

  /**
   * The Sichtungsvermerke of the given cases, newest first - read per page of the list to decide
   * the highlight. Deliberately no reader by person: who reviewed is no evaluation axis
   * (Personalrat Z7).
   */
  List<SuccessionReview> findByCaseIdInOrderByReviewedAtDesc(Collection<UUID> caseIds);
}
