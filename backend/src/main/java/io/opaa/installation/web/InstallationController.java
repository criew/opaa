package io.opaa.installation.web;

import io.opaa.api.dto.InstallationAnswers;
import io.opaa.api.dto.InstallationStatusResponse;
import io.opaa.api.dto.InstallationStepRequest;
import io.opaa.api.dto.InstallationStepState;
import io.opaa.common.InstallationStatus;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.installation.InstallationService;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.installation.InstallationStep;
import io.opaa.installation.StepInput;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The installation API (ADR-0060, Entscheidung 6). Every operation first answers {@code 404} once
 * the installation is completed, then asks {@link InstallationAccessGuard} for network, budget and
 * token, and only then reaches {@link InstallationService}.
 */
@RestController
@RequestMapping("/api/v1/installation")
public class InstallationController {

  private final InstallationService installation;
  private final InstallationAccessGuard guard;
  private final InstallationStatus status;

  InstallationController(
      InstallationService installation, InstallationAccessGuard guard, InstallationStatus status) {
    this.installation = installation;
    this.guard = guard;
    this.status = status;
  }

  @GetMapping
  public InstallationStatusResponse getInstallation(HttpServletRequest request) {
    admit(request);
    return InstallationResponseMapper.toResponse(installation.overview());
  }

  @PutMapping("/steps/{step}")
  public InstallationStepState saveInstallationStep(
      @PathVariable("step") String step,
      @RequestBody InstallationStepRequest body,
      HttpServletRequest request) {
    admit(request);
    InstallationStep parsed = parse(step);
    return InstallationResponseMapper.toResponse(
        installation.save(InstallationRequestMapper.toInput(parsed, body)));
  }

  @PostMapping("/steps/{step}/check")
  public InstallationStepState checkInstallationStep(
      @PathVariable("step") String step, HttpServletRequest request) {
    admit(request);
    return InstallationResponseMapper.toResponse(installation.check(parse(step)));
  }

  @PostMapping("/answer-file")
  public InstallationStatusResponse applyInstallationAnswerFile(
      @RequestBody InstallationAnswers body, HttpServletRequest request) {
    admit(request);
    List<StepInput> inputs = InstallationRequestMapper.toInputs(body);
    return InstallationResponseMapper.toResponse(
        installation.applyAnswers(inputs, CompletionSource.ANSWER_FILE_API));
  }

  @PostMapping("/complete")
  public InstallationStatusResponse completeInstallation(HttpServletRequest request) {
    admit(request);
    return InstallationResponseMapper.toResponse(
        installation.complete(CompletionSource.INSTALLATION_API));
  }

  private void admit(HttpServletRequest request) {
    if (!status.isPending()) {
      throw notFound();
    }
    guard.requireAccess(request);
  }

  private static InstallationStep parse(String step) {
    return Arrays.stream(InstallationStep.values())
        .filter(candidate -> candidate.name().equals(step))
        .findFirst()
        .orElseThrow(() -> new ValidationException("Unbekannter Schritt der Installation."));
  }

  private static NotFoundException notFound() {
    return new NotFoundException("Die angeforderte Ressource wurde nicht gefunden");
  }
}
