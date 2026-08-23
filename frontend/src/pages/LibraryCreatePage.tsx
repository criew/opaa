import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import IconButton from '@mui/material/IconButton'
import MenuItem from '@mui/material/MenuItem'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Select from '@mui/material/Select'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import DeleteIcon from '@mui/icons-material/Delete'
import { alpha } from '@mui/material/styles'
import { useNavigate } from 'react-router'
import PageHeading from '../components/a11y/PageHeading'
import { blue } from '../theme/tokens'
import FieldLabel from '../components/wizard/FieldLabel'
import WizardStepBar from '../components/wizard/WizardStepBar'
import {
  getMyGroups,
  getUserSummaries,
  testLibrarySource,
  upsertLibraryGrant,
} from '../services/api'
import { useLibraryStore } from '../stores/libraryStore'
import {
  allDocumentSourceTypes,
  assetRoleLabel,
  documentSourceTypeConfigKind,
  documentSourceTypeDescription,
  documentSourceTypeLabel,
  libraryVisibilities,
  libraryVisibilityDescription,
  libraryVisibilityLabel,
  permissionSubjectTypeLabel,
} from '../utils/labels'
import {
  deriveLibrarySourceConfigPayload,
  validateLibrarySourceFields,
} from '../utils/librarySourceConfig'
import type {
  AssetRole,
  DocumentSourceType,
  GroupListResponse,
  LibraryOwnerType,
  LibraryVisibility,
  PermissionSubjectType,
  SourceConnectionTestResponse,
  UserSummary,
} from '../types/api'

const STEPS = ['Stammdaten', 'Herkunft', 'Rechte'] as const
const STEP_TITLES = ['Stammdaten', 'Woher kommen die Dokumente?', 'Rechte'] as const
const GRANT_ROLES: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER']

interface PendingGrant {
  subjectType: PermissionSubjectType
  subjectId: string
  label: string
  role: AssetRole
}

/**
 * The library creation wizard (#596, mockup 1e), replacing CreateLibraryDialog. The origin step
 * carries the four source cards with the type-bound connection form and test (#514 invalidation
 * semantics preserved); the rights step sets the distribution level at creation time and queues
 * grants that are applied through the grant API right after the library exists.
 */
