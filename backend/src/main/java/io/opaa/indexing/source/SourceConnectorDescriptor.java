package io.opaa.indexing.source;

import io.opaa.knowledge.SourceType;
import java.time.Duration;
import java.util.Objects;

/**
 * What a {@link SourceConnector} is, as the administration asks it instead of branching on the
 * source type (ADR-0038). {@code GET /source-types} lists it.
 *
 * @param displayName the German name of the source type, e.g. "Confluence"
 * @param indexingRun whether a run fills the library - a library with a run can be scheduled, is
 *     created under the connector-library capability, carries a share cap and takes its whole
 *     bestand with it when deleted; a library without one is curated document by document through
 *     uploads
 * @param remote whether a document's {@code filePath} names a remote the connector run alone can
 *     read again, rather than a file this machine reads
 * @param deepLink whether a document's {@code filePath} is an address a reader can open
 * @param pushIntake the push intake the connector offers, {@code null} for none
 * @param fullSyncInterval the instance-wide rhythm of the connector's full reconciliation, which a
 *     library may lengthen; {@code null} for a connector whose every run is a full one
 */
public record SourceConnectorDescriptor(
    SourceType type,
    String displayName,
    boolean indexingRun,
    boolean remote,
    boolean deepLink,
    PushIntake pushIntake,
    Duration fullSyncInterval) {

  public SourceConnectorDescriptor {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(displayName, "displayName");
    if (deepLink && !remote) {
      throw new IllegalArgumentException("only a remote document can carry a deep link");
    }
  }

  /**
   * A run-based connector whose documents are remote addresses a reader can open, without push
   * intake or full-sync rhythm; the {@code with...} methods adjust it.
   */
  public static SourceConnectorDescriptor remoteRun(SourceType type, String displayName) {
    return new SourceConnectorDescriptor(type, displayName, true, true, true, null, null);
  }

  /** A run-based connector over files this machine reads itself. */
  public static SourceConnectorDescriptor localRun(SourceType type, String displayName) {
    return new SourceConnectorDescriptor(type, displayName, true, false, false, null, null);
  }

  public SourceConnectorDescriptor withoutDeepLink() {
    return new SourceConnectorDescriptor(
        type, displayName, indexingRun, remote, false, pushIntake, fullSyncInterval);
  }

  public SourceConnectorDescriptor withPushIntake(PushIntake intake) {
    return new SourceConnectorDescriptor(
        type, displayName, indexingRun, remote, deepLink, intake, fullSyncInterval);
  }

  public SourceConnectorDescriptor withFullSyncInterval(Duration interval) {
    return new SourceConnectorDescriptor(
        type, displayName, indexingRun, remote, deepLink, pushIntake, interval);
  }
}
