package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.permission.PermissionTransferPreviewToken.Check;
import io.opaa.security.LocalAuthKeyService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The signed preview id of a permission transfer (ADR-0053, Entscheidung 10): it holds for the
 * caller, the order and the rows it was issued for, until it expires, on any instance sharing the
 * secret - and for nothing else.
 */
class PermissionTransferPreviewTokenTest {

  private static final String SECRET = "permission-transfer-preview-test-secret-0123456789";
  private static final String ORDER = "GROUP:a>GROUP:b:ASSET_GRANTS";
  private static final String PRINT = "print-of-the-rows";
  private static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");
  private static final Instant UNTIL = NOW.plus(Duration.ofMinutes(30));

  private final PermissionTransferPreviewToken instanceA =
      new PermissionTransferPreviewToken(new LocalAuthKeyService(SECRET));
  private final PermissionTransferPreviewToken instanceB =
      new PermissionTransferPreviewToken(new LocalAuthKeyService(SECRET));
  private final UUID caller = UUID.randomUUID();

  @Test
  void aPreviewIssuedOnOneInstanceHoldsOnAnother() {
    String previewId = instanceA.issue(caller, ORDER, PRINT, UNTIL);

    assertThat(instanceB.check(previewId, caller, ORDER, PRINT, NOW)).isEqualTo(Check.VALID);
    assertThat(instanceB.check(previewId, caller, ORDER, PRINT, UNTIL)).isEqualTo(Check.VALID);
  }

  @Test
  void anotherCallerAnotherOrderOrAnExpiredPreviewHasNone() {
    String previewId = instanceA.issue(caller, ORDER, PRINT, UNTIL);

    assertThat(instanceB.check(previewId, UUID.randomUUID(), ORDER, PRINT, NOW))
        .isEqualTo(Check.NONE);
    assertThat(instanceB.check(previewId, caller, "GROUP:a>GROUP:c:ASSET_GRANTS", PRINT, NOW))
        .isEqualTo(Check.NONE);
    assertThat(instanceB.check(previewId, caller, ORDER, PRINT, UNTIL.plusSeconds(1)))
        .isEqualTo(Check.NONE);
  }

  @Test
  void changedRowsMakeAValidPreviewOutdated() {
    String previewId = instanceA.issue(caller, ORDER, PRINT, UNTIL);

    assertThat(instanceB.check(previewId, caller, ORDER, "another-print", NOW))
        .isEqualTo(Check.OUTDATED);
  }

  /** The payload is readable; only the signature makes it binding. */
  @Test
  void aForgedOrMalformedPreviewHasNone() {
    String previewId = instanceA.issue(caller, ORDER, PRINT, UNTIL);
    String payload = previewId.substring(0, previewId.indexOf('.'));
    String forgedPayload =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ("v1|"
                        + caller
                        + "|"
                        + ORDER
                        + "|"
                        + PRINT
                        + "|"
                        + UNTIL.plusSeconds(3600).getEpochSecond())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String otherKey =
        new PermissionTransferPreviewToken(new LocalAuthKeyService(SECRET + "-rotated"))
            .issue(caller, ORDER, PRINT, UNTIL);

    for (String presented :
        new String[] {
          null,
          "",
          UUID.randomUUID().toString(),
          payload,
          forgedPayload + previewId.substring(previewId.indexOf('.')),
          previewId + ".x",
          "%%%." + previewId.substring(previewId.indexOf('.') + 1),
          otherKey
        }) {
      assertThat(instanceB.check(presented, caller, ORDER, PRINT, NOW))
          .as("presented %s", presented)
          .isEqualTo(Check.NONE);
    }
  }
}
