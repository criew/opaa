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
} from '../../types/api'
import {
  executePermissionTransfer,
  previewPermissionTransfer,
} from '../../services/permissionTransferApi'
import { apiErrorCode } from '../../services/apiErrorDetails'
import { notify } from '../../stores/notificationStore'
import UserPicker from '../groups/UserPicker'
import FieldLabel from '../wizard/FieldLabel'
import GroupPicker from './GroupPicker'
import { confirmGroupSubject, PROTECTED_GROUP_SEARCH_HINT } from './subjectSelection'

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
  /** Die feste Quelle, wenn der Aufrufer sie kennt (Gruppe, eigenes Konto). */
  source?: TransferSubject
  /**
   * Statt einer festen Quelle: Die Quelle wird hier gewählt. Die Betriebsliste braucht das, weil
   * eine Zeile den früheren Eigentümer nur als Text nennt — eine Kennung gibt die API bewusst
   * nicht heraus (ADR-0036, Entscheidung 6).
   */
  sourceKinds?: PermissionSubjectType[]
  /** Welche Zielarten in Frage kommen — eine Person als Quelle gibt nur an eine Person ab. */
  targetKinds: PermissionSubjectType[]
  /** Welcher Umfang wählbar ist; die Vorauswahl ist der ganze angebotene Umfang. */
  scopes: PermissionTransferScope[]
  intro: string
  onTransferred?: () => void
}

/**
 * Die Übertragung von Rechten (ADR-0036, Entscheidung 10): Quelle und Ziel, der wählbare Umfang,
 * die Pflicht-Vorschau und die ausdrückliche Bestätigung. Verschiebt sich der Stand zwischen
 * Vorschau und Bestätigung, verlangt das Backend eine neue Vorlage — der Dialog sagt das und
 * beginnt bei der Vorschau von vorn.
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
}: PermissionTransferDialogProps) {
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
    // Dieselbe Zwischenfrage wie im Freigabedialog (ADR-0036, Entscheidung 2): Wer an eine Gruppe
    // eines externen Anbieters überträgt, bestätigt das ausdrücklich.
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
      <DialogTitle>Rechte übertragen</DialogTitle>
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
            {choosesSource ? (
              <UserPicker
                inputId="transfer-source"
                ariaLabel="Quelle"
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
              <FieldLabel htmlFor="transfer-target-group">Zielgruppe</FieldLabel>
              {/* Die gemeinsame Gruppensuche (#1820): serverseitige Auswahl mit Herkunft,
                  Kennzeichen „extern" und den Wählbarkeitsregeln - statt der vollen
                  Verwaltungsliste in einem Auswahlfeld. */}
              <GroupPicker
                inputId="transfer-target-group"
                ariaLabel="Zielgruppe"
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
              <FieldLabel htmlFor="transfer-target-user">Zielperson</FieldLabel>
              <UserPicker
                inputId="transfer-target-user"
                ariaLabel="Zielperson"
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
