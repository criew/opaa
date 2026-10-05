import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import Box from '@mui/material/Box'
import { SERVICE_ACCOUNT_CONFIRMATION, SERVICE_ACCOUNT_MISSING } from './sourceConsent'

interface ServiceAccountConfirmationProps {
  idPrefix: string
  checked: boolean
  onChange: (checked: boolean) => void
  /** Set once a start was tried without the confirmation. */
  missing: boolean
}

/** The required confirmation before a library's source is connected at the provider. */
export default function ServiceAccountConfirmation({
  idPrefix,
  checked,
  onChange,
  missing,
}: ServiceAccountConfirmationProps) {
  const errorId = `${idPrefix}-service-account-error`
  return (
    <Box>
      <FormControlLabel
        control={
          <Checkbox
            checked={checked}
            onChange={(event) => onChange(event.target.checked)}
            slotProps={{
              input: {
                'aria-required': true,
                'aria-invalid': missing && !checked,
                'aria-describedby': missing && !checked ? errorId : undefined,
              },
            }}
          />
        }
        label={SERVICE_ACCOUNT_CONFIRMATION}
      />
      {missing && !checked && (
        <FormHelperText id={errorId} error role="alert">
          {SERVICE_ACCOUNT_MISSING}
        </FormHelperText>
      )}
    </Box>
  )
}
