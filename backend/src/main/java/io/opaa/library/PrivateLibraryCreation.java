package io.opaa.library;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.asset.AssetShellService;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.connection.ConnectorReleaseService;
import io.opaa.connection.LibraryConnectionService;
import io.opaa.connection.PrivateLibraryConnections;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.SourceDraft;
import io.opaa.connection.profile.SourceDraft.DraftOwner;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a private library (ADR-0041, Entscheidung 6): owner-only on the asset shell, owned by its
 * creator, on a profile admitting persons where she has a connected account, reaching only that
 * account's target and signing in with it. The choice is made here, once: no later change makes a
 * shared library private or a private one shared.
 */
@Service
public class PrivateLibraryCreation {

  private final ConnectorReleaseService connectorRelease;
  private final PrivateLibraryConnections privateConnections;
  private final LibraryConnectionService libraryConnections;
  private final EffectiveSourceSettings drafts;
  private final SourceConnectorRegistry connectors;
  private final KnowledgeLibraryRepository libraryRepository;
  private final AssetShellService shellService;

  public PrivateLibraryCreation(
      ConnectorReleaseService connectorRelease,
      PrivateLibraryConnections privateConnections,
      LibraryConnectionService libraryConnections,
      EffectiveSourceSettings drafts,
      SourceConnectorRegistry connectors,
      KnowledgeLibraryRepository libraryRepository,
      AssetShellService shellService) {
    this.connectorRelease = connectorRelease;
    this.privateConnections = privateConnections;
    this.libraryConnections = libraryConnections;
    this.drafts = drafts;
    this.connectors = connectors;
    this.libraryRepository = libraryRepository;
    this.shellService = shellService;
  }

  /**
   * Creates the private library {@code request} describes for {@code caller}.
   *
   * @return its id
   * @throws ValidationException (German 400) for an owner group, a missing profile or connected
   *     account, a secret in the request and a target other than the account's
   */
  @Transactional
  public UUID create(LibraryCreation request, CurrentUser caller) {
    SourceType type = request.sourceType();
    if (type == null) {
      throw new ValidationException("sourceType ist erforderlich");
    }
    if (request.ownerType() == AssetOwnerType.GROUP) {
      throw new ValidationException(
          "Eine private Bibliothek gehört allein der Person, die sie anlegt, nie einer Gruppe");
    }
    UUID profileId = request.connectionProfileId();
    if (profileId == null) {
      throw new ValidationException(
          "Eine private Bibliothek läuft über ein verbundenes Konto: connectionProfileId ist"
              + " erforderlich");
    }
    connectorRelease.requireCreatable(caller, type, profileId);
    ConnectionProfile profile = privateConnections.requireOwnProfile(caller.id(), profileId, type);
    String name = LibraryFields.name(request.name());
    LibraryFields.description(request.description());
    SourceConnector connector = connectors.connector(type);
    SourceSettings requested =
        new SourceSettings(
            blankToNull(request.sourcePath()),
            privateConnections.addressOn(
                profile, request.sourceUrl() == null ? null : request.sourceUrl().toString()),
            blankToNull(request.sourceProxy()),
            blankToNull(request.sourceCredentials()),
            Boolean.TRUE.equals(request.sourceInsecureSsl()),
            readSettings(connector, request.sourceSettings()));
    SourceSettings validated =
        connector.validate(
            privateConnections.ofDraft(type, profileId, null, requested, caller.id()).settings());
    SourceSettings own =
        drafts
            .ownPart(
                new SourceDraft(type, profileId, null, requested, DraftOwner.PERSON), validated)
            .withoutCredentials();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownerOnly(
            caller.organizationId(),
            name,
            request.description(),
            caller.id(),
            type,
            own.sourcePath(),
            own.sourceUrl(),
            own.sourceProxy(),
            null,
            own.sourceInsecureSsl());
    connector.configureNew(library, own);
    if (request.schedule() != null) {
      LibraryFields.Schedule schedule =
          LibraryFields.schedule(request.schedule(), connector.descriptor().indexingRun());
      library.updateSchedule(schedule.enabled(), schedule.cron());
    }
    KnowledgeLibrary saved = libraryRepository.save(library);
    libraryConnections.attachNew(saved, profileId);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", saved.auditName());
    payload.put("sourceType", type.key());
    shellService.registerCreated(saved, caller.id(), payload);
    return saved.getId();
  }

  private static ConnectorData readSettings(SourceConnector connector, ConnectorData requested) {
    return requested == null ? null : connector.readSettings(requested);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
