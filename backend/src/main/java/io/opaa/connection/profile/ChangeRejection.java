package io.opaa.connection.profile;

import io.opaa.common.ValidationException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A library's connector refuses the configuration a transition would give it. A list of them is the
 * answer to a whole change; who may read which entry decides the caller, not the list.
 *
 * @param message the connector's German reason; it may name folders or spaces of the library
 */
public record ChangeRejection(UUID libraryId, Category category, String message) {

  /** Stable code of the refusal of a profile change. */
  public static final String PROFILE_CHANGE_REJECTED = "CONNECTION_PROFILE_CHANGE_REJECTED";

  private static final int REASONS_NAMED = 3;

  /** What of the effective configuration the connector refused. */
  public enum Category {
    /** A new address, proxy or TLS switch, checked with the library's credentials. */
    CONNECTION,
    /** The connector settings, such as a profile default. */
    SETTINGS
  }

  public ChangeRejection {
    Objects.requireNonNull(libraryId, "libraryId");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(message, "message");
  }

  /** The 400 refusing a whole profile change for {@code rejections}, never empty. */
  static ValidationException refusingProfileChange(List<ChangeRejection> rejections) {
    List<String> reasons =
        rejections.stream().map(ChangeRejection::message).distinct().limit(REASONS_NAMED).toList();
    return new ValidationException(
        "Der Konnektor lehnt die Änderung des Zugangs für "
            + rejections.size()
            + (rejections.size() == 1 ? " Bibliothek" : " Bibliotheken")
            + " ab; nichts wurde geändert. "
            + String.join(" ", reasons),
        PROFILE_CHANGE_REJECTED);
  }
}
