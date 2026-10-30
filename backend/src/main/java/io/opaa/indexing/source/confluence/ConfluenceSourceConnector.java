package io.opaa.indexing.source.confluence;

import static io.opaa.indexing.spi.ConnectorChecks.blankToNull;
import static io.opaa.indexing.spi.ConnectorChecks.unreachable;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.PersonalSecretForm;
import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.DefaultKey;
import io.opaa.indexing.spi.ProfileDeclaration;
import io.opaa.indexing.spi.PushIntake;
import io.opaa.indexing.spi.PushIntakeHandler;
import io.opaa.indexing.spi.PushNotification;
import io.opaa.indexing.spi.SignIn;
import io.opaa.indexing.spi.SourceBrowser;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceConnector;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import io.opaa.indexing.spi.SourceListing;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Confluence instance (ADR-0023). The edition is required, confirmed against the instance at
 * creation and permanent afterwards; {@code sourceUrl} is stored in the edition's normalised form;
 * the credentials must parse for the edition. The space selection is configuration, replaced as a
 * whole, and a library may lengthen the instance-wide full-sync rhythm to 1-365 days - all three in
 * {@link ConfluenceSourceSettings}. A changed address or selection discards the sync state, so the
 * next run is a full one (Entscheidung 4).
 */
public class ConfluenceSourceConnector
    implements SourceConnector, SourceBrowser, PushIntakeHandler {

  /** The type key this connector serves. */
  public static final SourceType TYPE = SourceType.of("CONFLUENCE");

  /**
   * The factory default of the full-sync rhythm (ADR-0023, Entscheidung 4): only the full run
   * reaches deletions Confluence never reports. The administration may set another for the
   * installation, a library its own ({@code FullSyncRhythm}).
   */
  public static final Duration FULL_SYNC_INTERVAL = Duration.ofDays(7);

  private static final Logger log = LoggerFactory.getLogger(ConfluenceSourceConnector.class);

  /** Upper bound of a space selection. */
  static final int MAX_SPACES = 500;

  private static final String SPACES_STATE = "confluenceSpaces";

  private static final String SPACES_REQUIRED =
      "sourceSettings.spaces: mindestens ein Space ist erforderlich, wenn sourceType CONFLUENCE ist"
          + " und allSpaces nicht gesetzt ist";

  private static final String ALL_OR_SELECTION =
      "sourceSettings.allSpaces: entweder alle Spaces oder eine Auswahl, nicht beides";

  /** What {@link #settingsState} names for the scope "Alle Spaces"; no space key looks like it. */
  private static final List<String> ALL_SPACES_STATE = List.of("*");

  private final ConfluenceConnectionService connectionService;
  private final SourceSyncStateRepository syncStateRepository;
  private final SourceConnectorDescriptor descriptor;
  private final ConfluencePushReceiver webhookService;

  public ConfluenceSourceConnector(
      ConfluenceConnectionService connectionService,
      SourceSyncStateRepository syncStateRepository,
      ConfluencePushReceiver webhookService) {
    this.connectionService = connectionService;
    this.syncStateRepository = syncStateRepository;
    this.webhookService = webhookService;
    this.descriptor =
        SourceConnectorDescriptor.remoteRun(TYPE, "Confluence")
            .withDataFlow("source-confluence")
            .withPushIntake(new PushIntake("confluenceWebhookSecret"))
            .withFullSyncInterval(FULL_SYNC_INTERVAL)
            .withProfiles(
                ProfileDeclaration.of(
                        ConnectionProfileSupport.OPTIONAL,
                        SignIn.personalSecret(
                            PersonalSecretForm.TOKEN, ConnectionOwnership.LIBRARY))
                    .withDefaults(
                        DefaultKey.choice(
                            ConfluenceSourceSettings.EDITION,
                            "Edition",
                            ConfluenceEdition.CLOUD.name(),
                            ConfluenceEdition.DATA_CENTER.name())));
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public PushNotification acceptNotification(
      KnowledgeLibrary library, ConnectorData settings, byte[] body, UnaryOperator<String> header) {
    return webhookService.accept(
        library.getId(),
        library,
        body,
        header.apply(ConfluenceWebhookSignature.HUB_SIGNATURE_HEADER),
        header.apply(ConfluenceWebhookSignature.SHARED_SECRET_HEADER));
  }

  @Override
  public void rejectForeign(byte[] body, UnaryOperator<String> header) {
    ConfluenceWebhookSignature.verify(
        body == null ? new byte[0] : body,
        header.apply(ConfluenceWebhookSignature.HUB_SIGNATURE_HEADER),
        header.apply(ConfluenceWebhookSignature.SHARED_SECRET_HEADER),
        null);
  }

  @Override
  public Set<String> settingsKeys() {
    return ConfluenceSourceSettings.KEYS;
  }

  /** Refuses a field the settings do not know and a part of the wrong kind; the rest waits. */
  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    requested.requireOnly(ConfluenceSourceSettings.KEYS);
    ConfluenceSourceSettings.read(requested);
    return requested;
  }

  /**
   * The edition must be the instance's (Entscheidung 2): after the static checks one
   * credential-free probe, so the invariant never depends on what the client sent.
   */
  @Override
  public SourceSettings validate(SourceSettings requested) {
    ConfluenceSourceSettings own = ConfluenceSourceSettings.read(requested.connectorSettings());
    String normalizedUrl = validateConnection(requested, own.edition());
    List<ConfluenceSpaceSelection> spaces = scope(own);
    if (spaces == null || (!own.all() && spaces.isEmpty())) {
      throw new ValidationException(SPACES_REQUIRED);
    }
    connectionService.requireEdition(normalizedUrl, own.edition());
    return requested
        .withSourceUrl(normalizedUrl)
        .withConnectorSettings(
            new ConfluenceSourceSettings(
                    own.edition(),
                    spaces,
                    validateFullSyncIntervalDays(own.fullSyncIntervalDays()),
                    own.all() ? Boolean.TRUE : null)
                .toData());
  }

  /**
   * The selection {@code own} asks for: empty for "Alle Spaces", which no selection may accompany,
   * {@code null} when it names neither.
   */
  private static List<ConfluenceSpaceSelection> scope(ConfluenceSourceSettings own) {
    if (own.all()) {
      if (own.spaces() != null && !own.spaces().isEmpty()) {
        throw new ValidationException(ALL_OR_SELECTION);
      }
      return List.of();
    }
    return own.spaces() == null ? null : validateSpaces(own.spaces());
  }

  /**
   * The edition is as permanent as the type; a present selection replaces the stored one; a
   * full-sync rhythm of 0 returns the library to the instance-wide default. Returns the rhythm as
   * requested, 0 included - {@link #applyChange} resolves it.
   */
  @Override
  public SourceSettings validateChange(
      SourceSettings stored, SourceSettings requested, boolean replacesConnection) {
    ConfluenceSourceSettings own = ConfluenceSourceSettings.read(requested.connectorSettings());
    ConfluenceEdition storedEdition =
        ConfluenceSourceSettings.stored(stored.connectorSettings()).edition();
    if (own.edition() != null && own.edition() != storedEdition) {
      throw new ValidationException(
          "sourceSettings.edition kann nach dem Anlegen der Bibliothek nicht mehr geändert werden"
              + (storedEdition == null
                  ? ""
                  : "; sie bleibt bei Confluence "
                      + ConfluenceConnectionService.label(storedEdition)));
    }
    List<ConfluenceSpaceSelection> spaces = scope(own);
    if (Boolean.FALSE.equals(own.allSpaces()) && spaces == null) {
      throw new ValidationException(SPACES_REQUIRED);
    }
    SourceSettings validated = requested;
    if (replacesConnection) {
      validated = requested.withSourceUrl(validateConnection(requested, storedEdition));
    }
    Integer intervalDays = own.fullSyncIntervalDays();
    if (intervalDays != null && intervalDays != 0) {
      validateFullSyncIntervalDays(intervalDays);
    }
    if (spaces == null && intervalDays == null && own.allSpaces() == null) {
      return validated.withConnectorSettings(null);
    }
    return validated.withConnectorSettings(
        new ConfluenceSourceSettings(null, spaces, intervalDays, own.allSpaces()).toData());
  }

  /** The connection half: edition, address and credentials. Returns the normalised address. */
  private static String validateConnection(SourceSettings settings, ConfluenceEdition edition) {
    if (edition == null) {
      throw new ValidationException(
          "sourceSettings.edition ist erforderlich, wenn sourceType CONFLUENCE ist");
    }
    if (settings.sourcePath() != null) {
      throw new ValidationException("sourcePath ist für sourceType CONFLUENCE nicht zulässig");
    }
    if (settings.sourceUrl() == null) {
      throw new ValidationException("sourceUrl ist erforderlich, wenn sourceType CONFLUENCE ist");
    }
    String normalizedUrl;
    try {
      normalizedUrl =
          ConfluenceConnection.normalizeBaseUrl(settings.sourceUrl(), edition).toString();
    } catch (ConfluenceConnection.InvalidBaseUrlException e) {
      throw new ValidationException(e.getMessage());
    }
    if (settings.sourceCredentials() == null) {
      throw new ValidationException(
          "sourceCredentials sind erforderlich, wenn sourceType CONFLUENCE ist");
    }
    try {
      ConfluenceCredentials.parse(edition, settings.sourceCredentials());
    } catch (ConfluenceCredentials.InvalidCredentialsFormatException e) {
      throw new ValidationException(e.getMessage());
    }
    return normalizedUrl;
  }

  /**
   * Keys are non-blank, at most 255 characters and unique per library, case-insensitively as
   * Confluence treats them - two spellings of one key would list the same pages twice.
   */
  private static List<ConfluenceSpaceSelection> validateSpaces(
      List<ConfluenceSpaceSelection> requested) {
    if (requested.isEmpty()) {
      throw new ValidationException(SPACES_REQUIRED);
    }
    if (requested.size() > MAX_SPACES) {
      throw new ValidationException(
          "sourceSettings.spaces: höchstens " + MAX_SPACES + " Spaces je Bibliothek");
    }
    Set<String> seen = new HashSet<>();
    List<ConfluenceSpaceSelection> normalized = new ArrayList<>();
    for (ConfluenceSpaceSelection selection : requested) {
      String key = selection == null ? null : blankToNull(selection.getSpaceKey());
      if (key == null) {
        throw new ValidationException(
            "sourceSettings.spaces: jeder Eintrag braucht einen Space-Schlüssel");
      }
      if (key.length() > 255) {
        throw new ValidationException(
            "sourceSettings.spaces: der Space-Schlüssel darf höchstens 255 Zeichen lang sein");
      }
      if (!seen.add(key.toUpperCase(Locale.ROOT))) {
        throw new ValidationException(
            "sourceSettings.spaces: der Space " + key + " ist mehrfach ausgewählt");
      }
      String name = blankToNull(selection.getSpaceName());
      if (name != null && name.length() > 255) {
        throw new ValidationException(
            "sourceSettings.spaces: der Space-Name darf höchstens 255 Zeichen lang sein");
      }
      normalized.add(new ConfluenceSpaceSelection(key, name));
    }
    return normalized;
  }

  /** The rhythm can be lengthened, never switched off; {@code null} follows the default. */
  private static Integer validateFullSyncIntervalDays(Integer days) {
    if (days != null && (days < 1 || days > 365)) {
      throw new ValidationException(
          "sourceSettings.fullSyncIntervalDays muss zwischen 1 und 365 Tagen liegen");
    }
    return days;
  }

  @Override
  public void configureNew(KnowledgeLibrary library, SourceSettings validated) {
    store(library, ConfluenceSourceSettings.read(validated.connectorSettings()));
  }

  @Override
  public void applyChange(
      KnowledgeLibrary library, ConnectorData storedSettings, SourceSettings validated) {
    if (validated.connectorSettings() == null) {
      return;
    }
    ConfluenceSourceSettings change = ConfluenceSourceSettings.read(validated.connectorSettings());
    ConfluenceSourceSettings stored = ConfluenceSourceSettings.stored(storedSettings);
    Integer intervalDays = stored.fullSyncIntervalDays();
    if (change.fullSyncIntervalDays() != null) {
      intervalDays = change.fullSyncIntervalDays() == 0 ? null : change.fullSyncIntervalDays();
    }
    // a selection without the flag means the selection; the flag alone keeps or drops "Alle"
    boolean all =
        change.allSpaces() != null ? change.allSpaces() : change.spaces() == null && stored.all();
    store(
        library,
        new ConfluenceSourceSettings(
            stored.edition(),
            all ? List.of() : change.spaces() != null ? change.spaces() : stored.spaceSelection(),
            intervalDays,
            all ? Boolean.TRUE : null));
  }

  /** Stores the settings with the spaces ordered by key - the order a run lists them in. */
  private static void store(KnowledgeLibrary library, ConfluenceSourceSettings settings) {
    library.updateSourceSettings(settings.sortedByKey().toData().toJson());
  }

  /**
   * Every reader sees the edition and the selection; the rhythm is administration detail. Stored
   * settings the record no longer reads are left out rather than failing the whole read.
   */
  @Override
  public ConnectorData settingsView(
      KnowledgeLibrary library, ConnectorData storedSettings, boolean manager) {
    ConfluenceSourceSettings stored = readableStored(library, storedSettings);
    if (stored == null || stored.edition() == null) {
      return null;
    }
    return new ConfluenceSourceSettings(
            stored.edition(),
            stored.spaceSelection(),
            manager ? stored.fullSyncIntervalDays() : null,
            stored.all() ? Boolean.TRUE : null)
        .toData();
  }

  /** The selection is exactly what every reader may see - a change leaves an audit trail. */
  @Override
  public Map<String, Object> settingsState(KnowledgeLibrary library, ConnectorData storedSettings) {
    ConfluenceSourceSettings stored = readableStored(library, storedSettings);
    if (stored != null && stored.all()) {
      return Map.of(SPACES_STATE, ALL_SPACES_STATE);
    }
    return Map.of(
        SPACES_STATE,
        stored == null
            ? List.of()
            : stored.spaceSelection().stream().map(ConfluenceSpaceSelection::getSpaceKey).toList());
  }

  /** The stored settings, or {@code null} with a log entry when they no longer read. */
  private static ConfluenceSourceSettings readableStored(
      KnowledgeLibrary library, ConnectorData storedSettings) {
    try {
      return ConfluenceSourceSettings.stored(storedSettings);
    } catch (ValidationException | IllegalArgumentException e) {
      log.warn(
          "Library {} carries Confluence settings the record rejects; left out: {}",
          library.getId(),
          e.getMessage());
      return null;
    }
  }

  /**
   * "No sync state" is how the next run learns it must be a full one, and how an interrupted full
   * sync's per-space progress for a now different selection is discarded.
   */
  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    if (addressChanged || changedSettings.contains(SPACES_STATE)) {
      syncStateRepository.deleteByLibraryId(library.getId());
    }
  }

  /**
   * Detects the edition without credentials and, when credentials are given, verifies them. An
   * instance problem is the test's result, not an exception; the detected edition is its finding.
   */
  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    if (settings.sourcePath() != null && !settings.sourcePath().isBlank()) {
      throw new ValidationException("sourcePath ist für sourceType CONFLUENCE nicht zulässig");
    }
    if (settings.sourceUrl() == null) {
      throw new ValidationException("sourceUrl ist erforderlich, wenn sourceType CONFLUENCE ist");
    }
    ConfluenceConnectionService.Probe probe;
    try {
      probe =
          connectionService.probe(
              settings.sourceUrl(),
              blankToNull(settings.sourceCredentials()),
              ConfluenceSourceSettings.read(settings.connectorSettings()).edition());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Der Verbindungstest wurde unterbrochen.");
    }
    return new SourceConnectionTestResult(
        probe.reachable(),
        probe.message(),
        probe.readableSpaces(),
        probe.credentialsVerified(),
        probe.detectedEdition() == null
            ? null
            : new ConfluenceSourceSettings(probe.detectedEdition(), null, null).toData());
  }

  @Override
  public String otherTypeMessage() {
    return "Die Bibliothek ist keine Confluence-Bibliothek";
  }

  /** Every space the credentials may read, for the edition the query names. */
  @Override
  public SourceListing browse(Query query) {
    SourceSettings settings = query.settings();
    String credentials = blankToNull(settings.sourceCredentials());
    if (credentials == null) {
      throw new ValidationException("sourceCredentials sind für die Space-Auflistung erforderlich");
    }
    List<ConfluenceSpace> spaces;
    try {
      spaces =
          connectionService.listSpaces(
              settings.sourceUrl(),
              ConfluenceSourceSettings.read(settings.connectorSettings()).edition(),
              credentials);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Space-Auflistung wurde unterbrochen.");
    }
    return new SourceListing(
        true,
        spaces.stream().map(space -> new SourceListing.Entry(space.key(), space.name())).toList(),
        null);
  }
}
