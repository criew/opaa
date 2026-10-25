package io.opaa.connection.token;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

/** The thresholds and periods of the connections for unit tests, without a database. */
public final class TestLifecycleSettings {

  private TestLifecycleSettings() {}

  /** Every setting at its factory default, as on an installation the administration never set. */
  public static ConnectionLifecycleSettings factoryDefaults() {
    return over(Map.of());
  }

  /** {@code setting} at {@code value} as if the administration set it, the others at default. */
  public static ConnectionLifecycleSettings with(ConnectionLifecycleSetting setting, int value) {
    return over(Map.of(setting, value));
  }

  private static ConnectionLifecycleSettings over(Map<ConnectionLifecycleSetting, Integer> set) {
    ConnectionLifecycleSettingRepository repository =
        mock(ConnectionLifecycleSettingRepository.class);
    when(repository.findValue(anyString()))
        .thenAnswer(
            call ->
                Optional.ofNullable(
                    set.get(ConnectionLifecycleSetting.valueOf(call.<String>getArgument(0)))));
    return new ConnectionLifecycleSettings(repository);
  }
}
