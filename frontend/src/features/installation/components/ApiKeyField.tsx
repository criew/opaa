import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import SecretInput from './SecretInput'
import { EDGE_ALIGNED_TEXT_BUTTON_SX, STEP_RHYTHM } from './stepLayout'

interface ApiKeyFieldProps {
  id: string
  /** A key is stored for this model. */
  keyStored: boolean
  value: string
  onChange: (value: string) => void
  /** The stored key is to be removed with the next save. */
  remove: boolean
  onRemoveChange: (remove: boolean) => void
  error?: string
  /** What the field asks for; by default that a service without key needs none. */
  hint?: string
}

/**
 * The API key of a model step: „Gesetzt · Ersetzen" for a stored key, and the explicit way to
 * remove it. The save sends what `apiKeyPart` returns: omitted keeps the key, empty removes it.
 */
export default function ApiKeyField({
  id,
  keyStored,
  value,
  onChange,
  remove,
  onRemoveChange,
  error,
  hint = 'Leer lassen, wenn der Dienst keinen Schlüssel verlangt.',
}: ApiKeyFieldProps) {
  return (
    <Box>
      <SecretInput
        id={id}
        label="API-Schlüssel"
        optional
        autoComplete="off"
        value={value}
        onChange={onChange}
        stored={keyStored && !remove}
        error={error}
        hint={hint}
      />
      {keyStored && (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            flexWrap: 'wrap',
            gap: 1,
            mt: STEP_RHYTHM.fieldToOwnAction,
          }}
        >
          {remove && (
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              Der gespeicherte Schlüssel wird beim Speichern entfernt.
            </Typography>
          )}
          <Button
            size="small"
            variant="text"
            sx={EDGE_ALIGNED_TEXT_BUTTON_SX}
            onClick={() => {
              onRemoveChange(!remove)
              onChange('')
            }}
          >
            {remove ? 'Schlüssel behalten' : 'Gespeicherten Schlüssel entfernen'}
          </Button>
        </Box>
      )}
    </Box>
  )
}
