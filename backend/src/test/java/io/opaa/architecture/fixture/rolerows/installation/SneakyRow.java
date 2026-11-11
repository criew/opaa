package io.opaa.architecture.fixture.rolerows.installation;

import io.opaa.architecture.fixture.rolerows.llm.rerank.RerankModelSettings;
import java.time.Instant;

/**
 * A row subclass outside llm, which earns no exception: its constructor and writer are reported.
 */
public class SneakyRow extends RerankModelSettings {

  public SneakyRow(Instant now) {
    super(now);
  }

  void overwrite() {
    replaceEndpoint("");
  }
}
