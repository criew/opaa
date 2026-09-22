package io.opaa.permission;

import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One object type's own answer to one of the three findings (#1819, ADR-0036 Entscheidung 6). The
 * package that owns the objects owns the rule: a library asks after its owner, a space after a
 * capable {@code ADMIN} member, a group after an active steward. A further asset type (#1726) adds
 * a bean, not a branch.
 *
 * <p><b>Every implementation derives, none stores.</b> Whoever answers here reads the current state
 * - so an object that regains a capable responsible party leaves the list on the next read, without
 * anybody clearing a flag.
 */
public interface SuccessionFindingSource {

  /** Which tab of the operational list this source fills. */
  SuccessionKind kind();

  /** The kind of object it reports. */
  SuccessionObjectType objectType();

  /** Everything of this kind currently found in one organization. */
  List<SuccessionFinding> findingsOf(UUID organizationId);

  /**
   * The same question for one object - what the object's own view and the reach guards ask. Empty
   * for a source whose finding is not a property of a single object (the two group tabs), and for
   * an object that is in order.
   */
  default Optional<SuccessionFinding> findingFor(UUID objectId) {
    return Optional.empty();
  }
}
