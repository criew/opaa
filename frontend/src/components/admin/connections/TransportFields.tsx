import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'

interface TransportFieldsProps {
  proxy: string
  insecureSsl: boolean
  onChange: (change: { sourceProxy?: string; sourceInsecureSsl?: boolean }) => void
}

/**
 * Proxy and certificate check of a profile with an http(s) server. Kept apart from the form so
 * that their announced removal in favour of the house-wide outbound proxy touches one file and
 * one call (#2496).
 */
export default function TransportFields({ proxy, insecureSsl, onChange }: TransportFieldsProps) {
  return (
    <>
      <TextField
        label="Proxy (optional)"
        size="small"
        value={proxy}
        onChange={(e) => onChange({ sourceProxy: e.target.value })}
        placeholder="proxy.example.com:8080"
        autoComplete="off"
        helperText="Gilt für jede Bibliothek auf diesem Zugang; die Bibliothek setzt keinen eigenen."
        slotProps={{ htmlInput: { maxLength: 255 } }}
      />
      <FormControlLabel
        control={
          <Switch
            checked={insecureSsl}
            onChange={(e) => onChange({ sourceInsecureSsl: e.target.checked })}
          />
        }
        label="Zertifikatsprüfung aussetzen"
      />
    </>
  )
}
