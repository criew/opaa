package io.opaa.architecture.fixture.rolerows.llm.administration;

import io.opaa.architecture.fixture.rolerows.llm.rerank.RerankModelSettings;
import java.time.Instant;
import java.util.function.Function;

/** Creates and changes the row, also by reference, as it may. */
public class RoleAdministration {

  private final Function<Instant, RerankModelSettings> rows = RerankModelSettings::new;

  void store(String baseUrl) {
    RerankModelSettings row = rows.apply(Instant.EPOCH);
    row.replaceEndpoint(baseUrl);
    row.replaceOwn("de");
  }
}
