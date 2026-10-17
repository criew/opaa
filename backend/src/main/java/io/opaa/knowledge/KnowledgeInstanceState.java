package io.opaa.knowledge;

import io.opaa.compliance.InstanceDisclosure.ConnectorUsage;
import io.opaa.compliance.InstanceStateSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The connector types shared libraries read from and the store of the originals, for the
 * disclosure. A private library is counted nowhere, so the disclosure cannot tell one from none
 * (ADR-0049, Entscheidung 1); the flows it may open are reported by the connection profiles that
 * admit persons.
 */
@Component
class KnowledgeInstanceState implements InstanceStateSource {

  /** The types whose documents a person can open at the source ({@code source-original}). */
  private static final List<String> ORIGINAL_AT_THE_SOURCE = List.of("HTTP_DIRECTORY", "RSS_FEED");

  private final KnowledgeLibraryRepository libraries;
  private final UploadedOriginalStore originalStore;

  KnowledgeInstanceState(
      KnowledgeLibraryRepository libraries, UploadedOriginalStore originalStore) {
    this.libraries = libraries;
    this.originalStore = originalStore;
  }

  @Override
  public List<ConnectorUsage> connectorUsage() {
    List<ConnectorUsage> usage = new ArrayList<>();
    sharedLibraryCounts()
        .forEach(
            (type, count) -> {
              if (!SourceType.UPLOAD.key().equals(type)) {
                usage.add(new ConnectorUsage(type, count, 0));
              }
            });
    return usage;
  }

  @Override
  public Map<String, Boolean> dataFlows() {
    Map<String, Long> counts = sharedLibraryCounts();
    Map<String, Boolean> flows = new HashMap<>();
    ConnectorDataFlows.BY_TYPE.forEach(
        (type, flow) -> flows.put(flow, counts.getOrDefault(type, 0L) > 0));
    flows.put("source-original", ORIGINAL_AT_THE_SOURCE.stream().anyMatch(counts::containsKey));
    flows.put("source-attachment", counts.containsKey("RSS_FEED"));
    flows.put("original-store", originalStore instanceof S3UploadedOriginalStore);
    return flows;
  }

  private Map<String, Long> sharedLibraryCounts() {
    Map<String, Long> counts = new HashMap<>();
    for (Object[] row : libraries.countSharedBySourceType()) {
      counts.put(((SourceType) row[0]).key(), ((Number) row[1]).longValue());
    }
    return counts;
  }
}
