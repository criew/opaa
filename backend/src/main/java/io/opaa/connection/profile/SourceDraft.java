package io.opaa.connection.profile;

import io.opaa.connection.token.SecretOwner.PendingConsent;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.SourceType;
import java.util.Objects;
import java.util.UUID;

/**
 * A source configuration before it is saved, as creation, connection test and listing hand it in.
 *
 * @param profileId the profile the draft runs through; {@code null} means the current one of {@code
 *     libraryId}, and without a library its own address
 * @param libraryId the stored library the draft changes, {@code null} before one exists
 * @param requested the fields as sent, the connector settings read by the connector
 * @param owner whose secret the draft will hold
 * @param pending the consent its person gave on an OAuth profile before the library exists, {@code
 *     null} for none
 */
public record SourceDraft(
    SourceType type,
    UUID profileId,
    UUID libraryId,
    SourceSettings requested,
    DraftOwner owner,
    PendingConsent pending) {

  /** Whose secret a draft will hold: a library's, or a person's connected account. */
  public enum DraftOwner {
    LIBRARY,
    PERSON
  }

  public SourceDraft {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(requested, "requested");
    Objects.requireNonNull(owner, "owner");
  }

  /** A draft without a pending consent. */
  public SourceDraft(
      SourceType type, UUID profileId, UUID libraryId, SourceSettings requested, DraftOwner owner) {
    this(type, profileId, libraryId, requested, owner, null);
  }

  /** This draft, signing in with the pending consent {@code consent}. */
  public SourceDraft withPending(PendingConsent consent) {
    return new SourceDraft(type, profileId, libraryId, requested, owner, consent);
  }

  /** A person's connected account on {@code profileId}, before it is stored. */
  public static SourceDraft ofPerson(SourceType type, UUID profileId, SourceSettings requested) {
    return new SourceDraft(
        type, Objects.requireNonNull(profileId, "profileId"), null, requested, DraftOwner.PERSON);
  }

  /** A draft whose secret the library holds. */
  public static SourceDraft ofLibrary(
      SourceType type, UUID profileId, UUID libraryId, SourceSettings requested) {
    return new SourceDraft(type, profileId, libraryId, requested, DraftOwner.LIBRARY);
  }
}
