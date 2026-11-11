package io.opaa.architecture.fixture.security.rows;

import java.util.UUID;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

@NoRepositoryBean
public interface AccountRepository extends Repository<Account, UUID> {

  Account save(Account account);
}
