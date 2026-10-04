package io.opaa.architecture.fixture.privateaudit.library;

import io.opaa.architecture.fixture.privateaudit.asset.Asset;
import io.opaa.architecture.fixture.privateaudit.audit.AuditEvent;
import java.util.Map;

/**
 * Two entries name the asset by its name or write its payload raw; the neutral one and a reader of
 * the name without a protocol entry pass.
 */
public class LibraryAudit {

  public void renamedByName(Asset library) {
    AuditEvent.builder().object("LIBRARY", "id", library.getName());
  }

  public void correctedRaw(Asset library, Map<String, Object> payload) {
    AuditEvent.builder().object("LIBRARY", "id", library.auditName()).after(payload);
  }

  public void renamedNeutrally(Asset library, Map<String, Object> payload) {
    AuditEvent.builder()
        .object("LIBRARY", "id", library.auditName())
        .before(library.auditPayload(payload));
  }

  public String title(Asset library) {
    return library.getName();
  }
}
