import type { MailSettingsResponse } from '../../../types/api'
import KeyValueList from '../../KeyValueList'
import StatusLine, { type StatusTone } from '../../StatusLine'
import { formatMailTimestamp, mailStatusOf } from './mailStatus'

const TONE: Record<string, StatusTone> = {
  UNCONFIGURED: 'neutral',
  DISABLED: 'neutral',
  UNTESTED: 'warning',
  SUCCESS: 'success',
  FAILURE: 'error',
}

/**
 * „Letzter erfolgreicher Versand" und „letzter Fehler" an einer Stelle (ADR-0033, Entscheidung
 * 10) - beide Zeilen bleiben stehen, auch wenn die Verdikt-Zeile nur eine von beiden nennt: nach
 * einem geglückten Test ist der vorherige Fehler die Information, die erklärt, was repariert wurde.
 *
 * Seit #1608 eine Zeile mit Punkt statt einer Karte: Der Zustand ist eine Aussage, kein Objekt,
 * das einen Rahmen bräuchte.
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
