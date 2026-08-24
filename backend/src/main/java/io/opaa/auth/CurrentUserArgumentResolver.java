package io.opaa.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves a {@link CurrentUser} controller-method parameter from the request attribute {@link
 * UserProvisioningFilter} sets for every authenticated request — the controller-side counterpart of
 * the filter's single {@code findBySubjectAndIssuer}/{@code findOrCreateUser} load. Mirrors
 * {@code @AuthenticationPrincipal Jwt}: a plain parameter, no annotation needed, since {@link
 * CurrentUser}'s type alone is unambiguous.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

  static final String REQUEST_ATTRIBUTE = CurrentUserArgumentResolver.class.getName() + ".value";

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return CurrentUser.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
    Object attribute = request == null ? null : request.getAttribute(REQUEST_ATTRIBUTE);
    if (!(attribute instanceof CurrentUser currentUser)) {
      // Same 401 the removed per-controller currentUser(Jwt) helpers returned when
      // findBySubjectAndIssuer found nothing - unauthenticated/unprovisioned requests never
      // reach a CurrentUser-typed controller method.
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden");
    }
    return currentUser;
  }
}
