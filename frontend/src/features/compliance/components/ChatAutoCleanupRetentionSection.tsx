import { confirmAction } from '@/shared/stores/confirmStore'
import PageSection from '@/shared/ui/PageSection'
import { getChatAutoCleanupRetention, updateChatAutoCleanupRetention } from '../api/retentionApi'
import { useRetentionSetting } from '../hooks/useRetentionSetting'
import RetentionForm from './RetentionForm'
import { periodLabel, type RetentionField, type RetentionValues } from '../utils/retentionPeriod'
import RetentionLoadState from './RetentionLoadState'
import RetentionPauseNotice from '@/shared/ui/feedback/RetentionPauseNotice'

const FIELDS: RetentionField[] = [
  { key: 'archiveAfterDays', label: 'Archivieren nach (Tage)', unit: 'days', min: 90, max: 3650 },
  { key: 'deleteAfterDays', label: 'Löschen nach (Tage)', unit: 'days', min: 30, max: 3650 },
]

/**
 * The periods of the automatic chat cleanup a space can switch on. A shorter period archives or
 * deletes earlier with the next nightly run and asks with a warning first.
 */
export default function ChatAutoCleanupRetentionSection() {
  const { current, loadError, reload, save } = useRetentionSetting(getChatAutoCleanupRetention, {
    loadFailed: 'Die Fristen der Chat-Bereinigung konnten nicht geladen werden.',
    saveFailed: 'Die Fristen der Chat-Bereinigung konnten nicht gespeichert werden.',
  })

  async function handleSave(values: RetentionValues): Promise<boolean> {
    if (!current) return false
    const archive = Number(values.archiveAfterDays)
    const remove = Number(values.deleteAfterDays)
    const shorter = archive < current.archiveAfterDays || remove < current.deleteAfterDays
    const confirmed = await confirmAction({
      question: `Fristen der Chat-Bereinigung auf ${periodLabel(archive, 'days')} und ${periodLabel(remove, 'days')} ändern?`,
      consequence: shorter
        ? 'In Spaces mit eingeschalteter Bereinigung archiviert oder löscht der nächste nächtliche Lauf Chats früher als bisher; Gelöschtes kommt nicht zurück. Die Änderung steht im Revisionsprotokoll.'
        : 'Die längeren Fristen gelten ab dem nächsten nächtlichen Lauf. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Fristen ändern',
      tone: shorter ? 'danger' : 'caution',
    })
    if (!confirmed) return false
    return save(
      updateChatAutoCleanupRetention({ archiveAfterDays: archive, deleteAfterDays: remove }),
      () => 'Die Fristen der Chat-Bereinigung sind gespeichert.',
    )
  }

  async function handleReset(): Promise<boolean> {
    if (!current) return false
    const shorter =
      current.defaultArchiveAfterDays < current.archiveAfterDays ||
      current.defaultDeleteAfterDays < current.deleteAfterDays
    const confirmed = await confirmAction({
      question: `Fristen der Chat-Bereinigung auf die Werkseinstellung von ${periodLabel(current.defaultArchiveAfterDays, 'days')} und ${periodLabel(current.defaultDeleteAfterDays, 'days')} zurücksetzen?`,
      consequence: shorter
        ? 'In Spaces mit eingeschalteter Bereinigung archiviert oder löscht der nächste nächtliche Lauf Chats früher als bisher; Gelöschtes kommt nicht zurück. Die Änderung steht im Revisionsprotokoll.'
        : 'Gilt ab dem nächsten nächtlichen Lauf für alle Spaces mit eingeschalteter Bereinigung. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Werkseinstellung wiederherstellen',
      tone: shorter ? 'danger' : 'caution',
    })
    if (!confirmed) return false
    return save(updateChatAutoCleanupRetention({}), () => 'Es gilt wieder die Werkseinstellung.')
  }

  async function handleConfirmShown() {
    if (!current) return
    const confirmed = await confirmAction({
      question: `Fristen der Chat-Bereinigung von ${periodLabel(current.archiveAfterDays, 'days')} und ${periodLabel(current.deleteAfterDays, 'days')} festlegen?`,
      consequence:
        'Danach gilt der angezeigte Wert als eigener Wert der Verwaltung, und der nächste nächtliche Lauf löscht nach ihm, sobald seine Frist erreicht ist. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Werte festlegen',
      tone: 'danger',
    })
    if (!confirmed) return
    await save(
      updateChatAutoCleanupRetention({
        archiveAfterDays: current.archiveAfterDays,
        deleteAfterDays: current.deleteAfterDays,
      }),
      () => 'Die Fristen der Chat-Bereinigung sind festgelegt.',
    )
  }

  return (
    <PageSection
      title="Bereinigung der Chats in Spaces"
      description="Fristen für alle Spaces mit automatischer Bereinigung: Archivieren nicht angehefteter Chats nach Tagen ohne Aktivität, Löschen nach Tagen im Archiv."
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
            key={`${current.archiveAfterDays}-${current.deleteAfterDays}-${current.overridden}`}
            fields={FIELDS}
            values={{
              archiveAfterDays: current.archiveAfterDays,
              deleteAfterDays: current.deleteAfterDays,
            }}
            defaults={{
              archiveAfterDays: current.defaultArchiveAfterDays,
              deleteAfterDays: current.defaultDeleteAfterDays,
            }}
            overridden={current.overridden}
            onSave={handleSave}
            onReset={handleReset}
          />
        </>
      )}
    </PageSection>
  )
}
