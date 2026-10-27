package io.opaa.llm;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the single {@link PreviousEmbeddingModel} row. */
@Repository
interface PreviousEmbeddingModelRepository extends JpaRepository<PreviousEmbeddingModel, Short> {

  default Optional<PreviousEmbeddingModel> findStored() {
    return findById(EmbeddingModelSettings.SINGLETON_ID);
  }
}
