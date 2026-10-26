/**
 * Managed chat models with exactly one systemwide active entry. {@link io.opaa.llm.LlmModelService}
 * is the single entry point for reading and changing them; it encrypts the optional API key before
 * the database sees it and audits every change. The first model comes from the installation ({@code
 * io.opaa.installation}, ADR-0060).
 */
package io.opaa.llm;
