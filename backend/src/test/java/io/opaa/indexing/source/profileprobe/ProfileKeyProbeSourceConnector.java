package io.opaa.indexing.source.profileprobe;

import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.DefaultKey;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.ServerAddressRule;
import io.opaa.indexing.source.ServiceAccountKeyAuth;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import io.opaa.test.FakeAuthorizationServer;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A test-only connector signing in with a service account key that may lie with a profile, like
 * Google Drive: fixed address, the imitated account {@code subject} only the profile sets, the
 * token endpoint the shared {@link FakeAuthorizationServer}. It remembers which settings changed
 * per library, so a test can read that a changed account discarded the run state.
 */
@Component
public class ProfileKeyProbeSourceConnector implements SourceConnector {

  public static final SourceType TYPE = SourceType.of("PROFILE_KEY_PROBE");
  public static final String ADDRESS = "https://profile-key.example.org";
  public static final String SCOPE = "https://profile-key.example.org/auth/read";

  private static final String SUBJECT = "subject";

  private final Map<UUID, Set<String>> changed = new ConcurrentHashMap<>();

  @Override
  public SourceConnectorDescriptor descriptor() {
    return SourceConnectorDescriptor.remoteRun(TYPE, "Testquelle mit Schlüssel am Zugang")
        .withProfiles(
            ProfileDeclaration.of(
                    ConnectionProfileSupport.OPTIONAL,
                    SignIn.serviceAccountKey(
                        new ServiceAccountKeyAuth(
                            FakeAuthorizationServer.shared().tokenEndpoint(), SCOPE)))
                .withAddress(ServerAddressRule.fixed(ADDRESS))
                .withDefaults(DefaultKey.text(SUBJECT, "Imitiertes Konto").onlyOnProfile()));
  }

  @Override
  public Set<String> settingsKeys() {
    return Set.of(SUBJECT);
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    requested.requireOnly(Set.of(SUBJECT));
    return requested.isEmpty() ? null : requested;
  }

  @Override
  public String normalizeSourceUrl(String requested) {
    return requested == null || requested.isBlank() ? ADDRESS : requested;
  }

  @Override
  public String assertionSubject(ConnectorData settings) {
    return settings != null && settings.get(SUBJECT) instanceof String subject ? subject : null;
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    String url = normalizeSourceUrl(requested.sourceUrl());
    if (!ADDRESS.equals(url)) {
      throw new ValidationException("sourceUrl ist für diese Testquelle fest " + ADDRESS);
    }
    return requested.withSourceUrl(url);
  }

  @Override
  public void configureNew(KnowledgeLibrary library, SourceSettings validated) {
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
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    changed.put(library.getId(), Set.copyOf(changedSettings));
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    return settings.sourceCredentials() == null
        ? new SourceConnectionTestResult(false, "Kein Zugriffstoken.", null, false, null)
        : new SourceConnectionTestResult(true, "Testquelle mit Schlüssel erreichbar.", 0L);
  }

  /** The settings the last change of {@code libraryId} changed, empty before any. */
  public Set<String> changedSettingsOf(UUID libraryId) {
    return changed.getOrDefault(libraryId, Set.of());
  }
}
