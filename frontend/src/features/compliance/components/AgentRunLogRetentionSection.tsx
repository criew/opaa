import { confirmAction } from '@/shared/stores/confirmStore'
import PageSection from '@/shared/ui/PageSection'
import { getAgentRunLogRetention, updateAgentRunLogRetention } from '../api/retentionApi'
import { useRetentionSetting } from '../hooks/useRetentionSetting'
import RetentionForm from './RetentionForm'
import { periodLabel, type RetentionField, type RetentionValues } from '../utils/retentionPeriod'
import RetentionLoadState from './RetentionLoadState'

const DEFAULT_DAYS = 7

const FIELDS: RetentionField[] = [
  { key: 'stepLogRetentionDays', label: 'Schrittprotokoll (Tage)', unit: 'days', min: 1, max: 30 },
  {
    key: 'sandboxLogRetentionDays',
    label: 'Sandboxprotokoll (Tage)',
    unit: 'days',
    min: 1,
    max: 30,
  },
]

/**
 * The periods of the step log and the sandbox log of agent runs. The deletion cannot be switched
 * off; a shorter period deletes with the next daily run and asks with a warning first.
 */
export default function AgentRunLogRetentionSection() {
  const { current, loadError, reload, save } = useRetentionSetting(getAgentRunLogRetention, {
    loadFailed: 'Die Fristen der Agentenprotokolle konnten nicht geladen werden.',
    saveFailed: 'Die Fristen der Agentenprotokolle konnten nicht gespeichert werden.',
  })

  async function change(step: number, sandbox: number, success: string): Promise<boolean> {
    if (!current) return false
    const shorter = step < current.stepLogRetentionDays || sandbox < current.sandboxLogRetentionDays
    const confirmed = await confirmAction({
      question: `Fristen der Agentenprotokolle auf ${periodLabel(step, 'days')} und ${periodLabel(sandbox, 'days')} ändern?`,
      consequence: shorter
        ? 'Ältere Einträge löscht der nächste tägliche Lauf endgültig. Die Änderung steht im Revisionsprotokoll.'
        : 'Die längeren Fristen gelten ab dem nächsten täglichen Lauf. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Fristen ändern',
      tone: shorter ? 'danger' : 'caution',
    })
    if (!confirmed) return false
    return save(
      updateAgentRunLogRetention({ stepLogRetentionDays: step, sandboxLogRetentionDays: sandbox }),
      () => success,
    )
  }

  function handleSave(values: RetentionValues): Promise<boolean> {
    return change(
      Number(values.stepLogRetentionDays),
      Number(values.sandboxLogRetentionDays),
      'Die Fristen der Agentenprotokolle sind gespeichert.',
    )
  }

  function handleReset(): Promise<boolean> {
    return change(DEFAULT_DAYS, DEFAULT_DAYS, 'Es gilt wieder die Werkseinstellung.')
  }

  return (
    <PageSection
      title="Protokolle der Agentenläufe"
      description="Wie lange das Schrittprotokoll und das Sandboxprotokoll der Agenten für die Fehlersuche aufbewahrt werden. Das Sandboxprotokoll höchstens so lange wie das Schrittprotokoll; die Löschung lässt sich nicht abschalten."
    >
      {current === null ? (
        <RetentionLoadState loadError={loadError} onRetry={reload} />
      ) : (
        <RetentionForm
          key={`${current.stepLogRetentionDays}-${current.sandboxLogRetentionDays}`}
          fields={FIELDS}
          values={{
            stepLogRetentionDays: current.stepLogRetentionDays,
            sandboxLogRetentionDays: current.sandboxLogRetentionDays,
          }}
          defaults={{ stepLogRetentionDays: DEFAULT_DAYS, sandboxLogRetentionDays: DEFAULT_DAYS }}
          overridden={
            current.stepLogRetentionDays !== DEFAULT_DAYS ||
            current.sandboxLogRetentionDays !== DEFAULT_DAYS
          }
          onSave={handleSave}
          onReset={handleReset}
        />
      )}
    </PageSection>
  )
}
