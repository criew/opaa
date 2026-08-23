import { useEffect, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import DarkModeIcon from '@mui/icons-material/DarkMode'
import LightModeIcon from '@mui/icons-material/LightMode'
import SettingsBrightnessIcon from '@mui/icons-material/SettingsBrightness'
import type { ColorScheme } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import { useBrandingStore, OPAA_BRANDING } from '../stores/brandingStore'
import PageHeading from '../components/a11y/PageHeading'
import BrandingPreview from '../components/admin/BrandingPreview'
import { checkAccentContrast, formatContrastRatio, parseHexColor } from '../utils/contrast'

/** Mirrors `BrandingLogoValidator` in the backend - rejected there too, just less pleasantly. */
const ACCEPTED_LOGO_TYPES = ['image/png', 'image/jpeg']
const MAX_LOGO_SIZE_BYTES = 512 * 1024

const MAX_PRODUCT_NAME_LENGTH = 60
const MAX_CLAIM_LENGTH = 120

const colorSchemeOptions: Array<{ value: ColorScheme; label: string; icon: React.ReactNode }> = [
  { value: 'LIGHT', label: 'Hell', icon: <LightModeIcon fontSize="small" /> },
  { value: 'SYSTEM', label: 'System', icon: <SettingsBrightnessIcon fontSize="small" /> },
  { value: 'DARK', label: 'Dunkel', icon: <DarkModeIcon fontSize="small" /> },
]

/**
 * The branding form (#583) - where an operator makes the interface their house's own without a
 * rebuild: product name, claim, logo, accent colour and the deployment's default colour scheme.
 *
 * Two things about it are deliberate and worth naming:
 *
 * **The contrast check warns, it does not block.** A Behörde's corporate colour is not something
 * this application gets to veto (#583: "blockiert aber nicht"). What the form owes the operator is
 * that the consequence is visible before they save, in both schemes - not a refusal.
 *
 * **Every field left empty means "back to the OPAA standard"**, matching the API's own PUT
 * semantics (#582). That is why the fields start out empty-able rather than pre-filled with the
 * defaults as if someone had typed them.
 */
export default function BrandingSettingsPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const branding = useBrandingStore((s) => s.branding)
  const isSaving = useBrandingStore((s) => s.isSaving)
  const storeError = useBrandingStore((s) => s.error)
  const saveBranding = useBrandingStore((s) => s.saveBranding)
  const saveLogo = useBrandingStore((s) => s.saveLogo)
  const removeLogo = useBrandingStore((s) => s.removeLogo)

  const [productName, setProductName] = useState(branding.productName)
  const [claim, setClaim] = useState(branding.claim)
  const [primaryColor, setPrimaryColor] = useState(branding.primaryColor)
  const [colorScheme, setColorScheme] = useState<ColorScheme>(branding.defaultColorScheme)
  const [logoFile, setLogoFile] = useState<File | null>(null)
  const [logoError, setLogoError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // The store may still be loading when this page mounts; adopt the values once they arrive,
  // but never overwrite something the operator has already started typing.
  const hydrated = useRef(false)
  useEffect(() => {
    if (hydrated.current) return
    hydrated.current = true
    setProductName(branding.productName)
    setClaim(branding.claim)
    setPrimaryColor(branding.primaryColor)
    setColorScheme(branding.defaultColorScheme)
  }, [branding])

  const logoPreviewUrl = useMemo(
    () => (logoFile ? URL.createObjectURL(logoFile) : undefined),
    [logoFile],
  )
  useEffect(
    () => () => {
      if (logoPreviewUrl) URL.revokeObjectURL(logoPreviewUrl)
    },
    [logoPreviewUrl],
  )

  const isColorValid = parseHexColor(primaryColor) !== null
  const contrastChecks = useMemo(
    () => (isColorValid ? checkAccentContrast(primaryColor) : []),
    [isColorValid, primaryColor],
  )
  const failingChecks = contrastChecks.filter((check) => !check.passes)

  const effectivePreview = {
    productName: productName.trim() || OPAA_BRANDING.productName,
    claim: claim.trim() || OPAA_BRANDING.claim,
    primaryColor: isColorValid ? primaryColor : OPAA_BRANDING.primaryColor,
    logoUrl: logoPreviewUrl ?? branding.logoUrl,
  }

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: 4, maxWidth: 720 }}>
        <PageHeading title="Branding" gutterBottom />
        <Alert severity="info">
          Das Branding wird von der Systemverwaltung gepflegt. Für Ihr Konto ist diese Seite nicht
          freigegeben.
        </Alert>
      </Box>
    )
  }

  function selectLogo(file: File | null) {
    setLogoError(null)
    if (!file) {
      setLogoFile(null)
      return
    }
    if (!ACCEPTED_LOGO_TYPES.includes(file.type)) {
      setLogoError(
        'Als Logo sind nur PNG- und JPEG-Dateien zulässig. SVG wird bewusst nicht angenommen,' +
          ' weil eine SVG-Datei Skripte enthalten kann.',
      )
      return
    }
    if (file.size > MAX_LOGO_SIZE_BYTES) {
      setLogoError(`Das Logo darf höchstens ${MAX_LOGO_SIZE_BYTES / 1024} KiB groß sein.`)
      return
    }
    setLogoFile(file)
  }

  async function handleSave() {
    setSaved(false)
    try {
      await saveBranding({
        productName: productName.trim(),
        claim: claim.trim(),
        primaryColor: primaryColor.trim(),
        defaultColorScheme: colorScheme,
      })
      if (logoFile) {
        await saveLogo(logoFile)
        setLogoFile(null)
        if (fileInputRef.current) fileInputRef.current.value = ''
      }
      setSaved(true)
    } catch {
      // The store already holds the message; the alert below renders it.
    }
  }

  async function handleRemoveLogo() {
    setSaved(false)
    setLogoFile(null)
    if (fileInputRef.current) fileInputRef.current.value = ''
    try {
      await removeLogo()
    } catch {
      // dito
    }
  }

  return (
    <Box sx={{ flexGrow: 1, p: 4, maxWidth: 960, overflowY: 'auto' }}>
      <PageHeading title="Branding" gutterBottom />
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Gilt für die gesamte Anwendung: Produktname, Claim, Logo, Akzentfarbe und Farbschema-Vorgabe
        Ihres Hauses. Änderungen sind ohne Neuinstallation für alle Nutzenden wirksam. Ein leeres
        Feld bedeutet: der OPAA-Standard gilt wieder.
      </Typography>

      {storeError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {storeError}
        </Alert>
      )}
      {saved && !storeError && (
        <Alert severity="success" sx={{ mb: 2 }}>
          Das Branding wurde gespeichert und ist sofort wirksam.
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: 3 }}>
        <Stack spacing={3}>
          <TextField
            label="Produktname"
            value={productName}
            onChange={(e) => setProductName(e.target.value)}
            slotProps={{ htmlInput: { maxLength: MAX_PRODUCT_NAME_LENGTH } }}
            helperText={`Erscheint in Seitenleiste, Anmeldeseite und Fenstertitel. Leer lassen für „${OPAA_BRANDING.productName}“.`}
            fullWidth
          />

          <TextField
            label="Claim"
            value={claim}
            onChange={(e) => setClaim(e.target.value)}
            slotProps={{ htmlInput: { maxLength: MAX_CLAIM_LENGTH } }}
            helperText={`Kurzer Satz unter dem Produktnamen. Leer lassen für „${OPAA_BRANDING.claim}“.`}
            fullWidth
          />

          <Box>
            <Stack direction="row" spacing={2} sx={{ alignItems: 'flex-start' }}>
              <TextField
                label="Primärfarbe"
                value={primaryColor}
                onChange={(e) => setPrimaryColor(e.target.value)}
                error={primaryColor.trim() !== '' && !isColorValid}
                helperText={
                  primaryColor.trim() !== '' && !isColorValid
                    ? 'Bitte einen sechsstelligen Hex-Wert mit führendem „#“ angeben, zum Beispiel #1292EE.'
                    : 'Akzentfarbe für Schaltflächen, Verweise und Fokusrahmen.'
                }
                sx={{ flexGrow: 1 }}
              />
              <TextField
                type="color"
                label="Auswählen"
                value={isColorValid ? primaryColor : OPAA_BRANDING.primaryColor}
                onChange={(e) => setPrimaryColor(e.target.value.toUpperCase())}
                sx={{ width: 96 }}
              />
            </Stack>
          </Box>

          {failingChecks.length > 0 && (
            <Alert severity="warning">
              <AlertTitle>Kontrast unterschritten</AlertTitle>
              Die gewählte Farbe erreicht den empfohlenen Kontrast nicht überall. Sie können
              trotzdem speichern — die Entscheidung liegt bei Ihrem Haus.
              <Box component="ul" sx={{ pl: 2.5, mb: 0, mt: 1 }}>
                {failingChecks.map((check) => (
                  <li key={check.label}>
                    {check.label}: {formatContrastRatio(check.ratio)} statt mindestens{' '}
                    {formatContrastRatio(check.required)}
                  </li>
                ))}
              </Box>
            </Alert>
          )}

          <Box>
            <Typography variant="subtitle2" gutterBottom>
              Farbschema-Vorgabe
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
              Gilt für alle, die in ihren eigenen Einstellungen noch nichts gewählt haben. Eine
              persönliche Wahl bleibt unberührt.
            </Typography>
            <ToggleButtonGroup
              value={colorScheme}
              exclusive
              onChange={(_e, value: ColorScheme | null) => {
                if (value !== null) setColorScheme(value)
              }}
              aria-label="Farbschema-Vorgabe"
            >
              {colorSchemeOptions.map((option) => (
                <ToggleButton key={option.value} value={option.value}>
                  <Box component="span" sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                    {option.icon}
                    {option.label}
                  </Box>
                </ToggleButton>
              ))}
            </ToggleButtonGroup>
          </Box>

          <Divider />

          <Box>
            <Typography variant="subtitle2" gutterBottom>
              Logo
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
              PNG oder JPEG, höchstens {MAX_LOGO_SIZE_BYTES / 1024} KiB. SVG wird nicht angenommen,
              weil eine SVG-Datei Skripte enthalten kann.
            </Typography>
            {logoError && (
              <Alert severity="error" sx={{ mb: 1.5 }}>
                {logoError}
              </Alert>
            )}
            <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <Button component="label" variant="outlined">
                Logo auswählen
                <Box
                  component="input"
                  type="file"
                  ref={fileInputRef}
                  accept={ACCEPTED_LOGO_TYPES.join(',')}
                  hidden
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                    selectLogo(e.target.files?.[0] ?? null)
                  }
                />
              </Button>
              {branding.logoUrl && (
                <Button color="error" onClick={() => void handleRemoveLogo()} disabled={isSaving}>
                  Logo entfernen
                </Button>
              )}
              {logoFile && (
                <Typography variant="body2" color="text.secondary">
                  {logoFile.name} — wird beim Speichern übernommen
                </Typography>
              )}
            </Stack>
          </Box>

          <Divider />

          <Box>
            <Typography variant="subtitle2" gutterBottom>
              Vorschau
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
              So wirkt die Einstellung in beiden Farbschemata — beide sind gleichermaßen
              verbindlich.
            </Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Box sx={{ flex: 1 }}>
                <BrandingPreview mode="light" {...effectivePreview} />
              </Box>
              <Box sx={{ flex: 1 }}>
                <BrandingPreview mode="dark" {...effectivePreview} />
              </Box>
            </Stack>
          </Box>

          <Stack direction="row" spacing={2}>
            <Button
              variant="contained"
              onClick={() => void handleSave()}
              disabled={isSaving || (primaryColor.trim() !== '' && !isColorValid)}
            >
              Speichern
            </Button>
            <Button
              onClick={() => {
                setProductName('')
                setClaim('')
                setPrimaryColor('')
                setColorScheme(OPAA_BRANDING.defaultColorScheme)
                setLogoFile(null)
                setLogoError(null)
                if (fileInputRef.current) fileInputRef.current.value = ''
              }}
              disabled={isSaving}
            >
              Auf OPAA-Standard zurücksetzen
            </Button>
          </Stack>
        </Stack>
      </Paper>
    </Box>
  )
}
