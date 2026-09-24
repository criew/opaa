package io.opaa.asset;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.auth.AccountActivityService;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.permission.GroupCapabilityService;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSubject;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.SuccessionFinding;
import io.opaa.permission.SuccessionFindingSource;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * An asset without a capable owner (#1819, ADR-0036 Entscheidung 6), for every asset type in one
 * query: a person whose account is no longer active, or a group that is dissolved, switched off or
 * has lost its last active account. Every type of the shell is reported, as {@link
 * SuccessionObjectType#ASSET} with its own asset type - there is no type this source skips.
 *
 * <p>The addressee follows the object, not the finder: an asset of an internal group is the
 * business of that group's stewards, everything else of the system administration.
 */
@Component
public class AssetSuccessionSource implements SuccessionFindingSource {

  private final AssetRepository assets;
  private final AccountActivityService accountActivity;
  private final GroupSubjectDirectory groupDirectory;
  private final GroupCapabilityService groupCapability;
  private final GroupMembershipResolver membershipResolver;
  private final UserRepository users;

  AssetSuccessionSource(
      AssetRepository assets,
      AccountActivityService accountActivity,
      GroupSubjectDirectory groupDirectory,
      GroupCapabilityService groupCapability,
      GroupMembershipResolver membershipResolver,
      UserRepository users) {
    this.assets = assets;
    this.accountActivity = accountActivity;
    this.groupDirectory = groupDirectory;
    this.groupCapability = groupCapability;
    this.membershipResolver = membershipResolver;
    this.users = users;
  }

  @Override
  public SuccessionKind kind() {
    return SuccessionKind.OPEN_SUCCESSION;
  }

  @Override
  public boolean answersFor(SuccessionObjectType objectType) {
    return objectType == SuccessionObjectType.ASSET;
  }

  @Override
  public List<SuccessionFinding> findingsOf(UUID organizationId) {
    return List.copyOf(findingsAmong(assets.findByOrganizationId(organizationId), true).values());
  }

  /**
   * The state of a whole list of assets, with <b>one</b> account query for all their person owners
   * - the overview carries the same marking as the detail view (ADR-0036, Entscheidung 6), and one
   * lookup per row would put it on every page of every reader.
   *
   * @param withMembershipHints only the operational list shows them; the marking at an object is
   *     state and addressee alone, so the reader's overview pays nothing for them
   */
  public Map<UUID, SuccessionFinding> findingsAmong(
      Collection<? extends OwnedAsset> candidates, boolean withMembershipHints) {
    Set<UUID> activeOwners =
        accountActivity.activeAmong(
            candidates.stream()
                .filter(asset -> asset.getOwnerType() == AssetOwnerType.USER)
                .map(OwnedAsset::getOwnerUserId)
                .toList());
    Map<UUID, SuccessionFinding> findings = new LinkedHashMap<>();
    for (OwnedAsset asset : candidates) {
      if (asset.getOwnerType() == AssetOwnerType.USER) {
        if (!activeOwners.contains(asset.getOwnerUserId())) {
          findings.put(asset.getId(), findingFor(asset, null, withMembershipHints));
        }
        continue;
      }
      GroupSubject owner = groupDirectory.find(asset.getOwnerGroupId()).orElse(null);
      if (owner == null || !groupCapability.isCapable(owner)) {
        findings.put(asset.getId(), findingFor(asset, owner, withMembershipHints));
      }
    }
    return findings;
  }

  @Override
  public Optional<SuccessionFinding> findingFor(UUID assetId) {
    return assets
        .findById(assetId)
        .filter(this::withoutCapableOwner)
        .map(
            asset ->
                findingFor(
                    asset,
                    asset.getOwnerType() == AssetOwnerType.GROUP
                        ? groupDirectory.find(asset.getOwnerGroupId()).orElse(null)
                        : null,
                    true));
  }

  private boolean withoutCapableOwner(Asset asset) {
    if (asset.getOwnerType() == AssetOwnerType.USER) {
      return accountActivity.activeAmong(List.of(asset.getOwnerUserId())).isEmpty();
    }
    return !groupCapability.isCapable(asset.getOwnerGroupId());
  }

  /**
   * The owner is named for the administration's orientation, never as a query axis - and a
   * protected group is named by its protection alone (ADR-0036, Entscheidung 9).
   */
  private SuccessionFinding findingFor(
      OwnedAsset asset, GroupSubject ownerGroup, boolean withMembershipHints) {
    boolean internalGroupOwner = ownerGroup != null && ownerGroup.internal();
    SuccessionFinding finding =
        SuccessionFinding.ofAsset(
            asset.getAssetType(),
            asset.getId(),
            asset.getName(),
            internalGroupOwner
                ? SuccessionAddressee.GROUP_STEWARDS
                : SuccessionAddressee.SYSTEM_ADMINISTRATION);
    if (ownerGroup != null) {
      return finding.withOwnerHint(
          ownerGroup.protectedGroup() ? "Geschützte Gruppe" : ownerGroup.name());
    }
    return finding
        .withOwnerHint(
            users.findById(asset.getOwnerUserId()).map(AssetSuccessionSource::nameOf).orElse(null))
        .withMembershipHints(
            withMembershipHints ? membershipHints(asset.getOwnerUserId()) : List.of());
  }

  /**
   * "War Mitglied von Referat 50" (Personalrat E4): a suggestion where to look for a successor,
   * Bestandsinformation and nothing else - never counted, sorted or queried, and a protected group
   * appears by its protection alone (ADR-0036, Entscheidung 9).
   */
  private List<String> membershipHints(UUID ownerUserId) {
    return membershipResolver.groupIdsForUser(ownerUserId).stream()
        .map(groupDirectory::find)
        .flatMap(Optional::stream)
        .filter(group -> !group.dissolved())
        .map(group -> group.protectedGroup() ? "Geschützte Gruppe" : group.name())
        .filter(Objects::nonNull)
        .sorted()
        .toList();
  }

  private static String nameOf(User user) {
    return user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
  }
}
