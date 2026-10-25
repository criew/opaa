package io.opaa.indexing.run;

import io.opaa.indexing.job.EndsRun;

/**
 * The connection of a running run was connected anew, perhaps as another account: the run ends at
 * its next ask for the secret, past every item catch ({@link EndsRun}), before it reads or removes
 * anything with the new secret.
 */
public class ConnectionReconnectedException extends RuntimeException implements EndsRun {

  public static final String MESSAGE =
      "Das verbundene Konto wurde während des Laufs neu verbunden. Der Lauf endet, bevor er mit"
          + " dem neuen Konto etwas abgleicht; der nächste Lauf prüft das Konto neu.";

  public ConnectionReconnectedException() {
    super(MESSAGE);
  }
}
