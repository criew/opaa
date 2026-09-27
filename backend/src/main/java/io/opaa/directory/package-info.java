/**
 * The directory side of the identity providers (ADR-0025, ADR-0036): the administration of the
 * {@code oidc_providers} rows, the directory synchronisation in {@code sync} and the directory
 * connectors below it. Sits above {@code io.opaa.group}, whose groups the synchronisation writes.
 * The registry that verifies tokens against the providers stays in {@code io.opaa.auth}.
 */
package io.opaa.directory;
