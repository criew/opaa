package io.opaa.connection.profile;

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
 */
public record SourceDraft(
    SourceType type, UUID profileId, UUID libraryId, SourceSettings requested, DraftOwner owner) {

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
