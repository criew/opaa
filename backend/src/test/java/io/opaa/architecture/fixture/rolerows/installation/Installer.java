package io.opaa.architecture.fixture.rolerows.installation;

import io.opaa.architecture.fixture.rolerows.llm.rerank.RerankModelSettings;
import java.time.Instant;

/** Creates a row past the administration, which it must not. */
public class Installer {

  RerankModelSettings install() {
    return new RerankModelSettings(Instant.EPOCH);
  }
}
