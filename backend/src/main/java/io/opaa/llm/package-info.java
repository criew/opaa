/**
 * Managed chat models with one systemwide active entry, changed only through {@link
 * io.opaa.llm.LlmModelService}, which encrypts the key and audits every change; the first model
 * comes from the installation (ADR-0060). Also the address rules of every model endpoint and the
 * scaffold of the optional roles, which live one package each below.
 */
package io.opaa.llm;
