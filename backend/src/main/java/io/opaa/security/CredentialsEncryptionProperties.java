package io.opaa.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link CredentialsEncryptor} (#483, ADR-0018 Entscheidung 4): the key used to
 * encrypt {@code KnowledgeLibrary.sourceCredentials} at rest.
 *
 * @param encryptionKey Base64-encoded AES-256 key (32 raw bytes) read from the environment variable
 *     {@code OPAA_CREDENTIALS_ENCRYPTION_KEY}. {@code null}/blank in the base configuration - the
 *     {@code local}/{@code dev} Spring profiles fill in a clearly-marked, non-production default
 *     (see {@code application.yml}) so {@code bootRun} and tests work without any operator setup.
 *     Any deployment that actually stores {@code sourceCredentials} (a {@code HTTP_DIRECTORY} or
 *     {@code RSS_FEED} library with credentials set) must set a real value - {@link
 *     CredentialsEncryptor} raises {@link CredentialsEncryptionKeyMissingException} rather than
 *     starting, or writing anything, without one.
 */
@ConfigurationProperties(prefix = "opaa.security.credentials")
public record CredentialsEncryptionProperties(String encryptionKey) {}
