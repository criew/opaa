import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import SecretInput from './SecretInput'
import { EDGE_ALIGNED_TEXT_BUTTON_SX, STEP_RHYTHM } from './stepLayout'

/** The words around a stored secret that can be removed. */
export interface StoredSecretWording {
  label: string
  /** Said while the stored secret is marked for removal. */
  removed: string
  keep: string
  remove: string
}

const API_KEY_WORDING: StoredSecretWording = {
  label: 'API-Schlüssel',
  removed: 'Der gespeicherte Schlüssel wird beim Speichern entfernt.',
  keep: 'Schlüssel behalten',
  remove: 'Gespeicherten Schlüssel entfernen',
}

interface ApiKeyFieldProps {
  id: string
  /** A secret is stored for this step. */
  keyStored: boolean
  value: string
  onChange: (value: string) => void
  /** The stored secret is to be removed with the next save. */
  remove: boolean
  onRemoveChange: (remove: boolean) => void
  error?: string
  /** What the field asks for; by default that a service without key needs none. */
  hint?: string
  wording?: StoredSecretWording
  autoComplete?: 'new-password' | 'off'
}

/**
 * An optional secret of a step, by default the API key of a model: „Gesetzt · Ersetzen" for a
 * stored one, and the explicit way to remove it. The save sends what `apiKeyPart` returns:
 * omitted keeps the secret, empty removes it.
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
  wording = API_KEY_WORDING,
  autoComplete = 'off',
}: ApiKeyFieldProps) {
  return (
    <Box>
      <SecretInput
        id={id}
        label={wording.label}
        optional
        autoComplete={autoComplete}
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
              {wording.removed}
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
            {remove ? wording.keep : wording.remove}
          </Button>
        </Box>
      )}
    </Box>
  )
}
