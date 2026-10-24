import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { fontFamily } from '@/shared/theme/tokens'
import {
  isValidS3BucketName,
  normalizeS3Prefix,
  type S3SourceValues,
} from '../../../utils/s3Source'
import type { DropboxSourceValues } from '../../../utils/dropboxSource'
import type { GoogleDriveSourceValues } from '../../../utils/googleDriveSource'

const monoInput = { '& input': { fontFamily: fontFamily.mono, fontSize: 13 } }

interface ExtraProps<V> {
  values: V
  onChange: (patch: Partial<V>) => void
  idPrefix: string
}

/** Bucket and folder typed by hand, for a key that may not list buckets. */
export function S3ManualEntry({ values, onChange, idPrefix }: ExtraProps<S3SourceValues>) {
  const [bucket, setBucket] = useState('')
  const [prefix, setPrefix] = useState('')
  const [error, setError] = useState<string | null>(null)
  function add() {
    const name = bucket.trim()
    if (!isValidS3BucketName(name)) {
      setError(name === '' ? 'Bitte den Bucket-Namen eingeben.' : 'Kein gültiger Bucket-Name.')
      return
    }
    setError(null)
    const scopes = values.scopes.filter((scope) => scope.bucket.trim() !== '')
    onChange({ scopes: [...scopes, { bucket: name, prefix: normalizeS3Prefix(prefix) }] })
    setBucket('')
    setPrefix('')
  }
  return (
    <Box
      sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'flex-start', maxWidth: 560 }}
    >
      <Box sx={{ flex: '1 1 180px' }}>
        <FieldLabel htmlFor={`${idPrefix}-manual-bucket`}>Bucket</FieldLabel>
        <TextField
          id={`${idPrefix}-manual-bucket`}
          size="small"
          fullWidth
          value={bucket}
          onChange={(e) => setBucket(e.target.value)}
          error={Boolean(error)}
          helperText={error}
          sx={monoInput}
          slotProps={{ formHelperText: { sx: { mx: 0 } } }}
        />
      </Box>
      <Box sx={{ flex: '1 1 180px' }}>
        <FieldLabel htmlFor={`${idPrefix}-manual-prefix`}>Ordner (optional)</FieldLabel>
        <TextField
          id={`${idPrefix}-manual-prefix`}
          size="small"
          fullWidth
          placeholder="protokolle/2024/"
          value={prefix}
          onChange={(e) => setPrefix(e.target.value)}
          sx={monoInput}
        />
      </Box>
      <Button variant="outlined" onClick={add} sx={{ mt: '21px' }}>
        Hinzufügen
      </Button>
    </Box>
  )
}

/** A Drive folder by its id, for folders the service account sees but the listing does not show. */
export function DriveManualEntry({
  values,
  onChange,
  idPrefix,
}: ExtraProps<GoogleDriveSourceValues>) {
  const [id, setId] = useState('')
  const [error, setError] = useState<string | null>(null)
  function add() {
    const folder = id.trim()
    if (!/^[A-Za-z0-9_-]{10,200}$/.test(folder)) {
      setError(
        folder === ''
          ? 'Bitte die Ordner-ID eingeben.'
          : 'Die Ordner-ID steht in der Adresse des Ordners nach /folders/.',
      )
      return
    }
    setError(null)
    onChange({ scopes: [...values.scopes, { kind: 'folder', id: folder, name: null }] })
    setId('')
  }
  return (
    <Box
      sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'flex-start', maxWidth: 560 }}
    >
      <Box sx={{ flex: '1 1 240px' }}>
        <FieldLabel htmlFor={`${idPrefix}-manual-folder`}>Ordner-ID</FieldLabel>
        <TextField
          id={`${idPrefix}-manual-folder`}
          size="small"
          fullWidth
          placeholder="1AbCdEfGhIjKlMnOp"
          value={id}
          onChange={(e) => setId(e.target.value)}
          error={Boolean(error)}
          helperText={error}
          sx={monoInput}
          slotProps={{ formHelperText: { sx: { mx: 0 } } }}
        />
      </Box>
      <Button variant="outlined" onClick={add} sx={{ mt: '21px' }}>
        Hinzufügen
      </Button>
    </Box>
  )
}

/** Whether the team space of a Dropbox Business account is read besides the member's folders. */
export function DropboxTeamSwitch({ values, onChange }: ExtraProps<DropboxSourceValues>) {
  return (
    <Box sx={{ mb: 2 }}>
      <FormControlLabel
        control={
          <Switch
            checked={values.teamSpace}
            onChange={(e) => onChange({ teamSpace: e.target.checked })}
          />
        }
        label="Team-Bereich lesen"
      />
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', ml: 6 }}>
        Nur bei Dropbox Business: die Ordner des Teams statt der eigenen.
      </Typography>
    </Box>
  )
}
