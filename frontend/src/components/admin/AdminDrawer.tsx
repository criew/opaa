import { useCallback, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import Drawer from '@mui/material/Drawer'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
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
  const setUrlConfig = useIndexingStore((s) => s.setUrlConfig)

  const [url, setUrl] = useState('')
  const [proxy, setProxy] = useState('')
  const [credentials, setCredentials] = useState('')
  const [insecureSsl, setInsecureSsl] = useState(false)

  const isRunning = status === 'RUNNING'
  const progressPercent =
    totalDocuments > 0 ? Math.round(((documentCount + documentsSkipped) / totalDocuments) * 100) : 0

  const handleTrigger = useCallback(() => {
    if (url.trim()) {
      setUrlConfig({
        url: url.trim(),
        proxy: proxy.trim() || undefined,
        credentials: credentials.trim() || undefined,
        insecureSsl: insecureSsl || undefined,
      })
    } else {
      setUrlConfig(null)
    }
    trigger()
  }, [url, proxy, credentials, insecureSsl, setUrlConfig, trigger])

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

          <Accordion
            disableGutters
            elevation={0}
            sx={{
              mt: 1.5,
              mb: 1,
              border: 1,
              borderColor: 'divider',
              borderRadius: 1,
              '&::before': { display: 'none' },
            }}
          >
            <AccordionSummary expandIcon={<ExpandMoreIcon />}>
              <Typography variant="body2">URL-Quelle (optional)</Typography>
            </AccordionSummary>
            <AccordionDetails sx={{ pt: 0 }}>
              <TextField
                label="URL"
                placeholder="https://example.com/files/"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                size="small"
                fullWidth
                sx={{ mb: 1.5 }}
                slotProps={{ htmlInput: { 'aria-label': 'URL' } }}
              />
              <TextField
                label="Proxy (host:port)"
                placeholder="proxy.example.com:8080"
                value={proxy}
                onChange={(e) => setProxy(e.target.value)}
                size="small"
                fullWidth
                sx={{ mb: 1.5 }}
                slotProps={{ htmlInput: { 'aria-label': 'Proxy' } }}
              />
              <TextField
                label="Anmeldedaten (Benutzer:Passwort)"
                type="password"
                value={credentials}
                onChange={(e) => setCredentials(e.target.value)}
                size="small"
                fullWidth
                sx={{ mb: 1 }}
                slotProps={{ htmlInput: { 'aria-label': 'Anmeldedaten' } }}
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={insecureSsl}
                    onChange={(e) => setInsecureSsl(e.target.checked)}
                    size="small"
                    slotProps={{ input: { 'aria-label': 'SSL-Prüfung überspringen' } }}
                  />
                }
                label={
                  <Typography variant="body2" color="text.secondary">
                    SSL-Prüfung überspringen
                  </Typography>
                }
              />
            </AccordionDetails>
          </Accordion>

          <Button
            variant="contained"
            startIcon={<PlayArrowIcon />}
            onClick={handleTrigger}
            disabled={isRunning}
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
