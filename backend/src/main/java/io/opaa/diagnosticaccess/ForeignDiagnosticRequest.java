package io.opaa.diagnosticaccess;

import io.opaa.api.types.DiagnosticTargetKind;
import java.util.UUID;

/**
 * What a caller asks for when it wants to run a diagnosis in a foreign rights context.
 *
 * <p>{@code targetUserId} and {@code justification} belong to {@link DiagnosticTargetKind#USER};
 * {@code profileGroupId} belongs to {@link DiagnosticTargetKind#PERMISSION_PROFILE} and names the
 * group a profile is (see {@code SearchDiagnosisService.PermissionProfile}), never a free-text
 * label - the protocol entry then holds an identifier that carries no personal data by construction
 * rather than by convention of the caller. Its library set is resolved from that group, not passed
 * in, so the recorded target and the searched libraries cannot disagree. {@link
 * ForeignDiagnosticContextService} rejects a request that mixes the two kinds.
 */
public record ForeignDiagnosticRequest(
    DiagnosticTargetKind targetKind,
    UUID targetUserId,
    UUID profileGroupId,
    String testQuestion,
    String justification) {

  public static ForeignDiagnosticRequest forUser(
      UUID targetUserId, String testQuestion, String justification) {
    return new ForeignDiagnosticRequest(
        DiagnosticTargetKind.USER, targetUserId, null, testQuestion, justification);
  }

  public static ForeignDiagnosticRequest forProfile(UUID profileGroupId, String testQuestion) {
    return new ForeignDiagnosticRequest(
        DiagnosticTargetKind.PERMISSION_PROFILE, null, profileGroupId, testQuestion, null);
  }
}
