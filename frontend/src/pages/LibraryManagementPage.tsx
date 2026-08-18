import { useEffect, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import InputLabel from '@mui/material/InputLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { AssetRole, LibraryListResponse, LibraryVisibility } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import { assetRoleLabel, libraryVisibilityLabel } from '../utils/labels'
import CreateLibraryDialog from '../components/CreateLibraryDialog'
import LibraryGrantsDialog from '../components/LibraryGrantsDialog'

const allVisibilities: LibraryVisibility[] = ['PRIVATE', 'SHARED', 'ORGANIZATION']
const personalLibraryVisibilities: LibraryVisibility[] = ['PRIVATE', 'SHARED']

function canEditLibrary(role: AssetRole | undefined): boolean {
  return role === 'MANAGER' || role === 'OWNER'
}

function canDeleteLibrary(role: AssetRole | undefined): boolean {
  return role === 'OWNER'
}

function ownerTypeSummary(library: LibraryListResponse): string {
  if (library.personal) return 'persönlich'
  if (library.ownerType === 'GROUP') return 'Gruppen-Bibliothek'
  if (library.ownerType === 'SYSTEM') return 'systemweit bereitgestellt'
  return 'eigene'
}

function documentCountSummary(documentCount: number): string {
  return `${documentCount} ${documentCount === 1 ? 'Dokument' : 'Dokumente'}`
}

function LibraryCard({ library }: { library: LibraryListResponse }) {
  const details = useLibraryStore((s) => s.libraryDetails[library.id])
  const loadLibraryDetails = useLibraryStore((s) => s.loadLibraryDetails)
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)
  const deleteExistingLibrary = useLibraryStore((s) => s.deleteExistingLibrary)
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')

  const [expanded, setExpanded] = useState(false)
  const [grantsDialogOpen, setGrantsDialogOpen] = useState(false)
  const [draft, setDraft] = useState<{
    libraryId: string | null
    name: string
    description: string
    visibility: LibraryVisibility
    listed: boolean
  }>({ libraryId: null, name: '', description: '', visibility: 'PRIVATE', listed: false })
  const [localError, setLocalError] = useState<string | null>(null)

  // #437 review: myRole on the list response never bypasses to OWNER for a system admin (see the
  // opaa-api.yaml Javadoc on LibraryListResponse.myRole), but the write paths (updateLibrary,
  // deleteLibrary) do bypass canManage/canDelete for one. Without this OR, a system admin without
  // a personal grant would see every organization-wide and the SYSTEM library as read-only, even
  // though the backend lets them administer it - leaving those libraries administrable by no one
  // through this page.
  const roleGrantsEdit = canEditLibrary(library.myRole)
  const roleGrantsDelete = canDeleteLibrary(library.myRole)
  const canEdit = roleGrantsEdit || isSystemAdmin
  // KnowledgeLibraryService#deleteLibrary rejects both the personal library and the SYSTEM
  // library (isSystemLibrary()) with a 400, regardless of caller - neither is ever deletable,
  // system admin or not.
  const canDelete =
    (roleGrantsDelete || isSystemAdmin) && !library.personal && library.ownerType !== 'SYSTEM'
  // #423 code review, finding 2: AssetGrantService#upsertGrant rejects every grant on the personal
  // library unconditionally (it is meant to reach only its owner) - the same exception canDelete
  // above already carries for the identical backend reason. Without it, "Rechte verwalten" opened
  // a dialog that could only ever fail with a 400 on the personal library.
  const canManageGrants = canEdit && !library.personal
  const isAdministrativeOverride = isSystemAdmin && !roleGrantsEdit

  const editableVisibilities = library.personal ? personalLibraryVisibilities : allVisibilities

  useEffect(() => {
    if (expanded && !details) {
      void loadLibraryDetails(library.id)
    }
  }, [expanded, details, library.id, loadLibraryDetails])

  const name = draft.libraryId === library.id ? draft.name : library.name
  const description =
    draft.libraryId === library.id ? draft.description : (library.description ?? '')
  const visibility = draft.libraryId === library.id ? draft.visibility : library.visibility
  const listed = draft.libraryId === library.id ? draft.listed : library.listed
  const visibilityLabelId = `library-visibility-label-${library.id}`

  return (
    <Accordion
      expanded={expanded}
      onChange={(_event, isExpanded) => setExpanded(isExpanded)}
      variant="outlined"
      disableGutters
    >
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexGrow: 1 }}>
          <Typography sx={{ fontWeight: 600 }}>{library.name}</Typography>
          <Typography variant="body2" color="text.secondary">
            {ownerTypeSummary(library)} · {libraryVisibilityLabel(library.visibility)}
            {library.listed ? ' · gelistet' : ''} · {documentCountSummary(library.documentCount)}
          </Typography>
          <Stack direction="row" spacing={0.5} sx={{ ml: 'auto' }}>
            <Chip label={assetRoleLabel(library.myRole)} size="small" variant="outlined" />
            {isAdministrativeOverride && (
              <Chip label="administrativ" size="small" color="info" variant="outlined" />
            )}
          </Stack>
        </Stack>
      </AccordionSummary>
      <AccordionDetails>
        {localError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setLocalError(null)}>
            {localError}
          </Alert>
        )}
        {!canEdit && (
          <Alert severity="info" sx={{ mb: 2 }}>
            Sie können diese Bibliothek einsehen, aber nicht bearbeiten.
          </Alert>
        )}
        {isAdministrativeOverride && (
          <Alert severity="info" sx={{ mb: 2 }}>
            Sie bearbeiten diese Bibliothek als System-Administrator, nicht über eine eigene
            Berechtigung.
          </Alert>
        )}
        <Stack spacing={1.5} sx={{ mb: 2 }}>
          <TextField
            label="Name der Bibliothek"
            value={name}
            onChange={(e) =>
              setDraft({
                libraryId: library.id,
                name: e.target.value,
                description,
                visibility,
                listed,
              })
            }
            disabled={!canEdit}
            size="small"
          />
          <TextField
            label="Beschreibung"
            value={description}
            onChange={(e) =>
              setDraft({
                libraryId: library.id,
                name,
                description: e.target.value,
                visibility,
                listed,
              })
            }
            multiline
            minRows={2}
            disabled={!canEdit}
            size="small"
          />
          <FormControl size="small" disabled={!canEdit}>
            <InputLabel id={visibilityLabelId}>Sichtbarkeit</InputLabel>
            <Select
              labelId={visibilityLabelId}
              label="Sichtbarkeit"
              value={visibility}
              onChange={(e) =>
                setDraft({
                  libraryId: library.id,
                  name,
                  description,
                  visibility: e.target.value as LibraryVisibility,
                  listed,
                })
              }
            >
              {editableVisibilities.map((option) => (
                <MenuItem key={option} value={option}>
                  {libraryVisibilityLabel(option)}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          {library.personal && (
            <Typography variant="caption" color="text.secondary">
              Die persönliche Bibliothek kann nicht organisationsweit sichtbar sein.
            </Typography>
          )}
          <FormControlLabel
            control={
              <Checkbox
                checked={listed}
                disabled={!canEdit}
                onChange={(e) =>
                  setDraft({
                    libraryId: library.id,
                    name,
                    description,
                    visibility,
                    listed: e.target.checked,
                  })
                }
              />
            }
            label="Im Katalog auffindbar"
          />
          <Typography variant="caption" color="text.secondary">
            Die Auffindbarkeit im Katalog ist unabhängig vom Zugriff — eine Bibliothek kann
            auffindbar sein, ohne dass Sie darauf zugreifen können.
          </Typography>

          {canEdit && (
            <Stack direction="row" spacing={1}>
              <Button
                variant="contained"
                size="small"
                onClick={async () => {
                  setLocalError(null)
                  try {
                    await updateExistingLibrary(library.id, {
                      name: name.trim(),
                      description: description.trim() || undefined,
                      visibility,
                      listed,
                    })
                  } catch (err) {
                    setLocalError(
                      err instanceof Error ? err.message : 'Aktualisierung fehlgeschlagen',
                    )
                  }
                }}
              >
                Speichern
              </Button>
              {canManageGrants && (
                <Button variant="outlined" size="small" onClick={() => setGrantsDialogOpen(true)}>
                  Rechte verwalten
                </Button>
              )}
              {canDelete && (
                <Button
                  color="error"
                  variant="outlined"
                  size="small"
                  onClick={async () => {
                    if (
                      !window.confirm(
                        `Bibliothek "${library.name}" löschen? Diese Aktion kann nicht rückgängig gemacht werden.`,
                      )
                    ) {
                      return
                    }
                    setLocalError(null)
                    try {
                      await deleteExistingLibrary(library.id)
                    } catch (err) {
                      setLocalError(err instanceof Error ? err.message : 'Löschen fehlgeschlagen')
                    }
                  }}
                >
                  Bibliothek löschen
                </Button>
              )}
            </Stack>
          )}
        </Stack>
      </AccordionDetails>
      {canManageGrants && (
        <LibraryGrantsDialog
          open={grantsDialogOpen}
          library={{ id: library.id, name: library.name }}
          onClose={() => setGrantsDialogOpen(false)}
        />
      )}
    </Accordion>
  )
}

export default function LibraryManagementPage() {
  const libraries = useLibraryStore((s) => s.libraries)
  const isLoading = useLibraryStore((s) => s.isLoading)
  const error = useLibraryStore((s) => s.error)
  const loadLibraries = useLibraryStore((s) => s.loadLibraries)
  const [createDialogOpen, setCreateDialogOpen] = useState(false)

  useEffect(() => {
    void loadLibraries()
  }, [loadLibraries])

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2, md: 3 }, overflowY: 'auto' }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h6">Wissensbibliotheken</Typography>
        <Button variant="contained" onClick={() => setCreateDialogOpen(true)}>
          Neue Bibliothek
        </Button>
      </Stack>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      <Divider sx={{ mb: 2 }} />

      {isLoading ? (
        <Typography color="text.secondary">Bibliotheken werden geladen …</Typography>
      ) : libraries.length === 0 ? (
        <Typography color="text.secondary">Es sind noch keine Bibliotheken vorhanden.</Typography>
      ) : (
        <Stack spacing={1}>
          {libraries.map((library) => (
            <LibraryCard key={library.id} library={library} />
          ))}
        </Stack>
      )}

      <CreateLibraryDialog
        open={createDialogOpen}
        onClose={() => setCreateDialogOpen(false)}
        onCreated={() => setCreateDialogOpen(false)}
      />
    </Box>
  )
}
