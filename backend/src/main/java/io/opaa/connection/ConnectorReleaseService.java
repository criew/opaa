package io.opaa.connection;

import io.opaa.api.types.Capability;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectionProfileService;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.ConnectorScope;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.SourceType;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.HeldScopes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The connector release (spec "Konnektor-Freigabe und Sperre"): whether a person may create a new
 * library of a connector type, with its own address or through a profile. It answers from {@code
 * CREATE_CONNECTOR_LIBRARY} in the scope of the type or the profile and from the locks. It governs
 * only a new library; an existing one runs on after a withdrawal, only a lock stops it.
 */
@Service
@Transactional(readOnly = true)
public class ConnectorReleaseService {

  private static final Capability RELEASE = Capability.CREATE_CONNECTOR_LIBRARY;

  private final CapabilityService capabilities;
  private final ConnectionProfileRepository profiles;
  private final ConnectionProfileService profileService;
  private final SourceConnectorRegistry connectors;
  private final ConnectorLockService locks;

  public ConnectorReleaseService(
      CapabilityService capabilities,
      ConnectionProfileRepository profiles,
      ConnectionProfileService profileService,
      SourceConnectorRegistry connectors,
      ConnectorLockService locks) {
    this.capabilities = capabilities;
    this.profiles = profiles;
    this.profileService = profileService;
    this.connectors = connectors;
    this.locks = locks;
  }

  /**
   * Refuses a new library of {@code type} through {@code profileId} - {@code null} for its own
   * address - with {@code 403}: {@code CONNECTOR_LOCKED} for a lock, {@code CAPABILITY_REQUIRED}
   * without the release.
   */
  public void requireCreatable(CurrentUser caller, SourceType type, UUID profileId) {
    ConnectionProfile profile =
        profileId == null
            ? null
            : profiles
                .findById(profileId)
                .orElseThrow(() -> new NotFoundException("Zugang nicht gefunden"));
    locks.requireUnlocked(type, profile);
    if (profile == null) {
      capabilities.requireCapability(caller, RELEASE, ConnectorScope.ofType(type), typeLabel(type));
    } else {
      capabilities.requireCapability(
          caller, RELEASE, ConnectorScope.ofProfile(profile.getId()), profileLabel(profile));
    }
  }

  /**
   * Refuses releasing a library from {@code profile} ({@code null} once deleted) to an own address:
   * a locked profile stays locked for everyone, and the own address needs the type's release.
   */
  public void requireDetachable(CurrentUser caller, SourceType type, ConnectionProfile profile) {
    if (profile != null && profile.isLocked()) {
      throw new AccessDeniedException(
          "Der Zugang „"
              + profile.getName()
              + "“ ist gesperrt. Die Bibliothek läuft erst wieder, wenn die Systemverwaltung die"
              + " Sperre aufhebt oder sie einem anderen, freigegebenen Zugang zugeordnet wird.",
          ConnectorLockService.CONNECTOR_LOCKED);
    }
    requireCreatable(caller, type, null);
  }

  /** Refuses the caller unless they hold the release in at least one scope. */
  public void requireAnyRelease(CurrentUser caller) {
    if (!capabilities.scopesOf(caller, RELEASE).any()) {
      throw new AccessDeniedException(
          CapabilityService.missing(RELEASE), CapabilityService.CAPABILITY_REQUIRED);
    }
  }

  /** What the caller may create, per connector type that reaches a source. */
  public Map<SourceType, TypeCreation> typeCreations(CurrentUser caller) {
    HeldScopes held = capabilities.scopesOf(caller, RELEASE);
    Map<SourceType, TypeCreation> creations = new LinkedHashMap<>();
    for (SourceConnectorDescriptor descriptor : connectors.descriptors()) {
      if (descriptor.uploads()) {
        continue;
      }
      SourceType type = descriptor.type();
      Optional<String> lock = locks.creationLock(type, null);
      if (lock.isPresent()) {
        creations.put(type, new TypeCreation(false, false, true, lock.get()));
        continue;
      }
      boolean ownAddress =
          descriptor.profileSupport() != ConnectionProfileSupport.REQUIRED
              && held.covers(ConnectorScope.ofType(type));
      boolean viaProfile =
          descriptor.admitsProfiles()
              && profileService.selectableFor(type).stream()
                  .anyMatch(
                      profile ->
                          !profile.isLocked()
                              && held.covers(ConnectorScope.ofProfile(profile.getId())));
      boolean creatable = ownAddress || viaProfile;
      creations.put(
          type,
          new TypeCreation(
              creatable, ownAddress, false, creatable ? null : creationNotice(descriptor, held)));
    }
    return creations;
  }

  /**
   * The profiles a library of {@code type} may be connected through, each with the caller's say.
   */
  public List<ProfileOption> profileOptions(CurrentUser caller, SourceType type) {
    HeldScopes held = capabilities.scopesOf(caller, RELEASE);
    return profileService.selectableFor(type).stream()
        .map(
            profile -> {
              Optional<String> lock = locks.creationLock(type, profile);
              if (lock.isPresent()) {
                return new ProfileOption(profile, false, lock.get());
              }
              boolean released = held.covers(ConnectorScope.ofProfile(profile.getId()));
              return new ProfileOption(
                  profile,
                  released,
                  released
                      ? null
                      : CapabilityService.missingInScope(RELEASE, profileLabel(profile)));
            })
        .toList();
  }

  /** Why nothing of {@code descriptor} can be created, naming who can change that. */
  private String creationNotice(SourceConnectorDescriptor descriptor, HeldScopes held) {
    if (descriptor.profileSupport() != ConnectionProfileSupport.REQUIRED) {
      return CapabilityService.missingInScope(RELEASE, typeLabel(descriptor.type()));
    }
    boolean anyProfile =
        profileService.selectableFor(descriptor.type()).stream()
            .anyMatch(profile -> !profile.isLocked());
    String name = "„" + descriptor.displayName() + "“";
    if (!anyProfile || held.all()) {
      return "Die Quellart "
          + name
          + " ist nur über einen Zugang nutzbar, und es gibt noch keinen. Zugänge legt die"
          + " Systemverwaltung an.";
    }
    return "Die Quellart "
        + name
        + " ist nur über einen Zugang nutzbar, und für keinen davon haben Sie das Anlegerecht."
        + " Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.";
  }

  private String typeLabel(SourceType type) {
    String name =
        connectors.find(type).map(connector -> connector.descriptor().displayName()).orElse(null);
    return "die Quellart „" + (name == null ? type.key() : name) + "“";
  }

  private static String profileLabel(ConnectionProfile profile) {
    return "den Zugang „" + profile.getName() + "“";
  }

  /**
   * Whether the caller may create a library of one type now, with its own address or through a
   * profile; {@code notice} says why not, naming who can change it.
   */
  public record TypeCreation(
      boolean creatable, boolean withOwnAddress, boolean locked, String notice) {}

  /** One selectable profile and whether the caller may create a library through it now. */
  public record ProfileOption(ConnectionProfile profile, boolean creatable, String notice) {}
}
