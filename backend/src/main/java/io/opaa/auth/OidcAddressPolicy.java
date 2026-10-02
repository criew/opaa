package io.opaa.auth;

import io.opaa.common.ValidationException;
import io.opaa.security.ConnectionAddressResolver;
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF policy for every address the backend fetches for a provider (#1329, ADR-0025 Entscheidung
 * 3): the issuer, the JWK set override and the {@code jwks_uri} a discovery document names. Its
 * {@link TargetAddressValidator} is built from the anmeldeseitige block {@code
 * opaa.auth.oidc.target-validation} - deliberately not from the indexing one, so switching a
 * connector's check off never switches the sign-in check off. On top of the allowlist, exactly the
 * two bootstrap addresses {@code OPAA_OIDC_ISSUER_URI}/{@code OPAA_OIDC_JWK_SET_URI} are always
 * allowed - by scheme, host <em>and port</em>, not by host alone, or the exception would release
 * the loopback for every local service. They come from the deployment configuration, the same trust
 * level as the allowlist itself, which is what keeps an upgrade of an installation with Keycloak on
 * {@code localhost} or a private address from locking everyone out.
 *
 * <p>Applied at save time ({@link io.opaa.directory.OidcProviderService}), at use time ({@link
 * OidcProviderRegistry}, so a narrowed allowlist or a row edited directly in the database cannot
 * slip past), before the connection test sends anything ({@link OidcDiscoveryClient}) - and to the
 * admin API address of a provider's directory access, both when it is stored and every time a run
 * uses it ({@code DirectoryConnectorService} and {@code ProviderDirectoryClient} in the group
 * package). The clients that reach these addresses resolve every connection through {@link
 * #resolve}, so the address checked is the address connected to.
 */
public class OidcAddressPolicy implements ConnectionAddressResolver {

  static final String ALLOWLIST_HINT =
      "Interne Adressen gibt der Betrieb über OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST frei.";

  private final TargetAddressValidator validator;
  private final Set<String> bootstrapOrigins;

  public OidcAddressPolicy(TargetAddressValidator validator) {
    this(validator, Set.of());
  }

  OidcAddressPolicy(TargetAddressValidator validator, Set<String> bootstrapOrigins) {
    this.validator = validator;
    this.bootstrapOrigins = Set.copyOf(bootstrapOrigins);
  }

  /** The production policy: the configured allowlist plus the two bootstrap addresses. */
  public static OidcAddressPolicy fromProperties(AuthProperties.OidcAuth oidc) {
    Set<String> origins = new HashSet<>();
    addOrigin(origins, oidc.issuerUri());
    addOrigin(origins, oidc.jwkSetUri());
    return new OidcAddressPolicy(
        new TargetAddressValidator(
            oidc.targetValidation().enabled(), oidc.targetValidation().allowlist()),
        origins);
  }

  private static void addOrigin(Set<String> origins, String uri) {
    if (uri == null || uri.isBlank()) {
      return;
    }
    try {
      String origin = originOf(URI.create(uri.trim()));
      if (origin != null) {
        origins.add(origin);
      }
    } catch (IllegalArgumentException e) {
      // an unparseable bootstrap address is reported by the seeder, not here
    }
  }

  /**
   * {@code scheme://host:port} with the scheme's default port made explicit; {@code null} without
   * host.
   */
  static String originOf(URI uri) {
    if (uri.getScheme() == null || uri.getHost() == null) {
      return null;
    }
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    int port = uri.getPort();
    if (port < 0) {
      port = scheme.equals("https") ? 443 : 80;
    }
    return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
  }

  /**
   * The connect-time counterpart of {@link #requireAllowed}: a bootstrap address by host and port
   * (its scheme was checked before the request), everything else through the validator.
   */
  @Override
  public InetAddress[] resolve(String host, int port) throws IOException {
    for (String origin : bootstrapOrigins) {
      if (origin.endsWith("://" + host.toLowerCase(Locale.ROOT) + ":" + port)) {
        return validator.lookup(host);
      }
    }
    return validator.resolveForConnection(host);
  }

  /**
   * @param fieldLabel the German field name for the message ({@code "Issuer-URI"}, {@code
   *     "JWK-Set-URI"})
   * @throws ValidationException when {@code uri} is malformed or resolves into a blocked range
   */
  public void requireAllowed(String uri, String fieldLabel) {
    URI parsed = OidcIssuerUris.requireHttpUri(uri, fieldLabel);
    if (bootstrapOrigins.contains(originOf(parsed))) {
      return;
    }
    try {
      validator.validate(parsed);
    } catch (IOException e) {
      throw new ValidationException(fieldLabel + ": " + e.getMessage() + " " + ALLOWLIST_HINT);
    }
  }
}
