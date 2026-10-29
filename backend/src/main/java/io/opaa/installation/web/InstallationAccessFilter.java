package io.opaa.installation.web;

import io.opaa.api.dto.ErrorResponse;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.UnauthorizedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The door of every path below {@code /api/v1/installation}, behind {@link InstallationGateFilter}
 * and before anything reads the body: network, budget and token through {@link
 * InstallationAccessGuard}, then a body of at most {@value #MAX_BODY_BYTES} bytes, read here - a
 * larger one, with or without length header, is {@code 400}. CORS preflights pass untouched. A new
 * operation below the path is covered without doing anything.
 */
public class InstallationAccessFilter extends OncePerRequestFilter {

  /** Far above every request of the installation, far below what a parser should hold. */
  static final int MAX_BODY_BYTES = 64 * 1024;

  static final String TOO_LARGE_MESSAGE = "Die Anfrage ist zu groß.";

  private final InstallationAccessGuard guard;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final UrlPathHelper pathHelper = new UrlPathHelper();

  public InstallationAccessFilter(
      InstallationAccessGuard guard, JsonMapper jsonMapper, Clock clock) {
    this.guard = guard;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !InstallationGateFilter.isInstallationApi(pathHelper.getPathWithinApplication(request));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (CorsUtils.isPreFlightRequest(request)) {
      chain.doFilter(request, response);
      return;
    }
    try {
      guard.requireAccess(request);
    } catch (AccessDeniedException e) {
      write(response, HttpStatus.FORBIDDEN, e.getMessage());
      return;
    } catch (UnauthorizedException e) {
      write(response, HttpStatus.UNAUTHORIZED, e.getMessage());
      return;
    } catch (TooManyRequestsException e) {
      if (e.retryAfterSeconds() > 0) {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()));
      }
      write(response, HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
      return;
    }
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      write(response, HttpStatus.BAD_REQUEST, TOO_LARGE_MESSAGE);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (body.length > MAX_BODY_BYTES) {
      write(response, HttpStatus.BAD_REQUEST, TOO_LARGE_MESSAGE);
      return;
    }
    chain.doFilter(new ReadBodyRequest(request, body), response);
  }

  private void write(HttpServletResponse response, HttpStatus status, String message)
      throws IOException {
    ErrorResponse body = new ErrorResponse(message, status.value(), clock.instant());
    response.setStatus(status.value());
    // runs before the security chain, so its default headers never reach this answer
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    jsonMapper.writeValue(response.getOutputStream(), body);
  }

  /**
   * The request with its body already read, at most {@link #MAX_BODY_BYTES} bytes, so a body
   * without a length header is refused with the same message as one with it.
   */
  private static final class ReadBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    ReadBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
          return in.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("the body is already read");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      Charset charset =
          getCharacterEncoding() == null
              ? StandardCharsets.UTF_8
              : Charset.forName(getCharacterEncoding());
      return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
