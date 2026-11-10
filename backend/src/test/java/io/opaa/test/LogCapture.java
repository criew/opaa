package io.opaa.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Every log event of every logger at every level, TRACE included, while it is attached: the root
 * logger is lowered to TRACE and given an appender of its own; {@link #close} restores the level. A
 * line is the formatted message plus the message and every frame of each exception in its cause
 * chain, since an appender writes all of that.
 */
public final class LogCapture implements AutoCloseable {

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  private final Level previousLevel;

  private LogCapture() {
    previousLevel = root.getLevel();
    root.setLevel(Level.TRACE);
    appender.start();
    root.addAppender(appender);
  }

  public static LogCapture ofAllLoggers() {
    return new LogCapture();
  }

  /** The events so far, copied once: background threads keep appending while a caller iterates. */
  public List<ILoggingEvent> events() {
    return List.of(appender.list.toArray(new ILoggingEvent[0]));
  }

  /**
   * One description per event whose line contains one of {@code values}: logger, level, which value
   * and the line itself, empty when no line names any of them.
   */
  public List<String> linesNaming(Collection<String> values) {
    List<String> found = new ArrayList<>();
    for (ILoggingEvent event : events()) {
      String line = lineOf(event);
      for (String value : values) {
        if (line.contains(value)) {
          found.add(
              event.getLoggerName() + " at " + event.getLevel() + " names " + value + ": " + line);
        }
      }
    }
    return found;
  }

  @Override
  public void close() {
    root.detachAppender(appender);
    root.setLevel(previousLevel);
    appender.stop();
  }

  private static String lineOf(ILoggingEvent event) {
    StringBuilder line = new StringBuilder(event.getFormattedMessage());
    for (IThrowableProxy proxy = event.getThrowableProxy();
        proxy != null;
        proxy = proxy.getCause()) {
      line.append(' ').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
      StackTraceElementProxy[] frames = proxy.getStackTraceElementProxyArray();
      for (int i = 0; frames != null && i < frames.length; i++) {
        line.append(' ').append(frames[i].getSTEAsString());
      }
    }
    return line.toString();
  }
}
