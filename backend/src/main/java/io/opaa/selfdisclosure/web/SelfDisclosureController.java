package io.opaa.selfdisclosure.web;

import io.opaa.api.dto.SelfDisclosureStatus;
import io.opaa.api.dto.SelfDisclosureTicket;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.selfdisclosure.SelfDisclosureService;
import io.opaa.selfdisclosure.SelfDisclosureService.Outcome;
import io.opaa.selfdisclosure.SelfDisclosureService.Run;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The self-disclosure of the signed-in person. No operation names a person: status and ticket are
 * the caller's own, the download belongs to the holder of the ticket. The ticket needs a recent
 * sign-in, which {@link SelfDisclosureService#issueTicket} checks itself so that a refusal is
 * counted. The download is a native form submission without bearer token, streamed synchronously on
 * the request thread.
 */
@RestController
@RequestMapping("/api/v1/me/self-disclosure")
public class SelfDisclosureController {

  private final SelfDisclosureService service;

  public SelfDisclosureController(SelfDisclosureService service) {
    this.service = service;
  }

  @GetMapping
  public SelfDisclosureStatus getSelfDisclosureStatus(@Caller CurrentUser caller) {
    return SelfDisclosureResponseMapper.toResponse(service.status(caller));
  }

  @PostMapping("/tickets")
  @ResponseStatus(HttpStatus.CREATED)
  public SelfDisclosureTicket createSelfDisclosureTicket(
      @Caller CurrentUser caller, Authentication authentication) {
    return new SelfDisclosureTicket(
        service.issueTicket(caller, authentication), SelfDisclosureService.TICKET_SECONDS);
  }

  @PostMapping(path = "/download", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public void downloadSelfDisclosure(
      @RequestParam("ticket") String ticket, HttpServletResponse response) throws IOException {
    Run run = service.start(ticket);
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/zip");
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment()
            .filename(run.fileName(service.installationZone()))
            .build()
            .toString());
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setHeader("X-Accel-Buffering", "no");
    Outcome outcome = service.stream(run, response.getOutputStream());
    // A client that went away hears nothing more; failing would only log an expected abort.
    if (outcome != Outcome.DELIVERED && outcome != Outcome.CLIENT_DISCONNECTED) {
      // Once the ZIP has begun, GlobalExceptionHandler cannot answer any more and lets the
      // failure reach the container, which breaks the connection instead of ending it cleanly.
      if (!response.isCommitted()) {
        response.reset();
      }
      throw new ServiceUnavailableException(ABORTED, ABORTED_RETRY_AFTER_SECONDS);
    }
  }

  static final String ABORTED =
      "Die Selbstauskunft wurde abgebrochen und nicht gezählt. Sie können sie erneut starten.";

  static final long ABORTED_RETRY_AFTER_SECONDS = 60;
}
