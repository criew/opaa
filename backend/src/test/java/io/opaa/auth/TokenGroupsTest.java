package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.TokenGroups.Reason;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The three cases a groups claim can be in (#1807): present with names, present and empty - the
 * provider revoking everything - and no usable claim at all, which must stay distinguishable from
 * the empty one because only the empty one is a revocation.
 */
class TokenGroupsTest {

  private static Map<String, Object> claims(Object groupsValue) {
    return Map.of("sub", "alice", "groups", groupsValue);
  }

  @Test
  void aClaimWithNamesCarriesThemInOrderWithBlanksAndNonStringsDropped() {
    TokenGroups groups = TokenGroups.read(claims(Arrays.asList("Fachbereich 3", " ", 7)), "groups");

    assertThat(groups).isEqualTo(TokenGroups.named(List.of("Fachbereich 3")));
  }

  @Test
  void aSingleStringIsOneName() {
    assertThat(TokenGroups.read(claims("CN=Referat 12"), "groups"))
        .isEqualTo(TokenGroups.named(List.of("CN=Referat 12")));
  }

  @Test
  void aNestedPathIsFollowed() {
    Map<String, Object> claims = Map.of("realm_access", Map.of("groups", List.of("Referat 12")));

    assertThat(TokenGroups.read(claims, "realm_access.groups"))
        .isEqualTo(TokenGroups.named(List.of("Referat 12")));
  }

  @Test
  void anEmptyClaimIsTheProviderRevokingEverything() {
    assertThat(TokenGroups.read(claims(List.of()), "groups"))
        .isEqualTo(TokenGroups.named(List.of()));
  }

  @Test
  void aMissingClaimSaysNothingAtAll() {
    assertThat(TokenGroups.read(Map.of("sub", "alice"), "groups"))
        .isEqualTo(TokenGroups.unavailable(Reason.CLAIM_MISSING));
  }

  @Test
  void aProviderThatNamesNoGroupsClaimSaysNothingAtAll() {
    assertThat(TokenGroups.read(claims(List.of("Fachbereich 3")), null))
        .isEqualTo(TokenGroups.unavailable(Reason.CLAIM_MISSING));
  }

  @Test
  void aValueOfAnotherShapeIsMalformedRatherThanEmpty() {
    assertThat(TokenGroups.read(claims(42), "groups"))
        .isEqualTo(TokenGroups.unavailable(Reason.CLAIM_MALFORMED));
    assertThat(TokenGroups.read(claims(Map.of("a", "b")), "groups"))
        .isEqualTo(TokenGroups.unavailable(Reason.CLAIM_MALFORMED));
    assertThat(TokenGroups.read(claims("  "), "groups"))
        .isEqualTo(TokenGroups.unavailable(Reason.CLAIM_MALFORMED));
  }

  /** A list that named something but nothing usable is not the provider saying "no groups". */
  @Test
  void aListWithoutASingleUsableNameIsMalformedRatherThanEmpty() {
    assertThat(TokenGroups.read(claims(Arrays.asList(1, 2)), "groups"))
        .isEqualTo(TokenGroups.unavailable(Reason.CLAIM_MALFORMED));
    assertThat(TokenGroups.read(claims(Arrays.asList(" ", "")), "groups"))
        .isEqualTo(TokenGroups.unavailable(Reason.CLAIM_MALFORMED));
  }

  /**
   * Entra ID above 200 groups: the claim is replaced by a {@code _claim_names} reference (OpenID
   * Connect 5.6.2). The groups are unknown here, not gone.
   */
  @Test
  void anOverageReferenceIsNamedAsSuchEvenWithoutTheClaimItself() {
    Map<String, Object> overage =
        Map.of(
            "sub",
            "alice",
            "_claim_names",
            Map.of("groups", "src1"),
            "_claim_sources",
            Map.of(
                "src1", Map.of("endpoint", "https://graph.example/v1.0/users/1/getMemberObjects")));

    assertThat(TokenGroups.read(overage, "groups"))
        .isEqualTo(TokenGroups.unavailable(Reason.CLAIM_OVERAGE));
  }

  @Test
  void anOverageOfAnotherClaimLeavesTheGroupsClaimAlone() {
    Map<String, Object> claims =
        Map.of("groups", List.of("Fachbereich 3"), "_claim_names", Map.of("roles", "src1"));

    assertThat(TokenGroups.read(claims, "groups"))
        .isEqualTo(TokenGroups.named(List.of("Fachbereich 3")));
  }

  /** The reference wins over a claim that is there anyway: what it still names may be a remnant. */
  @Test
  void anOverageReferenceWinsOverAClaimThatIsThereAsWell() {
    Map<String, Object> claims =
        Map.of("groups", List.of("Fachbereich 3"), "_claim_names", Map.of("groups", "src1"));

    assertThat(TokenGroups.read(claims, "groups"))
        .isEqualTo(TokenGroups.unavailable(Reason.CLAIM_OVERAGE));
  }

  /**
   * regression guard for #1807: a claim path of nothing but dots splits into an empty array, so
   * reading its first segment threw - and with a token that carries {@code _claim_names}, every
   * request of every account of that provider failed. No claim layout may fail a request; such a
   * path reaches nothing usable and therefore changes nothing.
   */
  @Test
  void aClaimPathOfNothingButDotsIsNoOverageAndThrowsNothing() {
    Map<String, Object> claims = Map.of("_claim_names", Map.of("groups", "src1"));

    assertThat(TokenGroups.read(claims, "."))
        .isEqualTo(TokenGroups.unavailable(Reason.CLAIM_MALFORMED));
    assertThat(TokenGroups.read(claims, ".."))
        .isEqualTo(TokenGroups.unavailable(Reason.CLAIM_MALFORMED));
  }

  @Test
  void theNamesOfAValueDoNotChangeWithTheListPassedIn() {
    List<String> names = new ArrayList<>(List.of("Fachbereich 3"));

    TokenGroups groups = TokenGroups.named(names);
    names.add("Fachbereich 4");

    assertThat(groups).isEqualTo(TokenGroups.named(List.of("Fachbereich 3")));
  }
}
