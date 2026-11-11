import { useState } from 'react'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import { TextAction } from '@/shared/ui/DetailSection'
import { addLibraryMetadataFieldValue } from '../../api/libraryMetadataApi'
import FormDialog from '@/shared/ui/dialog/FormDialog'

/** „Wert ergänzen": adds one value to the list of a SELECT field. */
export default function AddValueButton({
  libraryId,
  fieldKey,
  onAdded,
  onError,
}: {
  libraryId: string
  fieldKey: string
  onAdded: () => void
  onError: (message: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [code, setCode] = useState('')
  const [label, setLabel] = useState('')

  const [saving, setSaving] = useState(false)
  // Set by a submit without a code; the field then says so.
  const [codeMissing, setCodeMissing] = useState(false)

  async function submit() {
    if (!code.trim()) {
      setCodeMissing(true)
      return
    }
    setSaving(true)
    try {
      await addLibraryMetadataFieldValue(libraryId, fieldKey, { code, label })
      setOpen(false)
      setCode('')
      setLabel('')
      onAdded()
    } catch (err) {
      onError(err instanceof Error ? err.message : 'Der Wert konnte nicht ergänzt werden')
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <TextAction onClick={() => setOpen(true)}>Wert ergänzen</TextAction>
      <FormDialog
        open={open}
        title="Wert ergänzen"
        submitLabel="Ergänzen"
        onSubmit={() => void submit()}
        onClose={() => setOpen(false)}
        busy={saving}
        busyAnnouncement="Der Wert wird ergänzt"
      >
        <Stack spacing={2} sx={{ mt: 1 }}>
          <FormField
            size="small"
            label="Code"
            value={code}
            onChange={(e) => {
              setCode(e.target.value)
              setCodeMissing(false)
            }}
            error={codeMissing}
            helperText={codeMissing ? 'Bitte einen Code angeben.' : undefined}
          />
          <FormField
            size="small"
            label="Bezeichnung"
            value={label}
            onChange={(e) => setLabel(e.target.value)}
          />
        </Stack>
      </FormDialog>
    </>
  )
}
