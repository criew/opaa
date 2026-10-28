import { useState } from 'react'
import BoltOutlinedIcon from '@mui/icons-material/BoltOutlined'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import WifiTetheringOutlinedIcon from '@mui/icons-material/WifiTetheringOutlined'
import type { LlmModelResponse } from '@/shared/api/types'
import RowMenu from '@/shared/ui/RowMenu'
import { testLlmModel } from '../api/modelApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { useLlmModelStore } from '../stores/llmModelStore'

export const ACTIVE_DELETE_HINT =
  'Das aktive Modell kann nicht gelöscht werden – zuerst ein anderes Modell aktivieren.'

interface ModelRowMenuProps {
  model: LlmModelResponse
  onEdit: (model: LlmModelResponse) => void
}

/**
 * The row menu of a chat model. Bearbeiten opens the dialog; connection test,
 * activation and deletion take effect at once and report their result as a notification.
 *
 * The menu closes before a confirmation opens: two stacked layers with their own
 * focus trap could be left neither with the keyboard nor with a screen reader.
 */
export default function ModelRowMenu({ model, onEdit }: ModelRowMenuProps) {
  const activateExistingModel = useLlmModelStore((s) => s.activateExistingModel)
  const deleteExistingModel = useLlmModelStore((s) => s.deleteExistingModel)
  const [busy, setBusy] = useState(false)

  async function run(action: () => Promise<string>, fallback: string) {
    setBusy(true)
    try {
      notify(await action(), 'success')
    } catch (err) {
      notify(err instanceof Error ? err.message : fallback, 'error')
    } finally {
      setBusy(false)
    }
  }

  async function test() {
    setBusy(true)
    try {
      // Without `apiKey`, but with `modelId`: the test thus falls back to this model's **stored**
      // key instead of running without one.
      const result = await testLlmModel({
        baseUrl: model.baseUrl,
        modelIdentifier: model.modelIdentifier,
        modelId: model.id,
      })
      notify(result.message, result.success ? 'success' : 'error')
    } catch (err) {
      notify(err instanceof Error ? err.message : 'Verbindungstest fehlgeschlagen', 'error')
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (
      !(await confirmAction({
        question: `Modell „${model.displayName}“ löschen?`,
        consequence: 'Diese Aktion kann nicht rückgängig gemacht werden.',
        confirmLabel: 'Löschen',
        tone: 'danger',
      }))
    ) {
      return
    }
    void run(async () => {
      await deleteExistingModel(model.id)
      return `„${model.displayName}“ wurde gelöscht.`
    }, 'Löschen fehlgeschlagen')
  }

  return (
    <RowMenu
      name={model.displayName}
      disabled={busy}
      items={[
        { label: 'Bearbeiten', icon: EditOutlinedIcon, onClick: () => onEdit(model) },
        { label: 'Verbindung testen', icon: WifiTetheringOutlinedIcon, onClick: () => void test() },
        !model.active && {
          label: 'Aktiv setzen',
          icon: BoltOutlinedIcon,
          onClick: () =>
            void run(async () => {
              await activateExistingModel(model.id)
              return `„${model.displayName}“ ist jetzt das aktive Modell.`
            }, 'Aktivierung fehlgeschlagen'),
        },
        {
          label: 'Löschen',
          icon: DeleteOutlineIcon,
          onClick: () => void remove(),
          danger: true,
          disabled: model.active,
          disabledReason: ACTIVE_DELETE_HINT,
        },
      ]}
    />
  )
}
