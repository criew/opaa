import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Typography from '@mui/material/Typography'
import BoltOutlinedIcon from '@mui/icons-material/BoltOutlined'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import WifiTetheringOutlinedIcon from '@mui/icons-material/WifiTetheringOutlined'
import type { LlmModelResponse } from '../../../types/api'
import { testLlmModel } from '../../../services/api'
import { confirmAction } from '../../../stores/confirmStore'
import { notify } from '../../../stores/notificationStore'
import { useLlmModelStore } from '../../../stores/llmModelStore'

export const ACTIVE_DELETE_HINT =
  'Das aktive Modell kann nicht gelöscht werden – zuerst ein anderes Modell aktivieren.'

interface ModelRowMenuProps {
  model: LlmModelResponse
  onEdit: (model: LlmModelResponse) => void
}

/**
 * Das Zeilenmenü eines Chat-Modells (#1621). Bearbeiten öffnet den Dialog; Verbindungstest,
 * Aktivieren und Löschen wirken sofort und melden ihr Ergebnis als Benachrichtigung.
 *
 * Das Menü schließt sich, bevor eine Rückfrage aufgeht: Zwei übereinanderliegende Ebenen mit
 * eigenem Fokusfang wären weder mit der Tastatur noch mit einem Screenreader zu verlassen.
 */
export default function ModelRowMenu({ model, onEdit }: ModelRowMenuProps) {
  const activateExistingModel = useLlmModelStore((s) => s.activateExistingModel)
  const deleteExistingModel = useLlmModelStore((s) => s.deleteExistingModel)
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const [busy, setBusy] = useState(false)
  const reasonId = useId()

  const close = () => setAnchor(null)

  async function run(action: () => Promise<string>, fallback: string) {
    close()
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
    close()
    setBusy(true)
    try {
      // Ohne `apiKey`, aber mit `modelId`: Der Test greift damit auf den **gespeicherten**
      // Schlüssel dieses Modells zurück, statt ohne einen zu laufen.
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
    close()
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
    <>
      <IconButton
        size="small"
        onClick={(e) => setAnchor(e.currentTarget)}
        disabled={busy}
        aria-haspopup="true"
        aria-label={`Aktionen für „${model.displayName}“`}
      >
        <MoreVertIcon fontSize="small" />
      </IconButton>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={close}
        slotProps={{ list: { 'aria-label': `Aktionen für „${model.displayName}“` } }}
      >
        <MenuItem
          onClick={() => {
            close()
            onEdit(model)
          }}
        >
          <ListItemIcon>
            <EditOutlinedIcon fontSize="small" />
          </ListItemIcon>
          Bearbeiten
        </MenuItem>
        <MenuItem onClick={() => void test()}>
          <ListItemIcon>
            <WifiTetheringOutlinedIcon fontSize="small" />
          </ListItemIcon>
          Verbindung testen
        </MenuItem>
        {!model.active && (
          <MenuItem
            onClick={() =>
              void run(async () => {
                await activateExistingModel(model.id)
                return `„${model.displayName}“ ist jetzt das aktive Modell.`
              }, 'Aktivierung fehlgeschlagen')
            }
          >
            <ListItemIcon>
              <BoltOutlinedIcon fontSize="small" />
            </ListItemIcon>
            Aktiv setzen
          </MenuItem>
        )}
        <Divider />
        <MenuItem
          onClick={() => void remove()}
          disabled={model.active}
          aria-describedby={model.active ? reasonId : undefined}
        >
          <ListItemIcon>
            <DeleteOutlineIcon fontSize="small" color={model.active ? undefined : 'error'} />
          </ListItemIcon>
          <Typography sx={{ fontSize: 14, color: model.active ? undefined : 'error.main' }}>
            Löschen
          </Typography>
        </MenuItem>
        {model.active && (
          // Ein `<li role="presentation">` statt eines Absatzes: Ein `<p>` unter `role="menu"`
          // verletzt `aria-required-children`. Der gesperrte Eintrag verweist über
          // `aria-describedby` hierher, damit die Begründung auch vorgelesen wird - ein Tooltip
          // fände nur eine Maus.
          <Box
            component="li"
            role="presentation"
            id={reasonId}
            sx={{ px: 2, py: 1, maxWidth: 320 }}
          >
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              {ACTIVE_DELETE_HINT}
            </Typography>
          </Box>
        )}
      </Menu>
    </>
  )
}
