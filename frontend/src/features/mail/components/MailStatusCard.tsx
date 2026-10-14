import type { MailSettingsResponse } from '@/shared/api/types'
import KeyValueList from '@/shared/ui/KeyValueList'
import StatusLine, { type StatusTone } from '@/shared/ui/StatusLine'
import { formatMailTimestamp, mailStatusOf } from './mailStatus'

const TONE: Record<string, StatusTone> = {
  UNCONFIGURED: 'neutral',
  DISABLED: 'neutral',
  UNTESTED: 'warning',
  SUCCESS: 'success',
  FAILURE: 'error',
}

/**
 * „Letzter erfolgreicher Versand" and „letzter Fehler" in one place (ADR-0033, Entscheidung
 * 10) - both lines stay, even when the verdict line names only one of them: after
 * a successful test the previous error is the information that explains what was repaired.
 *
 * A line with a dot instead of a card: the state is a statement, not an object
 * that would need a frame.
 */
export default function MailStatusCard({ settings }: { settings: MailSettingsResponse }) {
  const status = mailStatusOf(settings)
  const success = formatMailTimestamp(settings.lastSuccessAt)
  const failure = formatMailTimestamp(settings.lastFailureAt)

  return (
    <StatusLine
      headline={status.headline}
      detail={status.detail}
      tone={TONE[status.kind] ?? 'neutral'}
      component="h2"
      label="Versandstatus"
    >
      <KeyValueList
        entries={[
          { label: 'Letzter erfolgreicher Versand', value: success ?? 'noch keiner' },
          {
            label: 'Letzter Fehler',
            value: failure
              ? `${failure} — ${settings.lastFailureReason ?? 'ohne Grund'}`
              : 'keiner',
          },
        ]}
      />
    </StatusLine>
  )
}
