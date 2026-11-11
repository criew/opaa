import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { TextAction } from '@/shared/ui/DetailSection'
import ValueRow from '@/shared/ui/ValueRow'
import FormField from '@/shared/ui/form/FormField'
import FormDialog from '@/shared/ui/dialog/FormDialog'
import { confirmAction } from '@/shared/stores/confirmStore'
import { formatDate } from '@/shared/utils/formatDate'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import type {
  AssetResponsibilityResponse,
  GroupListResponse,
  ResponsibleUnitRequest,
} from '@/shared/api/types'
import ResponsibleUnitField from './ResponsibleUnitField'
import {
  EMPTY_RESPONSIBLE,
  RESPONSIBLE_REQUIRED,
  responsibleLabel,
  responsibleRequest,
  type ResponsibleDraft,
} from './responsibility'

function draftOf(responsibility: AssetResponsibilityResponse): ResponsibleDraft {
  if (responsibility.groupId) {
    const name = responsibility.groupName ?? ''
    return { group: { id: responsibility.groupId, name }, text: name }
  }
  return { group: null, text: responsibility.function ?? '' }
}

/**
 * The responsible unit and the lock of an agent or a template. Whoever manages the object changes
 * the unit; only the system administration locks and unlocks, a lock always with a reason.
 */
export default function AssetResponsibilitySection({
  idPrefix,
  name,
  responsibility,
  canEdit,
  canLock,
  groups,
  lockConsequence,
  onSave,
  onLock,
  onUnlock,
}: {
  idPrefix: string
  /** The object's name, for the confirmation. */
  name: string
  responsibility: AssetResponsibilityResponse
  canEdit: boolean
  /** Only the system administration locks and unlocks. */
  canLock: boolean
  groups: GroupListResponse[]
  /** What the lock means for this type, in one sentence. */
  lockConsequence: string
  onSave: (unit: ResponsibleUnitRequest) => Promise<void>
  onLock: (reason: string) => Promise<void>
  onUnlock: () => Promise<void>
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<ResponsibleDraft>(EMPTY_RESPONSIBLE)
  const [fieldError, setFieldError] = useState<string | undefined>()
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [lockOpen, setLockOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | undefined>()

  function openEditor() {
    setDraft(draftOf(responsibility))
    setFieldError(undefined)
    setError(null)
    setEditing(true)
  }

  async function save() {
    const unit = responsibleRequest(draft)
    if (!unit) {
      setFieldError(RESPONSIBLE_REQUIRED)
      return
    }
    setSaving(true)
    setError(null)
    try {
      await onSave(unit)
      setEditing(false)
    } catch (err) {
      const fromServer = apiFieldErrors(err).find((f) => f.field === 'responsible')
      if (fromServer) setFieldError(fromServer.message)
      else setError(err instanceof Error ? err.message : 'Speichern fehlgeschlagen')
    } finally {
      setSaving(false)
    }
  }

  async function lock() {
    if (reason.trim() === '') {
      setReasonError('Geben Sie an, warum gesperrt wird.')
      return
    }
    setSaving(true)
    try {
      await onLock(reason.trim())
      setLockOpen(false)
    } catch (err) {
      setReasonError(err instanceof Error ? err.message : 'Sperren fehlgeschlagen')
    } finally {
      setSaving(false)
    }
  }

  async function unlock() {
    const confirmed = await confirmAction({
      question: `„${name}“ entsperren?`,
      consequence: responsibility.missing
        ? 'Die Stelle fehlt noch: Bei der nächsten stündlichen Prüfung wird wieder gesperrt.'
        : 'Ab dem nächsten Aufruf ist die Sperre auf allen Instanzen aufgehoben.',
      confirmLabel: 'Entsperren',
      tone: 'caution',
    })
    if (!confirmed) return
    setError(null)
    try {
      await onUnlock()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Entsperren fehlgeschlagen')
    }
  }

  const currentLock = responsibility.lock
  return (
    <>
      <ValueRow
        title="Verantwortliche Stelle"
        value={
          <>
            <Box component="span" sx={{ fontWeight: 600 }}>
              {responsibleLabel(responsibility)}
            </Box>
            {responsibility.missing && (
              <Box component="span" sx={{ color: 'text.secondary' }}>
                {responsibility.lockDueAt
                  ? ` · fehlt, Sperre am ${formatDate(responsibility.lockDueAt)}`
                  : ' · fehlt'}
              </Box>
            )}
          </>
        }
        action={
          canEdit && !editing ? (
            <TextAction onClick={openEditor} context="Verantwortliche Stelle">
              Ändern
            </TextAction>
          ) : undefined
        }
      >
        {editing && (
          <Stack spacing={2}>
            <ResponsibleUnitField
              id={`${idPrefix}-responsible`}
              groups={groups}
              value={draft}
              onChange={(next) => {
                setDraft(next)
                setFieldError(undefined)
              }}
              error={fieldError}
            />
            <Stack direction="row" spacing={1}>
              <Button
                variant="contained"
                size="small"
                onClick={() => void save()}
                disabled={saving}
              >
                {saving ? 'Wird gespeichert …' : 'Speichern'}
              </Button>
              <Button size="small" onClick={() => setEditing(false)} disabled={saving}>
                Abbrechen
              </Button>
            </Stack>
          </Stack>
        )}
      </ValueRow>
      <ValueRow
        title="Sperre"
        value={
          currentLock ? (
            <>
              <Box component="span" sx={{ fontWeight: 600 }}>
                Gesperrt
              </Box>
              <Box component="span" sx={{ color: 'text.secondary' }}>
                {` · seit ${formatDate(currentLock.lockedAt)}${currentLock.reason ? ` · ${currentLock.reason}` : ''}`}
              </Box>
            </>
          ) : (
            'Nicht gesperrt'
          )
        }
        action={
          canLock ? (
            currentLock ? (
              <TextAction onClick={() => void unlock()} context="Sperre">
                Entsperren
              </TextAction>
            ) : (
              <TextAction
                onClick={() => {
                  setReason('')
                  setReasonError(undefined)
                  setLockOpen(true)
                }}
                context="Sperre"
              >
                Sperren
              </TextAction>
            )
          ) : undefined
        }
      />
      {error && <Alert severity="error">{error}</Alert>}
      <FormDialog
        open={lockOpen}
        title="Sperren"
        submitLabel="Sperren"
        onSubmit={() => void lock()}
        onClose={() => setLockOpen(false)}
        busy={saving}
        busyAnnouncement="Wird gesperrt"
      >
        <Box>
          <Typography sx={{ color: 'text.secondary', mb: 2 }}>{lockConsequence}</Typography>

          <FormField
            label="Grund"
            id={`${idPrefix}-lock-reason`}
            fullWidth
            size="small"
            multiline
            value={reason}
            onChange={(e) => {
              setReason(e.target.value)
              setReasonError(undefined)
            }}
            error={Boolean(reasonError)}
            helperText={
              reasonError ??
              'Sehen die Systemverwaltung und alle, die das Objekt verwalten; wer es nur nutzt, sieht nur, seit wann es gesperrt ist.'
            }
            slotProps={{ htmlInput: { maxLength: 500 } }}
          />
        </Box>
      </FormDialog>
    </>
  )
}
