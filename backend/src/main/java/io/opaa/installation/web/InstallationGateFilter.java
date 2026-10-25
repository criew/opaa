package io.opaa.installation.web;

import io.opaa.api.dto.ErrorResponse;
import io.opaa.common.InstallationStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The installation mode at the door (ADR-0060, Entscheidungen 4 and 6), ahead of every other
 * filter: while the installation is pending only the installation API, the public configuration of
 * the interface (sign-in configuration, branding) and the health endpoints are served, everything
 * else answers {@code 503} with the code {@value #PENDING_CODE}. Once it is completed, every path
 * of the installation API answers {@code 404} on every instance. Matched on the decoded path within
 * the application, the same path the handler mapping sees.
 */
public class InstallationGateFilter extends OncePerRequestFilter {

  static final String PENDING_CODE = "INSTALLATION_PENDING";
  static final String PENDING_MESSAGE =
      "OPAA ist noch nicht eingerichtet. Bitte die Installation abschließen.";
  static final String NOT_FOUND_MESSAGE = "Die angeforderte Ressource wurde nicht gefunden";
  static final String INSTALLATION_PATH = "/api/v1/installation";

  /** Served while pending, independent of the method. */
  private static final Set<String> OPEN_PATHS =
      Set.of(
          "/api/v1/auth/config",
          "/api/health",
          "/actuator/health",
          "/actuator/health/readiness",
          "/actuator/health/liveness",
          "/actuator/info",
          "/actuator/metrics",
          "/actuator/prometheus");

  /** Served while pending for reading only: the sign-in page and the setup carry the branding. */
  private static final Set<String> OPEN_FOR_READING =
      Set.of(
          "/api/v1/branding",
          "/api/v1/branding/logo",
          "/api/v1/branding/login-logo",
          "/api/v1/branding/login-background");

  private final InstallationStatus status;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final UrlPathHelper pathHelper = new UrlPathHelper();

  public InstallationGateFilter(InstallationStatus status, JsonMapper jsonMapper, Clock clock) {
    this.status = status;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = pathHelper.getPathWithinApplication(request);
    boolean installationApi = isInstallationApi(path);
    if (!installationApi && isOpen(request, path)) {
      // open paths never ask the database, so health answers DOWN rather than 500 without it
      chain.doFilter(request, response);
      return;
    }
    boolean pending = status.isPending();
    if (pending && !installationApi) {
      write(response, HttpStatus.SERVICE_UNAVAILABLE, PENDING_MESSAGE, PENDING_CODE);
      return;
    }
    if (!pending && installationApi) {
      write(response, HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, null);
      return;
    }
    chain.doFilter(request, response);
  }

  static boolean isInstallationApi(String path) {
    return path.equals(INSTALLATION_PATH) || path.startsWith(INSTALLATION_PATH + "/");
  }

  private static boolean isOpen(HttpServletRequest request, String path) {
    if (OPEN_PATHS.contains(path) || path.startsWith("/actuator/metrics/")) {
      return true;
    }
    return OPEN_FOR_READING.contains(path)
        && (HttpMethod.GET.matches(request.getMethod())
            || HttpMethod.HEAD.matches(request.getMethod()));
  }

  private void write(HttpServletResponse response, HttpStatus status, String message, String code)
      throws IOException {
    ErrorResponse body = new ErrorResponse(message, status.value(), clock.instant());
    body.setCode(code);
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    jsonMapper.writeValue(response.getOutputStream(), body);
  }
}
