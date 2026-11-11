import { useState } from 'react'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import ChangeImpactNotice from './ChangeImpactNotice'
import { fontSize } from '@/shared/theme/tokens'
import FormDialog from '@/shared/ui/dialog/FormDialog'

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
        slotProps={{ typography: { sx: { fontSize: fontSize.text } } }}
      />
      <FormDialog
        open={pending != null}
        title={`${label} im Kontextpräfix ${pending ? 'einschalten' : 'ausschalten'}`}
        submitLabel={pending ? 'Einschalten' : 'Ausschalten'}
        onSubmit={() => {
          const next = pending
          setPending(null)
          if (next != null) onSave(next)
        }}
        onClose={() => setPending(null)}
      >
        <ChangeImpactNotice
          libraryId={libraryId}
          fieldKey={fieldKey}
          change={pending ? 'CONTEXT_PREFIX_ENABLED' : 'CONTEXT_PREFIX_DISABLED'}
        />
      </FormDialog>
    </>
  )
}
