package io.opaa.query;

/** What a stage did to a candidate: brought it in, passed it on, or dropped it. */
public enum CandidateOutcome {

  /** The candidate was not in the stage's input and is in its output. */
  ADDED,

  /** The candidate was in the stage's input and survived it. */
  KEPT,

  /** The candidate was in the stage's input and is not in its output. */
  DROPPED
}
