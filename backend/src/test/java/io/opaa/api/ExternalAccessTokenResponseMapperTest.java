package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.AdminExternalAccessTokenResponse;
import io.opaa.api.dto.CreatedExternalAccessTokenResponse;
import io.opaa.api.dto.OwnExternalAccessTokenResponse;
import io.opaa.api.types.ExternalAccessTokenStatus;
import io.opaa.externalaccess.token.ExternalAccessToken;
import io.opaa.externalaccess.token.ExternalAccessTokenAdminService.ExternalAccessTokenAdminView;
import io.opaa.externalaccess.token.ExternalAccessTokenService.ExternalAccessTokenView;
import io.opaa.externalaccess.token.ExternalAccessTokenService.IssuedExternalAccessToken;
import io.opaa.externalaccess.token.ExternalAccessTokenService.SelectedLibrary;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Field-by-field coverage of {@link ExternalAccessTokenResponseMapper} (AGENTS.md, "API &
 * DTO-Konvention"): without it nothing would assert that the administration's response really
 * carries no usage date and that the plain value really appears in the creation response only.
 */
class ExternalAccessTokenResponseMapperTest {

  private static final Instant CREATED = Instant.parse("2026-09-01T08:00:00Z");
  private static final Instant EXPIRES = Instant.parse("2026-12-01T08:00:00Z");
  private static final Instant NOW = Instant.parse("2026-09-18T08:00:00Z");

  private final UUID libraryId = UUID.randomUUID();
  private final UUID ownerId = UUID.randomUUID();

  private ExternalAccessToken token(LocalDate lastUsedOn) {
    ExternalAccessToken token =
        new ExternalAccessToken(
            ownerId,
            "Claude Code auf dem Dienstrechner",
            "opaa_pat_abc123",
            "0".repeat(64),
            CREATED,
            EXPIRES,
            List.of(libraryId));
    if (lastUsedOn != null) {
      token.touch(lastUsedOn);
    }
    return token;
  }

  private ExternalAccessTokenView view(ExternalAccessToken token) {
    return new ExternalAccessTokenView(token, Map.of(libraryId, "Vergaberecht"));
  }

  @Test
  void fillsEveryFieldOfTheCreationResponseIncludingThePlainValueOnce() {
    ExternalAccessToken token = token(null);

    CreatedExternalAccessTokenResponse response =
        ExternalAccessTokenResponseMapper.toCreated(
            new IssuedExternalAccessToken(
                token, "opaa_pat_geheim", Map.of(libraryId, "Vergaberecht")),
            NOW);

    assertThat(response.getId()).isEqualTo(token.getId());
    assertThat(response.getName()).isEqualTo("Claude Code auf dem Dienstrechner");
    assertThat(response.getPrefix()).isEqualTo("opaa_pat_abc123");
    assertThat(response.getToken()).isEqualTo("opaa_pat_geheim");
    assertThat(response.getCreatedAt()).isEqualTo(CREATED);
    assertThat(response.getExpiresAt()).isEqualTo(EXPIRES);
    assertThat(response.getStatus()).isEqualTo(ExternalAccessTokenStatus.ACTIVE);
    assertThat(response.getLibraries())
        .singleElement()
        .satisfies(
            library -> {
              assertThat(library.getId()).isEqualTo(libraryId);
              assertThat(library.getName()).isEqualTo("Vergaberecht");
              assertThat(library.getSuspended()).isFalse();
            });
  }

  @Test
  void fillsEveryFieldOfTheSelfViewIncludingTheDayOfUse() {
    ExternalAccessToken token = token(LocalDate.of(2026, 9, 17));

    OwnExternalAccessTokenResponse response =
        ExternalAccessTokenResponseMapper.toOwn(view(token), NOW);

    assertThat(response.getId()).isEqualTo(token.getId());
    assertThat(response.getName()).isEqualTo("Claude Code auf dem Dienstrechner");
    assertThat(response.getPrefix()).isEqualTo("opaa_pat_abc123");
    assertThat(response.getCreatedAt()).isEqualTo(CREATED);
    assertThat(response.getExpiresAt()).isEqualTo(EXPIRES);
    assertThat(response.getLastUsedOn()).isEqualTo("2026-09-17");
    assertThat(response.getStatus()).isEqualTo(ExternalAccessTokenStatus.ACTIVE);
    assertThat(response.getLibraries()).hasSize(1);
    assertThat(response.toString()).doesNotContain("0".repeat(64));
  }

  @Test
  void leavesTheDayOfUseUnsetWhenThereIsNone() {
    assertThat(ExternalAccessTokenResponseMapper.toOwn(view(token(null)), NOW).getLastUsedOn())
        .isNull();
  }

  @Test
  void fillsEveryFieldOfTheAdministrationsRowAndHasNoUsageDateAtAll() {
    ExternalAccessToken token = token(LocalDate.of(2026, 9, 17));

    AdminExternalAccessTokenResponse response =
        ExternalAccessTokenResponseMapper.toAdmin(
            new ExternalAccessTokenAdminView(
                token,
                "Erika Mustermann",
                List.of(new SelectedLibrary(libraryId, "Vergaberecht", true))),
            NOW);

    assertThat(response.getId()).isEqualTo(token.getId());
    assertThat(response.getOwnerUserId()).isEqualTo(ownerId);
    assertThat(response.getOwnerDisplayName()).isEqualTo("Erika Mustermann");
    assertThat(response.getName()).isEqualTo("Claude Code auf dem Dienstrechner");
    assertThat(response.getCreatedAt()).isEqualTo(CREATED);
    assertThat(response.getExpiresAt()).isEqualTo(EXPIRES);
    assertThat(response.getStatus()).isEqualTo(ExternalAccessTokenStatus.ACTIVE);
    assertThat(response.getLibraries())
        .singleElement()
        .satisfies(library -> assertThat(library.getSuspended()).isTrue());

    // The promise is structural, not a matter of what this mapper happens to set: the response
    // type has no field for a usage date and none for the value.
    assertThat(fieldNames(AdminExternalAccessTokenResponse.class))
        .doesNotContain("lastUsedOn", "token", "prefix", "usageCount");
  }

  @Test
  void derivesTheStateFromTheRow() {
    ExternalAccessToken expired = token(null);

    assertThat(
            ExternalAccessTokenResponseMapper.toOwn(view(expired), EXPIRES.plusSeconds(1))
                .getStatus())
        .isEqualTo(ExternalAccessTokenStatus.EXPIRED);

    ExternalAccessToken revoked = token(null);
    revoked.revoke(
        io.opaa.externalaccess.token.ExternalAccessTokenRevocationReason.OWNER,
        CREATED.plus(Duration.ofDays(1)));
    assertThat(ExternalAccessTokenResponseMapper.toOwn(view(revoked), NOW).getStatus())
        .isEqualTo(ExternalAccessTokenStatus.REVOKED);

    ExternalAccessToken blocked = token(null);
    blocked.revoke(
        io.opaa.externalaccess.token.ExternalAccessTokenRevocationReason.ADMIN,
        CREATED.plus(Duration.ofDays(1)));
    assertThat(ExternalAccessTokenResponseMapper.toOwn(view(blocked), NOW).getStatus())
        .isEqualTo(ExternalAccessTokenStatus.BLOCKED);
  }

  private static List<String> fieldNames(Class<?> type) {
    return java.util.Arrays.stream(type.getDeclaredFields()).map(Field::getName).toList();
  }
}
