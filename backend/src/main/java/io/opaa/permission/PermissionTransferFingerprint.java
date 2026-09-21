package io.opaa.permission;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The print of a transfer as it was previewed - what "confirmed against the state that was shown"
 * compares (#1834, ADR-0036 Entscheidung 10; the same idea {@code DirectorySyncPlanFingerprint}
 * serves for a synchronisation plan).
 *
 * <p>It covers exactly the rows the operation would move, each in a canonical, order-independent
 * form: every grant with the role and the expiry it carries, every space membership with its role,
 * every capability, every owned asset and every stewarded group. A grant whose role changed between
 * preview and confirmation therefore yields a different print, which counting alone would miss.
 */
final class PermissionTransferFingerprint {

  private PermissionTransferFingerprint() {}

  /** Separator that cannot occur in a uuid, a role name or an instant. */
  private static final String SEPARATOR = "\u001f";

  static String of(PermissionTransferSnapshot snapshot) {
    List<String> lines = new ArrayList<>();
    for (AssetGrant grant : snapshot.grants()) {
      lines.add(
          line(
              "grant",
              grant.getAssetType().value(),
              grant.getAssetId().toString(),
              grant.getRole().name(),
              String.valueOf(grant.getExpiresAt())));
    }
    for (GroupSpaceMembershipRef membership : snapshot.spaceMemberships()) {
      lines.add(line("space", membership.spaceId().toString()));
    }
    for (CapabilityGrant capability : snapshot.capabilities()) {
      lines.add(line("capability", capability.getCapability().name()));
    }
    snapshot
        .ownedAssets()
        .forEach(
            (assetType, assetIds) -> {
              for (var assetId : assetIds) {
                lines.add(line("owns", assetType.value(), assetId.toString()));
              }
            });
    for (var groupId : snapshot.stewardedGroups()) {
      lines.add(line("stewards", groupId.toString()));
    }
    lines.sort(String::compareTo);
    return sha256(String.join(SEPARATOR, lines));
  }

  private static String line(String kind, String... parts) {
    return kind + SEPARATOR + String.join(SEPARATOR, parts);
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every JVM", e);
    }
  }
}
