import { useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControl from '@mui/material/FormControl'
import FormHelperText from '@mui/material/FormHelperText'
import IconButton from '@mui/material/IconButton'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import DeleteIcon from '@mui/icons-material/Delete'
import { useNavigate } from 'react-router'
import { useEffect } from 'react'
import PageHeading from '../components/a11y/PageHeading'
import FieldLabel from '../components/wizard/FieldLabel'
import WizardStepBar from '../components/wizard/WizardStepBar'
import { getUsers } from '../services/api'
import { useSpaceStore } from '../stores/spaceStore'
import {
  spaceRoleLabel,
  spaceVisibilities,
  spaceVisibilityDescription,
  spaceVisibilityLabel,
} from '../utils/labels'
import type { SpaceRole, SpaceVisibility, UserInfo } from '../types/api'

const STEPS = ['Grunddaten', 'Mitglieder', 'Zusammenfassung'] as const

const MEMBER_ROLES: SpaceRole[] = ['MEMBER', 'CURATOR', 'ADMIN']

interface PendingMember {
  user: UserInfo
  role: SpaceRole
}

/**
 * The space creation wizard (#594, mockup 1b), replacing the former dialog. The mockup's
 * Datenquellen step waits on the backend's space↔library assignment (#203 follow-up) - the
 * wizard ships the steps today's API can honour: Grunddaten, Mitglieder, Zusammenfassung.
 */
export default function SpaceCreatePage() {
  const navigate = useNavigate()
  const createNewSpace = useSpaceStore((s) => s.createNewSpace)
  const addMember = useSpaceStore((s) => s.addMember)

  const [activeStep, setActiveStep] = useState(0)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [visibility, setVisibility] = useState<SpaceVisibility>('PRIVATE')
  const [pendingMembers, setPendingMembers] = useState<PendingMember[]>([])
  const [selectedUser, setSelectedUser] = useState<UserInfo | null>(null)
  const [selectedRole, setSelectedRole] = useState<SpaceRole>('MEMBER')
  const [allUsers, setAllUsers] = useState<UserInfo[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    // Same source as the space management page: admin-only - a regular user simply gets an
    // empty picker and skips the step.
    void getUsers()
      .then(setAllUsers)
      .catch(() => setAllUsers([]))
  }, [])

  const availableUsers = useMemo(() => {
    const pendingIds = new Set(pendingMembers.map((m) => m.user.id))
    return allUsers.filter((u) => !pendingIds.has(u.id))
  }, [allUsers, pendingMembers])

  const isDirty = name.trim() !== '' || description.trim() !== '' || pendingMembers.length > 0

  const handleCancel = () => {
    if (isDirty && !window.confirm('Eingaben verwerfen und den Assistenten verlassen?')) {
      return
    }
    navigate('/spaces')
  }

  const handleCreate = async () => {
    setSubmitting(true)
    setError(null)
    try {
      const spaceId = await createNewSpace(name.trim(), description.trim(), visibility)
      const failed: string[] = []
      for (const member of pendingMembers) {
        try {
          await addMember(spaceId, member.user.id, member.role)
        } catch {
          failed.push(member.user.displayName ?? member.user.email ?? member.user.id)
        }
      }
      if (failed.length > 0) {
        setError(
          `Der Space wurde angelegt, aber diese Mitglieder konnten nicht hinzugefügt werden: ${failed.join(', ')}. Ergänzen Sie sie in der Space-Verwaltung.`,
        )
        setSubmitting(false)
        return
      }
      navigate(`/spaces/${spaceId}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Der Space konnte nicht angelegt werden.')
      setSubmitting(false)
    }
  }

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ maxWidth: 640 }}>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 0.5 }}>
          Neuer Space
        </Typography>
        <PageHeading title="Neuer Space" visuallyHidden />
        <Typography component="div" sx={{ fontSize: 26, fontWeight: 600, mb: 3 }} aria-hidden>
          {STEPS[activeStep]}
        </Typography>
        <WizardStepBar steps={STEPS} active={activeStep} />

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {activeStep === 0 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>
            <Box>
              <FieldLabel htmlFor="space-create-name">Name</FieldLabel>
              <TextField
                id="space-create-name"
                size="small"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="z. B. Widerspruchsstelle"
                fullWidth
              />
            </Box>
            <Box>
              <FieldLabel htmlFor="space-create-description">Beschreibung (optional)</FieldLabel>
              <TextField
                id="space-create-description"
                size="small"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                multiline
                minRows={2}
                fullWidth
              />
            </Box>
            <FormControl fullWidth>
              <FieldLabel id="space-create-visibility-label">Sichtbarkeit</FieldLabel>
              <Select
                labelId="space-create-visibility-label"
                size="small"
                value={visibility}
                onChange={(e) => setVisibility(e.target.value as SpaceVisibility)}
                aria-describedby="space-create-visibility-helper"
              >
                {spaceVisibilities.map((option) => (
                  <MenuItem key={option} value={option}>
                    {spaceVisibilityLabel(option)}
                  </MenuItem>
                ))}
              </Select>
              <FormHelperText id="space-create-visibility-helper">
                {spaceVisibilityDescription(visibility)}
              </FormHelperText>
            </FormControl>
          </Box>
        )}

        {activeStep === 1 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Mitglieder lassen sich auch später jederzeit in der Space-Verwaltung ergänzen — dieser
              Schritt ist optional.
            </Typography>
            <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
              <Autocomplete
                options={availableUsers}
                size="small"
                getOptionLabel={(option) =>
                  option.displayName
                    ? `${option.displayName} (${option.email ?? option.id})`
                    : (option.email ?? option.id)
                }
                value={selectedUser}
                onChange={(_e, value) => setSelectedUser(value)}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    placeholder="Benutzer suchen …"
                    slotProps={{
                      ...params.slotProps,
                      htmlInput: { ...params.slotProps.htmlInput, 'aria-label': 'Benutzer' },
                    }}
                  />
                )}
                isOptionEqualToValue={(option, value) => option.id === value.id}
                sx={{ minWidth: 280, flex: 1 }}
              />
              <Select
                size="small"
                value={selectedRole}
                onChange={(e) => setSelectedRole(e.target.value as SpaceRole)}
                aria-label="Rolle des neuen Mitglieds"
                sx={{ width: 170 }}
              >
                {MEMBER_ROLES.map((role) => (
                  <MenuItem key={role} value={role}>
                    {spaceRoleLabel(role)}
                  </MenuItem>
                ))}
              </Select>
              <Button
                variant="outlined"
                disabled={!selectedUser}
                onClick={() => {
                  if (!selectedUser) return
                  setPendingMembers((prev) => [...prev, { user: selectedUser, role: selectedRole }])
                  setSelectedUser(null)
                }}
              >
                Vormerken
              </Button>
            </Box>
            {pendingMembers.length > 0 && (
              <Box sx={{ border: 1, borderColor: 'divider', borderRadius: '10px' }}>
                {pendingMembers.map((member) => (
                  <Box
                    key={member.user.id}
                    sx={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 1.5,
                      px: 2,
                      py: 1.25,
                      '& + &': { borderTop: 1, borderColor: 'divider' },
                    }}
                  >
                    <Typography sx={{ fontSize: 13.5, flex: 1 }} noWrap>
                      {member.user.displayName ?? member.user.email ?? member.user.id}
                    </Typography>
                    <Typography sx={{ fontSize: 11.5, color: 'text.secondary' }}>
                      {spaceRoleLabel(member.role)}
                    </Typography>
                    <IconButton
                      size="small"
                      aria-label={`Vorgemerktes Mitglied ${member.user.displayName ?? member.user.email ?? member.user.id} entfernen`}
                      onClick={() =>
                        setPendingMembers((prev) =>
                          prev.filter((m) => m.user.id !== member.user.id),
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
        )}

        {activeStep === 2 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
            {[
              { label: 'Name', value: name.trim() },
              { label: 'Beschreibung', value: description.trim() || '–' },
              { label: 'Sichtbarkeit', value: spaceVisibilityLabel(visibility) },
              {
                label: 'Mitglieder',
                value:
                  pendingMembers.length === 0
                    ? 'nur Sie'
                    : pendingMembers
                        .map(
                          (m) =>
                            `${m.user.displayName ?? m.user.email ?? m.user.id} (${spaceRoleLabel(m.role)})`,
                        )
                        .join(', '),
              },
            ].map((row) => (
              <Box key={row.label} sx={{ display: 'flex', gap: 2 }}>
                <Typography
                  component="span"
                  sx={{ fontSize: 12.5, color: 'text.secondary', width: 120, flex: 'none' }}
                >
                  {row.label}
                </Typography>
                <Typography component="span" sx={{ fontSize: 13.5 }}>
                  {row.value}
                </Typography>
              </Box>
            ))}
            <Typography sx={{ fontSize: 11.5, color: 'text.secondary', mt: 1 }}>
              Die Datenquellen des Space ordnen Sie zu, sobald die Zuordnung verfügbar ist — bis
              dahin bestimmt die @-Eingrenzung im Chat den Suchbereich.
            </Typography>
          </Box>
        )}

        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1.5,
            mt: 4,
            pt: 2,
            borderTop: 1,
            borderColor: 'divider',
          }}
        >
          <Button variant="text" onClick={handleCancel} disabled={submitting}>
            Abbrechen
          </Button>
          <Box sx={{ flex: 1 }} />
          {activeStep === 1 && (
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              {pendingMembers.length === 1
                ? '1 Mitglied vorgemerkt'
                : `${pendingMembers.length} Mitglieder vorgemerkt`}
            </Typography>
          )}
          {activeStep > 0 && (
            <Button
              variant="outlined"
              onClick={() => setActiveStep((s) => s - 1)}
              disabled={submitting}
            >
              Zurück
            </Button>
          )}
          {activeStep < STEPS.length - 1 ? (
            <Button
              variant="contained"
              onClick={() => setActiveStep((s) => s + 1)}
              disabled={name.trim() === ''}
            >
              Weiter
            </Button>
          ) : (
            <Button
              variant="contained"
              onClick={() => void handleCreate()}
              disabled={submitting || name.trim() === ''}
            >
              {submitting ? 'Wird angelegt …' : 'Space anlegen'}
            </Button>
          )}
        </Box>
      </Box>
    </Box>
  )
}
