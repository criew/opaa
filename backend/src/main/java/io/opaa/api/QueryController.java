package io.opaa.api;

import io.opaa.api.dto.QueryRequest;
import io.opaa.api.dto.QueryResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.query.QueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class QueryController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final QueryService queryService;
  private final UserService userService;

  public QueryController(QueryService queryService, UserService userService) {
    this.queryService = queryService;
    this.userService = userService;
  }

  @PostMapping("/query")
  public QueryResponse query(
      @Valid @RequestBody QueryRequest request, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    boolean useKnowledge = request.getUseKnowledge() == null || request.getUseKnowledge();
    return QueryResponseMapper.toResponse(
        queryService.query(
            request.getQuestion(),
            request.getChatId(),
            currentUser.getId(),
            useKnowledge,
            request.getLibraryIds()));
  }

  private User currentUser(Jwt jwt) {
    String issuer = jwt.getClaimAsString("iss");
    if (issuer == null || issuer.isBlank()) {
      issuer = UNKNOWN_ISSUER;
    }

    return userService
        .findBySubjectAndIssuer(jwt.getSubject(), issuer)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));
  }
}
