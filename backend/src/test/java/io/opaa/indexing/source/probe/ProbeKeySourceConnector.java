package io.opaa.indexing.source.probe;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.ServiceAccountKeyAuth;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A test-only connector that signs in with a service account key (ADR-0040, Entscheidung 2): fixed
 * address, the imitated account as its one setting {@code subject}. It remembers the secret it was
 * last handed, so a test can assert that the core never hands it the key.
 */
@Component
public class ProbeKeySourceConnector implements SourceConnector, SourceBrowser {

  public static final SourceType TYPE = SourceType.of("PROBE_KEY");
  public static final String ADDRESS = "https://probe-key.example.org";
  public static final String SCOPE = "https://probe-key.example.org/auth/read";

  private static final String SUBJECT = "subject";

  private final URI tokenEndpoint;
  private volatile String lastSecret;

  public ProbeKeySourceConnector() {
    this(URI.create("https://oauth.probe-key.example.org/token"));
  }

  public ProbeKeySourceConnector(URI tokenEndpoint) {
    this.tokenEndpoint = tokenEndpoint;
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return new SourceConnectorDescriptor(
        TYPE,
        "Testquelle mit Dienstkonto",
        false,
        true,
        false,
        false,
        null,
        null,
        new ServiceAccountKeyAuth(tokenEndpoint, SCOPE));
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    requested.requireOnly(Set.of(SUBJECT));
    return requested;
  }

  @Override
  public String normalizeSourceUrl(String requested) {
    return requested == null ? ADDRESS : requested;
  }

  @Override
  public String assertionSubject(ConnectorData settings) {
    return settings != null && settings.get(SUBJECT) instanceof String subject ? subject : null;
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    lastSecret = requested.sourceCredentials();
    String url = normalizeSourceUrl(requested.sourceUrl());
    if (!ADDRESS.equals(url)) {
      throw new ValidationException("sourceUrl ist für diese Testquelle fest " + ADDRESS);
    }
    return requested.withSourceUrl(url);
  }

  @Override
  public SourceSettings validateChange(
      SourceSettings stored, SourceSettings requested, boolean replacesConnection) {
    lastSecret = requested.sourceCredentials();
    return replacesConnection ? validate(requested) : requested;
  }

  @Override
  public void configureNew(KnowledgeLibrary library, SourceSettings validated) {
    if (validated.connectorSettings() != null) {
      library.updateSourceSettings(validated.connectorSettings().toJson());
    }
  }

  @Override
  public void applyChange(
      KnowledgeLibrary library, ConnectorData stored, SourceSettings validated) {
    if (validated.connectorSettings() != null) {
      library.updateSourceSettings(validated.connectorSettings().toJson());
    }
  }

  @Override
  public Map<String, Object> settingsState(KnowledgeLibrary library, ConnectorData stored) {
    String subject = assertionSubject(stored);
    return subject == null ? Map.of() : Map.of(SUBJECT, subject);
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    lastSecret = settings.sourceCredentials();
    return new SourceConnectionTestResult(true, "Testquelle mit Dienstkonto erreichbar.", 0L);
  }

  @Override
  public String otherTypeMessage() {
    return "Die Bibliothek ist keine Testquelle mit Dienstkonto";
  }

  @Override
  public SourceListing browse(Query query) {
    lastSecret = query.settings().sourceCredentials();
    return new SourceListing(true, List.of(), null);
  }

  /** The secret this connector was handed last, by any method. */
  public String lastSecret() {
    return lastSecret;
  }
}
