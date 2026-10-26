import { confirmAction } from '@/shared/stores/confirmStore'
import PageSection from '@/shared/ui/PageSection'
import { getIncidentScopeRetention, updateIncidentScopeRetention } from '../api/retentionApi'
import { useRetentionSetting } from '../hooks/useRetentionSetting'
import RetentionForm from './RetentionForm'
import { periodLabel, type RetentionField, type RetentionValues } from '../utils/retentionPeriod'
import RetentionLoadState from './RetentionLoadState'
import RetentionPauseNotice from '@/shared/ui/feedback/RetentionPauseNotice'

const FIELDS: RetentionField[] = [
  { key: 'retentionYears', label: 'Löschfrist (Kalenderjahre)', unit: 'years', min: 1, max: 10 },
]

/**
 * The period the requests and approvals of the anlassbezogene Klärung are kept. A longer period
 * belongs in the Dienstvereinbarung; a shorter one deletes evidence earlier and warns first.
 */
export default function IncidentScopeRetentionSection() {
  const { current, loadError, reload, save } = useRetentionSetting(getIncidentScopeRetention, {
    loadFailed: 'Die Löschfrist der anlassbezogenen Klärungen konnte nicht geladen werden.',
    saveFailed: 'Die Löschfrist der anlassbezogenen Klärungen konnte nicht gespeichert werden.',
  })

  async function handleSave(values: RetentionValues): Promise<boolean> {
    if (!current) return false
    const years = Number(values.retentionYears)
    const shorter = years < current.retentionYears
    const confirmed = await confirmAction({
      question: `Löschfrist der anlassbezogenen Klärungen auf ${periodLabel(years, 'years')} ändern?`,
      consequence: shorter
        ? 'Der nächste tägliche Lauf löscht Anträge und Freigaben früher als bisher; Gelöschtes kommt nicht zurück. Die Änderung steht im Revisionsprotokoll.'
        : 'Die längere Frist gilt ab dem nächsten täglichen Lauf; sie gehört in die Dienstvereinbarung. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Frist ändern',
      tone: shorter ? 'danger' : 'caution',
    })
    if (!confirmed) return false
    return save(
      updateIncidentScopeRetention({ retentionYears: years }),
      (saved) => `Die Löschfrist beträgt jetzt ${periodLabel(saved.retentionYears, 'years')}.`,
    )
  }

  async function handleReset(): Promise<boolean> {
    if (!current) return false
    const confirmed = await confirmAction({
      question: `Löschfrist der anlassbezogenen Klärungen auf die Werkseinstellung von ${periodLabel(current.defaultRetentionYears, 'years')} zurücksetzen?`,
      consequence: 'Gilt ab dem nächsten täglichen Lauf. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Werkseinstellung wiederherstellen',
      tone: current.defaultRetentionYears < current.retentionYears ? 'danger' : 'caution',
    })
    if (!confirmed) return false
    return save(updateIncidentScopeRetention({}), () => 'Es gilt wieder die Werkseinstellung.')
  }

  async function handleConfirmShown() {
    if (!current) return
    const confirmed = await confirmAction({
      question: `Löschfrist der anlassbezogenen Klärungen von ${periodLabel(current.retentionYears, 'years')} festlegen?`,
      consequence:
        'Danach gilt der angezeigte Wert als eigener Wert der Verwaltung, und der nächste nächtliche Lauf löscht nach ihm, sobald seine Frist erreicht ist. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Wert festlegen',
      tone: 'danger',
    })
    if (!confirmed) return
    await save(
      updateIncidentScopeRetention({ retentionYears: current.retentionYears }),
      () => 'Die Löschfrist ist festgelegt.',
    )
  }

  return (
    <PageSection
      title="Anlassbezogene Klärungen"
      description="Anträge und Freigaben einer anlassbezogenen Klärung werden am Ende des Kalenderjahres gelöscht, das so viele Jahre nach dem Ende ihrer Nutzbarkeit liegt. Die Zahl der Fälle je Monat und Zweck bleibt erhalten."
    >
      {current === null ? (
        <RetentionLoadState loadError={loadError} onRetry={reload} />
      ) : (
        <>
          <RetentionPauseNotice
            pauses={current.pausedBy}
            onConfirm={() => void handleConfirmShown()}
          />
          <RetentionForm
            key={`${current.retentionYears}-${current.overridden}`}
            fields={FIELDS}
            values={{ retentionYears: current.retentionYears }}
            defaults={{ retentionYears: current.defaultRetentionYears }}
            overridden={current.overridden}
            onSave={handleSave}
            onReset={handleReset}
          />
        </>
      )}
    </PageSection>
  )
}
