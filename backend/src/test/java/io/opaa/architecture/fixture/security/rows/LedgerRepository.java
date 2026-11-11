package io.opaa.architecture.fixture.security.rows;

import java.util.UUID;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

@NoRepositoryBean
public interface LedgerRepository extends Repository<Ledger, UUID> {

  Ledger save(Ledger ledger);
}
