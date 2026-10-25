import { useState } from 'react'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import ChangeImpactNotice from './ChangeImpactNotice'

/**
 * One switchable core field. The Folgekosten of the planned switch stand in a confirmation dialog
 * before it is saved - the same rule every prefix-effective change follows.
 */
export default function CoreContextPrefixSwitch({
  libraryId,
  label,
  fieldKey,
  checked,
  disabled,
  onSave,
}: {
  libraryId: string
  label: string
  fieldKey: string
  checked: boolean
  disabled: boolean
  onSave: (next: boolean) => void
}) {
  const [pending, setPending] = useState<boolean | null>(null)

  return (
    <>
      <FormControlLabel
        control={
          <Switch
            checked={checked}
            disabled={disabled}
            onChange={(e) => setPending(e.target.checked)}
          />
        }
        label={label}
        slotProps={{ typography: { sx: { fontSize: 13.5 } } }}
      />
      <Dialog open={pending != null} onClose={() => setPending(null)} fullWidth maxWidth="sm">
        <DialogTitle>{`${label} im Kontextpräfix ${pending ? 'einschalten' : 'ausschalten'}`}</DialogTitle>
        <DialogContent>
          <ChangeImpactNotice
            libraryId={libraryId}
            fieldKey={fieldKey}
            change={pending ? 'CONTEXT_PREFIX_ENABLED' : 'CONTEXT_PREFIX_DISABLED'}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPending(null)}>Abbrechen</Button>
          <Button
            variant="contained"
            onClick={() => {
              const next = pending
              setPending(null)
              if (next != null) onSave(next)
            }}
          >
            Speichern
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
