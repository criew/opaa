package io.opaa.llm;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for {@link LlmModel} (#756). */
@Repository
public interface LlmModelRepository extends JpaRepository<LlmModel, UUID> {

  List<LlmModel> findAllByOrderByDisplayNameAsc();

  /**
   * Every model currently active - expected to hold at most one row ({@code
   * ux_llm_models_single_active}, migration 058), but returning a list rather than a single {@code
   * Optional} lets {@link LlmModelService#activateModel} deactivate every match rather than
   * silently assume the database invariant it is itself protected by.
   */
  List<LlmModel> findAllByActiveTrue();
}
