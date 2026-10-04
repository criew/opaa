package io.opaa.indexing.source.profileprobe;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.PersonalSecretForm;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.DefaultKey;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.ServerAddressRule;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceTargetRefusedException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * A connector that exists only in test code and admits connection profiles: it reports its profile
 * declaration in its descriptor and needs no line outside this package. Its settings {@code
 * edition} and {@code topic} are optional texts; a profile may set {@code edition}, at an {@code
 * https}, {@code http} or {@code smb} address. It remembers what it last validated and probed and
 * every change check and source change, so a test can read what the core handed it. The topic
 * {@value #CLOUD_ONLY_TOPIC} exists only in edition {@code CLOUD}: under {@code DC} it refuses it.
 */
@Component
public class ProfileProbeSourceConnector implements SourceConnector {

  public static final SourceType TYPE = SourceType.of("PROFILE_PROBE");

  /** What a profile of this type leaves open, as a feed leaves its detail pages. */
  public static final String GAP = "Die Pflicht legt nur die Adresse der Testquelle fest.";

  /** A proxy host through which the connector cannot reach its target. */
  public static final String UNREACHABLE_PROXY = "unerreichbar.example.org";

  /** A topic the connector refuses in edition {@code DC}. */
  public static final String CLOUD_ONLY_TOPIC = "nur-cloud";

  private static final Set<String> KEYS = Set.of("edition", "topic");

  private final List<ChangeCheck> changeChecks = new CopyOnWriteArrayList<>();
  private final List<SourceChange> sourceChanges = new CopyOnWriteArrayList<>();

  private final AtomicReference<SourceSettings> lastValidated = new AtomicReference<>();
  private final AtomicReference<SourceSettings> lastTested = new AtomicReference<>();

  /** What the last {@link #validate} received, then forgotten; empty when none ran since. */
  public Optional<SourceSettings> lastValidated() {
    return Optional.ofNullable(lastValidated.getAndSet(null));
  }

  /** What the last {@link #testConnection} received, then forgotten; empty when none ran since. */
  public Optional<SourceSettings> lastTested() {
    return Optional.ofNullable(lastTested.getAndSet(null));
  }

  /** Every {@link #validateChange} since the last call, then forgotten. */
  public List<ChangeCheck> changeChecks() {
    List<ChangeCheck> seen = List.copyOf(changeChecks);
    changeChecks.clear();
    return seen;
  }

  /** Every {@link #onSourceChanged} since the last call, then forgotten. */
  public List<SourceChange> sourceChanges() {
    List<SourceChange> seen = List.copyOf(sourceChanges);
    sourceChanges.clear();
    return seen;
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return SourceConnectorDescriptor.remoteRun(TYPE, "Testquelle mit Zugang")
        .withProfiles(
            ProfileDeclaration.of(
                    ConnectionProfileSupport.OPTIONAL,
                    SignIn.of(
                        ConnectionAuthMethod.NONE,
                        ConnectionOwnership.LIBRARY,
                        ConnectionOwnership.PERSON),
                    SignIn.personalSecret(
                        PersonalSecretForm.USERNAME_AND_PASSWORD, ConnectionOwnership.LIBRARY))
                .withAddress(ServerAddressRule.schemes("https", "http", "smb"))
                .withDefaults(DefaultKey.choice("edition", "Edition", "CLOUD", "DC"))
                .withRequirementGap(GAP));
  }

  @Override
  public Set<String> settingsKeys() {
    return KEYS;
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    requested.requireOnly(KEYS);
    for (String key : KEYS) {
      if (requested.has(key) && !(requested.get(key) instanceof String)) {
        throw new ValidationException("sourceSettings." + key + " muss ein Text sein");
      }
    }
    return requested.isEmpty() ? null : requested;
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    lastValidated.set(requested);
    if (requested.sourceUrl() == null) {
      throw new ValidationException("sourceUrl ist erforderlich");
    }
    requireTopicFits(requested.connectorSettings());
    if (requested.sourceProxy() != null && requested.sourceProxy().startsWith(UNREACHABLE_PROXY)) {
      throw new SourceTargetRefusedException(
          "Die Testquelle ist über diesen Proxy nicht erreichbar");
    }
    return requested;
  }

  @Override
  public SourceSettings validateChange(
      SourceSettings stored, SourceSettings requested, boolean replacesConnection) {
    changeChecks.add(new ChangeCheck(stored, requested, replacesConnection));
    requireTopicFits(
        requested.connectorSettings() != null
            ? requested.connectorSettings()
            : stored.connectorSettings());
    return replacesConnection ? validate(requested) : requested;
  }

  private static void requireTopicFits(ConnectorData settings) {
    if (settings != null
        && CLOUD_ONLY_TOPIC.equals(settings.get("topic"))
        && "DC".equals(settings.get("edition"))) {
      throw new ValidationException(
          "sourceSettings.topic „" + CLOUD_ONLY_TOPIC + "“ gibt es nur in der Edition CLOUD");
    }
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
    Map<String, Object> state = new HashMap<>();
    for (String key : KEYS) {
      state.put(key, stored == null ? null : stored.get(key));
    }
    return state;
  }

  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    sourceChanges.add(
        new SourceChange(library.getId(), addressChanged, Set.copyOf(changedSettings)));
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    lastTested.set(settings);
    return new SourceConnectionTestResult(true, "Testquelle mit Zugang erreichbar.", 0L);
  }

  /** One {@link #validateChange} as the core asked it. */
  public record ChangeCheck(
      SourceSettings stored, SourceSettings requested, boolean replacesConnection) {}

  /** One {@link #onSourceChanged} as the core reported it. */
  public record SourceChange(UUID libraryId, boolean addressChanged, Set<String> changedSettings) {}
}
