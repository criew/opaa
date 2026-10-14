import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useNavigate } from 'react-router'
import type { SpaceResponse } from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'
import { useSpaceStore } from '../stores/spaceStore'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import ChatAutoCleanupField from './ChatAutoCleanupField'
import ModelInstructionField from './ModelInstructionField'
import SectionHead from '@/shared/ui/SectionHead'

interface SpaceGeneralSectionProps {
  spaceId: string
  space: SpaceResponse
  /** Only an administrator changes name, description, chat cleanup and instruction. */
  canManage: boolean
  /** Archiving and deleting stay reserved for the owner. */
  isOwner: boolean
}

/**
 * The „Stammdaten" tab of the space settings: name, description, the switch of the
 * automatic chat cleanup and the instruction to the model that every member sees, and at the
 * end the set-off danger zone with archiving and deleting.
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
    chatAutoCleanup: boolean
    modelInstruction: string
  }>({ spaceId: null, name: '', description: '', chatAutoCleanup: false, modelInstruction: '' })
  const [localError, setLocalError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  // deleteSpace's 409 - "Der Space enthält noch Chats ... Archivieren Sie den Space
  // stattdessen." - is the one failure this page offers a direct way out of, instead of just
  // showing the message.
  const [deleteBlockedByChats, setDeleteBlockedByChats] = useState(false)

  const name = draft.spaceId === spaceId ? draft.name : (space.name ?? '')
  const description = draft.spaceId === spaceId ? draft.description : (space.description ?? '')
  const chatAutoCleanup =
    draft.spaceId === spaceId ? draft.chatAutoCleanup : space.chatAutoCleanup.enabled
  const savedModelInstruction = space.modelInstruction ?? ''
  const modelInstruction =
    draft.spaceId === spaceId ? draft.modelInstruction : savedModelInstruction
  const current = { spaceId, name, description, chatAutoCleanup, modelInstruction }

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
              onChange={(event) => setDraft({ ...current, name: event.target.value })}
              slotProps={{ htmlInput: { readOnly: !canManage } }}
            />
          </Box>
          <Box>
            <FieldLabel htmlFor="space-manage-description">Beschreibung</FieldLabel>
            <TextField
              id="space-manage-description"
              size="small"
              fullWidth
              value={description}
              onChange={(event) => setDraft({ ...current, description: event.target.value })}
              multiline
              minRows={2}
              slotProps={{ htmlInput: { readOnly: !canManage } }}
            />
          </Box>
          <ChatAutoCleanupField
            id="space-chat-auto-cleanup"
            checked={chatAutoCleanup}
            onChange={(checked) => setDraft({ ...current, chatAutoCleanup: checked })}
            disabled={!canManage || (space.isDefault && !isOwner)}
            personalSpaceOfOther={space.isDefault && !isOwner}
            archiveAfterDays={space.chatAutoCleanup.archiveAfterDays}
            deleteAfterDays={space.chatAutoCleanup.deleteAfterDays}
          />
          <ModelInstructionField
            id="space-model-instruction"
            value={modelInstruction}
            onChange={(value) => setDraft({ ...current, modelInstruction: value })}
            canChange={canManage}
          />
          {canManage && (
            <Box>
              <Button
                variant="contained"
                onClick={async () => {
                  setLocalError(null)
                  try {
                    // Switch and instruction are sent only if they were changed: a mere
                    // rename thus resets no state someone else has changed
                    // in the meantime.
                    await updateDetails(
                      spaceId,
                      name,
                      description,
                      chatAutoCleanup !== space.chatAutoCleanup.enabled &&
                        !(space.isDefault && !isOwner)
                        ? chatAutoCleanup
                        : undefined,
                      modelInstruction !== savedModelInstruction ? modelInstruction : undefined,
                    )
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

      {/* Danger zone after GitLab's pattern: the two consequential actions
         are set off at the end of the master data, not between the fields. Whoever may not
         perform them does not see the area at all. */}
      {isOwner && !space.isDefault && (
        <Box
          sx={{
            border: 1,
            borderColor: 'error.main',
            borderRadius: 1,
            p: 2.5,
          }}
        >
          {/* No heading repeats the tab's name, so this is the panel's h2. */}
          <SectionHead>Gefahrenbereich</SectionHead>
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
                    // deleteSpace's own 409 message names archiving as the way out - offer
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
