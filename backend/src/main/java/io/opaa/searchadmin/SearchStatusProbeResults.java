package io.opaa.searchadmin;

import io.opaa.api.types.ModelRole;
import java.time.Duration;
import java.util.Optional;

/**
 * The reachability probes of the chat and the embedding role, shared for a short time by every
 * instance of an installation (ADR-0053, Entscheidung 12): the search status page shows the same
 * state whichever instance answers it, and one probe per role and time span is sent, not one per
 * instance.
 */
public interface SearchStatusProbeResults {

  /** The result of {@code role} recorded within the last {@code maxAge}, if any. */
  Optional<ModelRoleStatus> fresh(ModelRole role, Duration maxAge);

  /**
   * Shares {@code probed} unless another caller shared a result within {@code maxAge} meanwhile,
   * and returns the result that is shared now - the one every instance shows.
   */
  ModelRoleStatus share(ModelRoleStatus probed, Duration maxAge);

  /** Forgets every shared result; the next status probes again. */
  void forget();
}
