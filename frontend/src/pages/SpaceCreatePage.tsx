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
import { useLocation, useNavigate } from 'react-router'
import PageHeading from '../components/a11y/PageHeading'
import AssetTilePicker from '../components/assets/AssetTilePicker'
import type { AssetPick, SpaceCreateLocationState } from '../components/assets/assetPick'
import ChatAutoCleanupField from '../components/space/ChatAutoCleanupField'
import FieldLabel from '../components/wizard/FieldLabel'
import WizardStepBar from '../components/wizard/WizardStepBar'
import { confirmAction } from '../stores/confirmStore'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import { useMyCapabilities } from '../hooks/useMyCapabilities'
import { useUserSearch } from '../hooks/useUserSearch'
import {
  capabilityMissingMessage,
  spaceRoleLabel,
  spaceVisibilities,
  spaceVisibilityDescription,
  spaceVisibilityLabel,
} from '../utils/labels'
import type { SpaceRole, SpaceVisibility, UserSummary } from '../types/api'

const STEPS = ['Grunddaten', 'Mitglieder', 'Inhalte', 'Zusammenfassung'] as const

export const NO_KNOWLEDGE_SUMMARY =
  'Kein Wissen zugeordnet — der Space durchsucht kein Wissen, bis Sie etwas zuordnen.'

const MEMBER_ROLES: SpaceRole[] = ['MEMBER', 'CURATOR', 'ADMIN']

interface PendingMember {
  user: UserSummary
  role: SpaceRole
}

/**
 * The space creation wizard (#594, mockup 1b): Grunddaten, Mitglieder, Inhalte, Zusammenfassung.
 * "Inhalte" associates assets of every type from the catalog's tile list and may be skipped; the
 * space and its associations are created in one call.
 */
