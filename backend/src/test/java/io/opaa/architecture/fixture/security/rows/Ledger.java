package io.opaa.architecture.fixture.security.rows;

/** A persistent type without any field named like security state. */
public class Ledger {

  private String note;

  public void annotate(String note) {
    this.note = note;
  }
}
