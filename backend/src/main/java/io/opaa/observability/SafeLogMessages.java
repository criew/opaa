package io.opaa.observability;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.pattern.DynamicConverter;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.boot.context.event.ApplicationStartingEvent;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;
import org.springframework.context.ApplicationListener;

/**
 * Messages and exception messages of the plain-text log cannot forge an entry: a line break
 * continues indented by {@link #CONTINUATION}, any other control or bidi character shows as {@code
 * ?}, a tab stays. Replaces the converter of {@code %m}/{@code %msg}/{@code %message} and registers
 * {@code %opaaEx}, selected by {@code logging.exception-conversion-word} instead of {@code %wEx}.
 */
public final class SafeLogMessages {

  /** What a line break of a message becomes. */
  static final String CONTINUATION = "\n    ";

  /** The conversion word of the stack trace with safe messages. */
  static final String EXCEPTION_WORD = "opaaEx";

  private static final Pattern LINE_BREAK = Pattern.compile("\r\n|[\r\n\\x{85}\\x{2028}\\x{2029}]");

  private static final Pattern OTHER_UNSAFE =
      Pattern.compile(
          "[\\p{Cc}&&[^\n\t]]|[\\x{61C}\\x{200E}\\x{200F}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}]");

  private SafeLogMessages() {}

  /** The conversion rules this class contributes, by conversion word. */
  static Map<String, Supplier<DynamicConverter>> rules() {
    return Map.of(
        "m",
        MessageText::new,
        "msg",
        MessageText::new,
        "message",
        MessageText::new,
        EXCEPTION_WORD,
        ExceptionText::new);
  }

  /** Installs the rules as defaults of every pattern layout built from now on. */
  public static void install() {
    rules()
        .forEach(
            (word, supplier) -> {
              PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put(word, supplier);
              PatternLayout.DEFAULT_CONVERTER_MAP.put(word, supplier.get().getClass().getName());
            });
  }

  /** {@code text} as described in the class comment; {@code null} stays {@code null}. */
  static String safe(String text) {
    if (text == null) {
      return null;
    }
    String continued = LINE_BREAK.matcher(text).replaceAll(CONTINUATION);
    return OTHER_UNSAFE.matcher(continued).replaceAll("?");
  }

  /**
   * Installs the rules when an application starts, before Spring Boot configures its logging
   * system; registered in {@code META-INF/spring.factories}.
   */
  public static final class Installer implements ApplicationListener<ApplicationStartingEvent> {
    @Override
    public void onApplicationEvent(ApplicationStartingEvent event) {
      install();
    }
  }

  /** {@code %m} made safe. */
  public static final class MessageText extends MessageConverter {
    @Override
    public String convert(ILoggingEvent event) {
      return safe(super.convert(event));
    }
  }

  /** Spring Boot's {@code %wEx} with the message of every throwable made safe; frames untouched. */
  public static final class ExceptionText extends ExtendedWhitespaceThrowableProxyConverter {
    @Override
    protected String throwableProxyToString(IThrowableProxy proxy) {
      return super.throwableProxyToString(SafeThrowable.of(proxy));
    }
  }

  /** A throwable proxy whose messages, of its causes and suppressed ones too, are made safe. */
  record SafeThrowable(IThrowableProxy delegate) implements IThrowableProxy {

    static IThrowableProxy of(IThrowableProxy proxy) {
      return proxy == null ? null : new SafeThrowable(proxy);
    }

    @Override
    public String getMessage() {
      return safe(delegate.getMessage());
    }

    @Override
    public String getOverridingMessage() {
      return safe(delegate.getOverridingMessage());
    }

    @Override
    public String getClassName() {
      return delegate.getClassName();
    }

    @Override
    public StackTraceElementProxy[] getStackTraceElementProxyArray() {
      return delegate.getStackTraceElementProxyArray();
    }

    @Override
    public int getCommonFrames() {
      return delegate.getCommonFrames();
    }

    @Override
    public IThrowableProxy getCause() {
      return of(delegate.getCause());
    }

    @Override
    public IThrowableProxy[] getSuppressed() {
      IThrowableProxy[] suppressed = delegate.getSuppressed();
      return suppressed == null
          ? null
          : Arrays.stream(suppressed).map(SafeThrowable::of).toArray(IThrowableProxy[]::new);
    }

    @Override
    public boolean isCyclic() {
      return delegate.isCyclic();
    }
  }
}
