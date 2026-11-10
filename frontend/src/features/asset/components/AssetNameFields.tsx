import Box from '@mui/material/Box'
import FormField from '@/shared/ui/form/FormField'

interface AssetNameFieldsProps {
  /** Prefix of the field ids; unique per page. */
  idPrefix: string
  name: string
  onNameChange: (name: string) => void
  description: string
  onDescriptionChange: (description: string) => void
  namePlaceholder: string
}

/** Name and description of a new asset - the first step of every creation wizard. */
export default function AssetNameFields({
  idPrefix,
  name,
  onNameChange,
  description,
  onDescriptionChange,
  namePlaceholder,
}: AssetNameFieldsProps) {
  return (
    <>
      <Box>
        <FormField
          label="Name"
          id={`${idPrefix}-name`}
          size="small"
          value={name}
          onChange={(e) => onNameChange(e.target.value)}
          placeholder={namePlaceholder}
          fullWidth
          slotProps={{ htmlInput: { maxLength: 255 } }}
        />
      </Box>
      <Box>
        <FormField
          label="Beschreibung"
          optional
          id={`${idPrefix}-description`}
          size="small"
          value={description}
          onChange={(e) => onDescriptionChange(e.target.value)}
          multiline
          fullWidth
          slotProps={{ htmlInput: { maxLength: 2000 } }}
        />
      </Box>
    </>
  )
}
