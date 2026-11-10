package io.opaa.architecture.fixture.rolerows.llm.rerank;

/** Changes the row past the administration, which it must not. */
public class RerankRole {

  void reset(RerankModelSettings row) {
    row.replaceEndpoint("");
  }
}
