package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.TokenRoles.Reason;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The three cases a roles claim can be in (#1830): present with values, present and naming none of
 * the configured roles - the provider withdrawing the elevated role - and no usable claim at all,
 * which must stay distinguishable from the empty one because only the empty one is a withdrawal.
 */
class TokenRolesTest {

  private static Map<String, Object> claims(Object rolesValue) {
    return Map.of("sub", "alice", "roles", rolesValue);
  }

  @Test
  void aClaimWithValuesCarriesThemInOrderWithBlanksAndNonStringsDropped() {
    TokenRoles roles = TokenRoles.read(claims(Arrays.asList("opaa-admin", " ", 7)), "roles");

    assertThat(roles).isEqualTo(TokenRoles.named(List.of("opaa-admin")));
  }

  @Test
  void aSingleStringIsOneValue() {
    assertThat(TokenRoles.read(claims("opaa-auditor"), "roles"))
        .isEqualTo(TokenRoles.named(List.of("opaa-auditor")));
  }

  @Test
  void aNestedPathIsFollowed() {
    Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("opaa-admin")));

    assertThat(TokenRoles.read(claims, "realm_access.roles"))
        .isEqualTo(TokenRoles.named(List.of("opaa-admin")));
  }

  @Test
  void anEmptyClaimIsTheProviderGrantingNoElevatedRole() {
    assertThat(TokenRoles.read(claims(List.of()), "roles")).isEqualTo(TokenRoles.named(List.of()));
  }

  @Test
  void aMissingClaimSaysNothingAtAll() {
    assertThat(TokenRoles.read(Map.of("sub", "alice"), "roles"))
        .isEqualTo(TokenRoles.unavailable(Reason.CLAIM_MISSING));
  }

  @Test
  void aProviderThatNamesNoRolesClaimSaysNothingAtAll() {
    assertThat(TokenRoles.read(claims(List.of("opaa-admin")), null))
        .isEqualTo(TokenRoles.unavailable(Reason.CLAIM_MISSING));
  }

  @Test
  void aValueOfAnotherShapeIsMalformedRatherThanEmpty() {
    assertThat(TokenRoles.read(claims(42), "roles"))
        .isEqualTo(TokenRoles.unavailable(Reason.CLAIM_MALFORMED));
    assertThat(TokenRoles.read(claims(Map.of("a", "b")), "roles"))
        .isEqualTo(TokenRoles.unavailable(Reason.CLAIM_MALFORMED));
    assertThat(TokenRoles.read(claims("  "), "roles"))
        .isEqualTo(TokenRoles.unavailable(Reason.CLAIM_MALFORMED));
  }

  /** A list that named something but nothing usable is not the provider saying "no roles". */
  @Test
  void aListWithoutASingleUsableValueIsMalformedRatherThanEmpty() {
    assertThat(TokenRoles.read(claims(Arrays.asList(1, 2)), "roles"))
        .isEqualTo(TokenRoles.unavailable(Reason.CLAIM_MALFORMED));
    assertThat(TokenRoles.read(claims(Arrays.asList(" ", "")), "roles"))
        .isEqualTo(TokenRoles.unavailable(Reason.CLAIM_MALFORMED));
  }

  /**
   * Entra ID replaces a claim it cannot fit by a {@code _claim_names} reference (OpenID Connect
   * 5.6.2). The roles are unknown here, not withdrawn.
   */
  @Test
  void anOverageReferenceIsNamedAsSuchEvenWithoutTheClaimItself() {
    Map<String, Object> overage =
        Map.of(
            "sub",
            "alice",
            "_claim_names",
            Map.of("roles", "src1"),
            "_claim_sources",
            Map.of("src1", Map.of("endpoint", "https://graph.example/v1.0/users/1/roles")));

    assertThat(TokenRoles.read(overage, "roles"))
        .isEqualTo(TokenRoles.unavailable(Reason.CLAIM_OVERAGE));
  }

  @Test
  void anOverageOfAnotherClaimLeavesTheRolesClaimAlone() {
    Map<String, Object> claims =
        Map.of("roles", List.of("opaa-admin"), "_claim_names", Map.of("groups", "src1"));

    assertThat(TokenRoles.read(claims, "roles")).isEqualTo(TokenRoles.named(List.of("opaa-admin")));
  }

  /** The reference wins over a claim that is there anyway: what it still names may be a remnant. */
  @Test
  void anOverageReferenceWinsOverAClaimThatIsThereAsWell() {
    Map<String, Object> claims =
        Map.of("roles", List.of("opaa-admin"), "_claim_names", Map.of("roles", "src1"));

    assertThat(TokenRoles.read(claims, "roles"))
        .isEqualTo(TokenRoles.unavailable(Reason.CLAIM_OVERAGE));
  }

  /**
   * A claim path of nothing but dots splits into an empty array, so reading its first segment
   * throws unless the split keeps its limit - and with a token that carries {@code _claim_names},
   * every request of every account of that provider would fail. No claim layout may fail a request.
   */
  @Test
  void aClaimPathOfNothingButDotsIsNoOverageAndThrowsNothing() {
    Map<String, Object> claims = Map.of("_claim_names", Map.of("roles", "src1"));

    assertThat(TokenRoles.read(claims, "."))
        .isEqualTo(TokenRoles.unavailable(Reason.CLAIM_MALFORMED));
    assertThat(TokenRoles.read(claims, ".."))
        .isEqualTo(TokenRoles.unavailable(Reason.CLAIM_MALFORMED));
  }

  @Test
  void theValuesOfAValueDoNotChangeWithTheListPassedIn() {
    List<String> values = new ArrayList<>(List.of("opaa-admin"));

    TokenRoles roles = TokenRoles.named(values);
    values.add("opaa-auditor");

    assertThat(roles).isEqualTo(TokenRoles.named(List.of("opaa-admin")));
  }

  private static Stream<Object> claimShapes() {
    return Stream.of(
        List.of("opaa-admin", "offline_access"),
        List.of(),
        "opaa-admin",
        "  ",
        42,
        Map.of("a", "b"),
        Arrays.asList(1, 2),
        Arrays.asList("opaa-admin", " ", 7));
  }

  /**
   * Both claim types read through the one shared classification, so the same token must land in the
   * same case for roles and for groups - otherwise "present and empty" and "no usable claim" drift
   * apart between the two and one of them starts revoking on a claim the other leaves alone.
   */
  @ParameterizedTest
  @MethodSource("claimShapes")
  void rolesAndGroupsClassifyTheSameClaimTheSameWay(Object value) {
    Map<String, Object> claims = claims(value);

    assertThat(describe(TokenRoles.read(claims, "roles")))
        .isEqualTo(describe(TokenGroups.read(claims, "roles")));
  }

  @Test
  void rolesAndGroupsClassifyAMissingClaimAndAnOverageTheSameWay() {
    Map<String, Object> absent = Map.of("sub", "alice");
    Map<String, Object> overage = Map.of("_claim_names", Map.of("roles", "src1"));

    assertThat(describe(TokenRoles.read(absent, "roles")))
        .isEqualTo(describe(TokenGroups.read(absent, "roles")));
    assertThat(describe(TokenRoles.read(overage, "roles")))
        .isEqualTo(describe(TokenGroups.read(overage, "roles")));
  }

  private static String describe(TokenRoles roles) {
    return switch (roles) {
      case TokenRoles.Named named -> "named" + named.values();
      case TokenRoles.Unavailable unavailable -> unavailable.reason().name();
    };
  }

  private static String describe(TokenGroups groups) {
    return switch (groups) {
      case TokenGroups.Named named -> "named" + named.names();
      case TokenGroups.Unavailable unavailable -> unavailable.reason().name();
    };
  }
}
