import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Divider from '@mui/material/Divider'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormLabel from '@mui/material/FormLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { DocumentSourceType, GroupListResponse, LibraryOwnerType } from '../types/api'
import { getMyGroups } from '../services/api'
import { useLibraryStore } from '../stores/libraryStore'
import {
  allDocumentSourceTypes,
  documentSourceTypeConfigKind,
  documentSourceTypeDescription,
  documentSourceTypeLabel,
} from '../utils/labels'

interface CreateLibraryDialogProps {
  open: boolean
  onClose: () => void
  onCreated: (libraryId: string) => void
}

function initialSourceType(): DocumentSourceType {
  // UPLOAD ist der einfachste und bislang einzig verfuegbare Weg gewesen, bevor Konnektortypen
  // dazukamen - als Vorauswahl bleibt der bestehende Anlageweg unveraendert, waehrend die anderen
  // Vorlagen explizit gewaehlt werden.
  return 'UPLOAD'
}

export default function CreateLibraryDialog({
  open,
  onClose,
  onCreated,
}: CreateLibraryDialogProps) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [ownerType, setOwnerType] = useState<LibraryOwnerType>('USER')
  const [selectedGroup, setSelectedGroup] = useState<GroupListResponse | null>(null)
  const [groups, setGroups] = useState<GroupListResponse[]>([])
  const [groupsError, setGroupsError] = useState<string | null>(null)
  const [groupsLoaded, setGroupsLoaded] = useState(false)
  const [sourceType, setSourceType] = useState<DocumentSourceType>(initialSourceType())
  const [sourcePath, setSourcePath] = useState('')
  const [sourceUrl, setSourceUrl] = useState('')
  const [sourceProxy, setSourceProxy] = useState('')
  const [sourceCredentials, setSourceCredentials] = useState('')
  const [sourceInsecureSsl, setSourceInsecureSsl] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const createNewLibrary = useLibraryStore((s) => s.createNewLibrary)

  useEffect(() => {
    if (!open) return
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
    return () => {
      cancelled = true
    }
  }, [open])

  function resetForm() {
    setName('')
    setDescription('')
    setOwnerType('USER')
    setSelectedGroup(null)
    setGroups([])
    setGroupsLoaded(false)
    setGroupsError(null)
    setSourceType(initialSourceType())
    setSourcePath('')
    setSourceUrl('')
    setSourceProxy('')
    setSourceCredentials('')
    setSourceInsecureSsl(false)
    setError(null)
  }

  function handleClose() {
    if (submitting) return
    resetForm()
    onClose()
  }

  async function handleCreate() {
    const trimmedName = name.trim()
    if (!trimmedName) {
      setError('Name ist erforderlich')
      return
    }
    if (ownerType === 'GROUP' && !selectedGroup) {
      setError('Bitte eine Gruppe auswählen')
      return
    }
    const configKind = documentSourceTypeConfigKind[sourceType]
    const trimmedPath = sourcePath.trim()
    if (configKind === 'path' && !trimmedPath) {
      setError('Verzeichnispfad ist erforderlich')
      return
    }
    if (configKind === 'path' && !trimmedPath.startsWith('/')) {
      setError('Verzeichnispfad muss ein absoluter Pfad sein, z. B. /data/dokumente')
      return
    }
    const trimmedUrl = sourceUrl.trim()
    if (configKind === 'url' && !trimmedUrl) {
      setError('Adresse (URL) ist erforderlich')
      return
    }
    if (configKind === 'url' && trimmedUrl && !/^https?:\/\//i.test(trimmedUrl)) {
      setError('Adresse (URL) muss mit http:// oder https:// beginnen')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      const libraryId = await createNewLibrary({
        name: trimmedName,
        description: description.trim() || undefined,
        ownerType,
        ownerId: ownerType === 'GROUP' ? (selectedGroup?.id ?? undefined) : undefined,
        // sourceType ist seit ADR-0018 Pflichtfeld und beim Anlegen unveränderlich; die Auswahl
        // erfolgt oben als Vorlagenwahl. Nur der zum Typ passende Teil der Konfigurationsfelder
        // wird gesendet - das Backend lehnt jede Kombination ab, die dem Typ widerspricht.
        sourceType,
        sourcePath: configKind === 'path' ? trimmedPath : undefined,
        sourceUrl: configKind === 'url' ? trimmedUrl : undefined,
        sourceProxy: configKind === 'url' && sourceProxy.trim() ? sourceProxy.trim() : undefined,
        sourceCredentials:
          configKind === 'url' && sourceCredentials.trim() ? sourceCredentials.trim() : undefined,
        sourceInsecureSsl: configKind === 'url' ? sourceInsecureSsl : false,
      })
      resetForm()
      onCreated(libraryId)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Bibliothek konnte nicht erstellt werden')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth>
      <DialogTitle>Wissensbibliothek erstellen</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <FormControl sx={{ mt: 1, width: '100%' }}>
          <FormLabel id="library-source-type-label">Vorlage</FormLabel>
          <RadioGroup
            aria-labelledby="library-source-type-label"
            value={sourceType}
            onChange={(e) => setSourceType(e.target.value as DocumentSourceType)}
          >
            {allDocumentSourceTypes.map((type, index) => (
              <FormControlLabel
                key={type}
                value={type}
                control={<Radio autoFocus={index === 0} />}
                label={
                  <Box sx={{ py: 0.5 }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {documentSourceTypeLabel(type)}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {documentSourceTypeDescription(type)}
                    </Typography>
                  </Box>
                }
              />
            ))}
          </RadioGroup>
        </FormControl>

        <Divider sx={{ my: 2 }} />

        <TextField
          label="Name"
          fullWidth
          required
          value={name}
          onChange={(e) => setName(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 255 } }}
        />
        <TextField
          label="Beschreibung"
          fullWidth
          multiline
          minRows={2}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 2000 } }}
          sx={{ mt: 2 }}
        />

        {documentSourceTypeConfigKind[sourceType] === 'path' && (
          <TextField
            label="Verzeichnispfad"
            fullWidth
            required
            value={sourcePath}
            onChange={(e) => setSourcePath(e.target.value)}
            placeholder="/data/dokumente"
            helperText="Absoluter Pfad auf dem Server, den OPAA regelmäßig einliest."
            slotProps={{ htmlInput: { maxLength: 2000 } }}
            sx={{ mt: 2 }}
          />
        )}

        {documentSourceTypeConfigKind[sourceType] === 'url' && (
          <Stack spacing={2} sx={{ mt: 2 }}>
            {sourceType === 'RSS_FEED' && (
              <Alert severity="info">
                OPAA ruft neben dem Feed auch die von ihm verlinkten Detailseiten ab. Welche
                Adressen das sind, bestimmt der Betreiber des Feeds, nicht Sie selbst.
              </Alert>
            )}
            <TextField
              label="Adresse (URL)"
              fullWidth
              required
              value={sourceUrl}
              onChange={(e) => setSourceUrl(e.target.value)}
              placeholder="https://files.example.com/dokumente/"
              helperText="http oder https."
              slotProps={{ htmlInput: { maxLength: 2000 } }}
            />
            <TextField
              label="Proxy"
              fullWidth
              value={sourceProxy}
              onChange={(e) => setSourceProxy(e.target.value)}
              placeholder="proxy.example.com:8080"
              helperText="Optional."
              // Chrome/Safari can pair a plain text field right above a password field into the
              // same credential group and offer to save it as the "username" - autoComplete="off"
              // keeps the proxy address (a server, not a login) out of that pairing.
              autoComplete="off"
              slotProps={{ htmlInput: { maxLength: 255 } }}
            />
            <TextField
              label="Anmeldedaten"
              type="password"
              fullWidth
              value={sourceCredentials}
              onChange={(e) => setSourceCredentials(e.target.value)}
              placeholder="benutzer:passwort"
              helperText="Optional. Wird nie in einer API-Antwort ausgegeben."
              // "new-password" statt "current-password": dieses Feld gehoert zur Quellkonfiguration
              // der Bibliothek (eine fremde Basic-Auth), nicht zum OPAA-Konto der Nutzerin - der
              // Browser-Passwortmanager soll es weder mit dem OPAA-Kennwort befuellen noch die hier
              // eingegebene fremde Quell-Zugangsdaten unter dem OPAA-Login speichern.
              autoComplete="new-password"
              slotProps={{ htmlInput: { maxLength: 500 } }}
            />
            <FormControlLabel
              control={
                <Switch
                  checked={sourceInsecureSsl}
                  onChange={(e) => setSourceInsecureSsl(e.target.checked)}
                />
              }
              label="Zertifikatsprüfung aussetzen"
            />
          </Stack>
        )}

        <FormControl sx={{ mt: 2 }}>
          <FormLabel id="library-owner-label">Eigentümer</FormLabel>
          <RadioGroup
            aria-labelledby="library-owner-label"
            value={ownerType}
            onChange={(e) => setOwnerType(e.target.value as LibraryOwnerType)}
          >
            <FormControlLabel value="USER" control={<Radio />} label="Mein Konto" />
            <FormControlLabel value="GROUP" control={<Radio />} label="Eine Gruppe" />
          </RadioGroup>
        </FormControl>
        {ownerType === 'GROUP' && (
          <>
            {groupsError && (
              <Alert severity="error" sx={{ mt: 2 }}>
                {groupsError}
              </Alert>
            )}
            {groupsLoaded && !groupsError && groups.length === 0 && (
              <Alert severity="info" sx={{ mt: 2 }}>
                Sie sind aktuell in keiner Gruppe Mitglied. Eine Bibliothek mit Gruppen-Eigentum
                lässt sich erst anlegen, sobald Sie einer Gruppe angehören.
              </Alert>
            )}
            <Autocomplete
              options={groups}
              getOptionLabel={(option) => option.name}
              value={selectedGroup}
              onChange={(_event, value) => setSelectedGroup(value)}
              disabled={groups.length === 0}
              renderInput={(params) => (
                <TextField {...params} label="Gruppe" placeholder="Gruppe auswählen …" />
              )}
              isOptionEqualToValue={(option, value) => option.id === value.id}
              sx={{ mt: 2 }}
            />
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={handleClose} disabled={submitting}>
          Abbrechen
        </Button>
        <Button onClick={handleCreate} variant="contained" disabled={submitting || !name.trim()}>
          {submitting ? 'Wird erstellt …' : 'Erstellen'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
