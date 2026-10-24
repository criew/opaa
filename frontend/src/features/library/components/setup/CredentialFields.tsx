import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import SecretField from './SecretField'
import SetupTextField from './SetupTextField'

interface Part {
  id: string
  label: string
  value: string
  onChange: (next: string) => void
  error?: string | null
  hint?: string
  placeholder?: string
}

interface CredentialFieldsProps {
  /** The account; absent where only a secret is entered (a token alone). */
  account?: Part
  secret: Part
  /** Credentials are stored and still serve the address: they read „gesetzt“ until replaced. */
  stored: boolean
}

/**
 * Account and secret of a connector as one pair, the way they are stored: in the edit mode a
 * stored pair reads „Zugangsdaten gesetzt“ with „Ersetzen“, never the stored account or a masked
 * secret; replacing opens both fields empty.
 */
export default function CredentialFields({ account, secret, stored }: CredentialFieldsProps) {
  const [replacing, setReplacing] = useState(false)
  const entered = (account?.value ?? '') !== '' || secret.value !== ''
  const error = account?.error ?? secret.error

  if (stored && !replacing && !entered) {
    return (
      <Box>
        <FieldLabel id={`${secret.id}-label`}>Zugangsdaten</FieldLabel>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minHeight: 40 }}>
          <Typography sx={{ fontSize: 13.5 }}>gesetzt</Typography>
          <Button
            size="small"
            variant="outlined"
            id={account?.id ?? secret.id}
            aria-label="Zugangsdaten ersetzen"
            onClick={() => {
              setReplacing(true)
              requestAnimationFrame(() =>
                document.getElementById(account?.id ?? secret.id)?.focus(),
              )
            }}
          >
            Ersetzen
          </Button>
        </Box>
        {error && (
          <Typography sx={{ fontSize: 12, color: 'error.main', mt: 0.5 }}>{error}</Typography>
        )}
      </Box>
    )
  }

  return (
    <Stack spacing={2}>
      {account && (
        <SetupTextField
          id={account.id}
          label={account.label}
          value={account.value}
          onChange={account.onChange}
          error={account.error}
          hint={account.hint}
          placeholder={account.placeholder}
        />
      )}
      <SecretField
        id={secret.id}
        label={secret.label}
        value={secret.value}
        onChange={secret.onChange}
        stored={false}
        error={secret.error}
        hint={secret.hint}
      />
      {stored && (
        <Box>
          <Button
            size="small"
            variant="text"
            onClick={() => {
              account?.onChange('')
              secret.onChange('')
              setReplacing(false)
            }}
          >
            Gespeicherte Zugangsdaten behalten
          </Button>
        </Box>
      )}
    </Stack>
  )
}
