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
import Checkbox from '@mui/material/Checkbox'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import PageHeading from '../components/a11y/PageHeading'
import FieldLabel from '../components/wizard/FieldLabel'
import WizardStepBar from '../components/wizard/WizardStepBar'
import { getLibraries, getUserSummaries } from '../services/api'
import { useSpaceStore } from '../stores/spaceStore'
import {
  spaceRoleLabel,
  spaceVisibilities,
  spaceVisibilityDescription,
  spaceVisibilityLabel,
} from '../utils/labels'
import type { LibraryListResponse, SpaceRole, SpaceVisibility, UserSummary } from '../types/api'

const STEPS = ['Grunddaten', 'Mitglieder', 'Datenquellen', 'Zusammenfassung'] as const

const MEMBER_ROLES: SpaceRole[] = ['MEMBER', 'CURATOR', 'ADMIN']

interface PendingMember {
  user: UserSummary
  role: SpaceRole
}

/**
 * The space creation wizard (#594, mockup 1b), replacing the former dialog. Grunddaten, Mitglieder,
 * Datenquellen (#203/#686), Zusammenfassung.
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
  const [selectedUser, setSelectedUser] = useState<UserSummary | null>(null)
  const [selectedRole, setSelectedRole] = useState<SpaceRole>('MEMBER')
  const [allUsers, setAllUsers] = useState<UserSummary[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // #686: only libraries the creator may themselves read are offered - GET /v1/libraries already
  // returns exactly that set, and the backend re-checks the same rule when the space is created
  // (SpaceAssetAssociationService#associate).
  const [availableLibraries, setAvailableLibraries] = useState<LibraryListResponse[]>([])
  const [selectedLibraryIds, setSelectedLibraryIds] = useState<string[]>([])
  // #706 review: a failed load must not read as "you have no libraries" - that is a legitimate,
  // silent state, while a failed request needs its own visible message.
  const [libraryLoadError, setLibraryLoadError] = useState<string | null>(null)

  useEffect(() => {
    // #777: any authenticated user can reach this endpoint, unlike the admin-only user list -
    // see getUserSummaries's Javadoc counterpart, UserSearchController.
    void getUserSummaries()
      .then(setAllUsers)
      .catch(() => setAllUsers([]))
    void getLibraries()
      .then(setAvailableLibraries)
      .catch((err) => {
        setAvailableLibraries([])
        setLibraryLoadError(
          err instanceof Error ? err.message : 'Bibliotheken konnten nicht geladen werden.',
        )
      })
  }, [])

  function toggleLibrary(libraryId: string) {
    setSelectedLibraryIds((prev) =>
      prev.includes(libraryId) ? prev.filter((id) => id !== libraryId) : [...prev, libraryId],
    )
  }

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
      const spaceId = await createNewSpace(
        name.trim(),
        description.trim(),
        visibility,
        selectedLibraryIds,
      )
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
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Wählen Sie die Bibliotheken, die dieser Space durchsuchen soll — nur Bibliotheken, auf
              die Sie selbst Zugriff haben, stehen zur Auswahl. Die Zuordnung gewährt niemandem
              zusätzlichen Zugriff und lässt sich später jederzeit in der Space-Verwaltung ändern.
            </Typography>
            {libraryLoadError ? (
              <Alert severity="error">{libraryLoadError}</Alert>
            ) : availableLibraries.length === 0 ? (
              <Typography color="text.secondary">
                Sie haben derzeit keinen Zugriff auf eine Bibliothek.
              </Typography>
            ) : (
              <List dense sx={{ border: 1, borderColor: 'divider', borderRadius: '10px', py: 0 }}>
                {availableLibraries.map((library) => (
                  <ListItemButton
                    key={library.id}
                    onClick={() => toggleLibrary(library.id)}
                    sx={{ '& + &': { borderTop: 1, borderColor: 'divider' } }}
                  >
                    <ListItemIcon sx={{ minWidth: 36 }}>
                      <Checkbox
                        edge="start"
                        checked={selectedLibraryIds.includes(library.id)}
                        tabIndex={-1}
                        disableRipple
                        slotProps={{
                          input: { 'aria-label': `Bibliothek ${library.name} zuordnen` },
                        }}
                      />
                    </ListItemIcon>
                    <ListItemText primary={library.name} />
                  </ListItemButton>
                ))}
              </List>
            )}
          </Box>
        )}

        {activeStep === 3 && (
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
              {
                label: 'Datenquellen',
                value:
                  selectedLibraryIds.length === 0
                    ? 'keine — durchsucht bis auf Weiteres alles Lesbare'
                    : availableLibraries
                        .filter((l) => selectedLibraryIds.includes(l.id))
                        .map((l) => l.name)
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
