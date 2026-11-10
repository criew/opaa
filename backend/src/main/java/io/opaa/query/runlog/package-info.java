/**
 * The step log of agent runs and the log of their sandboxes (ADR-0070, Entscheidung 4, Bestand b;
 * ADR-0073): technical facts per step and per sandbox, no account, no search text, no script or
 * file content. Kept for days, dropped by day partition, read only by the system administration for
 * one run with a stated reason.
 */
package io.opaa.query.runlog;
