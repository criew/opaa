package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.auth.oidc.OidcClaimMapping;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * {@link TokenClaims} (#1331, ADR-0025 Entscheidung 4): the same token read through two providers'
 * mappings yields two different identities - address, display name, roles and groups each come from
 * the claim the provider's mapping names, nested paths included; a mapping without a roles or
 * groups claim knows nothing about them, which is not "none".
 */
class TokenClaimsTest {

  private static Jwt token() {
    return Jwt.withTokenValue("t")
        .header("alg", "none")
        .claim("sub", "alice")
        .claim("iss", "https://idp.example/realms/a")
        .claim("email", "alice@behoerde.example")
        .claim("upn", "alice.mustermann@partner.example")
        .claim("name", "Alice Mustermann")
        .claim("preferred_username", "amustermann")
        .claim("realm_access", Map.of("roles", List.of("opaa-admin", "offline_access")))
        .claim("groups", List.of("Fachbereich 3", "Projekt Phoenix", " "))
        .claim("memberOf", "CN=Referat 12")
        .build();
  }

  @Test
  void theKeycloakDefaultsReadEmailAndNameAndDeriveNoRolesOrGroups() {
    TokenClaims claims = TokenClaims.read(token(), OidcClaimMapping.keycloakDefaults());

    assertThat(claims.subject()).isEqualTo("alice");
    assertThat(claims.issuer()).isEqualTo("https://idp.example/realms/a");
    assertThat(claims.email()).isEqualTo("alice@behoerde.example");
    assertThat(claims.displayName()).isEqualTo("Alice Mustermann");
    // a mapping without a roles claim knows nothing about roles - which is not "no elevated role"
    assertThat(claims.roles()).isEqualTo(TokenRoles.unavailable(TokenRoles.Reason.CLAIM_MISSING));
    // a mapping without a groups claim knows nothing about groups - which is not "no groups"
    assertThat(claims.groups())
        .isEqualTo(TokenGroups.unavailable(TokenGroups.Reason.CLAIM_MISSING));
  }

  @Test
  void anotherProvidersMappingReadsOtherClaimsIncludingNestedPathsAndScalarGroups() {
    OidcClaimMapping partner =
        new OidcClaimMapping(
            "upn", "given_name", "realm_access.roles", "opaa-admin", null, "memberOf");

    TokenClaims claims = TokenClaims.read(token(), partner);

    assertThat(claims.email()).isEqualTo("alice.mustermann@partner.example");
    // given_name is absent: preferred_username is the fallback, never the subject
    assertThat(claims.displayName()).isEqualTo("amustermann");
    assertThat(claims.roles()).isEqualTo(TokenRoles.named(List.of("opaa-admin", "offline_access")));
    assertThat(claims.groups()).isEqualTo(TokenGroups.named(List.of("CN=Referat 12")));
  }

  @Test
  void groupsComeFromTheNamedClaimWithBlankEntriesDropped() {
    OidcClaimMapping mapping = new OidcClaimMapping(null, null, null, null, null, "groups");

    assertThat(TokenClaims.read(token(), mapping).groups())
        .isEqualTo(TokenGroups.named(List.of("Fachbereich 3", "Projekt Phoenix")));
  }

  @Test
  void aPathThatLeadsNowhereOrToAnotherShapeYieldsNothing() {
    OidcClaimMapping mapping =
        new OidcClaimMapping("email", "name", "realm_access.missing", "x", null, "name.deeper");
    Jwt bare = Jwt.withTokenValue("t").header("alg", "none").claim("sub", "bob").build();

    TokenClaims claims = TokenClaims.read(token(), mapping);
    // regression guard for #1830: a path that leads nowhere is not an empty roles claim, which
    // would read as USER and demote the account
    assertThat(claims.roles()).isEqualTo(TokenRoles.unavailable(TokenRoles.Reason.CLAIM_MISSING));
    // regression guard for #1807: a path that leads nowhere is not an empty groups claim
    assertThat(claims.groups())
        .isEqualTo(TokenGroups.unavailable(TokenGroups.Reason.CLAIM_MISSING));
    TokenClaims none = TokenClaims.read(bare, OidcClaimMapping.keycloakDefaults());
    assertThat(none.email()).isNull();
    assertThat(none.displayName()).isNull();
    assertThat(none.issuer()).isEqualTo("unknown");
  }
}
