package io.opaa.knowledge;

import io.opaa.compliance.InstanceDisclosure.ConnectorUsage;
import io.opaa.compliance.InstanceStateSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The connector types libraries read from and the store of the originals, for the disclosure. A
 * connector's data flow is on while at least one library reads from it.
 */
@Component
class KnowledgeInstanceState implements InstanceStateSource {

  /**
   * The data flow of each connector type that fetches from outside the installation; a type missing
   * here ({@code FILESYSTEM}) reads only from the server itself.
   */
  static final Map<String, String> CONNECTOR_FLOWS =
      Map.of(
          "HTTP_DIRECTORY", "source-web",
          "RSS_FEED", "source-rss",
          "CONFLUENCE", "source-confluence",
          "DROPBOX", "source-dropbox",
          "GOOGLE_DRIVE", "source-google-drive",
          "NEXTCLOUD", "source-nextcloud",
          "SHAREPOINT", "source-sharepoint",
          "S3", "source-s3",
          "SMB", "source-smb");

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
    libraryCounts()
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
    Map<String, Long> counts = libraryCounts();
    Map<String, Boolean> flows = new HashMap<>();
    CONNECTOR_FLOWS.forEach((type, flow) -> flows.put(flow, counts.getOrDefault(type, 0L) > 0));
    flows.put("source-original", ORIGINAL_AT_THE_SOURCE.stream().anyMatch(counts::containsKey));
    flows.put("source-attachment", counts.containsKey("RSS_FEED"));
    flows.put("original-store", originalStore instanceof S3UploadedOriginalStore);
    return flows;
  }

  private Map<String, Long> libraryCounts() {
    Map<String, Long> counts = new HashMap<>();
    for (Object[] row : libraries.countBySourceType()) {
      counts.put((String) row[0], ((Number) row[1]).longValue());
    }
    return counts;
  }
}
