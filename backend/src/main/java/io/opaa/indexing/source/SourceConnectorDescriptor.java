package io.opaa.indexing.source;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.knowledge.SourceType;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

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
 * @param profileSupport how the connector stands to connection profiles ("Profilangabe")
 * @param authMethods the sign-in methods a profile of this connector may choose; empty exactly when
 *     profiles are forbidden
 * @param serviceAccountKey the sign-in by service account key the core performs for a library
 *     without profile, {@code null} when {@code sourceCredentials} reach the connector as stored
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
    ConnectionProfileSupport profileSupport,
    Set<ConnectionAuthMethod> authMethods,
    ServiceAccountKeyAuth serviceAccountKey) {

  public SourceConnectorDescriptor {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(profileSupport, "profileSupport");
    authMethods =
        authMethods == null || authMethods.isEmpty()
            ? Set.of()
            : Set.copyOf(EnumSet.copyOf(authMethods));
    if ((profileSupport == ConnectionProfileSupport.FORBIDDEN) != authMethods.isEmpty()) {
      throw new IllegalArgumentException(
          "a connector names sign-in methods exactly when it admits profiles");
    }
    if (uploads && profileSupport != ConnectionProfileSupport.FORBIDDEN) {
      throw new IllegalArgumentException("a library filled by uploads has no profile");
    }
    if (deepLink && !remote) {
      throw new IllegalArgumentException("only a remote document can carry a deep link");
    }
    if (uploads && (indexingRun || remote)) {
      throw new IllegalArgumentException("a library filled by uploads has no run and no remote");
    }
    if (serviceAccountKey != null && !remote) {
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
        ConnectionProfileSupport.FORBIDDEN,
        Set.of(),
        null);
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
        profileSupport,
        authMethods,
        serviceAccountKey);
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
        profileSupport,
        authMethods,
        serviceAccountKey);
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
        profileSupport,
        authMethods,
        serviceAccountKey);
  }

  /** The connector admits profiles as {@code support} says, signing in with {@code methods}. */
  public SourceConnectorDescriptor withProfiles(
      ConnectionProfileSupport support, Set<ConnectionAuthMethod> methods) {
    return new SourceConnectorDescriptor(
        type,
        displayName,
        indexingRun,
        remote,
        deepLink,
        uploads,
        pushIntake,
        fullSyncInterval,
        support,
        methods,
        serviceAccountKey);
  }

  /** The connector signs in with a service account key the core exchanges for an access token. */
  public SourceConnectorDescriptor withServiceAccountKey(ServiceAccountKeyAuth auth) {
    return new SourceConnectorDescriptor(
        type,
        displayName,
        indexingRun,
        remote,
        deepLink,
        uploads,
        pushIntake,
        fullSyncInterval,
        profileSupport,
        authMethods,
        auth);
  }

  /** Whether a library of this connector may be connected through a profile. */
  public boolean admitsProfiles() {
    return profileSupport != ConnectionProfileSupport.FORBIDDEN;
  }
}
