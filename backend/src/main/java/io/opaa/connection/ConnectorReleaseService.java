package io.opaa.connection;

import io.opaa.api.types.Capability;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectionProfileService;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.ConnectorScope;
import io.opaa.connection.profile.ProfileRequirementService;
import io.opaa.connection.profile.ProfileRequirements;
import io.opaa.connection.token.PersonAccounts;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.SourceType;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.HeldScopes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
  private final ProfileRequirements requirements;
  private final ProfileRequirementService requirementService;
  private final PersonAccounts personAccounts;

  public ConnectorReleaseService(
      CapabilityService capabilities,
      ConnectionProfileRepository profiles,
      ConnectionProfileService profileService,
      SourceConnectorRegistry connectors,
      ConnectorLockService locks,
      ProfileRequirements requirements,
      ProfileRequirementService requirementService,
      PersonAccounts personAccounts) {
    this.capabilities = capabilities;
    this.profiles = profiles;
    this.profileService = profileService;
    this.connectors = connectors;
    this.locks = locks;
    this.requirements = requirements;
    this.requirementService = requirementService;
    this.personAccounts = personAccounts;
  }

  /**
   * Refuses a new library of {@code type} through {@code profileId} - {@code null} for its own
   * address: {@code 403 CONNECTOR_LOCKED} for a lock, {@code 400 PROFILE_REQUIRED} for an own
   * address where only a profile is admitted, {@code 403 CAPABILITY_REQUIRED} without the release.
   */
  public void requireCreatable(CurrentUser caller, SourceType type, UUID profileId) {
    ConnectionProfile profile =
        profileId == null
            ? null
            : profiles
                .findById(profileId)
                .orElseThrow(() -> new NotFoundException("Zugang nicht gefunden"));
    locks.requireUnlocked(type, profile);
    if (profile == null && requirements.profileRequired(type)) {
      throw requirementService.ownAddressRefused(type);
    }
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
      boolean profileRequired = requirements.profileRequired(type);
      Optional<String> lock = locks.creationLock(type, null);
      if (lock.isPresent()) {
        creations.put(type, new TypeCreation(false, false, true, profileRequired, lock.get()));
        continue;
      }
      boolean ownAddress = !profileRequired && held.covers(ConnectorScope.ofType(type));
      boolean viaProfile =
          descriptor.admitsProfiles()
              && usableProfiles(caller, type, true).stream()
                  .anyMatch(
                      profile ->
                          !profile.isLocked()
                              && held.covers(ConnectorScope.ofProfile(profile.getId())));
      boolean creatable = ownAddress || viaProfile;
      creations.put(
          type,
          new TypeCreation(
              creatable,
              ownAddress,
              false,
              profileRequired,
              creatable ? null : creationNotice(descriptor, profileRequired, held)));
    }
    return creations;
  }

  /**
   * The profiles a library of {@code type} may be connected through, each with the caller's say and
   * whether she has a connected account on it. A profile for persons only is listed where she has
   * one and {@code withPersonProfiles} holds - for her private library; never anything about
   * another person's account.
   */
  public List<ProfileOption> profileOptions(
      CurrentUser caller, SourceType type, boolean withPersonProfiles) {
    HeldScopes held = capabilities.scopesOf(caller, RELEASE);
    List<ConnectionProfile> candidates = profiles.findBySourceTypeOrderByNameAsc(type);
    Set<UUID> ownAccounts = ownAccountsAmong(caller, candidates);
    return candidates.stream()
        .filter(
            profile ->
                profile.getOwnership().admitsLibraries()
                    || (withPersonProfiles && ownAccounts.contains(profile.getId())))
        .map(
            profile -> {
              boolean ownAccount = ownAccounts.contains(profile.getId());
              Optional<String> lock = locks.creationLock(type, profile);
              if (lock.isPresent()) {
                return new ProfileOption(profile, false, lock.get(), ownAccount);
              }
              boolean released = held.covers(ConnectorScope.ofProfile(profile.getId()));
              return new ProfileOption(
                  profile,
                  released,
                  released
                      ? null
                      : CapabilityService.missingInScope(RELEASE, profileLabel(profile)),
                  ownAccount);
            })
        .toList();
  }

  /** The profiles of {@code type} {@link #profileOptions} lists, without the caller's say. */
  private List<ConnectionProfile> usableProfiles(
      CurrentUser caller, SourceType type, boolean withPersonProfiles) {
    List<ConnectionProfile> candidates = profiles.findBySourceTypeOrderByNameAsc(type);
    Set<UUID> ownAccounts = withPersonProfiles ? ownAccountsAmong(caller, candidates) : Set.of();
    return candidates.stream()
        .filter(
            profile ->
                profile.getOwnership().admitsLibraries() || ownAccounts.contains(profile.getId()))
        .toList();
  }

  /** The ones of {@code candidates} the caller has a connected account on, in one query. */
  private Set<UUID> ownAccountsAmong(CurrentUser caller, List<ConnectionProfile> candidates) {
    if (candidates.isEmpty()) {
      return Set.of();
    }
    return personAccounts
        .accountsAmong(
            Set.of(caller.id()), candidates.stream().map(ConnectionProfile::getId).toList())
        .stream()
        .map(PersonAccounts.AccountKey::getProfileId)
        .collect(Collectors.toSet());
  }

  /** Why nothing of {@code descriptor} can be created, naming who can change that. */
  private String creationNotice(
      SourceConnectorDescriptor descriptor, boolean profileRequired, HeldScopes held) {
    if (!profileRequired) {
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
   * profile, and whether only a profile is admitted; {@code notice} says why not, naming who can
   * change it.
   */
  public record TypeCreation(
      boolean creatable,
      boolean withOwnAddress,
      boolean locked,
      boolean profileRequired,
      String notice) {}

  /**
   * One selectable profile, whether the caller may create a library through it now, and whether she
   * has a connected account of her own on it.
   */
  public record ProfileOption(
      ConnectionProfile profile, boolean creatable, String notice, boolean ownAccount) {}
}
