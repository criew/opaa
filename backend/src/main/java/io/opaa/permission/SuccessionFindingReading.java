package io.opaa.permission;

import io.opaa.api.types.SuccessionKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One reading that fills several tabs of the operational list at once (ADR-0036 Entscheidung 6).
 * The sources of those tabs name it as their {@link SuccessionFindingSource#sharedReading}, so
 * counting every tab runs it once instead of once per tab. It answers exactly the tabs of the
 * sources that name it, with the same findings they list.
 */
public interface SuccessionFindingReading {

  /** Everything each of its tabs currently finds in one organization. */
  Map<SuccessionKind, List<SuccessionFinding>> findingsByKindOf(UUID organizationId);
}
