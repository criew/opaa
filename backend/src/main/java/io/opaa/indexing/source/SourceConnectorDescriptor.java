package io.opaa.indexing.source;

import io.opaa.knowledge.SourceType;
import java.time.Duration;
import java.util.Objects;

/**
 * What a {@link SourceConnector} is, as the administration asks it instead of branching on the
 * source type (ADR-0038). {@code GET /source-types} lists it.
 *
 * @param displayName the German name of the source type, e.g. "Confluence"
 * @param indexingRun whether a run fills the library - only a library with a run can be scheduled
 *     and triggered
 * @param remote whether a document's {@code filePath} names a remote the connector run alone can
 *     read again, rather than a file this machine reads
 * @param deepLink whether a document's {@code filePath} is an address a reader can open
 * @param uploads whether the library is curated document by document through uploads - created
 *     under the upload capability, without share cap, blocked from deletion while it holds
 *     documents; every other library is a connector library. Only {@link SourceType#UPLOAD} may
 *     accept uploads: the upload store, its folders and originals are keyed to that type
 * @param pushIntake the push intake the connector offers, {@code null} for none
 * @param fullSyncInterval the instance-wide rhythm of the connector's full reconciliation, which a
 *     library may lengthen; {@code null} for a connector whose every run is a full one
 * @param profileDeclaration how the connector stands to connection profiles ("Profilangabe"),
 *     including the service account key the core signs with for a library's own key
 */
public record SourceConnectorDescriptor(
    SourceType type,
    String displayName,
    boolean indexingRun,
    boolean remote,
    boolean deepLink,
    boolean uploads,
    PushIntake pushIntake,
    Duration fullSyncInterval,
    ProfileDeclaration profileDeclaration) {

  public SourceConnectorDescriptor {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(profileDeclaration, "profileDeclaration");
    if (deepLink && !remote) {
      throw new IllegalArgumentException("only a remote document can carry a deep link");
    }
    if (uploads && (indexingRun || remote)) {
      throw new IllegalArgumentException("a library filled by uploads has no run and no remote");
    }
    if (profileDeclaration.serviceAccountKey() != null && !remote) {
      throw new IllegalArgumentException(
          "only a remote source signs in with a service account key");
    }
  }

  /** A connector without profiles. */
  public SourceConnectorDescriptor(
      SourceType type,
      String displayName,
      boolean indexingRun,
      boolean remote,
      boolean deepLink,
      boolean uploads,
      PushIntake pushIntake,
      Duration fullSyncInterval) {
    this(
        type,
        displayName,
        indexingRun,
        remote,
        deepLink,
        uploads,
        pushIntake,
        fullSyncInterval,
        ProfileDeclaration.forbidden());
  }

  /**
   * A run-based connector whose documents are remote addresses a reader can open, without push
   * intake or full-sync rhythm; the {@code with...} methods adjust it.
   */
  public static SourceConnectorDescriptor remoteRun(SourceType type, String displayName) {
    return new SourceConnectorDescriptor(type, displayName, true, true, true, false, null, null);
  }

  /** A run-based connector over files this machine reads itself. */
  public static SourceConnectorDescriptor localRun(SourceType type, String displayName) {
    return new SourceConnectorDescriptor(type, displayName, true, false, false, false, null, null);
  }

  /** The library curated through uploads, without run and without source configuration. */
  public static SourceConnectorDescriptor acceptingUploads(SourceType type, String displayName) {
    return new SourceConnectorDescriptor(type, displayName, false, false, false, true, null, null);
  }

  public SourceConnectorDescriptor withoutDeepLink() {
    return new SourceConnectorDescriptor(
        type,
        displayName,
        indexingRun,
        remote,
        false,
        uploads,
        pushIntake,
        fullSyncInterval,
        profileDeclaration);
  }

  public SourceConnectorDescriptor withPushIntake(PushIntake intake) {
    return new SourceConnectorDescriptor(
        type,
        displayName,
        indexingRun,
        remote,
        deepLink,
        uploads,
        intake,
        fullSyncInterval,
        profileDeclaration);
  }

  public SourceConnectorDescriptor withFullSyncInterval(Duration interval) {
    return new SourceConnectorDescriptor(
        type,
        displayName,
        indexingRun,
        remote,
        deepLink,
        uploads,
        pushIntake,
        interval,
        profileDeclaration);
  }

  /** The connector stands to connection profiles as {@code declaration} says. */
  public SourceConnectorDescriptor withProfiles(ProfileDeclaration declaration) {
    return new SourceConnectorDescriptor(
        type,
        displayName,
        indexingRun,
        remote,
        deepLink,
        uploads,
        pushIntake,
        fullSyncInterval,
        declaration);
  }

  /** Whether a library of this connector may be connected through a profile. */
  public boolean admitsProfiles() {
    return profileDeclaration.admitsProfiles();
  }
}
