import { confirmAction } from '@/shared/stores/confirmStore'
import PageSection from '@/shared/ui/PageSection'
import {
  getLockedAccountChatRetention,
  updateLockedAccountChatRetention,
} from '../api/retentionApi'
import { useRetentionSetting } from '../hooks/useRetentionSetting'
import RetentionForm from './RetentionForm'
import {
  periodLabel,
  type RetentionField,
  type RetentionToggle,
  type RetentionValues,
} from '../utils/retentionPeriod'
import RetentionLoadState from './RetentionLoadState'
import RetentionPauseNotice from '@/shared/ui/feedback/RetentionPauseNotice'

const FIELDS: RetentionField[] = [
  { key: 'deleteAfterDays', label: 'Löschen nach (Tage)', unit: 'days', min: 90, max: 3650 },
]

const TOGGLE: RetentionToggle = { key: 'enabled', label: 'Chats nach der Sperre löschen' }

/**
 * Switch and period of the deletion of the chats of local accounts the administration locked.
 * Switching off keeps every chat and starts every period anew once switched on again.
 */
export default function LockedAccountChatRetentionSection() {
  const { current, loadError, reload, save } = useRetentionSetting(getLockedAccountChatRetention, {
    loadFailed: 'Die Löschung der Chats gesperrter Konten konnte nicht geladen werden.',
    saveFailed: 'Die Löschung der Chats gesperrter Konten konnte nicht gespeichert werden.',
  })

  async function handleSave(values: RetentionValues): Promise<boolean> {
    if (!current) return false
    const enabled = Boolean(values.enabled)
    const days = Number(values.deleteAfterDays)
    // Switching on starts every period anew, so only a shorter period while on deletes earlier.
    const earlier = enabled && current.enabled && days < current.deleteAfterDays
    const confirmed = await confirmAction({
      question: enabled
        ? `Chats gesperrter Konten ${periodLabel(days, 'days')} nach der Sperre löschen?`
        : 'Löschung der Chats gesperrter Konten abschalten?',
      consequence: enabled
        ? earlier
          ? 'Der nächste nächtliche Lauf löscht die Chats gesperrter Konten früher als bisher; Gelöschtes kommt nicht zurück. Die Änderung steht im Revisionsprotokoll.'
          : current.enabled
            ? 'Die Frist gilt ab dem nächsten nächtlichen Lauf. Die Änderung steht im Revisionsprotokoll.'
            : 'Jede Frist beginnt mit dem nächsten nächtlichen Lauf neu. Die Änderung steht im Revisionsprotokoll.'
        : 'Alle Chats bleiben erhalten; nach dem Wiedereinschalten beginnt jede Frist neu. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Ändern',
      tone: earlier ? 'danger' : 'caution',
    })
    if (!confirmed) return false
    return save(
      updateLockedAccountChatRetention({ enabled, deleteAfterDays: days }),
      () => 'Die Löschung der Chats gesperrter Konten ist gespeichert.',
    )
  }

  async function handleReset(): Promise<boolean> {
    if (!current) return false
    const earlier =
      current.enabled &&
      current.defaultEnabled &&
      current.defaultDeleteAfterDays < current.deleteAfterDays
    const confirmed = await confirmAction({
      question: `Löschung der Chats gesperrter Konten auf die Werkseinstellung zurücksetzen (${current.defaultEnabled ? 'eingeschaltet' : 'abgeschaltet'}, ${periodLabel(current.defaultDeleteAfterDays, 'days')})?`,
      consequence: earlier
        ? 'Der nächste nächtliche Lauf löscht die Chats gesperrter Konten früher als bisher; Gelöschtes kommt nicht zurück. Die Änderung steht im Revisionsprotokoll.'
        : 'Gilt ab dem nächsten nächtlichen Lauf. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Werkseinstellung wiederherstellen',
      tone: earlier ? 'danger' : 'caution',
    })
    if (!confirmed) return false
    return save(updateLockedAccountChatRetention({}), () => 'Es gilt wieder die Werkseinstellung.')
  }

  async function handleConfirmShown() {
    if (!current) return
    const confirmed = await confirmAction({
      question: `Löschung der Chats gesperrter Konten (${current.enabled ? 'eingeschaltet' : 'abgeschaltet'}, ${periodLabel(current.deleteAfterDays, 'days')}) festlegen?`,
      consequence:
        'Danach gilt der angezeigte Wert als eigener Wert der Verwaltung, und der nächste nächtliche Lauf löscht nach ihm, sobald seine Frist erreicht ist. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Werte festlegen',
      tone: 'danger',
    })
    if (!confirmed) return
    await save(
      updateLockedAccountChatRetention({
        enabled: current.enabled,
        deleteAfterDays: current.deleteAfterDays,
      }),
      () => 'Die Löschung der Chats gesperrter Konten ist festgelegt.',
    )
  }

  return (
    <PageSection
      title="Chats gesperrter Konten"
      description="Sperrt die Verwaltung ein lokales Konto, löscht OPAA dessen Chats nach so vielen Tagen. Eine Sperre wegen Inaktivität oder Fehlversuchen löst nichts aus; angehalten wird die Löschung je Konto in der Benutzerverwaltung."
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
            key={`${current.enabled}-${current.deleteAfterDays}-${current.overridden}`}
            fields={FIELDS}
            toggle={TOGGLE}
            values={{ enabled: current.enabled, deleteAfterDays: current.deleteAfterDays }}
            defaults={{
              enabled: current.defaultEnabled,
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
