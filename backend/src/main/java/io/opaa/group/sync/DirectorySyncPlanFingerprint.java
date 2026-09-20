package io.opaa.group.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The print of a synchronisation plan as it was shown - what "confirmed against a fresh snapshot"
 * compares (ADR-0036, Entscheidung 3, #1816).
 *
 * <p>It covers exactly the changes a plan proposes, each in a canonical, order-independent form:
 * groups created, renamed and dissolved, and per group which user is added or removed. It
 * deliberately covers neither the moment the plan was computed nor its message or outcome - two
 * runs minutes apart that would do the identical thing are the same plan, and re-presenting them
 * for a differing timestamp would make confirmation impossible in practice.
 */
final class DirectorySyncPlanFingerprint {

  private DirectorySyncPlanFingerprint() {}

  static String of(SyncReport report) {
    List<String> lines = new ArrayList<>();
    for (GroupChange change : report.groupsCreated()) {
      lines.add("create" + change.externalId() + "" + change.name());
    }
    for (GroupChange change : report.groupsRenamed()) {
      lines.add("rename" + change.externalId() + "" + change.name());
    }
    for (GroupChange change : report.groupsDissolved()) {
      lines.add("dissolve" + change.externalId());
    }
    for (MembershipChange change : report.membershipChanges()) {
      for (UserRef added : change.added()) {
        lines.add("add" + change.externalId() + "" + added.id());
      }
      for (UserRef removed : change.removed()) {
        lines.add("remove" + change.externalId() + "" + removed.id());
      }
    }
    lines.sort(String::compareTo);
    return sha256(String.join("", lines));
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
