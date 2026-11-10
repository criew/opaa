package io.opaa.architecture.fixture.security.rows;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

@NoRepositoryBean
public interface DynamicGroupRepository extends Repository<DynamicGroup, UUID> {

  <S extends DynamicGroup> List<S> saveAll(Iterable<S> groups);
}
