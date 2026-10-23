package io.opaa.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler method, or every handler method of a controller, as reachable only with a token
 * whose sign-in is recent: {@link RecentLoginInterceptor} applies {@link RecentLoginPolicy} before
 * the handler runs and answers {@code 403} with code {@value RecentLoginPolicy#CODE} otherwise.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresRecentLogin {}
