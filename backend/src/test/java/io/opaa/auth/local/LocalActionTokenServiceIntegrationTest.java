package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixtures.LocalAccount;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The single-use links (ADR-0033, Entscheidungen 3 and 11): only the HMAC of a raw token is stored,
 * a new link of the same purpose supersedes the open ones, redemption is atomic and consumes, an
 * expired or foreign-purpose token is nothing.
 */
@OpaaIntegrationTest
class LocalActionTokenServiceIntegrationTest {

  @Autowired private LocalActionTokenService service;
  @Autowired private LocalActionTokenRepository repository;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;

  private LocalAccountFixtures fixtures;
  private LocalAccount user;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    repository.deleteAll();
    user = fixtures.activeUser("erika-" + UUID.randomUUID() + "@stadt.example");
  }

  @AfterEach
  void tearDown() {
    repository.deleteAll();
    fixtures.cleanUp();
  }

  @Test
  void issuesARandomTokenStoresOnlyItsHashAndRedeemsItExactlyOnce() {
    LocalActionTokenService.IssuedActionToken issued =
        service.issue(user.id(), ActionTokenPurpose.SET_PASSWORD, Duration.ofHours(72));

    assertThat(issued.rawToken()).hasSizeGreaterThanOrEqualTo(43).doesNotContain("=");
    assertThat(issued.toString()).doesNotContain(issued.rawToken());
    assertThat(issued.expiresAt())
        .isAfter(Instant.now().plus(Duration.ofHours(71)))
        .isBefore(Instant.now().plus(Duration.ofHours(73)));
    assertThat(repository.findAll())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getTokenHash()).isNotEqualTo(issued.rawToken());
              assertThat(row.getUserId()).isEqualTo(user.id());
              assertThat(row.getPurpose()).isEqualTo(ActionTokenPurpose.SET_PASSWORD);
            });

    assertThat(service.findRedeemable(issued.rawToken(), ActionTokenPurpose.SET_PASSWORD))
        .isPresent();
    assertThat(service.findRedeemable(issued.rawToken(), ActionTokenPurpose.RESET_PASSWORD))
        .isEmpty();
    assertThat(service.redeem(issued.rawToken(), ActionTokenPurpose.SET_PASSWORD))
        .isPresent()
        .get()
        .extracting(LocalActionToken::getUserId)
        .isEqualTo(user.id());
    assertThat(service.redeem(issued.rawToken(), ActionTokenPurpose.SET_PASSWORD)).isEmpty();
    assertThat(service.findRedeemable(issued.rawToken(), ActionTokenPurpose.SET_PASSWORD))
        .isEmpty();
  }

  @Test
  void aNewLinkSupersedesTheOpenOnesOfTheSamePurposeOnly() {
    LocalActionTokenService.IssuedActionToken reset =
        service.issue(user.id(), ActionTokenPurpose.RESET_PASSWORD, Duration.ofMinutes(30));
    LocalActionTokenService.IssuedActionToken first =
        service.issue(user.id(), ActionTokenPurpose.SET_PASSWORD, Duration.ofHours(1));
    LocalActionTokenService.IssuedActionToken second =
        service.issue(user.id(), ActionTokenPurpose.SET_PASSWORD, Duration.ofHours(1));

    assertThat(service.findRedeemable(first.rawToken(), ActionTokenPurpose.SET_PASSWORD)).isEmpty();
    assertThat(service.findRedeemable(second.rawToken(), ActionTokenPurpose.SET_PASSWORD))
        .isPresent();
    assertThat(service.findRedeemable(reset.rawToken(), ActionTokenPurpose.RESET_PASSWORD))
        .isPresent();
  }

  @Test
  void anExpiredOrUnknownTokenIsNothing() {
    LocalActionTokenService.IssuedActionToken expired =
        service.issue(user.id(), ActionTokenPurpose.SET_PASSWORD, Duration.ofMillis(1));
    assertThat(service.findRedeemable(expired.rawToken(), ActionTokenPurpose.SET_PASSWORD))
        .isEmpty();
    assertThat(service.redeem(expired.rawToken(), ActionTokenPurpose.SET_PASSWORD)).isEmpty();
    assertThat(service.findRedeemable("nicht-vorhanden", ActionTokenPurpose.SET_PASSWORD))
        .isEmpty();
    assertThat(service.findRedeemable("", ActionTokenPurpose.SET_PASSWORD)).isEmpty();
    assertThat(service.findRedeemable(null, ActionTokenPurpose.SET_PASSWORD)).isEmpty();
  }
}
