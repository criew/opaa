import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type {
  GroupListResponse,
  PermissionSubjectType,
  PermissionTransferPreviewResponse,
  PermissionTransferScope,
  UserSummary,
} from '../../types/api'
import { getGroups } from '../../services/api'
import {
  executePermissionTransfer,
  previewPermissionTransfer,
} from '../../services/permissionTransferApi'
import { apiErrorCode } from '../../services/apiErrorDetails'
import { notify } from '../../stores/notificationStore'
import UserPicker from '../groups/UserPicker'
import FieldLabel from '../wizard/FieldLabel'
import { groupIneffectiveReason, groupOptionLabel } from '../groups/groupOriginLabels'

const scopeLabels: Record<PermissionTransferScope, string> = {
  ASSET_GRANTS: 'Berechtigungen an Objekten',
  SPACE_MEMBERSHIPS: 'Space-Mitgliedschaften',
  CAPABILITIES: 'Anlegerechte',
  OWNERSHIP: 'Eigentum an Objekten',
  STEWARDSHIP: 'Verantwortung für interne Gruppen',
}

export interface TransferSubject {
  type: PermissionSubjectType
  id: string
  /** Nur für die Anzeige; bei einer Person als Quelle nennt das Backend keinen Namen. */
  name: string
}

interface PermissionTransferDialogProps {
  open: boolean
  onClose: () => void
  source: TransferSubject
  /** Welche Zielarten in Frage kommen — eine Person als Quelle gibt nur an eine Person ab. */
  targetKinds: PermissionSubjectType[]
  /** Welcher Umfang wählbar ist; die Vorauswahl ist der ganze angebotene Umfang. */
  scopes: PermissionTransferScope[]
  intro: string
  onTransferred?: () => void
}

/**
 * Die Übertragung von Wirkungen (ADR-0036, Entscheidung 10): Quelle und Ziel, der wählbare Umfang,
 * die Pflicht-Vorschau und die ausdrückliche Bestätigung. Verschiebt sich der Stand zwischen
 * Vorschau und Bestätigung, verlangt das Backend eine neue Vorlage — der Dialog sagt das und
 * beginnt bei der Vorschau von vorn.
 */
