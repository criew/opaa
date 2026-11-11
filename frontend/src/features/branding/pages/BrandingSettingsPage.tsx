import { useEffect, useMemo, useRef, useState } from 'react'
import { previewColorOf, type BrandingDraft } from '@/shared/ui/setup/setupDrafts'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import PaletteOutlinedIcon from '@mui/icons-material/PaletteOutlined'
import SectionHead from '@/shared/ui/SectionHead'
import { useAuthStore } from '@/shared/session/authStore'
import type { BrandingImageKind } from '@/shared/branding/brandingStore'
import { BRANDING_IMAGES, useBrandingStore, OPAA_BRANDING } from '@/shared/branding/brandingStore'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import AreaPageHeader from '@/shared/ui/AreaPageHeader'
import BrandingPreviews from '@/shared/ui/branding/BrandingPreviews'
import {
  BRANDING_LABELS,
  BRANDING_TEXTS,
  isInvalidPrimaryColor,
} from '@/shared/ui/branding/brandingSettings'
import BrandingFields from '@/shared/ui/setup/BrandingFields'
import { contentWidth, formWidth } from '@/shared/theme/tokens'

/** Mirrors `BrandingImageValidator` in the backend - rejected there too, just less pleasantly. */
const ACCEPTED_IMAGE_TYPES = ['image/png', 'image/jpeg']

/**
 * The branding form - where an operator makes the interface their house's own without a
 * rebuild: product name, claim, logo and accent colour. Light or dark is
 * each person's own choice, never set here.
 *
 * Two things about it are deliberate and worth naming:
 *
 * **The contrast check warns, it does not block.** A Behörde's corporate colour is not something
 * this application gets to veto ("blockiert aber nicht"). What the form owes the operator is
 * that the consequence is visible before they save, in both schemes - not a refusal.
 *
 * **Every field left empty means "back to the OPAA standard"**, matching the API's own PUT
 * semantics. That is why the fields start out empty-able rather than pre-filled with the
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

  const [draft, setDraft] = useState<BrandingDraft>({
    productName: branding.productName,
    claim: branding.claim,
    aiNotice: branding.aiNotice,
    primaryColor: branding.primaryColor,
  })
  const { productName, claim, aiNotice, primaryColor } = draft
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
    setDraft({
      productName: branding.productName,
      claim: branding.claim,
      aiNotice: branding.aiNotice,
      primaryColor: branding.primaryColor,
    })
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

  const colorInvalid = isInvalidPrimaryColor(primaryColor)

  const effectivePreview = {
    productName: productName.trim() || OPAA_BRANDING.productName,
    claim: claim.trim() || OPAA_BRANDING.claim,
    primaryColor: previewColorOf(primaryColor),
    logoUrl: logoPreviewUrl ?? branding.logoUrl,
  }

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, maxWidth: contentWidth.form }}>
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
        // The standard text is sent as "unset", so a later change to the standard reaches this
        // installation too.
        aiNotice: aiNotice.trim() === branding.aiNoticeDefault ? '' : aiNotice.trim(),
        primaryColor: primaryColor.trim(),
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
      setDraft((current) => ({
        ...current,
        aiNotice: useBrandingStore.getState().branding.aiNotice,
      }))
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
      // ditto
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
      <Box sx={{ maxWidth: contentWidth.list }}>
        <AreaPageHeader
          icon={PaletteOutlinedIcon}
          title="Branding"
          description="Produktname, Claim, Logo und Akzentfarbe Ihres Hauses, sofort für alle wirksam. Ein leeres Feld stellt den OPAA-Standard wieder her."
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

        <Stack spacing={3} sx={{ maxWidth: formWidth.column }}>
          <BrandingFields
            idPrefix="branding"
            value={draft}
            onChange={(patch) => setDraft((current) => ({ ...current, ...patch }))}
            aiNoticeDefault={branding.aiNoticeDefault}
          />

          {imageSlots.map((slot) => (
            <Box key={slot.kind}>
              <SectionHead>{BRANDING_IMAGES[slot.kind].label}</SectionHead>
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

          <Box>
            <SectionHead>{BRANDING_LABELS.preview}</SectionHead>
            <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1.5 }}>
              {BRANDING_TEXTS.previewHint}
            </Typography>
            <BrandingPreviews {...effectivePreview} />
          </Box>

          <Stack direction="row" spacing={2}>
            <Button
              variant="contained"
              onClick={() => void handleSave()}
              disabled={isSaving || colorInvalid}
            >
              Speichern
            </Button>
            <Button
              onClick={() => {
                setDraft({
                  productName: '',
                  claim: '',
                  aiNotice: branding.aiNoticeDefault,
                  primaryColor: '',
                })
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
