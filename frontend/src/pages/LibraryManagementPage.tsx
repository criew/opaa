import { useEffect, useState } from 'react'
import { Link as RouterLink, useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { LibraryListResponse } from '../types/api'
import { useLibraryStore } from '../stores/libraryStore'
import { assetRoleLabel, documentSourceTypeLabel, libraryVisibilityLabel } from '../utils/labels'
import CreateLibraryDialog from '../components/CreateLibraryDialog'
import PageHeading from '../components/a11y/PageHeading'

function ownerTypeSummary(library: LibraryListResponse): string {
  if (library.ownerName) return library.ownerName
  if (library.ownerType === 'GROUP') return 'Gruppen-Bibliothek'
  return 'eigene'
}

function documentCountSummary(documentCount: number): string {
  return `${documentCount.toLocaleString('de-DE')} ${documentCount === 1 ? 'Dokument' : 'Dokumente'}`
}

function LibraryListItem({ library }: { library: LibraryListResponse }) {
  return (
    // #506 review, finding 6: a real link (right-click "open in new tab", middle-click, browser
    // history) instead of a navigate()-triggering button - RouterLink gives ButtonBase an actual
    // href while keeping the button's focus/hover/ripple behaviour.
    <ButtonBase
      component={RouterLink}
      to={`/libraries/${library.id}`}
      sx={{
        display: 'block',
        width: '100%',
        textAlign: 'left',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        p: 1.5,
      }}
    >
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', width: '100%' }}>
        <Box sx={{ minWidth: 0, flexGrow: 1 }}>
          <Typography sx={{ fontWeight: 600 }}>{library.name}</Typography>
          <Typography variant="body2" color="text.secondary">
            {ownerTypeSummary(library)} · {libraryVisibilityLabel(library.visibility)}
            {library.listed ? ' · gelistet' : ''} · {documentCountSummary(library.documentCount)}
          </Typography>
        </Box>
        <Stack direction="row" spacing={0.5}>
          <Chip
            label={documentSourceTypeLabel(library.sourceType)}
            size="small"
            variant="outlined"
          />
          <Chip label={assetRoleLabel(library.myRole)} size="small" variant="outlined" />
        </Stack>
      </Stack>
    </ButtonBase>
  )
}

export default function LibraryManagementPage() {
  const navigate = useNavigate()
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
        <PageHeading title="Wissensbibliotheken" variant="h6" />
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
            <LibraryListItem key={library.id} library={library} />
          ))}
        </Stack>
      )}

      <CreateLibraryDialog
        open={createDialogOpen}
        onClose={() => setCreateDialogOpen(false)}
        onCreated={(libraryId) => {
          setCreateDialogOpen(false)
          navigate(`/libraries/${libraryId}`)
        }}
      />
    </Box>
  )
}
