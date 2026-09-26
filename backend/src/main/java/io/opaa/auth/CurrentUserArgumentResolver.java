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
 * Resolves an {@code @}{@link Caller}-annotated {@link CurrentUser} controller-method parameter
 * from the request attribute {@link UserProvisioningFilter} sets for every authenticated request —
 * the controller-side counterpart of the filter's single {@code findBySubjectAndIssuer}/{@code
 * findOrCreateUser} load.
 *
 * <p>{@code supportsParameter} requires the {@link Caller} annotation, not merely the {@link
 * CurrentUser} type: a bare-type check would let Spring MVC's catch-all {@code
 * ModelAttributeMethodProcessor} claim the parameter instead whenever this resolver is missing from
 * the chain (see {@link CurrentUser}'s Javadoc for the attacker-controlled-binding consequence that
 * would follow). Requiring the annotation means a missing resolver leaves the parameter unclaimed
 * by every resolver, which fails the request rather than silently binding it from request/query
 * parameters.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

  static final String REQUEST_ATTRIBUTE = CurrentUserArgumentResolver.class.getName() + ".value";

  /**
   * The caller the filter chain attached to {@code request}, or {@code null} when it carries none.
   * The one way to read this attribute from outside this package, for code that is not a controller
   * method: the MCP endpoint (#1721) is a {@code RouterFunction} and has no {@code @Caller}
   * parameter to resolve.
   */
  public static CurrentUser callerOf(HttpServletRequest request) {
    Object attribute = request == null ? null : request.getAttribute(REQUEST_ATTRIBUTE);
    return attribute instanceof CurrentUser currentUser ? currentUser : null;
  }

  /**
   * Attaches the caller of an external-access token call to {@code request} - always the reduced
   * snapshot of {@link CurrentUser#forExternalAccess}, never the person's full identity. Its one
   * intended caller is the access-token filter of {@code io.opaa.externalaccess}; nothing else
   * binds a caller through it.
   */
  public static void bindExternalAccessCaller(HttpServletRequest request, User user) {
    request.setAttribute(REQUEST_ATTRIBUTE, CurrentUser.forExternalAccess(user));
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(Caller.class)
        && CurrentUser.class.equals(parameter.getParameterType());
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
      // Deliberately does not distinguish "no authentication at all" from "authenticated but
      // UserProvisioningFilter never ran/provisioned" to an external caller - both mean the
      // request carries no caller identity a controller can act on.
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nicht angemeldet");
    }
    return currentUser;
  }
}
