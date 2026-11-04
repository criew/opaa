/**
 * The retrieval pipeline's frame - the stage contract, the run's context and state, and the
 * explanation protocol every stage writes (docs/handbuch/referenz/wie-die-suche-arbeitet.md) -
 * with its entrance {@link io.opaa.retrieval.KnowledgeRetrieval} and its parameters. The stages
 * live in the sub-packages {@code scope}, {@code search} and {@code ranking}, their order in {@code
 * config}; this package depends on none of them.
 */
package io.opaa.retrieval;
