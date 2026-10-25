import { confirmAction } from '@/shared/stores/confirmStore'
import PageSection from '@/shared/ui/PageSection'
import { getChatAutoCleanupRetention, updateChatAutoCleanupRetention } from '../api/retentionApi'
import { useRetentionSetting } from '../hooks/useRetentionSetting'
import RetentionForm from './RetentionForm'
import { periodLabel, type RetentionField, type RetentionValues } from '../utils/retentionPeriod'
import RetentionLoadState from './RetentionLoadState'

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
    const confirmed = await confirmAction({
      question: `Fristen der Chat-Bereinigung auf die Werkseinstellung von ${periodLabel(current.defaultArchiveAfterDays, 'days')} und ${periodLabel(current.defaultDeleteAfterDays, 'days')} zurücksetzen?`,
      consequence:
        'Gilt ab dem nächsten nächtlichen Lauf für alle Spaces mit eingeschalteter Bereinigung. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Werkseinstellung wiederherstellen',
      tone: 'caution',
    })
    if (!confirmed) return false
    return save(updateChatAutoCleanupRetention({}), () => 'Es gilt wieder die Werkseinstellung.')
  }

  return (
    <PageSection
      title="Bereinigung der Chats in Spaces"
      description="Schaltet ein Space die automatische Bereinigung ein, archiviert OPAA nicht angeheftete Chats nach so vielen Tagen ohne Aktivität und löscht archivierte Chats nach so vielen Tagen im Archiv. Die Fristen gelten für alle Spaces gleich."
    >
      {current === null ? (
        <RetentionLoadState loadError={loadError} onRetry={reload} />
      ) : (
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
      )}
    </PageSection>
  )
}
