package io.opaa.architecture.fixture.security.rows;

import java.util.UUID;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

@NoRepositoryBean
public interface VersionedTokenRepository extends Repository<VersionedToken, UUID> {

  VersionedToken save(VersionedToken token);
}
