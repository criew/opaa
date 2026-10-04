package io.opaa.architecture.fixture.privateaudit.audit;

/** A protocol entry with its builder, as in the audit package. */
public class AuditEvent {

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    public Builder object(String type, String id, String label) {
      return this;
    }

    public Builder before(Object payload) {
      return this;
    }

    public Builder after(Object payload) {
      return this;
    }
  }
}
