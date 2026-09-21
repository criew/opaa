package io.opaa.group.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The print of a synchronisation plan as it was shown - what "confirmed against a fresh snapshot"
 * compares (ADR-0036, Entscheidung 3, #1816).
 *
 * <p>It covers exactly the changes a plan proposes, each in a canonical, order-independent form:
 * groups created, renamed, dissolved and reactivated, the parent unit and the path the directory
 * reports for each of them, per group which user is added or removed, and which account the run
 * would lock or unlock (#1818). Reactivation, hierarchy and path change no membership and are
 * therefore not in the report, but a run applies them - a print blind to them would let a
 * confirmation write something nobody was shown. It deliberately covers neither the moment the plan
 * was computed nor its message or outcome - two runs minutes apart that would do the identical
 * thing are the same plan, and re-presenting them for a differing timestamp would make confirmation
 * impossible in practice.
 */
final class DirectorySyncPlanFingerprint {

  private DirectorySyncPlanFingerprint() {}

  /** Separator that cannot occur in an external id, a name or a uuid. */
  private static final String SEPARATOR = "\u001f";

  static String of(
      SyncReport report,
      Collection<String> reactivatedExternalIds,
      Map<String, String> parentExternalIdByExternalId,
      Map<String, String> sourcePathByExternalId) {
    List<String> lines = new ArrayList<>();
    for (String externalId : reactivatedExternalIds) {
      lines.add(line("reactivate", externalId));
    }
    parentExternalIdByExternalId.forEach(
        (externalId, parentExternalId) ->
            lines.add(line("parent", externalId, String.valueOf(parentExternalId))));
    sourcePathByExternalId.forEach(
        (externalId, sourcePath) ->
            lines.add(line("path", externalId, String.valueOf(sourcePath))));
    for (GroupChange change : report.groupsCreated()) {
      lines.add(line("create", change.externalId(), change.name()));
    }
    for (GroupChange change : report.groupsRenamed()) {
      lines.add(line("rename", change.externalId(), change.name()));
    }
    for (GroupChange change : report.groupsDissolved()) {
      lines.add(line("dissolve", change.externalId()));
    }
    for (MembershipChange change : report.membershipChanges()) {
      for (UserRef added : change.added()) {
        lines.add(line("add", change.externalId(), added.id().toString()));
      }
      for (UserRef removed : change.removed()) {
        lines.add(line("remove", change.externalId(), removed.id().toString()));
      }
    }
    for (UserRef locked : report.accountsLocked()) {
      lines.add(line("lock", locked.id().toString()));
    }
    for (UserRef unlocked : report.accountsUnlocked()) {
      lines.add(line("unlock", unlocked.id().toString()));
    }
    lines.sort(String::compareTo);
    return sha256(String.join(SEPARATOR, lines));
  }

  private static String line(String kind, String... parts) {
    return kind + SEPARATOR + String.join(SEPARATOR, parts);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JRE", e);
    }
  }
}
