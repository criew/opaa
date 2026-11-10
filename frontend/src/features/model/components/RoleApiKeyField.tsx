import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import TextField from '@mui/material/TextField'
import VisibilityOffOutlined from '@mui/icons-material/VisibilityOffOutlined'
import VisibilityOutlined from '@mui/icons-material/VisibilityOutlined'
import type { ApiKeyDraft } from './roleForm'

/**
 * The key field of a model role: never shows a stored key, an empty field keeps it, and
 * „Gespeicherten Schlüssel entfernen" marks it for removal on the next save. The eye shows only
 * what was typed here, so a pasted key can be checked.
 */
export default function RoleApiKeyField({
  id,
  value,
  onChange,
  apiKeySet,
  error,
  label = 'Zugangsschlüssel (optional)',
  emptyHint = 'Wird verschlüsselt gespeichert und nie wieder angezeigt.',
}: {
  id: string
  value: ApiKeyDraft
  onChange: (next: ApiKeyDraft) => void
  apiKeySet: boolean
  error?: string
  label?: string
  /** The hint while no key is stored. */
  emptyHint?: string
}) {
  const [shown, setShown] = useState(false)
  const name = label.replace(/\s*\(optional\)$/, '')
  return (
    <Box>
      <TextField
        id={id}
        label={label}
        type={shown ? 'text' : 'password'}
        value={value.input}
        disabled={value.clearRequested}
        onChange={(e) => onChange({ input: e.target.value, touched: true, clearRequested: false })}
        error={Boolean(error)}
        helperText={
          error ??
          (value.clearRequested
            ? 'Der gespeicherte Schlüssel wird beim Speichern entfernt.'
            : apiKeySet
              ? 'Leer lassen, um den gespeicherten Schlüssel unverändert zu lassen.'
              : emptyHint)
        }
        size="small"
        fullWidth
        autoComplete="new-password"
        slotProps={{
          input: {
            endAdornment: (
              <InputAdornment position="end">
                <IconButton
                  size="small"
                  edge="end"
                  aria-label={shown ? `${name} verbergen` : `${name} anzeigen`}
                  aria-pressed={shown}
                  disabled={value.clearRequested}
                  onClick={() => setShown((current) => !current)}
                >
                  {shown ? (
                    <VisibilityOffOutlined fontSize="small" />
                  ) : (
                    <VisibilityOutlined fontSize="small" />
                  )}
                </IconButton>
              </InputAdornment>
            ),
          },
        }}
      />
      {apiKeySet && (
        <Button
          size="small"
          color={value.clearRequested ? 'primary' : 'error'}
          sx={{ mt: 0.5 }}
          onClick={() =>
            onChange({ input: '', touched: false, clearRequested: !value.clearRequested })
          }
        >
          {value.clearRequested
            ? 'Entfernen rückgängig machen'
            : 'Gespeicherten Schlüssel entfernen'}
        </Button>
      )}
    </Box>
  )
}