export default function LibraryCreatePage() {
  const navigate = useNavigate()
  const createNewLibrary = useLibraryStore((s) => s.createNewLibrary)

  const [activeStep, setActiveStep] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [ownerType, setOwnerType] = useState<LibraryOwnerType>('USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const [groups, setGroups] = useState<GroupListResponse[]>([])
  const [groupsError, setGroupsError] = useState<string | null>(null)
  const [groupsLoaded, setGroupsLoaded] = useState(false)

  const [sourceType, setSourceType] = useState<DocumentSourceType>('UPLOAD')
  const [sourcePath, setSourcePath] = useState('')
  const [sourceUrl, setSourceUrl] = useState('')
  const [sourceProxy, setSourceProxy] = useState('')
  const [sourceCredentials, setSourceCredentials] = useState('')
  const [sourceInsecureSsl, setSourceInsecureSsl] = useState(false)
  const [testResult, setTestResult] = useState<SourceConnectionTestResponse | null>(null)
  const [testErrorMessage, setTestErrorMessage] = useState<string | null>(null)
  const [testing, setTesting] = useState(false)

  const [visibility, setVisibility] = useState<LibraryVisibility>('PRIVATE')
  const [pendingGrants, setPendingGrants] = useState<PendingGrant[]>([])
  const [grantSubjectType, setGrantSubjectType] = useState<PermissionSubjectType>('USER')
  const [grantRole, setGrantRole] = useState<AssetRole>('VIEWER')
  const [selectedUser, setSelectedUser] = useState<UserSummary | null>(null)
  const [selectedGrantGroup, setSelectedGrantGroup] = useState<GroupListResponse | null>(null)
  const [users, setUsers] = useState<UserSummary[]>([])

  useEffect(() => {
    let cancelled = false
    void getMyGroups()
      .then((result) => {
        if (cancelled) return
        setGroups(result)
        setGroupsLoaded(true)
        setGroupsError(null)
      })
      .catch((err) => {
        if (cancelled) return
        setGroups([])
        setGroupsLoaded(true)
        setGroupsError(err instanceof Error ? err.message : 'Gruppen konnten nicht geladen werden')
      })
    // #777: any authenticated user can reach this endpoint, unlike the admin-only user list -
    // see getUserSummaries's Javadoc counterpart, UserSearchController.
    void getUserSummaries()
      .then((result) => {
        if (!cancelled) setUsers(result)
      })
      .catch(() => {
        if (!cancelled) setUsers([])
      })
    return () => {
      cancelled = true
    }
  }, [])

  const configKind = documentSourceTypeConfigKind[sourceType]

  // #514: any edit to a field the connection test depends on invalidates a previous result -
  // a stale "erreichbar" must never survive a since-changed address.
  function clearTestResult() {
    setTestResult((prev) => (prev === null ? prev : null))
    setTestErrorMessage((prev) => (prev === null ? prev : null))
  }

  const availableUsers = useMemo(() => {
    const pending = new Set(
      pendingGrants.filter((g) => g.subjectType === 'USER').map((g) => g.subjectId),
    )
    return users.filter((u) => !pending.has(u.id))
  }, [users, pendingGrants])

  const availableGrantGroups = useMemo(() => {
    const pending = new Set(
      pendingGrants.filter((g) => g.subjectType === 'GROUP').map((g) => g.subjectId),
    )
    return groups.filter((g) => !pending.has(g.id))
  }, [groups, pendingGrants])

  const isDirty =
    name.trim() !== '' ||
    description.trim() !== '' ||
    sourcePath !== '' ||
    sourceUrl !== '' ||
    pendingGrants.length > 0

  const handleCancel = () => {
    if (isDirty && !window.confirm('Eingaben verwerfen und den Assistenten verlassen?')) {
      return
    }
    navigate('/libraries')
  }

  const handleNext = () => {
    if (activeStep === 0 && ownerType === 'GROUP' && !selectedGroup) {
      setError('Bitte eine Gruppe auswählen')
      return
    }
    if (activeStep === 1) {
      const validationError = validateLibrarySourceFields(sourceType, { sourcePath, sourceUrl })
      if (validationError) {
        setError(validationError)
        return
      }
    }
    setError(null)
    setActiveStep((s) => s + 1)
  }

  const handleTest = async () => {
    const validationError = validateLibrarySourceFields(sourceType, { sourcePath, sourceUrl })
    if (validationError) {
      setError(validationError)
      return
    }
    setError(null)
    setTestResult(null)
    setTestErrorMessage(null)
    setTesting(true)
    try {
      const result = await testLibrarySource({
        sourceType,
        ...deriveLibrarySourceConfigPayload(sourceType, {
          sourcePath,
          sourceUrl,
          sourceProxy,
          sourceCredentials,
          sourceInsecureSsl,
        }),
      })
      setTestResult(result)
    } catch (err) {
      setTestErrorMessage(
        err instanceof Error ? err.message : 'Verbindung konnte nicht getestet werden',
      )
    } finally {
      setTesting(false)
    }
  }

  const handleAddGrant = () => {
    if (grantSubjectType === 'USER' && selectedUser) {
      setPendingGrants((prev) => [
        ...prev,
        {
          subjectType: 'USER',
          subjectId: selectedUser.id,
          label: selectedUser.displayName ?? selectedUser.email ?? selectedUser.id,
          role: grantRole,
        },
      ])
      setSelectedUser(null)
    }
    if (grantSubjectType === 'GROUP' && selectedGrantGroup) {
      setPendingGrants((prev) => [
        ...prev,
        {
          subjectType: 'GROUP',
          subjectId: selectedGrantGroup.id,
          label: selectedGrantGroup.name,
          role: grantRole,
        },
      ])
      setSelectedGrantGroup(null)
    }
  }

  const handleCreate = async () => {
    setSubmitting(true)
    setError(null)
    try {
      const libraryId = await createNewLibrary({
        name: name.trim(),
        description: description.trim() || undefined,
        ownerType,
        ownerId: ownerType === 'GROUP' ? (selectedGroup?.id ?? undefined) : undefined,
        sourceType,
        ...deriveLibrarySourceConfigPayload(sourceType, {
          sourcePath,
          sourceUrl,
          sourceProxy,
          sourceCredentials,
          sourceInsecureSsl,
        }),
        visibility,
      })
      const failed: string[] = []
      for (const grant of pendingGrants) {
        try {
          await upsertLibraryGrant(libraryId, {
            subjectType: grant.subjectType,
            subjectId: grant.subjectId,
            role: grant.role,
          })
        } catch {
          failed.push(grant.label)
        }
      }
      if (failed.length > 0) {
        setError(
          `Die Bibliothek wurde angelegt, aber diese Freigaben konnten nicht gespeichert werden: ${failed.join(', ')}. Ergänzen Sie sie auf der Detailseite.`,
        )
        setSubmitting(false)
        return
      }
      navigate(`/libraries/${libraryId}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Bibliothek konnte nicht erstellt werden')
      setSubmitting(false)
    }
  }

  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box sx={{ maxWidth: 720 }}>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 0.5 }}>
          Neue Wissensbibliothek
        </Typography>
        <PageHeading title="Neue Wissensbibliothek" visuallyHidden />
        <Typography component="div" sx={{ fontSize: 26, fontWeight: 600, mb: 3 }} aria-hidden>
          {STEP_TITLES[activeStep]}
        </Typography>
        <WizardStepBar steps={STEPS} active={activeStep} />

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        {activeStep === 0 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, maxWidth: 640 }}>
            <Box>
              <FieldLabel htmlFor="library-create-name">Name</FieldLabel>
              <TextField
                id="library-create-name"
                size="small"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="z. B. Rechtsquellen Soziales"
                fullWidth
                slotProps={{ htmlInput: { maxLength: 255 } }}
              />
            </Box>
            <Box>
              <FieldLabel htmlFor="library-create-description">Beschreibung (optional)</FieldLabel>
              <TextField
                id="library-create-description"
                size="small"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                multiline
                minRows={2}
                fullWidth
                slotProps={{ htmlInput: { maxLength: 2000 } }}
              />
            </Box>
            <FormControl>
              <FieldLabel id="library-create-owner-label">Eigentümer</FieldLabel>
              <RadioGroup
                row
                aria-labelledby="library-create-owner-label"
                value={ownerType}
                onChange={(e) => setOwnerType(e.target.value as LibraryOwnerType)}
              >
                <FormControlLabel value="USER" control={<Radio />} label="Mein Konto" />
                <FormControlLabel value="GROUP" control={<Radio />} label="Eine Gruppe" />
              </RadioGroup>
            </FormControl>
            {ownerType === 'GROUP' && (
              <>
                {groupsError && <Alert severity="error">{groupsError}</Alert>}
                {groupsLoaded && !groupsError && groups.length === 0 && (
                  <Alert severity="info">
                    Sie sind aktuell in keiner Gruppe Mitglied. Eine Bibliothek mit Gruppen-Eigentum
                    lässt sich erst anlegen, sobald Sie einer Gruppe angehören.
                  </Alert>
                )}
                <Autocomplete
                  options={groups}
                  size="small"
                  getOptionLabel={(option) => option.name}
                  value={selectedGroup}
                  onChange={(_e, value) => setSelectedGroup(value)}
                  disabled={groups.length === 0}
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      placeholder="Gruppe auswählen …"
                      slotProps={{
                        ...params.slotProps,
                        htmlInput: { ...params.slotProps.htmlInput, 'aria-label': 'Gruppe' },
                      }}
                    />
                  )}
                  isOptionEqualToValue={(option, value) => option.id === value.id}
                />
              </>
            )}
          </Box>
        )}

        {activeStep === 1 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            <RadioGroup
              aria-label="Herkunft wählen"
              value={sourceType}
              onChange={(e) => {
                setSourceType(e.target.value as DocumentSourceType)
                clearTestResult()
                setError(null)
              }}
              sx={{
                display: 'grid',
                gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
                gap: '14px',
              }}
            >
              {allDocumentSourceTypes.map((type) => {
                const selected = type === sourceType
                return (
                  <FormControlLabel
                    key={type}
                    value={type}
                    control={<Radio size="small" sx={{ p: 0, mt: '2px' }} />}
                    label={
                      <Box>
                        <Typography sx={{ fontSize: 14.5, fontWeight: 600 }}>
                          {documentSourceTypeLabel(type)}
                        </Typography>
                        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.25 }}>
                          {documentSourceTypeDescription(type)}
                        </Typography>
                      </Box>
                    }
                    sx={{
                      alignItems: 'flex-start',
                      gap: '12px',
                      m: 0,
                      p: 2,
                      border: selected ? 2 : 1,
                      borderColor: selected ? 'primary.main' : 'divider',
                      borderRadius: '10px',
                      bgcolor: selected
                        ? (theme) =>
                            theme.palette.mode === 'dark'
                              ? alpha(theme.palette.primary.main, 0.16)
                              : blue[50]
                        : 'transparent',
                      '&:hover': { borderColor: selected ? 'primary.main' : 'text.disabled' },
                    }}
                  />
                )
              })}
            </RadioGroup>

            {configKind === 'none' && (
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                Dokumente laden Sie nach dem Anlegen auf der Detailseite hoch — einzeln oder
                gebündelt.
              </Typography>
            )}

            {configKind === 'path' && (
              <Box sx={{ maxWidth: 640 }}>
                <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
                  Verbindung zum Dateisystem
                </Typography>
                <FieldLabel htmlFor="library-create-path">Verzeichnispfad</FieldLabel>
                <TextField
                  id="library-create-path"
                  size="small"
                  value={sourcePath}
                  onChange={(e) => {
                    setSourcePath(e.target.value)
                    clearTestResult()
                  }}
                  placeholder="/data/dokumente"
                  helperText="Absoluter Pfad auf dem Server, den OPAA regelmäßig einliest."
                  fullWidth
                  slotProps={{ htmlInput: { maxLength: 2000, sx: { fontFamily: 'monospace' } } }}
                />
              </Box>
            )}

            {configKind === 'url' && (
              <Box sx={{ maxWidth: 640 }}>
                <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1.75 }}>
                  Verbindung zum {documentSourceTypeLabel(sourceType)}
                </Typography>
                {sourceType === 'RSS_FEED' && (
                  <Alert severity="info" sx={{ mb: 1.75 }}>
                    OPAA ruft neben dem Feed auch die von ihm verlinkten Detailseiten ab. Welche
                    Adressen das sind, bestimmt der Betreiber des Feeds, nicht Sie selbst.
                  </Alert>
                )}
                <Box
                  sx={{
                    display: 'grid',
                    gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
                    gap: '14px',
                  }}
                >
                  <Box sx={{ gridColumn: '1 / -1' }}>
                    <FieldLabel htmlFor="library-create-url">Adresse (URL)</FieldLabel>
                    <TextField
                      id="library-create-url"
                      size="small"
                      value={sourceUrl}
                      onChange={(e) => {
                        setSourceUrl(e.target.value)
                        clearTestResult()
                      }}
                      placeholder="https://files.example.com/dokumente/"
                      helperText="http oder https."
                      fullWidth
                      slotProps={{
                        htmlInput: { maxLength: 2000, sx: { fontFamily: 'monospace' } },
                      }}
                    />
                  </Box>
                  <Box>
                    <FieldLabel htmlFor="library-create-proxy">Proxy (optional)</FieldLabel>
                    <TextField
                      id="library-create-proxy"
                      size="small"
                      value={sourceProxy}
                      onChange={(e) => {
                        setSourceProxy(e.target.value)
                        clearTestResult()
                      }}
                      placeholder="proxy.example.com:8080"
                      autoComplete="off"
                      fullWidth
                      slotProps={{ htmlInput: { maxLength: 255 } }}
                    />
                  </Box>
                  <Box>
                    <FieldLabel htmlFor="library-create-credentials">
                      Anmeldedaten (optional)
                    </FieldLabel>
                    <TextField
                      id="library-create-credentials"
                      size="small"
                      type="password"
                      value={sourceCredentials}
                      onChange={(e) => {
                        setSourceCredentials(e.target.value)
                        clearTestResult()
                      }}
                      placeholder="benutzer:passwort"
                      helperText="Wird nie in einer API-Antwort ausgegeben."
                      autoComplete="new-password"
                      fullWidth
                      slotProps={{ htmlInput: { maxLength: 500 } }}
                    />
                  </Box>
                  <FormControlLabel
                    sx={{ gridColumn: '1 / -1' }}
                    control={
                      <Switch
                        checked={sourceInsecureSsl}
                        onChange={(e) => {
                          setSourceInsecureSsl(e.target.checked)
                          clearTestResult()
                        }}
                      />
                    }
                    label="Zertifikatsprüfung aussetzen"
                  />
                </Box>
              </Box>
            )}

            {configKind !== 'none' && (
              <Box sx={{ maxWidth: 640 }}>
                <Button onClick={() => void handleTest()} disabled={testing} variant="outlined">
                  {testing ? 'Verbindung wird getestet …' : 'Verbindung testen'}
                </Button>
                {testErrorMessage && (
                  <Alert severity="error" sx={{ mt: 1 }}>
                    {testErrorMessage}
                  </Alert>
                )}
                {testResult && (
                  <Alert severity={testResult.reachable ? 'success' : 'warning'} sx={{ mt: 1 }}>
                    {testResult.message}
                  </Alert>
                )}
                <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 2 }}>
                  Der erste Lauf startet nach dem Anlegen; sein Stand bleibt auf der Detailseite
                  sichtbar.
                </Typography>
              </Box>
            )}
          </Box>
        )}

        {activeStep === 2 && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, maxWidth: 640 }}>
            <FormControl fullWidth>
              <FieldLabel id="library-create-visibility-label">Verteilungsstufe</FieldLabel>
              <Select
                labelId="library-create-visibility-label"
                size="small"
                value={visibility}
                onChange={(e) => setVisibility(e.target.value as LibraryVisibility)}
                aria-describedby="library-create-visibility-helper"
              >
                {libraryVisibilities.map((option) => (
                  <MenuItem key={option} value={option}>
                    {libraryVisibilityLabel(option)}
                  </MenuItem>
                ))}
              </Select>
              <FormHelperText id="library-create-visibility-helper">
                {libraryVisibilityDescription(visibility)}
              </FormHelperText>
            </FormControl>

            <Box>
              <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>
                Freigaben lassen sich auch später jederzeit auf der Detailseite ergänzen — dieser
                Schritt ist optional.
              </Typography>
              <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
                <Select
                  size="small"
                  value={grantSubjectType}
                  onChange={(e) => setGrantSubjectType(e.target.value as PermissionSubjectType)}
                  aria-label="Art des Freigabeempfängers"
                  sx={{ width: 130 }}
                >
                  {(['USER', 'GROUP'] as const).map((type) => (
                    <MenuItem key={type} value={type}>
                      {permissionSubjectTypeLabel(type)}
                    </MenuItem>
                  ))}
                </Select>
                {grantSubjectType === 'USER' ? (
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
                        placeholder="Person suchen …"
                        slotProps={{
                          ...params.slotProps,
                          htmlInput: { ...params.slotProps.htmlInput, 'aria-label': 'Person' },
                        }}
                      />
                    )}
                    isOptionEqualToValue={(option, value) => option.id === value.id}
                    sx={{ minWidth: 200, flex: 1 }}
                  />
                ) : (
                  <Autocomplete
                    options={availableGrantGroups}
                    size="small"
                    getOptionLabel={(option) => option.name}
                    value={selectedGrantGroup}
                    onChange={(_e, value) => setSelectedGrantGroup(value)}
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        placeholder="Gruppe suchen …"
                        slotProps={{
                          ...params.slotProps,
                          htmlInput: { ...params.slotProps.htmlInput, 'aria-label': 'Gruppe' },
                        }}
                      />
                    )}
                    isOptionEqualToValue={(option, value) => option.id === value.id}
                    sx={{ minWidth: 200, flex: 1 }}
                  />
                )}
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
                  disabled={grantSubjectType === 'USER' ? !selectedUser : !selectedGrantGroup}
                  onClick={handleAddGrant}
                >
                  Vormerken
                </Button>
              </Box>
              {pendingGrants.length > 0 && (
                <Box sx={{ border: 1, borderColor: 'divider', borderRadius: '10px', mt: 1.5 }}>
                  {pendingGrants.map((grant) => (
                    <Box
                      key={`${grant.subjectType}-${grant.subjectId}`}
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
                        {grant.label}
                      </Typography>
                      <Typography sx={{ fontSize: 11.5, color: 'text.secondary' }}>
                        {permissionSubjectTypeLabel(grant.subjectType)} ·{' '}
                        {assetRoleLabel(grant.role)}
                      </Typography>
                      <IconButton
                        size="small"
                        aria-label={`Vorgemerkte Freigabe für ${grant.label} entfernen`}
                        onClick={() =>
                          setPendingGrants((prev) =>
                            prev.filter(
                              (g) =>
                                !(
                                  g.subjectType === grant.subjectType &&
                                  g.subjectId === grant.subjectId
                                ),
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
          {activeStep === 2 && pendingGrants.length > 0 && (
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              {pendingGrants.length === 1
                ? '1 Freigabe vorgemerkt'
                : `${pendingGrants.length} Freigaben vorgemerkt`}
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
            <Button variant="contained" onClick={handleNext} disabled={name.trim() === ''}>
              {activeStep === 1 ? 'Weiter zu Rechten' : 'Weiter'}
            </Button>
          ) : (
            <Button
              variant="contained"
              onClick={() => void handleCreate()}
              disabled={submitting || name.trim() === ''}
            >
              {submitting ? 'Wird angelegt …' : 'Bibliothek anlegen'}
            </Button>
          )}
        </Box>
      </Box>
    </Box>
  )
}
