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
  /** Die Fristen der Installation; fehlen sie (im Assistenten), nennt der Hinweis keine Zahlen. */
  archiveAfterDays?: number
  deleteAfterDays?: number
}

/**
 * Der Schalter „Inaktive Chats automatisch archivieren und löschen" eines Space, mit der
 * Erklärung, was er bewirkt. Die Fristen sind systemweit fest und nur vom Betrieb einstellbar.
 */
export default function ChatAutoCleanupField({
  id,
  checked,
  onChange,
  disabled = false,
  personalSpaceOfOther = false,
  archiveAfterDays,
  deleteAfterDays,
}: ChatAutoCleanupFieldProps) {
  const periods =
    archiveAfterDays != null && deleteAfterDays != null
      ? `Chats ohne Aktivität werden nach ${archiveAfterDays} Tagen archiviert und nach weiteren ${deleteAfterDays} Tagen im Archiv endgültig gelöscht.`
      : 'Chats ohne Aktivität werden nach einer festen Frist archiviert und nach einer weiteren Frist im Archiv endgültig gelöscht.'
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
        label="Inaktive Chats automatisch archivieren und löschen"
      />
      <FormHelperText id={`${id}-helper`} sx={{ mx: 0 }}>
        {periods} Angeheftete Chats sind ausgenommen; eine neue Frage, das Anheften oder das
        Zurückholen aus dem Archiv lässt die Frist neu beginnen. Die Fristen beginnen frühestens mit
        dem Einschalten und gelten für alle Mitglieder; festgelegt werden sie vom Betrieb.
        {personalSpaceOfOther &&
          ' Im persönlichen Space legt nur die Person selbst fest, ob ihre Chats bereinigt werden.'}
      </FormHelperText>
    </FormControl>
  )
}
