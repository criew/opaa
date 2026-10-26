package io.opaa.externalaccess.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.ExternalAccessSettingsResponse;
import io.opaa.externalaccess.ExternalAccessDefaults;
import io.opaa.externalaccess.ExternalAccessSettings.Values;
import io.opaa.externalaccess.ExternalAccessSettingsService.View;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every field of the response is filled from the domain view (AGENTS.md, "API & DTO-Konvention"):
 * the service tests assert against {@link Values}, so without this test nothing would check that
 * the administration actually sees them.
 */
class ExternalAccessSettingsResponseMapperTest {

  @Test
  void carriesEveryValueTheAdministrationShows() {
    Instant changedAt = Instant.parse("2026-09-18T07:30:00Z");
    View view =
        new View(
            new Values(true, 30, 120, List.of("10.0.0.0/8", "::1/128"), 900, "Zuerst suchen.", 30),
            364,
            changedAt,
            "Frau Vogel");

    ExternalAccessSettingsResponse response = ExternalAccessSettingsResponseMapper.toResponse(view);

    assertThat(response.getEnabled()).isTrue();
    assertThat(response.getTokenMaxLifetimeDays()).isEqualTo(30);
    assertThat(response.getTokenRateLimitPerHour()).isEqualTo(120);
    assertThat(response.getAllowedCidrs()).containsExactly("10.0.0.0/8", "::1/128");
    assertThat(response.getMassRetrievalAlertThreshold()).isEqualTo(900);
    assertThat(response.getServerInstructions()).isEqualTo("Zuerst suchen.");
    assertThat(response.getDefaultServerInstructions())
        .isEqualTo(ExternalAccessDefaults.SERVER_INSTRUCTIONS);
    assertThat(response.getReleaseReminderLeadDays()).isEqualTo(30);
    assertThat(response.getReleaseReminderLeadDaysOverridden()).isTrue();
    assertThat(response.getDefaultReleaseReminderLeadDays()).isEqualTo(14);
    assertThat(response.getMaxReleaseReminderLeadDays()).isEqualTo(364);
    assertThat(response.getStoredReleaseReminderLeadDays()).isEqualTo(30);
    assertThat(response.getUpdatedAt()).isEqualTo(changedAt);
    assertThat(response.getUpdatedBy()).isEqualTo("Frau Vogel");
  }

  /** Without an own lead the factory default is in force, never more than the bound allows. */
  @Test
  void withoutAnOwnLeadTheFactoryDefaultIsInForceWithinTheBound() {
    View factory = new View(Values.defaults(), 364, Instant.parse("2026-09-18T07:30:00Z"), null);
    View narrow = new View(Values.defaults(), 9, Instant.parse("2026-09-18T07:30:00Z"), null);

    ExternalAccessSettingsResponse response =
        ExternalAccessSettingsResponseMapper.toResponse(factory);
    assertThat(response.getReleaseReminderLeadDays()).isEqualTo(14);
    assertThat(response.getReleaseReminderLeadDaysOverridden()).isFalse();
    assertThat(ExternalAccessSettingsResponseMapper.toResponse(narrow).getReleaseReminderLeadDays())
        .isEqualTo(9);
  }

  @Test
  void aChangeByTheInstallationItselfNamesNobody() {
    View view = new View(Values.defaults(), 364, Instant.parse("2026-09-18T07:30:00Z"), null);

    ExternalAccessSettingsResponse response = ExternalAccessSettingsResponseMapper.toResponse(view);

    assertThat(response.getUpdatedBy()).isNull();
    assertThat(response.getEnabled()).isFalse();
    assertThat(response.getAllowedCidrs()).isEqualTo(ExternalAccessDefaults.ALLOWED_CIDRS);
    assertThat(response.getServerInstructions())
        .isEqualTo(ExternalAccessDefaults.SERVER_INSTRUCTIONS);
  }
}
