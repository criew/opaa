/**
 * Agents (ADR-0070): an asset type on the shell with instruction, libraries, tools, plan step and
 * limits, the installation-wide switch with the tools switched on, and the delivered agent
 * "Recherche".
 *
 * <p><b>Dependencies.</b> This package builds on {@code io.opaa.asset} and asks {@code
 * io.opaa.permission} for roles, {@code io.opaa.knowledge} for the libraries a person may read and
 * {@code io.opaa.template} for the document templates and their switch; it holds no grant, release,
 * ownership or succession logic of its own. Assigning a library or template grants nothing: a run
 * reads and fills only what the calling person may read.
 */
package io.opaa.agent;
