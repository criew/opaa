package io.opaa.auth;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Refuses to start the {@code dev} profile when the Compose stack publishes the frontend beyond the
 * loopback interface ({@code OPAA_FRONTEND_BIND_ADDRESS}, passed in as {@link
 * AuthProperties.DevAuth#publishedAddress()}) without {@code OPAA_AUTH_DEV_ALLOW_REMOTE=true}: in
 * this mode {@link DevAuthFilter} makes every request a system administrator. With the confirmation
 * the start goes ahead and says so at {@code WARN}. An empty address means the backend runs outside
 * that stack and is not judged.
 */
@Configuration
@Profile("dev")
public class DevExposureGuard {

  static final String BIND_VARIABLE = "OPAA_FRONTEND_BIND_ADDRESS";
  static final String ALLOW_VARIABLE = "OPAA_AUTH_DEV_ALLOW_REMOTE";

  private static final Logger log = LoggerFactory.getLogger(DevExposureGuard.class);

  private final AuthProperties properties;

  public DevExposureGuard(AuthProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void rejectRemoteExposure() {
    String address = properties.dev().publishedAddress().trim();
    if (address.isEmpty() || isLoopback(address)) {
      return;
    }
    if (Boolean.TRUE.equals(properties.dev().allowRemote())) {
      log.warn(
          "The \"dev\" profile runs with the frontend published on {} ({}) and {}=true: every"
              + " request that reaches this address acts as system administrator without signing"
              + " in. Use the \"oidc\" profile for anything but a trusted development network.",
          address,
          BIND_VARIABLE,
          ALLOW_VARIABLE);
      return;
    }
    throw new IllegalStateException(
        "The \"dev\" profile refuses to start with the frontend published on "
            + address
            + " ("
            + BIND_VARIABLE
            + "): without sign-in every request acts as system administrator. Publish on"
            + " 127.0.0.1, switch to SPRING_PROFILES_ACTIVE=docker,oidc, or confirm the exposure"
            + " with "
            + ALLOW_VARIABLE
            + "=true. See docs/handbuch/betrieb/docker-compose.md.");
  }

  static boolean isLoopback(String address) {
    String host = address.toLowerCase(Locale.ROOT);
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    return host.equals("localhost") || host.equals("::1") || host.startsWith("127.");
  }
}
