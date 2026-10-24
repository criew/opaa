import Box from '@mui/material/Box'
import TextField from '@mui/material/TextField'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'

interface AssetNameFieldsProps {
  /** Prefix of the field ids; unique per page. */
  idPrefix: string
  name: string
  onNameChange: (name: string) => void
  description: string
  onDescriptionChange: (description: string) => void
  namePlaceholder: string
  /** The rejection of the entered name, shown at the field. */
  nameError?: string | null
}

/** Name and description of a new asset - the first step of every creation wizard. */
export default function AssetNameFields({
  idPrefix,
  name,
  onNameChange,
  description,
  onDescriptionChange,
  namePlaceholder,
  nameError = null,
}: AssetNameFieldsProps) {
  return (
    <>
      <Box>
        <FieldLabel htmlFor={`${idPrefix}-name`}>Name</FieldLabel>
        <TextField
          id={`${idPrefix}-name`}
          size="small"
          value={name}
          onChange={(e) => onNameChange(e.target.value)}
          placeholder={namePlaceholder}
          fullWidth
          error={nameError !== null}
          helperText={nameError ?? undefined}
          slotProps={{ htmlInput: { maxLength: 255, 'data-source-field': 'name' } }}
        />
      </Box>
      <Box>
        <FieldLabel htmlFor={`${idPrefix}-description`}>Beschreibung (optional)</FieldLabel>
        <TextField
          id={`${idPrefix}-description`}
          size="small"
          value={description}
          onChange={(e) => onDescriptionChange(e.target.value)}
          multiline
          minRows={2}
          fullWidth
          slotProps={{ htmlInput: { maxLength: 2000 } }}
        />
      </Box>
    </>
  )
}
