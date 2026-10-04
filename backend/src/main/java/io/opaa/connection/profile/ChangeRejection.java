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

  /** Why the connector refused, as it reports it. */
  public enum Category {
    /** The target is out of reach or refuses the sign-in ({@code SourceTargetRefusedException}). */
    CONNECTION,
    /** A setting is inadmissible - every other refusal. */
    SETTINGS
  }

  public ChangeRejection {
    Objects.requireNonNull(libraryId, "libraryId");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(message, "message");
  }

  /**
   * The 400 refusing a whole profile change for {@code rejections}, never empty: number and
   * categories only. The connectors' reasons leave only through the preview's mapper.
   */
  static ValidationException refusingProfileChange(List<ChangeRejection> rejections) {
    long connection =
        rejections.stream()
            .filter(rejection -> rejection.category() == Category.CONNECTION)
            .count();
    long settings = rejections.size() - connection;
    return new ValidationException(
        "Der Konnektor lehnt die Änderung des Zugangs für "
            + rejections.size()
            + (rejections.size() == 1 ? " Bibliothek" : " Bibliotheken")
            + " ab (Verbindung: "
            + connection
            + ", Einstellungen: "
            + settings
            + "); nichts wurde geändert. Die Gründe nennt die Vorschau der Auswirkungen.",
        PROFILE_CHANGE_REJECTED);
  }
}
