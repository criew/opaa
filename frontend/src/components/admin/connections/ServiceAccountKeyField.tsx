import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import { readGoogleDriveKey } from '../../../utils/googleDriveSource'

interface ServiceAccountKeyFieldProps {
  idPrefix: string
  /** The client id the stored key named; `null` before one is stored. */
  storedAccount: string | null
  keySet: boolean
  /** The chosen key file as text, `''` for none. */
  onChange: (keyFile: string) => void
}

/**
 * The service account key of a profile, uploaded as its JSON file. The client id is the key's
 * `client_email`, so the form asks for none; the file leaves the browser only with saving.
 */
export default function ServiceAccountKeyField({
  idPrefix,
  storedAccount,
  keySet,
  onChange,
}: ServiceAccountKeyFieldProps) {
  const [account, setAccount] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const input = useRef<HTMLInputElement | null>(null)

  const onFile = async (file: File | undefined) => {
    if (!file) return
    const text = await file.text()
    const read = readGoogleDriveKey(text)
    if ('error' in read) {
      setError(read.error)
      setAccount(null)
      onChange('')
    } else {
      setError(null)
      setAccount(read.account)
      onChange(text)
    }
    if (input.current) input.current.value = ''
  }

  const status =
    account !== null
      ? `Schlüssel des Dienstkontos ${account} ausgewählt. Die Client-ID wird daraus übernommen.`
      : keySet && storedAccount
        ? `Ein Schlüssel des Dienstkontos ${storedAccount} ist hinterlegt. Eine Datei nur für einen neuen Schlüssel wählen.`
        : 'Noch kein Schlüssel gewählt.'

  return (
    <Box>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
        <Button variant="outlined" component="label" startIcon={<UploadFileIcon />}>
          Schlüsseldatei wählen
          <input
            id={`${idPrefix}-key`}
            ref={input}
            type="file"
            accept="application/json,.json"
            hidden
            aria-label="Schlüsseldatei des Dienstkontos"
            onChange={(e) => void onFile(e.target.files?.[0])}
          />
        </Button>
        <Typography variant="body2" data-testid="profile-key-status">
          {status}
        </Typography>
      </Stack>
      {error && (
        <Alert severity="error" sx={{ mt: 1 }}>
          {error}
        </Alert>
      )}
      <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mt: 0.5 }}>
        Wird verschlüsselt gespeichert und nie wieder angezeigt. Ein Schlüssel eines anderen
        Dienstkontos ändert die Registrierung.
      </Typography>
    </Box>
  )
}
