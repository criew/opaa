package io.opaa.indexing.source.sharepoint;

import io.opaa.compliance.DataFlow;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.msgraph.GraphClient;
import io.opaa.msgraph.GraphException;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The OneDrive Graph names under {@code me/drive} for the account the access token belongs to
 * (ADR-0058, Entscheidung 3). Only that drive, and only of {@code driveType = business}, is read
 * for a OneDrive library; {@link #require} is asked before any request names a drive.
 *
 * @param owner the owner's e-mail address, else display name; {@code null} when Graph names none
 */
@DataFlow("source-onedrive")
record OwnDrive(String id, String driveType, String name, String owner) {

  static final String NOT_BUSINESS =
      "Das verbundene Microsoft-Konto hat kein OneDrive for Business. Private Microsoft-Konten"
          + " werden noch nicht unterstützt; bitte mit dem Arbeits- oder Schulkonto verbinden.";
  static final String OTHER_DRIVE =
      "Das verbundene Microsoft-Konto gehört zu einem anderen OneDrive als dem dieser Bibliothek."
          + " Bitte mit dem Konto verbinden, dem das OneDrive gehört, oder das OneDrive neu wählen.";
  static final String NO_DRIVE =
      "Für das verbundene Microsoft-Konto ist kein OneDrive bereitgestellt.";
  static final String NOT_READABLE =
      "Das verbundene Microsoft-Konto darf sein OneDrive nicht lesen; die Anwendung braucht die"
          + " delegierte Berechtigung Files.Read.";

  /** The OneDrive of the token's account. */
  static OwnDrive read(GraphClient graph) throws GraphException, InterruptedException {
    JsonNode drive = graph.get("me/drive", Map.of("$select", "id,driveType,name,owner"));
    JsonNode user = drive.path("owner").path("user");
    String email = text(user, "email");
    return new OwnDrive(
        text(drive, "id"),
        text(drive, "driveType"),
        text(drive, "name"),
        email != null ? email : text(user, "displayName"));
  }

  /**
   * Refuses unless {@code driveId} is the token's own OneDrive for Business: a mismatch, another
   * kind of drive or none at all is {@link FileAccessException.ContainerUnlistable}, a rejected
   * token {@link FileAccessException.CredentialsRejected} (one without consent {@link
   * FileAccessException.PermissionMissing}), any other failure {@link
   * FileAccessException.RunEnding}.
   */
  static void require(GraphClient graph, String driveId)
      throws FileAccessException, InterruptedException {
    OwnDrive own;
    try {
      own = read(graph);
    } catch (GraphException e) {
      throw switch (e.kind()) {
        case UNAUTHORIZED -> DriveFileStore.unauthorized(e);
        case NOT_FOUND -> new FileAccessException.ContainerUnlistable(NO_DRIVE);
        case FORBIDDEN ->
            new FileAccessException.ContainerUnlistable(
                e.consentMissing() ? e.getMessage() : NOT_READABLE);
        default -> new FileAccessException.RunEnding(e.getMessage());
      };
    }
    String refusal = own.refusal(driveId);
    if (refusal != null) {
      throw new FileAccessException.ContainerUnlistable(refusal);
    }
  }

  /** Why {@code driveId} is not read with this account, {@code null} when it is. */
  String refusal(String driveId) {
    if (!DriveRule.ONEDRIVE.admits(driveType)) {
      return NOT_BUSINESS;
    }
    return id != null && id.equals(driveId) ? null : OTHER_DRIVE;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() || !value.isValueNode() ? null : value.asString();
  }
}
