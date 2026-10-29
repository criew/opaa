package io.opaa.auth;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Refuses to start the {@code dev} profile when it is reachable beyond the loopback interface,
 * because {@link DevAuthFilter} makes every request a system administrator - unless {@code
 * OPAA_AUTH_DEV_ALLOW_REMOTE=true} confirms it; then the start says so at {@code WARN}. Judged is
 * the address the Compose stack publishes the frontend on ({@link
 * AuthProperties.DevAuth#publishedAddress()}); with the {@code docker} profile a missing one counts
 * as not loopback, outside Docker {@code server.address} takes its place (empty means loopback).
 */
@Configuration
@Profile("dev")
public class DevExposureGuard {

  static final String BIND_VARIABLE = "OPAA_FRONTEND_BIND_ADDRESS";
  static final String SERVER_VARIABLE = "OPAA_SERVER_ADDRESS";
  static final String ALLOW_VARIABLE = "OPAA_AUTH_DEV_ALLOW_REMOTE";

  private static final Logger log = LoggerFactory.getLogger(DevExposureGuard.class);

  private final AuthProperties properties;
  private final Environment environment;

  public DevExposureGuard(AuthProperties properties, Environment environment) {
    this.properties = properties;
    this.environment = environment;
  }

  @PostConstruct
  void rejectRemoteExposure() {
    String published = properties.dev().publishedAddress().trim();
    String address;
    String source;
    if (!published.isEmpty()) {
      address = published;
      source = BIND_VARIABLE;
    } else if (environment.acceptsProfiles(Profiles.of("docker"))) {
      address = "an unknown address";
      source = BIND_VARIABLE + " not passed in by the Compose stack";
    } else {
      address = environment.getProperty("server.address", "").trim();
      source = SERVER_VARIABLE;
      if (address.isEmpty()) {
        return;
      }
    }
    if (isLoopback(address)) {
      return;
    }
    if (Boolean.TRUE.equals(properties.dev().allowRemote())) {
      log.warn(
          "The \"dev\" profile runs reachable on {} ({}) and {}=true: every request that reaches"
              + " it acts as system administrator without signing in. Use the \"oidc\" profile for"
              + " anything but a trusted development network.",
          address,
          source,
          ALLOW_VARIABLE);
      return;
    }
    throw new IllegalStateException(
        "The \"dev\" profile refuses to start reachable on "
            + address
            + " ("
            + source
            + "): without sign-in every request acts as system administrator. Bind to"
            + " 127.0.0.1, switch to the \"oidc\" profile, or confirm the exposure with "
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
