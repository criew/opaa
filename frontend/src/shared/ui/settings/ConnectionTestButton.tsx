import BusyButton from '../a11y/BusyButton'

/**
 * „Verbindung testen“ (guidelines 5.16): secondary and always active, beside „Speichern“ on a
 * page and at the left of a dialog's footer. The test takes the entries as they stand, saved or
 * not; its result stands inline below.
 */
export default function ConnectionTestButton({
  busy,
  busyAnnouncement = 'Verbindung wird getestet',
  onTest,
}: {
  /** True while the test runs. */
  busy: boolean
  busyAnnouncement?: string
  onTest: () => void
}) {
  return (
    <BusyButton variant="outlined" busy={busy} busyAnnouncement={busyAnnouncement} onClick={onTest}>
      Verbindung testen
    </BusyButton>
  )
}