export default function PermissionTransferDialog({
  open,
  onClose,
  source,
  targetKinds,
  scopes,
  intro,
  onTransferred,
}: PermissionTransferDialogProps) {
  const [targetType, setTargetType] = useState<PermissionSubjectType>(targetKinds[0])
  const [targetGroupId, setTargetGroupId] = useState('')
  const [targetUser, setTargetUser] = useState<UserSummary | null>(null)
  const [selectedScopes, setSelectedScopes] = useState<PermissionTransferScope[]>(scopes)
  const [groups, setGroups] = useState<GroupListResponse[]>([])
  const [preview, setPreview] = useState<PermissionTransferPreviewResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Über den Wahrheitswert statt über `targetKinds`: Die Elternkomponente übergibt ein
  // Array-Literal, und an ihm hinge der Effekt bei jedem Render der Eltern neu.
  const needsGroups = targetKinds.includes('GROUP')

  useEffect(() => {
    if (!open || !needsGroups) return
    void getGroups()
      .then(setGroups)
      .catch(() => setGroups([]))
  }, [open, needsGroups])

  const targetId = targetType === 'GROUP' ? targetGroupId : (targetUser?.id ?? '')
  const ready = targetId !== '' && selectedScopes.length > 0

  function toggleScope(scope: PermissionTransferScope) {
    setPreview(null)
    setSelectedScopes((current) =>
      current.includes(scope) ? current.filter((s) => s !== scope) : [...current, scope],
    )
  }

  async function createPreview() {
    setBusy(true)
    setError(null)
    try {
      setPreview(
        await previewPermissionTransfer({
          sourceType: source.type,
          sourceId: source.id,
          targetType,
          targetId,
          scope: selectedScopes,
        }),
      )
    } catch (err) {
      setPreview(null)
      setError(err instanceof Error ? err.message : 'Die Vorschau konnte nicht erstellt werden.')
    } finally {
      setBusy(false)
    }
  }

  async function confirmTransfer() {
    if (!preview) return
    setBusy(true)
    setError(null)
    try {
      const result = await executePermissionTransfer({
        previewId: preview.previewId,
        sourceType: source.type,
        sourceId: source.id,
        targetType,
        targetId,
        scope: selectedScopes,
        confirmed: true,
      })
      notify(`Übertragen: ${result.summary}`, 'success')
      setPreview(null)
      onTransferred?.()
      onClose()
    } catch (err) {
      setPreview(null)
      setError(
        apiErrorCode(err) === 'TRANSFER_PREVIEW_REQUIRED'
          ? 'Der Stand hat sich seit der Vorschau geändert. Es wurde nichts übertragen — bitte die Vorschau erneut erstellen und prüfen.'
          : err instanceof Error
            ? err.message
            : 'Die Übertragung ist fehlgeschlagen.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Wirkungen übertragen</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 2 }}>{intro}</Typography>

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        <Stack spacing={2}>
          <Box>
            <FieldLabel htmlFor="transfer-source">Quelle</FieldLabel>
            <TextField id="transfer-source" fullWidth size="small" value={source.name} disabled />
          </Box>

          {targetKinds.length > 1 && (
            <Box>
              <FieldLabel htmlFor="transfer-target-type">Art des Ziels</FieldLabel>
              <TextField
                id="transfer-target-type"
                select
                fullWidth
                size="small"
                value={targetType}
                onChange={(e) => {
                  setPreview(null)
                  setTargetType(e.target.value as PermissionSubjectType)
                }}
              >
                {targetKinds.map((kind) => (
                  <MenuItem key={kind} value={kind}>
                    {kind === 'GROUP' ? 'Gruppe' : 'Person'}
                  </MenuItem>
                ))}
              </TextField>
            </Box>
          )}

          {targetType === 'GROUP' ? (
            <Box>
              <FieldLabel htmlFor="transfer-target-group">Zielgruppe</FieldLabel>
              <TextField
                id="transfer-target-group"
                select
                fullWidth
                size="small"
                value={targetGroupId}
                onChange={(e) => {
                  setPreview(null)
                  setTargetGroupId(e.target.value)
                }}
                slotProps={{ htmlInput: { 'aria-label': 'Zielgruppe' } }}
              >
                {/* Nicht wählbare Gruppen bleiben sichtbar und nennen ihren Grund — dieselben
                    drei, die das Backend abweist. Verstecken ließe die Person suchen. */}
                {groups
                  .filter((group) => group.id !== source.id)
                  .map((group) => (
                    <MenuItem
                      key={group.id}
                      value={group.id}
                      disabled={groupIneffectiveReason(group) !== null}
                    >
                      {groupOptionLabel(group)}
                    </MenuItem>
                  ))}
              </TextField>
            </Box>
          ) : (
            <Box>
              <FieldLabel htmlFor="transfer-target-user">Zielperson</FieldLabel>
              <UserPicker
                ariaLabel="Zielperson"
                placeholder="Person suchen …"
                value={targetUser}
                onChange={(next) => {
                  setPreview(null)
                  setTargetUser(next)
                }}
                excludedUserIds={source.type === 'USER' ? [source.id] : []}
              />
            </Box>
          )}

          <Box>
            <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 0.5 }}>Umfang</Typography>
            {scopes.map((scope) => (
              <FormControlLabel
                key={scope}
                control={
                  <Checkbox
                    checked={selectedScopes.includes(scope)}
                    onChange={() => toggleScope(scope)}
                  />
                }
                label={scopeLabels[scope]}
              />
            ))}
          </Box>

          {preview && (
            <Alert severity="info">
              <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>Vorschau</Typography>
              <Typography sx={{ fontSize: 13.5 }}>{preview.summary}</Typography>
              <Typography sx={{ fontSize: 12.5, mt: 0.5 }}>
                Die Vorschau ist 30 Minuten gültig und selbst ein Protokollereignis. Erst die
                Bestätigung überträgt.
              </Typography>
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Abbrechen</Button>
        <Button onClick={() => void createPreview()} disabled={!ready || busy}>
          Vorschau erstellen
        </Button>
        <Button
          variant="contained"
          onClick={() => void confirmTransfer()}
          disabled={!preview || busy}
        >
          Übertragung bestätigen
        </Button>
      </DialogActions>
    </Dialog>
  )
}