export default function SpaceCreatePage() {
  const navigate = useNavigate()
  const location = useLocation()
  const preselect = (location.state as SpaceCreateLocationState | null)?.preselect
  const createNewSpace = useSpaceStore((s) => s.createNewSpace)
  const currentUserId = useAuthStore((s) => s.user?.id)

  const [activeStep, setActiveStep] = useState(0)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [visibility, setVisibility] = useState<SpaceVisibility>('PRIVATE')
  const [chatAutoCleanup, setChatAutoCleanup] = useState(false)
  const [pendingMembers, setPendingMembers] = useState<PendingMember[]>([])
  const [selectedUser, setSelectedUser] = useState<UserSummary | null>(null)
  const [selectedRole, setSelectedRole] = useState<SpaceRole>('MEMBER')
  const {
    query: userQuery,
    setQuery: setUserQuery,
    users: userResults,
    isLoading: isSearchingUsers,
    error: userSearchError,
  } = useUserSearch()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // ADR-0036, Entscheidung 5: a missing Anlegerecht is explained, not hidden - the button stays
  // visible and says why it cannot be used. The backend refuses the same call regardless.
  const { isMissing } = useMyCapabilities()
  const mayNotCreate = isMissing('CREATE_SPACE')
  // Only what the creator may read is offered; the backend re-checks the same rule on creation.
  const [selectedAssets, setSelectedAssets] = useState<AssetPick[]>(preselect ? [preselect] : [])

  const availableUsers = useMemo(() => {
    // The creator becomes the owner and ADMIN anyway; offering them would only be overruled.
    const excluded = new Set([...pendingMembers.map((m) => m.user.id), currentUserId])
    return userResults.filter((u) => !excluded.has(u.id))
  }, [userResults, pendingMembers, currentUserId])

  const isDirty =
    name.trim() !== '' ||
    description.trim() !== '' ||
    pendingMembers.length > 0 ||
    selectedAssets.length > 0

  const handleCancel = async () => {
    if (isDirty) {
      const confirmed = await confirmAction({
        question: 'Eingaben verwerfen und den Assistenten verlassen?',
        confirmLabel: 'Verwerfen',
        tone: 'caution',
      })
      if (!confirmed) return
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
        selectedAssets.map(({ assetType, assetId }) => ({ assetType, assetId })),
        chatAutoCleanup,
        pendingMembers.map((member) => ({ userId: member.user.id, role: member.role })),
      )
      navigate(`/spaces/${spaceId}`)
    } catch (err) {
      // Space, members and assets are created together or not at all - nothing to clean up here.
      const reason = err instanceof Error ? err.message : null
      setError(
        `Der Space wurde nicht angelegt${reason ? `: ${reason}` : '.'} Prüfen Sie Mitglieder und Inhalte und versuchen Sie es erneut.`,
      )
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

        {mayNotCreate && (
          <Alert severity="info" sx={{ mb: 2 }} id="space-create-capability-hint">
            {capabilityMissingMessage('CREATE_SPACE')}
          </Alert>
        )}

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
            <ChatAutoCleanupField
              id="space-create-chat-auto-cleanup"
              checked={chatAutoCleanup}
              onChange={setChatAutoCleanup}
            />
          </Box>
        )}

        {activeStep === 1 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Mitglieder lassen sich auch später jederzeit in der Space-Verwaltung ergänzen — dieser
              Schritt ist optional.
            </Typography>
            {userSearchError && (
              // #778 review, finding 3: a failed search must not just read as "no matches" - the
              // field looks identically empty either way otherwise.
              <Alert severity="error">{userSearchError}</Alert>
            )}
            <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
              <Autocomplete
                options={availableUsers}
                size="small"
                loading={isSearchingUsers}
                filterOptions={(x) => x}
                inputValue={userQuery}
                onInputChange={(_e, value, reason) => {
                  // 'reset' fires when the input text is set to match a just-selected option's
                  // label (or reverted on blur) - propagating that as a fresh query would re-fire
                  // a search for text the caller never typed.
                  if (reason !== 'reset') setUserQuery(value)
                }}
                noOptionsText={
                  userQuery.trim().length < 2 ? 'Mindestens 2 Zeichen eingeben' : 'Keine Treffer'
                }
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
                  setUserQuery('')
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
              Ein Chat in diesem Space nutzt nur, was Sie hier zuordnen. Zur Auswahl steht, was Sie
              selbst lesen dürfen. Die Zuordnung gewährt niemandem zusätzlichen Zugriff und lässt
              sich später in den Einstellungen des Space ändern — dieser Schritt ist optional.
            </Typography>
            <AssetTilePicker
              value={selectedAssets}
              onChange={setSelectedAssets}
              aria-label="Inhalte für diesen Space"
            />
          </Box>
        )}

        {activeStep === 3 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
            {[
              { label: 'Name', value: name.trim() },
              { label: 'Beschreibung', value: description.trim() || '–' },
              { label: 'Sichtbarkeit', value: spaceVisibilityLabel(visibility) },
              {
                label: 'Inaktive Chats',
                value: chatAutoCleanup
                  ? 'werden automatisch archiviert und gelöscht'
                  : 'bleiben, bis sie jemand selbst archiviert oder löscht',
              },
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
                label: 'Inhalte',
                value:
                  selectedAssets.length === 0
                    ? 'keine'
                    : selectedAssets.map((pick) => pick.name).join(', '),
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
            {!selectedAssets.some((pick) => pick.assetType === 'KNOWLEDGE_LIBRARY') && (
              <Alert severity="info">{NO_KNOWLEDGE_SUMMARY}</Alert>
            )}
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
          <Button variant="text" onClick={() => void handleCancel()} disabled={submitting}>
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
              disabled={submitting || mayNotCreate || name.trim() === ''}
              aria-describedby={mayNotCreate ? 'space-create-capability-hint' : undefined}
            >
              {submitting ? 'Wird angelegt …' : 'Space anlegen'}
            </Button>
          )}
        </Box>
      </Box>
    </Box>
  )
}
