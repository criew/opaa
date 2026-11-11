import { useState } from 'react'
import type { LlmModelResponse, ToolCapability } from '@/shared/api/types'
import RowMenu from '@/shared/ui/RowMenu'
import { testLlmModel } from '../api/modelApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { useRuntimeSwitchStore } from '@/shared/session/runtimeSwitches'
import { useLlmModelStore } from '../stores/llmModelStore'
import ToolCapabilityDialog from './ToolCapabilityDialog'
import ToolCheckDialog from './ToolCheckDialog'

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
  // „Werkzeuge prüfen“ only while agents are switched on; the server answers 404 otherwise
  const toolCheckOffered = useRuntimeSwitchStore((s) => s.agents)
  const activateExistingModel = useLlmModelStore((s) => s.activateExistingModel)
  const deleteExistingModel = useLlmModelStore((s) => s.deleteExistingModel)
  const [busy, setBusy] = useState(false)
  const [toolCapabilityOpen, setToolCapabilityOpen] = useState(false)
  const [toolCheckOpen, setToolCheckOpen] = useState(false)
  const [preset, setPreset] = useState<
    { value: ToolCapability; measurementRun: string } | undefined
  >(undefined)

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
        question: `„${model.displayName}“ löschen?`,
        consequence:
          'Das Modell und sein hinterlegter Schlüssel werden gelöscht; es steht danach nicht mehr zur Auswahl.',
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
    <>
      <RowMenu
        name={model.displayName}
        disabled={busy}
        items={[
          { label: 'Bearbeiten', onClick: () => onEdit(model) },
          { label: 'Verbindung testen', onClick: () => void test() },
          toolCheckOffered && { label: 'Werkzeuge prüfen', onClick: () => setToolCheckOpen(true) },
          {
            label: 'Werkzeugfähigkeit festlegen',
            onClick: () => {
              setPreset(undefined)
              setToolCapabilityOpen(true)
            },
          },
          !model.active && {
            label: 'Aktiv setzen',
            onClick: () =>
              void run(async () => {
                await activateExistingModel(model.id)
                return `„${model.displayName}“ ist jetzt das aktive Modell.`
              }, 'Aktivierung fehlgeschlagen'),
          },
          {
            label: 'Löschen',
            onClick: () => void remove(),
            danger: true,
            disabled: model.active,
            disabledReason: ACTIVE_DELETE_HINT,
          },
        ]}
      />
      {toolCheckOpen && (
        <ToolCheckDialog
          model={model}
          open
          onClose={() => setToolCheckOpen(false)}
          onAdopt={(proposal) => {
            setToolCheckOpen(false)
            setPreset(proposal)
            setToolCapabilityOpen(true)
          }}
        />
      )}
      {toolCapabilityOpen && (
        <ToolCapabilityDialog
          model={model}
          open
          preset={preset}
          onClose={() => setToolCapabilityOpen(false)}
          onSaved={(message) => notify(message, 'success')}
        />
      )}
    </>
  )
}
