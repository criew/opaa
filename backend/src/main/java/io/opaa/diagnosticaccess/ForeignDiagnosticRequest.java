package io.opaa.diagnosticaccess;

import io.opaa.api.types.DiagnosticTargetKind;
import java.util.Set;
import java.util.UUID;

/**
 * What a caller asks for when it wants to run a diagnosis in a foreign rights context.
 *
 * <p>{@code targetUserId} and {@code justification} belong to {@link DiagnosticTargetKind#USER};
 * {@code profileLabel} and {@code profileLibraryIds} belong to {@link
 * DiagnosticTargetKind#PERMISSION_PROFILE}, whose library set the caller resolves - a profile is a
 * role plus a library set and has no persistence of its own here. {@link
 * ForeignDiagnosticContextService} rejects a request that mixes the two.
 */
public record ForeignDiagnosticRequest(
    DiagnosticTargetKind targetKind,
    UUID targetUserId,
    String profileLabel,
    Set<UUID> profileLibraryIds,
    String testQuestion,
    String justification) {

  public static ForeignDiagnosticRequest forUser(
      UUID targetUserId, String testQuestion, String justification) {
    return new ForeignDiagnosticRequest(
        DiagnosticTargetKind.USER, targetUserId, null, Set.of(), testQuestion, justification);
  }

  public static ForeignDiagnosticRequest forProfile(
      String profileLabel, Set<UUID> profileLibraryIds, String testQuestion) {
    return new ForeignDiagnosticRequest(
        DiagnosticTargetKind.PERMISSION_PROFILE,
        null,
        profileLabel,
        Set.copyOf(profileLibraryIds),
        testQuestion,
        null);
  }
}
