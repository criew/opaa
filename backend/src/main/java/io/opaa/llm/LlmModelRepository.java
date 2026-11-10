package io.opaa.llm;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for {@link LlmModel}. */
@Repository
public interface LlmModelRepository extends JpaRepository<LlmModel, UUID> {

  List<LlmModel> findAllByOrderByDisplayNameAsc();

  /**
   * Every model currently active - expected to hold at most one row ({@code
   * ux_llm_models_single_active}), but returning a list rather than a single {@code Optional} lets
   * {@link LlmModelService#activateModel} deactivate every match rather than silently assume the
   * database invariant it is itself protected by.
   */
  List<LlmModel> findAllByActiveTrue();

  /**
   * The row of {@code id}, locked until the caller's transaction ends - so that an edit of the
   * address and a recorded tool capability never interleave on the same model.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT m FROM LlmModel m WHERE m.id = :id")
  Optional<LlmModel> findLockedById(@Param("id") UUID id);
}
