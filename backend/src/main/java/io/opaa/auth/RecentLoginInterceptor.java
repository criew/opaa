package io.opaa.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies {@link RecentLoginPolicy} before every handler marked {@link RequiresRecentLogin} on the
 * method or its controller. The refusal is thrown, so {@code GlobalExceptionHandler} answers it
 * like any other domain refusal; unmarked handlers pass untouched.
 */
public class RecentLoginInterceptor implements HandlerInterceptor {

  private final RecentLoginPolicy policy;

  public RecentLoginInterceptor(RecentLoginPolicy policy) {
    this.policy = policy;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (handler instanceof HandlerMethod method && requiresRecentLogin(method)) {
      policy.requireRecentLogin(SecurityContextHolder.getContext().getAuthentication());
    }
    return true;
  }

  static boolean requiresRecentLogin(HandlerMethod method) {
    return method.hasMethodAnnotation(RequiresRecentLogin.class)
        || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RequiresRecentLogin.class);
  }
}
