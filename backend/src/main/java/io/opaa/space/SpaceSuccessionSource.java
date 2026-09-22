package io.opaa.space;

import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.permission.SuccessionFinding;
import io.opaa.permission.SuccessionFindingSource;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * A space without a capable {@code ADMIN} member (#1819, ADR-0036 Entscheidung 6). The rule is
 * {@code SpaceAccessPolicy#hasCapableAdmin} from #1815 - the same one the protection of the last
 * capable {@code ADMIN} decides on, so the state and the guard can never disagree.
 *
 * <p><b>The personal space is left out</b>, as changesets 044 and 052 leave it out of the history:
 * nobody decides its ownership, it has no successor by construction, and listing one per locked
 * account would bury the list under entries nobody can act on.
 */
@Component
class SpaceSuccessionSource implements SuccessionFindingSource {

  private final SpaceRepository spaces;
  private final SpaceAccessPolicy accessPolicy;

  SpaceSuccessionSource(SpaceRepository spaces, SpaceAccessPolicy accessPolicy) {
    this.spaces = spaces;
    this.accessPolicy = accessPolicy;
  }

  @Override
  public SuccessionKind kind() {
    return SuccessionKind.OPEN_SUCCESSION;
  }

  @Override
  public SuccessionObjectType objectType() {
    return SuccessionObjectType.SPACE;
  }

  @Override
  public List<SuccessionFinding> findingsOf(UUID organizationId) {
    List<Space> candidates = spaces.findByOrganizationIdWithMemberships(organizationId);
    Set<UUID> open = openAmong(candidates);
    return candidates.stream()
        .filter(space -> open.contains(space.getId()))
        .map(SpaceSuccessionSource::findingOf)
        .toList();
  }

  @Override
  public Optional<SuccessionFinding> findingFor(UUID spaceId) {
    return spaces
        .findByIdWithMemberships(spaceId)
        .filter(space -> openAmong(List.of(space)).contains(space.getId()))
        .map(SpaceSuccessionSource::findingOf);
  }

  /**
   * The one derivation of "Nachfolge offen" for spaces - the response fields, the reach guard and
   * the operational list all read it here, so no second definition can drift away from the {@code
   * is_default} exception. One account query for the whole list.
   */
  /**
   * The marking of a whole list of spaces, from the same derivation as {@link #openAmong} - the
   * overview carries state <b>and</b> addressee, not only the bare flag (ADR-0036, Entscheidung 6).
   * Spaces in order; a space in order is absent from the map.
   */
  Map<UUID, SuccessionFinding> findingsAmong(Collection<Space> candidates) {
    Set<UUID> open = openAmong(candidates);
    Map<UUID, SuccessionFinding> findings = new LinkedHashMap<>();
    for (Space space : candidates) {
      if (open.contains(space.getId())) {
        findings.put(space.getId(), findingOf(space));
      }
    }
    return findings;
  }

  Set<UUID> openAmong(Collection<Space> candidates) {
    List<Space> relevant = candidates.stream().filter(space -> !space.isDefault()).toList();
    Set<UUID> capable = accessPolicy.spacesWithCapableAdmin(relevant);
    return relevant.stream()
        .map(Space::getId)
        .filter(id -> !capable.contains(id))
        .collect(Collectors.toSet());
  }

  /**
   * The addressee are the space's remaining capable {@code ADMIN} members - a statement of
   * responsibility even where there is none left, in which case the list is the only place it
   * surfaces at all.
   */
  private static SuccessionFinding findingOf(Space space) {
    return SuccessionFinding.of(
        SuccessionObjectType.SPACE,
        space.getId(),
        space.getName(),
        SuccessionAddressee.SPACE_ADMINS);
  }
}
