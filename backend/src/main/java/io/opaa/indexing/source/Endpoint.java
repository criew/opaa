package io.opaa.indexing.source;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where a sign-in sends what the profile's registration proves: an address the connector's
 * description fixes, a template it completes with the profile's tenant, or the address the profile
 * itself names, fixed when it is saved. Never an address from a key file, a library or an answer.
 */
public sealed interface Endpoint permits Endpoint.Fixed, Endpoint.WithTenant, Endpoint.FromProfile {

  /**
   * The address for a profile with {@code tenant}.
   *
   * @throws IllegalArgumentException for a tenant the endpoint needs but is not given or not valid
   * @throws IllegalStateException for {@link FromProfile}, which only the profile resolves
   */
  URI resolve(String tenant);

  /** Whether a profile names a tenant for this endpoint. */
  boolean needsTenant();

  /** Exactly {@code uri}. */
  record Fixed(URI uri) implements Endpoint {

    public Fixed {
      Objects.requireNonNull(uri, "uri");
    }

    @Override
    public URI resolve(String tenant) {
      return uri;
    }

    @Override
    public boolean needsTenant() {
      return false;
    }
  }

  /** {@code template} with its one {@value #PLACEHOLDER} replaced by the profile's tenant. */
  record WithTenant(String template) implements Endpoint {

    public static final String PLACEHOLDER = "{tenant}";

    /** A tenant is a host-like name or an id: it cannot add a path, a query or another host. */
    public static final Pattern TENANT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,254}");

    public WithTenant {
      Objects.requireNonNull(template, "template");
      if (template.indexOf(PLACEHOLDER) < 0
          || template.indexOf(PLACEHOLDER) != template.lastIndexOf(PLACEHOLDER)) {
        throw new IllegalArgumentException("an endpoint template names " + PLACEHOLDER + " once");
      }
    }

    @Override
    public URI resolve(String tenant) {
      if (tenant == null || !TENANT.matcher(tenant).matches()) {
        throw new IllegalArgumentException("no valid tenant for an endpoint template");
      }
      return URI.create(template.replace(PLACEHOLDER, tenant));
    }

    @Override
    public boolean needsTenant() {
      return true;
    }
  }

  /**
   * The address the profile names for this endpoint (such as a Keycloak realm's), set by the
   * administration when it saves the profile and never discovered at run time.
   */
  record FromProfile() implements Endpoint {

    @Override
    public URI resolve(String tenant) {
      throw new IllegalStateException("an endpoint from the profile is resolved by the profile");
    }

    @Override
    public boolean needsTenant() {
      return false;
    }
  }
}
