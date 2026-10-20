package io.opaa.test;

import io.opaa.instance.InvalidationBus;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link InvalidationBus} for unit tests: listening unless told otherwise, and remembering what
 * it was asked to announce instead of sending it.
 */
public final class TestInvalidationBus implements InvalidationBus {

  private final List<String> published = new CopyOnWriteArrayList<>();
  private volatile boolean listening = true;

  public static TestInvalidationBus listening() {
    return new TestInvalidationBus();
  }

  @Override
  public void publish(String topic) {
    published.add(topic);
  }

  @Override
  public boolean isListening() {
    return listening;
  }

  public void setListening(boolean listening) {
    this.listening = listening;
  }

  public List<String> published() {
    return List.copyOf(published);
  }
}
