package io.opaa.indexing.source.confluence;

import static io.opaa.indexing.source.ConnectorChecks.blankToNull;
import static io.opaa.indexing.source.ConnectorChecks.unreachable;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.PushIntake;
import io.opaa.indexing.source.PushIntakeHandler;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookSignature;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

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

  /** Upper bound of a space selection - matches LibraryRequest.confluenceSpaces.maxItems. */
  static final int MAX_SPACES = 500;

  private static final String SPACES_STATE = "confluenceSpaces";

  private static final String SPACES_REQUIRED =
      "confluenceSpaces: mindestens ein Space ist erforderlich, wenn sourceType CONFLUENCE ist";

  private final ConfluenceConnectionService connectionService;
  private final SourceSyncStateRepository syncStateRepository;
  private final SourceConnectorDescriptor descriptor;
  private final ConfluenceWebhookService webhookService;

  public ConfluenceSourceConnector(
      ConfluenceConnectionService connectionService,
      ConfluenceProperties properties,
      SourceSyncStateRepository syncStateRepository,
      ConfluenceWebhookService webhookService) {
    this.connectionService = connectionService;
    this.syncStateRepository = syncStateRepository;
    this.webhookService = webhookService;
    this.descriptor =
        new SourceConnectorDescriptor(
            DocumentSourceType.CONFLUENCE,
            true,
            new PushIntake("confluenceWebhookSecret", "Ein Webhook-Geheimnis"),
            properties.fullSyncInterval());
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public void acceptNotification(UUID libraryId, byte[] body, UnaryOperator<String> header) {
    webhookService.accept(
        libraryId,
        body,
        header.apply(ConfluenceWebhookSignature.HUB_SIGNATURE_HEADER),
        header.apply(ConfluenceWebhookSignature.SHARED_SECRET_HEADER));
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    ConfluenceSourceSettings own = ConfluenceSourceSettings.read(requested.connectorSettings());
    String normalizedUrl = validateConnection(requested, own.edition());
    List<ConfluenceSpaceSelection> spaces =
        own.spaces() == null ? List.of() : validateSpaces(own.spaces());
    if (spaces.isEmpty()) {
      throw new ValidationException(SPACES_REQUIRED);
    }
    return requested
        .withSourceUrl(normalizedUrl)
        .withConnectorSettings(
            new ConfluenceSourceSettings(
                    own.edition(), spaces, validateFullSyncIntervalDays(own.fullSyncIntervalDays()))
                .toData());
  }

  /**
   * The edition is as permanent as the type; a present selection replaces the stored one; a
   * full-sync rhythm of 0 returns the library to the instance-wide default. Returns the rhythm as
   * requested, 0 included - {@link #applyChange} resolves it.
   */
  @Override
  public SourceSettings validateChange(
      KnowledgeLibrary library, SourceSettings requested, boolean replacesConnection) {
    ConfluenceSourceSettings own = ConfluenceSourceSettings.read(requested.connectorSettings());
    ConfluenceEdition storedEdition = ConfluenceSourceSettings.of(library).edition();
    if (own.edition() != null && own.edition() != storedEdition) {
      throw new ValidationException(
          "confluenceEdition kann nach dem Anlegen der Bibliothek nicht mehr geändert werden");
    }
    List<ConfluenceSpaceSelection> spaces =
        own.spaces() == null ? null : validateSpaces(own.spaces());
    SourceSettings validated = requested;
    if (replacesConnection) {
      validated = requested.withSourceUrl(validateConnection(requested, storedEdition));
    }
    Integer intervalDays = own.fullSyncIntervalDays();
    if (intervalDays != null && intervalDays != 0) {
      validateFullSyncIntervalDays(intervalDays);
    }
    if (spaces == null && intervalDays == null) {
      return validated.withConnectorSettings(null);
    }
    return validated.withConnectorSettings(
        new ConfluenceSourceSettings(null, spaces, intervalDays).toData());
  }

  /** The connection half: edition, address and credentials. Returns the normalised address. */
  private static String validateConnection(SourceSettings settings, ConfluenceEdition edition) {
    if (edition == null) {
      throw new ValidationException(
          "confluenceEdition ist erforderlich, wenn sourceType CONFLUENCE ist");
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
          "confluenceSpaces: höchstens " + MAX_SPACES + " Spaces je Bibliothek");
    }
    Set<String> seen = new HashSet<>();
    List<ConfluenceSpaceSelection> normalized = new ArrayList<>();
    for (ConfluenceSpaceSelection selection : requested) {
      String key = selection == null ? null : blankToNull(selection.getSpaceKey());
      if (key == null) {
        throw new ValidationException(
            "confluenceSpaces: jeder Eintrag braucht einen Space-Schlüssel");
      }
      if (key.length() > 255) {
        throw new ValidationException(
            "confluenceSpaces: der Space-Schlüssel darf höchstens 255 Zeichen lang sein");
      }
      if (!seen.add(key.toUpperCase(Locale.ROOT))) {
        throw new ValidationException(
            "confluenceSpaces: der Space " + key + " ist mehrfach ausgewählt");
      }
      String name = blankToNull(selection.getSpaceName());
      if (name != null && name.length() > 255) {
        throw new ValidationException(
            "confluenceSpaces: der Space-Name darf höchstens 255 Zeichen lang sein");
      }
      normalized.add(new ConfluenceSpaceSelection(key, name));
    }
    return normalized;
  }

  /** The rhythm can be lengthened, never switched off; {@code null} follows the default. */
  private static Integer validateFullSyncIntervalDays(Integer days) {
    if (days != null && (days < 1 || days > 365)) {
      throw new ValidationException(
          "confluenceFullSyncIntervalDays muss zwischen 1 und 365 Tagen liegen");
    }
    return days;
  }

  /**
   * The stored edition must be the instance's (Entscheidung 2): one credential-free probe at
   * creation, so the invariant never depends on what the client sent.
   */
  @Override
  public void configureNew(KnowledgeLibrary library, SourceSettings validated) {
    ConfluenceSourceSettings own = ConfluenceSourceSettings.read(validated.connectorSettings());
    connectionService.requireEdition(
        validated.sourceUrl(),
        validated.sourceProxy(),
        validated.sourceInsecureSsl(),
        own.edition());
    store(library, own);
  }

  @Override
  public void applyChange(KnowledgeLibrary library, SourceSettings validated) {
    if (validated.connectorSettings() == null) {
      return;
    }
    ConfluenceSourceSettings change = ConfluenceSourceSettings.read(validated.connectorSettings());
    ConfluenceSourceSettings stored = ConfluenceSourceSettings.of(library);
    Integer intervalDays = stored.fullSyncIntervalDays();
    if (change.fullSyncIntervalDays() != null) {
      intervalDays = change.fullSyncIntervalDays() == 0 ? null : change.fullSyncIntervalDays();
    }
    store(
        library,
        new ConfluenceSourceSettings(
            stored.edition(),
            change.spaces() != null ? change.spaces() : stored.spaceSelection(),
            intervalDays));
  }

  /** Stores the settings with the spaces ordered by key - the order a run lists them in. */
  private static void store(KnowledgeLibrary library, ConfluenceSourceSettings settings) {
    library.updateSourceSettings(settings.sortedByKey().toData().toJson());
  }

  /** Every reader sees the edition and the selection; the rhythm is administration detail. */
  @Override
  public ConnectorData settingsView(KnowledgeLibrary library, boolean manager) {
    ConfluenceSourceSettings stored = ConfluenceSourceSettings.of(library);
    if (stored.edition() == null) {
      return null;
    }
    return new ConfluenceSourceSettings(
            stored.edition(),
            stored.spaceSelection(),
            manager ? stored.fullSyncIntervalDays() : null)
        .toData();
  }

  /** The selection is exactly what every reader may see - a change leaves an audit trail. */
  @Override
  public Map<String, Object> settingsState(KnowledgeLibrary library) {
    return Map.of(
        SPACES_STATE,
        ConfluenceSourceSettings.of(library).spaceSelection().stream()
            .map(ConfluenceSpaceSelection::getSpaceKey)
            .toList());
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
              blankToNull(settings.sourceProxy()),
              blankToNull(settings.sourceCredentials()),
              settings.sourceInsecureSsl(),
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
              blankToNull(settings.sourceProxy()),
              credentials,
              settings.sourceInsecureSsl());
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
