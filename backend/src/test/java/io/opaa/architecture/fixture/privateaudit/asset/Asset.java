package io.opaa.architecture.fixture.privateaudit.asset;

import java.util.Map;

/** The asset shell with its name, its neutral protocol name and its neutral payload. */
public class Asset {

  public String getName() {
    return "Personalakte";
  }

  public String auditName() {
    return "Private Bibliothek";
  }

  public Map<String, Object> auditPayload(Map<String, Object> payload) {
    return payload;
  }
}
