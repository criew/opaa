package io.opaa.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The address under which this installation is reachable from outside (#1536, ADR-0033 Entscheidung
 * 10).
 *
 * @param publicBaseUrl read from {@code OPAA_PUBLIC_BASE_URL}. Deliberately an environment variable
 *     and not an administrable setting: it is a property of the deployment, like {@code
 *     OPAA_CORS_ALLOWED_ORIGINS}, and whoever can change it in the browser can point every link in
 *     every mail at a site of their choosing from one compromised administrator session. Empty by
 *     default - a deployment that has not set it simply has no flows that depend on a link.
 */
@ConfigurationProperties(prefix = "opaa")
public record PublicBaseUrlProperties(String publicBaseUrl) {}
