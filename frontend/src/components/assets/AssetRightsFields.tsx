import { useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import IconButton from '@mui/material/IconButton'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Typography from '@mui/material/Typography'
import DeleteIcon from '@mui/icons-material/Delete'
import SubjectPicker from '../permissions/SubjectPicker'
import {
  confirmExternalSubject,
  emptySubjectSelection,
  groupLabel,
  selectedSubjectId,
  type SubjectSelection,
} from '../permissions/subjectSelection'
import type { AssetRole } from '../../types/api'
import { assetRoleLabel, permissionSubjectTypeLabel } from '../../utils/labels'
import type { PendingGrant } from './pendingGrants'

const GRANT_ROLES: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER']

interface AssetRightsFieldsProps {
  idPrefix: string
  /**
   * Findability in the catalog. Omitted where the wizard does not offer it; when offered it starts
   * unchecked, because listing is a deliberate act (docs/features/spaces-and-assets.md).
   */
  listed?: { value: boolean; onChange: (listed: boolean) => void }
  pendingGrants: PendingGrant[]
  onPendingGrantsChange: (grants: PendingGrant[]) => void
}

/**
 * The rights step of a creation wizard: optionally findability, and grants noted for after the
 * creation - chosen with the same subject picker and the same confirmation for an external
 * provider's group as the rights dialog of an existing asset. "Alle Konten" is deliberately not
 * offered here (#1931): an asset that does not exist yet has no reach to widen, and the ceiling
 * that governs such a grant is a property of the created asset.
 */
export default function AssetRightsFields({
  idPrefix,
  listed,
  pendingGrants,
  onPendingGrantsChange,
}: AssetRightsFieldsProps) {
  const [grantSubject, setGrantSubject] = useState<SubjectSelection>(emptySubjectSelection)
  const [grantRole, setGrantRole] = useState<AssetRole>('VIEWER')

  const pendingUserIds = useMemo(
    () => pendingGrants.filter((g) => g.subjectType === 'USER').map((g) => g.subjectId),
    [pendingGrants],
  )
  const pendingGroupIds = useMemo(
    () => pendingGrants.filter((g) => g.subjectType === 'GROUP').map((g) => g.subjectId),
    [pendingGrants],
  )

  const handleAddGrant = async () => {
    const subjectId = selectedSubjectId(grantSubject)
    if (!subjectId || grantSubject.type === 'ALL_ACCOUNTS') return
    const subjectType = grantSubject.type
    if (!(await confirmExternalSubject(grantSubject))) return
    const label =
      subjectType === 'USER'
        ? (grantSubject.user?.displayName ?? grantSubject.user?.email ?? subjectId)
        : grantSubject.group
          ? groupLabel(grantSubject.group)
          : subjectId
    onPendingGrantsChange([...pendingGrants, { subjectType, subjectId, label, role: grantRole }])
    setGrantSubject({ type: grantSubject.type, user: null, group: null })
  }

  return (
    <>
      {listed && (
        <Box>
          <FormControlLabel
            control={
              <Checkbox
                checked={listed.value}
                onChange={(e) => listed.onChange(e.target.checked)}
              />
            }
            label="Im Katalog auffindbar, auch ohne Berechtigung"
          />
          <FormHelperText sx={{ mt: 0 }}>
            Standardmäßig aus. Auffindbar heißt nicht lesbar: Sichtbar wird der Eintrag, nicht der
            Inhalt.
          </FormHelperText>
        </Box>
      )}

      <Box>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>
          Freigaben lassen sich auch später jederzeit auf der Detailseite ergänzen — dieser Schritt
          ist optional.
        </Typography>
        <SubjectPicker
          labelId={`${idPrefix}-grant-subject-label`}
          value={grantSubject}
          onChange={setGrantSubject}
          excludedUserIds={pendingUserIds}
          excludedGroupIds={pendingGroupIds}
        />
        <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', mt: 1.5 }}>
          <Select
            size="small"
            value={grantRole}
            onChange={(e) => setGrantRole(e.target.value as AssetRole)}
            aria-label="Rolle der Freigabe"
            sx={{ width: 150 }}
          >
            {GRANT_ROLES.map((role) => (
              <MenuItem key={role} value={role}>
                {assetRoleLabel(role)}
              </MenuItem>
            ))}
          </Select>
          <Button
            variant="outlined"
            disabled={!selectedSubjectId(grantSubject)}
            onClick={() => void handleAddGrant()}
          >
            Vormerken
          </Button>
        </Box>
        {pendingGrants.length > 0 && (
          <Box sx={{ mt: 1.5, borderTop: 1, borderColor: 'divider' }}>
            {pendingGrants.map((grant) => (
              <Box
                key={`${grant.subjectType}-${grant.subjectId}`}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1.5,
                  py: 1.25,
                  borderBottom: 1,
                  borderColor: 'divider',
                }}
              >
                <Typography sx={{ fontSize: 13.5, flex: 1 }} noWrap>
                  {grant.label}
                </Typography>
                <Typography sx={{ fontSize: 11.5, color: 'text.secondary' }}>
                  {permissionSubjectTypeLabel(grant.subjectType)} · {assetRoleLabel(grant.role)}
                </Typography>
                <IconButton
                  size="small"
                  aria-label={`Vorgemerkte Freigabe für ${grant.label} entfernen`}
                  onClick={() =>
                    onPendingGrantsChange(
                      pendingGrants.filter(
                        (g) =>
                          !(g.subjectType === grant.subjectType && g.subjectId === grant.subjectId),
                      ),
                    )
                  }
                >
                  <DeleteIcon sx={{ fontSize: 16 }} />
                </IconButton>
              </Box>
            ))}
          </Box>
        )}
      </Box>
    </>
  )
}
