package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code granted_by_user_id} answers "who procured the role this grant carries now" (#1052). These
 * tests pin the three halves of that contract on the entity itself, since {@code
 * LibraryAccessService#holdsIndependentOwnerRole} reads the field to tell a self-procured {@link
 * AssetRole#OWNER} from one somebody else conferred - and it discounts an expired grant entirely,
 * which is why reviving one counts as procuring it.
 */
class AssetGrantTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

  @Test
  void aRealRoleChangeCarriesTheChangerIntoTheGrant() {
    UUID originalGranter = UUID.randomUUID();
    UUID changer = UUID.randomUUID();
    AssetGrant grant = grantOf(AssetRole.VIEWER, null, originalGranter);

    grant.updateRole(AssetRole.OWNER, null, changer, NOW);

    assertThat(grant.getRole()).isEqualTo(AssetRole.OWNER);
    assertThat(grant.getGrantedByUserId()).isEqualTo(changer);
  }

  /**
   * An extension of one's own grant must not turn its holder into its own conferrer - that would
   * cost an independent owner the right to lift a diagnostics lock, for a change that conferred
   * nothing.
   */
  @Test
  void anUnchangedRoleLeavesTheConferrerInPlace() {
    UUID originalGranter = UUID.randomUUID();
    UUID holder = UUID.randomUUID();
    Instant extendedExpiry = NOW.plus(90, ChronoUnit.DAYS);
    AssetGrant grant = grantOf(AssetRole.OWNER, NOW.plus(1, ChronoUnit.DAYS), originalGranter);

    grant.updateRole(AssetRole.OWNER, extendedExpiry, holder, NOW);

    assertThat(grant.getExpiresAt()).isEqualTo(extendedExpiry);
    assertThat(grant.getGrantedByUserId()).isEqualTo(originalGranter);
  }

  /**
   * Reviving an already expired grant is procuring the role anew, even at an unchanged role: an
   * expired grant confers nothing, so the person who makes it effective again is its conferrer -
   * otherwise an administrator could reactivate a long-lapsed foreign {@code OWNER} grant on a
   * library and count as independently entitled to lift its Diagnosesperre.
   */
  @Test
  void revivingAnExpiredGrantMakesTheReviverTheConferrer() {
    UUID originalGranter = UUID.randomUUID();
    UUID reviver = UUID.randomUUID();
    Instant newExpiry = NOW.plus(90, ChronoUnit.DAYS);
    AssetGrant grant = grantOf(AssetRole.OWNER, NOW.minus(365, ChronoUnit.DAYS), originalGranter);

    grant.updateRole(AssetRole.OWNER, newExpiry, reviver, NOW);

    assertThat(grant.getExpiresAt()).isEqualTo(newExpiry);
    assertThat(grant.getGrantedByUserId()).isEqualTo(reviver);
  }

  /**
   * The counterpart to the revival case: an expired grant that stays expired confers nothing either
   * way, so it keeps its original conferrer rather than crediting the person who touched it.
   */
  @Test
  void anExpiredGrantThatStaysExpiredKeepsItsConferrer() {
    UUID originalGranter = UUID.randomUUID();
    UUID toucher = UUID.randomUUID();
    Instant stillPast = NOW.minus(1, ChronoUnit.DAYS);
    AssetGrant grant = grantOf(AssetRole.OWNER, NOW.minus(365, ChronoUnit.DAYS), originalGranter);

    grant.updateRole(AssetRole.OWNER, stillPast, toucher, NOW);

    assertThat(grant.getGrantedByUserId()).isEqualTo(originalGranter);
  }

  private static AssetGrant grantOf(AssetRole role, Instant expiresAt, UUID grantedByUserId) {
    return AssetGrant.forUser(
        LIBRARY_ID, ORGANIZATION_ID, UUID.randomUUID(), role, expiresAt, grantedByUserId);
  }
}
