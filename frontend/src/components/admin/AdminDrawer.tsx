import { useEffect } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import { useIndexingStore } from '../../stores/indexingStore'

const DRAWER_WIDTH = 320

export default function AdminDrawer() {
  const drawerOpen = useIndexingStore((s) => s.drawerOpen)
  const setDrawerOpen = useIndexingStore((s) => s.setDrawerOpen)
  const status = useIndexingStore((s) => s.status)
  const documentCount = useIndexingStore((s) => s.documentCount)
  const totalDocuments = useIndexingStore((s) => s.totalDocuments)
  const documentsSkipped = useIndexingStore((s) => s.documentsSkipped)
  const timestamp = useIndexingStore((s) => s.timestamp)
  const trigger = useIndexingStore((s) => s.triggerIndexing)
  const libraries = useIndexingStore((s) => s.libraries)
  const librariesLoading = useIndexingStore((s) => s.librariesLoading)
  const fetchLibraries = useIndexingStore((s) => s.fetchLibraries)
  const selectedLibraryId = useIndexingStore((s) => s.selectedLibraryId)
  const setSelectedLibraryId = useIndexingStore((s) => s.setSelectedLibraryId)

  useEffect(() => {
    if (drawerOpen) {
      fetchLibraries()
    }
  }, [drawerOpen, fetchLibraries])

  const isRunning = status === 'RUNNING'
  const progressPercent =
    totalDocuments > 0 ? Math.round(((documentCount + documentsSkipped) / totalDocuments) * 100) : 0

  return (
    <Drawer
      anchor="right"
      open={drawerOpen}
      onClose={() => setDrawerOpen(false)}
      sx={{ '& .MuiDrawer-paper': { width: DRAWER_WIDTH } }}
    >
      <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            p: 2,
          }}
        >
          <Typography variant="h6" sx={{ fontWeight: 700 }}>
            Admin
          </Typography>
          <IconButton onClick={() => setDrawerOpen(false)} aria-label="Admin-Bereich schließen">
            <CloseIcon />
          </IconButton>
        </Box>

        <Divider />

        <Box sx={{ p: 2 }}>
          <Typography variant="overline" color="text.secondary">
            Dokumentenindizierung
          </Typography>

          {/*
            #478: Adresse, Proxy, Anmeldedaten und die SSL-Option sind keine Felder dieses Anstoßes
            mehr - sie stammen jetzt aus der gespeicherten Quellkonfiguration der Bibliothek
            (ADR-0018). Wer diese Konfiguration einsehen oder ändern will, tut das künftig auf der
            Bibliotheksdetailseite (#481); hier bleibt nur die Auswahl der Zielbibliothek und der
            Anstoß-Knopf.
          */}
          <TextField
            select
            label="Zielbibliothek"
            value={selectedLibraryId ?? ''}
            onChange={(e) => setSelectedLibraryId(e.target.value || null)}
            size="small"
            fullWidth
            disabled={isRunning || librariesLoading}
            sx={{ mt: 1.5, mb: 1.5 }}
            helperText={
              librariesLoading
                ? 'Bibliotheken werden geladen …'
                : libraries.length === 0
                  ? 'Keine Bibliothek mit Bearbeitungsrecht verfügbar'
                  : 'Ohne Auswahl kann keine Indizierung gestartet werden'
            }
            slotProps={{ htmlInput: { 'aria-label': 'Zielbibliothek' } }}
          >
            {libraries.map((library) => (
              <MenuItem key={library.id} value={library.id}>
                {library.name}
              </MenuItem>
            ))}
          </TextField>

          <Button
            variant="contained"
            startIcon={<PlayArrowIcon />}
            onClick={trigger}
            disabled={isRunning || !selectedLibraryId}
            fullWidth
            sx={{ mt: 1, mb: 2 }}
          >
            {isRunning ? 'Indizierung läuft …' : 'Dokumente indizieren'}
          </Button>

          {isRunning && (
            <Box sx={{ mb: 2 }}>
              <LinearProgress
                variant={totalDocuments > 0 ? 'determinate' : 'indeterminate'}
                value={progressPercent}
                sx={{ mb: 1 }}
              />
              <Typography variant="body2" color="text.secondary">
                {totalDocuments > 0
                  ? `${documentCount + documentsSkipped} von ${totalDocuments} Dokumenten verarbeitet`
                  : 'Dokumente werden ermittelt …'}
              </Typography>
            </Box>
          )}

          {status !== 'IDLE' && !isRunning && (
            <Box sx={{ mt: 1 }}>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                Letzte Indizierung: {status === 'COMPLETED' ? 'Abgeschlossen' : 'Fehlgeschlagen'}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Dokumente: {documentCount} verarbeitet
                {documentsSkipped > 0 && ` (${documentsSkipped} übersprungen)`}
              </Typography>
              {timestamp && (
                <Typography variant="caption" color="text.secondary">
                  {new Date(timestamp).toLocaleString('de-DE')}
                </Typography>
              )}
            </Box>
          )}
        </Box>
      </Box>
    </Drawer>
  )
}
