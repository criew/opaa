import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import Switch from '@mui/material/Switch'

interface ChatAutoCleanupFieldProps {
  id: string
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
  /** Im persönlichen Space einer anderen Person legt nur diese selbst den Schalter fest. */
  personalSpaceOfOther?: boolean
  /** The installation's periods; while they are unknown the switch names no numbers. */
  archiveAfterDays?: number
  deleteAfterDays?: number
}

/** The switch label, with the installation's periods once they are known. */
function chatAutoCleanupLabel(archiveAfterDays?: number, deleteAfterDays?: number) {
  return archiveAfterDays != null && deleteAfterDays != null
    ? `Inaktive Chats nach ${archiveAfterDays} Tagen archivieren und nach weiteren ${deleteAfterDays} Tagen löschen`
    : 'Inaktive Chats automatisch archivieren und löschen'
}

/** The automatic chat cleanup switch of a space; the operator sets the periods. */
export default function ChatAutoCleanupField({
  id,
  checked,
  onChange,
  disabled = false,
  personalSpaceOfOther = false,
  archiveAfterDays,
  deleteAfterDays,
}: ChatAutoCleanupFieldProps) {
  return (
    <FormControl disabled={disabled}>
      <FormControlLabel
        control={
          <Switch
            id={id}
            checked={checked}
            onChange={(event) => onChange(event.target.checked)}
            slotProps={{ input: { 'aria-describedby': `${id}-helper` } }}
          />
        }
        label={chatAutoCleanupLabel(archiveAfterDays, deleteAfterDays)}
      />
      <FormHelperText id={`${id}-helper`} sx={{ mx: 0 }}>
        Angeheftete Chats sind ausgenommen.
        {personalSpaceOfOther &&
          ' Im persönlichen Space legt nur die Person selbst fest, ob ihre Chats bereinigt werden.'}
      </FormHelperText>
    </FormControl>
  )
}
