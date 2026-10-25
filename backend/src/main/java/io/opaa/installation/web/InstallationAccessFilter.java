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
import java.io.IOException;
import java.time.Clock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The door of every path below {@code /api/v1/installation}, behind {@link InstallationGateFilter}
 * and before anything reads the body: network, budget and token through {@link
 * InstallationAccessGuard}, then a body of at most {@value #MAX_BODY_BYTES} bytes - a larger one is
 * unreadable ({@code 400}). A new operation below the path is covered without doing anything.
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
    chain.doFilter(new BoundedBodyRequest(request), response);
  }

  private void write(HttpServletResponse response, HttpStatus status, String message)
      throws IOException {
    ErrorResponse body = new ErrorResponse(message, status.value(), clock.instant());
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    jsonMapper.writeValue(response.getOutputStream(), body);
  }

  /** A body without a length header ends with an error past {@link #MAX_BODY_BYTES}. */
  private static final class BoundedBodyRequest extends HttpServletRequestWrapper {

    BoundedBodyRequest(HttpServletRequest request) {
      super(request);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      ServletInputStream in = super.getInputStream();
      return new ServletInputStream() {
        private long read;

        @Override
        public int read() throws IOException {
          int next = in.read();
          if (next >= 0 && ++read > MAX_BODY_BYTES) {
            throw new IOException(TOO_LARGE_MESSAGE);
          }
          return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
          int count = in.read(buffer, offset, length);
          if (count > 0 && (read += count) > MAX_BODY_BYTES) {
            throw new IOException(TOO_LARGE_MESSAGE);
          }
          return count;
        }

        @Override
        public boolean isFinished() {
          return in.isFinished();
        }

        @Override
        public boolean isReady() {
          return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
          in.setReadListener(listener);
        }
      };
    }
  }
}
