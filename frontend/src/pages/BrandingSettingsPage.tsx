import { useEffect, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import DarkModeIcon from '@mui/icons-material/DarkMode'
import LightModeIcon from '@mui/icons-material/LightMode'
import SettingsBrightnessIcon from '@mui/icons-material/SettingsBrightness'
import PaletteOutlinedIcon from '@mui/icons-material/PaletteOutlined'
import type { ColorScheme } from '../types/api'
import { useAuthStore } from '../stores/authStore'
import type { BrandingImageKind } from '../stores/brandingStore'
import { BRANDING_IMAGES, useBrandingStore, OPAA_BRANDING } from '../stores/brandingStore'
import PageHeading from '../components/a11y/PageHeading'
import AreaPageHeader from '../components/AreaPageHeader'
import BrandingPreview from '../components/admin/BrandingPreview'
import { checkAccentContrast, formatContrastRatio, parseHexColor } from '../utils/contrast'
import { contentWidth } from '../theme/tokens'

/** Mirrors `BrandingImageValidator` in the backend - rejected there too, just less pleasantly. */
const ACCEPTED_IMAGE_TYPES = ['image/png', 'image/jpeg']

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
  const saveImage = useBrandingStore((s) => s.saveImage)
  const removeImage = useBrandingStore((s) => s.removeImage)

  const [productName, setProductName] = useState(branding.productName)
  const [claim, setClaim] = useState(branding.claim)
  const [primaryColor, setPrimaryColor] = useState(branding.primaryColor)
  const [colorScheme, setColorScheme] = useState<ColorScheme>(branding.defaultColorScheme)
  // One pending file per slot; the upload happens with the rest of the form, so a single
  // "Speichern" carries every change the operator made on this page.
  const [pendingImages, setPendingImages] = useState<Partial<Record<BrandingImageKind, File>>>({})
  const [imageErrors, setImageErrors] = useState<Partial<Record<BrandingImageKind, string>>>({})
  const [saved, setSaved] = useState(false)
  const fileInputRefs = useRef<Partial<Record<BrandingImageKind, HTMLInputElement | null>>>({})

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

  const logoPreviewUrl = useMemo(() => {
    const file = pendingImages.logo
    return file ? URL.createObjectURL(file) : undefined
  }, [pendingImages.logo])
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
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.notice }}>
        <PageHeading title="Branding" gutterBottom />
        <Alert severity="info">
          Das Branding wird von der Systemverwaltung gepflegt. Für Ihr Konto ist diese Seite nicht
          freigegeben.
        </Alert>
      </Box>
    )
  }

  function clearInput(kind: BrandingImageKind) {
    const input = fileInputRefs.current[kind]
    if (input) input.value = ''
  }

  function selectImage(kind: BrandingImageKind, file: File | null) {
    const { label, maxBytes } = BRANDING_IMAGES[kind]
    setImageErrors((errors) => ({ ...errors, [kind]: undefined }))
    if (!file) {
      setPendingImages((files) => ({ ...files, [kind]: undefined }))
      return
    }
    if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) {
      setImageErrors((errors) => ({
        ...errors,
        [kind]:
          `${label}: nur PNG- und JPEG-Dateien sind zulässig. SVG wird bewusst nicht angenommen,` +
          ' weil eine SVG-Datei Skripte enthalten kann.',
      }))
      return
    }
    if (file.size > maxBytes) {
      setImageErrors((errors) => ({
        ...errors,
        [kind]: `${label}: höchstens ${Math.round(maxBytes / 1024)} KiB.`,
      }))
      return
    }
    setPendingImages((files) => ({ ...files, [kind]: file }))
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
      // Sequentially, not in parallel: each upload answers with the whole effective branding, and
      // two in flight at once would let the slower answer overwrite the faster one's slot.
      for (const kind of Object.keys(BRANDING_IMAGES) as BrandingImageKind[]) {
        const file = pendingImages[kind]
        if (!file) continue
        await saveImage(kind, file)
        setPendingImages((files) => ({ ...files, [kind]: undefined }))
        clearInput(kind)
      }
      setSaved(true)
    } catch {
      // The store already holds the message; the alert below renders it.
    }
  }

  const imageSlots: Array<{
    kind: BrandingImageKind
    description: string
    configuredUrl?: string
  }> = [
    {
      kind: 'logo',
      description: 'Steht in der Seitenleiste und im Kopf der Anwendung.',
      configuredUrl: branding.logoUrl,
    },
    {
      kind: 'loginLogo',
      description:
        'Wird auf der Anmeldeseite groß dargestellt. Ohne eigenes Bild gilt dort das Logo der Anwendung.',
      configuredUrl: branding.loginLogoUrl,
    },
    {
      kind: 'loginBackground',
      description:
        'Liegt hinter der Markenfläche der Anmeldeseite. Über dem Bild liegt ein fester dunkler Schleier, damit die Schrift lesbar bleibt — unabhängig davon, wie hell das Bild ist. Ohne Bild bleibt die heutige Fläche.',
      configuredUrl: branding.loginBackgroundUrl,
    },
  ]

  async function handleRemoveImage(kind: BrandingImageKind) {
    setSaved(false)
    setPendingImages((files) => ({ ...files, [kind]: undefined }))
    clearInput(kind)
    try {
      await removeImage(kind)
    } catch {
      // dito
    }
  }

  return (
    <Box
      sx={{
        flexGrow: 1,
        p: { xs: 2.5, md: 5 },
        overflowY: 'auto',
      }}
    >
      <Box sx={{ maxWidth: contentWidth.areaContent }}>
        <AreaPageHeader
          icon={PaletteOutlinedIcon}
          title="Branding"
          description="Gilt für die gesamte Anwendung: Produktname, Claim, Logo, Akzentfarbe und Farbschema-Vorgabe Ihres Hauses. Änderungen sind ohne Neuinstallation für alle Nutzenden wirksam. Ein leeres Feld bedeutet: der OPAA-Standard gilt wieder."
        />

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
            {/* Level 2 (also below): these section headings follow the page's h1 directly;
                the subtitle2 look is only visual. */}
            <Typography variant="subtitle2" component="h2" gutterBottom>
              Farbschema-Vorgabe
            </Typography>
            <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1.5 }}>
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

          {imageSlots.map((slot) => (
            <Box key={slot.kind}>
              <Typography variant="subtitle2" component="h2" gutterBottom>
                {BRANDING_IMAGES[slot.kind].label}
              </Typography>
              <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1.5 }}>
                {slot.description} PNG oder JPEG, höchstens{' '}
                {Math.round(BRANDING_IMAGES[slot.kind].maxBytes / 1024)} KiB. SVG wird nicht
                angenommen, weil eine SVG-Datei Skripte enthalten kann.
              </Typography>
              {imageErrors[slot.kind] && (
                <Alert severity="error" sx={{ mb: 1.5 }}>
                  {imageErrors[slot.kind]}
                </Alert>
              )}
              <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
                <Button component="label" variant="outlined">
                  {BRANDING_IMAGES[slot.kind].label} auswählen
                  <Box
                    component="input"
                    type="file"
                    ref={(element: HTMLInputElement | null) => {
                      fileInputRefs.current[slot.kind] = element
                    }}
                    accept={ACCEPTED_IMAGE_TYPES.join(',')}
                    hidden
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                      selectImage(slot.kind, e.target.files?.[0] ?? null)
                    }
                  />
                </Button>
                {slot.configuredUrl && (
                  <Button
                    color="error"
                    onClick={() => void handleRemoveImage(slot.kind)}
                    disabled={isSaving}
                  >
                    {BRANDING_IMAGES[slot.kind].label} entfernen
                  </Button>
                )}
                {pendingImages[slot.kind] && (
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    {pendingImages[slot.kind]?.name} — wird beim Speichern übernommen
                  </Typography>
                )}
              </Stack>
            </Box>
          ))}

          <Divider />

          <Box>
            <Typography variant="subtitle2" component="h2" gutterBottom>
              Vorschau
            </Typography>
            <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1.5 }}>
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
                setPendingImages({})
                setImageErrors({})
                for (const kind of Object.keys(BRANDING_IMAGES) as BrandingImageKind[]) {
                  clearInput(kind)
                }
              }}
              disabled={isSaving}
            >
              Auf OPAA-Standard zurücksetzen
            </Button>
          </Stack>
        </Stack>
      </Box>
    </Box>
  )
}
