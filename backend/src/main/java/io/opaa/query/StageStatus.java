package io.opaa.query;

/** Whether a registered stage ran in a given retrieval run, and if not, why not. */
public enum StageStatus {

  /** The stage ran. */
  EXECUTED("executed"),

  /**
   * The stage is registered but switched off for this run (see {@link
   * RetrievalPipelineProperties}). The pipeline then behaves as if the stage were not in the chain
   * at all.
   */
  DISABLED("stage switched off for this run"),

  /**
   * The run halted before reaching this stage - today only because the search scope was empty, in
   * which case there is nothing to search and no stage after the scope stage has any work.
   */
  NOT_REACHED("run halted before this stage: nothing left to retrieve");

  private final String note;

  StageStatus(String note) {
    this.note = note;
  }

  String note() {
    return note;
  }
}
