package io.opaa.mail;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Transport timeouts for the SMTP sender (#1536, ADR-0033 Entscheidung 10). Deliberately the only
 * mail configuration that lives in the environment: host, port, credentials and encryption are an
 * administrative decision and live in {@code mail_settings}, while "how long may a stalled peer
 * hold a request thread" is a property of the deployment.
 *
 * <p>Named {@code SmtpProperties} rather than {@code MailProperties} because {@code
 * io.opaa.indexing.format.file.mail.MailProperties} already exists for reading {@code .eml}
 * documents - two unrelated concerns that must stay distinguishable at the import line.
 *
 * @param connectTimeout how long establishing the TCP/TLS connection may take
 * @param readTimeout how long the server may take to answer a command
 * @param writeTimeout how long writing the message body may take
 */
@ConfigurationProperties(prefix = "opaa.mail")
public record SmtpProperties(
    Duration connectTimeout, Duration readTimeout, Duration writeTimeout) {}
