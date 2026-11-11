package io.opaa.llm;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/** The single row of an optional model role. */
@NoRepositoryBean
public interface OptionalRoleSettingsRepository<R extends OptionalRoleSettings>
    extends JpaRepository<R, Short> {

  default Optional<R> findStored() {
    return findById(OptionalRoleSettings.SINGLETON_ID);
  }
}
