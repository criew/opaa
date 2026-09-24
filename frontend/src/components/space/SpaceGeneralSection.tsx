import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControl from '@mui/material/FormControl'
import FormHelperText from '@mui/material/FormHelperText'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useNavigate } from 'react-router'
import type { SpaceResponse, SpaceVisibility } from '../../types/api'
import { confirmAction } from '../../stores/confirmStore'
import { useSpaceStore } from '../../stores/spaceStore'
import {
  spaceVisibilities,
  spaceVisibilityDescription,
  spaceVisibilityLabel,
} from '../../utils/labels'
import FieldLabel from '../wizard/FieldLabel'
import SectionHead from '../SectionHead'

interface SpaceGeneralSectionProps {
  spaceId: string
  space: SpaceResponse
  /** Nur ein Administrator ändert Name, Beschreibung und Sichtbarkeit. */
  canManage: boolean
  /** Archivieren und Löschen bleiben dem Eigentümer vorbehalten. */
  isOwner: boolean
}

/**
 * Der Reiter „Stammdaten" der Space-Einstellungen: Name, Beschreibung und Sichtbarkeit, und am
 * Ende der abgesetzte Gefahrenbereich mit Archivieren und Löschen (#1917).
 */
export default function SpaceGeneralSection({
  spaceId,
  space,
  canManage,
  isOwner,
}: SpaceGeneralSectionProps) {
  const navigate = useNavigate()
  const storeError = useSpaceStore((s) => s.error)
  const updateDetails = useSpaceStore((s) => s.updateDetails)
  const deleteSelectedSpace = useSpaceStore((s) => s.deleteSelectedSpace)
  const archiveSelectedSpace = useSpaceStore((s) => s.archiveSelectedSpace)
  const [draft, setDraft] = useState<{
    spaceId: string | null
    name: string
    description: string
    visibility: SpaceVisibility
  }>({ spaceId: null, name: '', description: '', visibility: 'PRIVATE' })
  const [localError, setLocalError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  // #543: deleteSpace's 409 - "Der Space enthält noch Chats ... Archivieren Sie den Space
  // stattdessen." - is the one failure this page offers a direct way out of, instead of just
  // showing the message.
  const [deleteBlockedByChats, setDeleteBlockedByChats] = useState(false)

  const name = draft.spaceId === spaceId ? draft.name : (space.name ?? '')
  const description = draft.spaceId === spaceId ? draft.description : (space.description ?? '')
  const visibility = draft.spaceId === spaceId ? draft.visibility : (space.visibility ?? 'PRIVATE')

  async function archive() {
    setLocalError(null)
    setDeleteBlockedByChats(false)
    try {
      await archiveSelectedSpace(spaceId)
      setSuccessMessage('Space archiviert')
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : 'Archivieren fehlgeschlagen')
    }
  }

  return (
    <Stack spacing={5}>
      {(localError || storeError) && (
        <Alert
          severity="error"
          action={
            deleteBlockedByChats ? (
              <Button color="inherit" size="small" onClick={archive}>
                Space archivieren
              </Button>
            ) : undefined
          }
        >
          {localError ?? storeError}
        </Alert>
      )}
      {successMessage && <Alert severity="success">{successMessage}</Alert>}

      <Box>
        {/* Die Reiterleiste bringt keine Überschrift mit: Jedes Panel beginnt deshalb mit seiner
            eigenen h2 unter der h1 der Seite, damit die Ebenen lückenlos bleiben. */}
        <SectionHead>Stammdaten</SectionHead>
        {space.archived && (
          <Alert severity="info" sx={{ mb: 2 }}>
            Dieser Space ist archiviert und nimmt keinen neuen Inhalt mehr an. Private Chats bleiben
            für ihre Autoren weiterhin lesbar.
          </Alert>
        )}
        <Stack spacing={2.5}>
          <Box>
            <FieldLabel htmlFor="space-manage-name">Name des Space</FieldLabel>
            <TextField
              id="space-manage-name"
              size="small"
              fullWidth
              value={name}
              onChange={(event) =>
                setDraft({ spaceId, name: event.target.value, description, visibility })
              }
              disabled={!canManage}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor="space-manage-description">Beschreibung</FieldLabel>
            <TextField
              id="space-manage-description"
              size="small"
              fullWidth
              value={description}
              onChange={(event) =>
                setDraft({ spaceId, name, description: event.target.value, visibility })
              }
              multiline
              minRows={2}
              disabled={!canManage}
            />
          </Box>
          <FormControl disabled={!canManage} fullWidth>
            <FieldLabel id="space-visibility-label">Sichtbarkeit</FieldLabel>
            <Select
              labelId="space-visibility-label"
              size="small"
              value={visibility}
              onChange={(event) =>
                setDraft({
                  spaceId,
                  name,
                  description,
                  visibility: event.target.value as SpaceVisibility,
                })
              }
              aria-describedby="space-visibility-helper"
            >
              {spaceVisibilities.map((option) => (
                <MenuItem key={option} value={option}>
                  {spaceVisibilityLabel(option)}
                </MenuItem>
              ))}
            </Select>
            <FormHelperText id="space-visibility-helper">
              {spaceVisibilityDescription(visibility)}
            </FormHelperText>
          </FormControl>
          {canManage && (
            <Box>
              <Button
                variant="contained"
                onClick={async () => {
                  setLocalError(null)
                  try {
                    await updateDetails(spaceId, name, description, visibility)
                    setSuccessMessage('Space aktualisiert')
                  } catch (err) {
                    setLocalError(
                      err instanceof Error ? err.message : 'Aktualisierung fehlgeschlagen',
                    )
                  }
                }}
              >
                Einstellungen speichern
              </Button>
            </Box>
          )}
        </Stack>
      </Box>

      {/* Gefahrenbereich nach dem Muster von GitLab (#1917): die beiden folgenreichen Handlungen
          stehen abgesetzt am Ende der Stammdaten, nicht zwischen den Feldern. Wer sie nicht
          ausführen darf, sieht den Bereich gar nicht. */}
      {isOwner && !space.isDefault && (
        <Box
          sx={{
            border: 1,
            borderColor: 'error.main',
            borderRadius: 1,
            p: 2.5,
          }}
        >
          <SectionHead component="h3">Gefahrenbereich</SectionHead>
          <Stack spacing={2} sx={{ mt: 1.5 }}>
            {!space.archived && (
              <Stack
                direction={{ xs: 'column', md: 'row' }}
                spacing={1.5}
                sx={{ justifyContent: 'space-between', alignItems: { md: 'center' } }}
              >
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Den Space archivieren: Er nimmt danach keinen neuen Inhalt mehr an und wird aus
                  den regulären Listen ausgeblendet. Umkehrbar durch die Systemverwaltung.
                </Typography>
                <Button
                  variant="outlined"
                  sx={{ flex: 'none' }}
                  onClick={async () => {
                    const confirmed = await confirmAction({
                      question: 'Diesen Space archivieren?',
                      consequence:
                        'Er nimmt danach keinen neuen Inhalt mehr an und wird aus den regulären Listen ausgeblendet.',
                      confirmLabel: 'Archivieren',
                      tone: 'caution',
                    })
                    if (!confirmed) return
                    await archive()
                  }}
                >
                  Space archivieren
                </Button>
              </Stack>
            )}
            <Stack
              direction={{ xs: 'column', md: 'row' }}
              spacing={1.5}
              sx={{ justifyContent: 'space-between', alignItems: { md: 'center' } }}
            >
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                Den Space löschen: Chats, Mitgliedschaften und Zuordnungen dieses Space gehen
                verloren. Nicht umkehrbar.
              </Typography>
              <Button
                color="error"
                variant="outlined"
                sx={{ flex: 'none' }}
                onClick={async () => {
                  const confirmed = await confirmAction({
                    question: 'Diesen Space löschen?',
                    consequence: 'Diese Aktion kann nicht rückgängig gemacht werden.',
                    confirmLabel: 'Löschen',
                    tone: 'danger',
                  })
                  if (!confirmed) return
                  setLocalError(null)
                  setDeleteBlockedByChats(false)
                  try {
                    await deleteSelectedSpace(spaceId)
                    navigate('/spaces')
                  } catch (err) {
                    const message = err instanceof Error ? err.message : 'Löschen fehlgeschlagen'
                    setLocalError(message)
                    // #543: deleteSpace's own 409 message names archiving as the way out - offer
                    // it directly instead of leaving the user to figure out the next step.
                    setDeleteBlockedByChats(message.includes('Archivieren'))
                  }
                }}
              >
                Space löschen
              </Button>
            </Stack>
          </Stack>
        </Box>
      )}
    </Stack>
  )
}
