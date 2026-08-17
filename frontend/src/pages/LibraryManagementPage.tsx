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
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type { AssetRole, LibraryListResponse, LibraryVisibility } from '../types/api'
import { useLibraryStore } from '../stores/libraryStore'
import { assetRoleLabel, libraryVisibilityLabel } from '../utils/labels'
import CreateLibraryDialog from '../components/CreateLibraryDialog'

const editableVisibilities: LibraryVisibility[] = ['PRIVATE', 'SHARED', 'ORGANIZATION']

function canEditLibrary(role: AssetRole | undefined): boolean {
  return role === 'MANAGER' || role === 'OWNER'
}

function canDeleteLibrary(role: AssetRole | undefined): boolean {
  return role === 'OWNER'
}

function ownerTypeSummary(library: LibraryListResponse): string {
  if (library.personal) return 'persönlich'
  if (library.ownerType === 'GROUP') return 'Gruppen-Bibliothek'
  if (library.ownerType === 'SYSTEM') return 'organisationsweit'
  return 'eigene'
}

function LibraryCard({ library }: { library: LibraryListResponse }) {
  const details = useLibraryStore((s) => s.libraryDetails[library.id])
  const loadLibraryDetails = useLibraryStore((s) => s.loadLibraryDetails)
  const updateExistingLibrary = useLibraryStore((s) => s.updateExistingLibrary)
  const deleteExistingLibrary = useLibraryStore((s) => s.deleteExistingLibrary)

  const [expanded, setExpanded] = useState(false)
  const [draft, setDraft] = useState<{
    libraryId: string | null
    name: string
    description: string
    visibility: LibraryVisibility
    listed: boolean
  }>({ libraryId: null, name: '', description: '', visibility: 'PRIVATE', listed: false })
  const [localError, setLocalError] = useState<string | null>(null)

  const canEdit = canEditLibrary(library.myRole)
  const canDelete = canDeleteLibrary(library.myRole) && !library.personal

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
            {library.listed ? ' · gelistet' : ''}
          </Typography>
          <Chip
            label={assetRoleLabel(library.myRole)}
            size="small"
            variant="outlined"
            sx={{ ml: 'auto' }}
          />
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
        {typeof details?.documentCount === 'number' && (
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {details.documentCount} {details.documentCount === 1 ? 'Dokument' : 'Dokumente'}
          </Typography>
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
          <Select
            size="small"
            value={visibility}
            disabled={!canEdit}
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
