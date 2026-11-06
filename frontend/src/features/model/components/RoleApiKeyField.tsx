import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import type { ApiKeyDraft } from './roleForm'

/**
 * The key field of a model role: never shows a stored key, an empty field keeps it, and
 * „Gespeicherten Schlüssel entfernen" marks it for removal on the next save.
 */
export default function RoleApiKeyField({
  id,
  value,
  onChange,
  apiKeySet,
  error,
}: {
  id: string
  value: ApiKeyDraft
  onChange: (next: ApiKeyDraft) => void
  apiKeySet: boolean
  error?: string
}) {
  return (
    <Box>
      <TextField
        id={id}
        label="Zugangsschlüssel (optional)"
        type="password"
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
              : 'Wird verschlüsselt gespeichert und nie wieder angezeigt.')
        }
        size="small"
        fullWidth
        autoComplete="new-password"
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
