package io.opaa.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method parameter to be resolved by {@link CurrentUserArgumentResolver} rather
 * than any other {@code HandlerMethodArgumentResolver}. Required, not optional: an unannotated
 * {@link CurrentUser} parameter would otherwise be eligible for Spring MVC's catch-all {@code
 * ModelAttributeMethodProcessor}, which binds command objects from request/query parameters by name
 * (e.g. {@code ?systemRole=SYSTEM_ADMIN}) - a caller-controlled bypass of every
 * {@code @PreAuthorize} check downstream if the dedicated resolver is ever missing from the
 * resolver chain (misconfigured test slice, resolver ordering bug, ...). {@link
 * CurrentUserArgumentResolver#supportsParameter} only claims parameters carrying this annotation,
 * so a resolver-chain misconfiguration fails the request (no resolver claims it) instead of
 * silently falling through to attacker-controlled binding.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Caller {}
