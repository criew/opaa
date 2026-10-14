import { useState } from 'react'
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
  PermissionSubjectType,
  PermissionTransferPreviewResponse,
  PermissionTransferScope,
  SelectableGroupResponse,
  UserSummary,
} from '@/shared/api/types'
import { executePermissionTransfer, previewPermissionTransfer } from '../api/permissionTransferApi'
import { apiErrorCode } from '@/shared/api/apiErrorDetails'
import { notify } from '@/shared/stores/notificationStore'
import { UserPicker } from '@/features/principal'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { GroupPicker } from '@/features/principal'
import { confirmGroupSubject, PROTECTED_GROUP_SEARCH_HINT } from '@/features/principal'

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
  /** For display only; for a person as source the backend names no name. */
  name: string
}

interface PermissionTransferDialogProps {
  open: boolean
  onClose: () => void
  /** The fixed source, when the caller knows it (group, own account). */
  source?: TransferSubject
  /**
   * Instead of a fixed source: the source is chosen here. The operations list needs that, because
   * a row names the previous owner only as text — the API deliberately does not hand out an
   * identifier (ADR-0036, Entscheidung 6).
   */
  sourceKinds?: PermissionSubjectType[]
  /** Which target kinds are eligible — a person as source hands over only to a person. */
  targetKinds: PermissionSubjectType[]
  /** Which scope can be chosen; the preselection is the whole offered scope. */
  scopes: PermissionTransferScope[]
  intro: string
  onTransferred?: () => void
  /** Wording for a caller that names the task more plainly, e.g. „Nachfolge bestimmen“. */
  labels?: { title?: string; source?: string; targetUser?: string; targetGroup?: string }
}

/**
 * The transfer of rights (ADR-0036, Entscheidung 10): source and target, the selectable scope,
 * the mandatory preview and the explicit confirmation. If the state shifts between
 * preview and confirmation, the backend requires a new presentation — the dialog says so and
 * starts over at the preview.
 */
export default function PermissionTransferDialog({
  open,
  onClose,
  source,
  sourceKinds,
  targetKinds,
  scopes,
  intro,
  onTransferred,
  labels = {},
}: PermissionTransferDialogProps) {
  const title = labels.title ?? 'Rechte übertragen'
  const sourceLabel = labels.source ?? 'Quelle'
  const targetUserLabel = labels.targetUser ?? 'Zielperson'
  const targetGroupLabel = labels.targetGroup ?? 'Zielgruppe'
  const [targetType, setTargetType] = useState<PermissionSubjectType>(targetKinds[0])
  const [targetGroup, setTargetGroup] = useState<SelectableGroupResponse | null>(null)
  const [targetUser, setTargetUser] = useState<UserSummary | null>(null)
  const [sourceUser, setSourceUser] = useState<UserSummary | null>(null)
  const [selectedScopes, setSelectedScopes] = useState<PermissionTransferScope[]>(scopes)
  const [preview, setPreview] = useState<PermissionTransferPreviewResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const choosesSource = source === undefined
  const sourceType: PermissionSubjectType = source?.type ?? sourceKinds?.[0] ?? 'USER'
  const sourceId = source?.id ?? sourceUser?.id ?? ''
  const targetId = targetType === 'GROUP' ? (targetGroup?.id ?? '') : (targetUser?.id ?? '')
  const ready = sourceId !== '' && targetId !== '' && selectedScopes.length > 0

  function toggleScope(scope: PermissionTransferScope) {
    setPreview(null)
    setSelectedScopes((current) =>
      current.includes(scope) ? current.filter((s) => s !== scope) : [...current, scope],
    )
  }

  async function createPreview() {
    // The same confirmation question as in the sharing dialog (ADR-0036, Entscheidung 2): whoever
    // transfers to a group of an external provider confirms that explicitly.
    if (targetType === 'GROUP' && targetGroup && !(await confirmGroupSubject(targetGroup))) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      setPreview(
        await previewPermissionTransfer({
          sourceType,
          sourceId,
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
        sourceType,
        sourceId,
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
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 2 }}>{intro}</Typography>

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        <Stack spacing={2}>
          <Box>
            <FieldLabel htmlFor="transfer-source">{sourceLabel}</FieldLabel>
            {choosesSource ? (
              <UserPicker
                inputId="transfer-source"
                ariaLabel={sourceLabel}
                placeholder="Person suchen …"
                value={sourceUser}
                onChange={(next) => {
                  setPreview(null)
                  setSourceUser(next)
                }}
                excludedUserIds={targetUser ? [targetUser.id] : []}
              />
            ) : (
              <TextField id="transfer-source" fullWidth size="small" value={source.name} disabled />
            )}
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
              <FieldLabel htmlFor="transfer-target-group">{targetGroupLabel}</FieldLabel>
              {/* The shared group search: server-side selection with origin,
                 „extern" marker and the eligibility rules - instead of the full
                 administration list in a select field. */}
              <GroupPicker
                inputId="transfer-target-group"
                ariaLabel={targetGroupLabel}
                placeholder="Zielgruppe suchen …"
                value={targetGroup}
                onChange={(group) => {
                  setPreview(null)
                  setTargetGroup(group)
                }}
                excludedGroupIds={sourceType === 'GROUP' && sourceId !== '' ? [sourceId] : []}
              />
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                {PROTECTED_GROUP_SEARCH_HINT}
              </Typography>
            </Box>
          ) : (
            <Box>
              <FieldLabel htmlFor="transfer-target-user">{targetUserLabel}</FieldLabel>
              <UserPicker
                inputId="transfer-target-user"
                ariaLabel={targetUserLabel}
                placeholder="Person suchen …"
                value={targetUser}
                onChange={(next) => {
                  setPreview(null)
                  setTargetUser(next)
                }}
                excludedUserIds={sourceId !== '' && sourceType === 'USER' ? [sourceId] : []}
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
