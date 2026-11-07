import { useEffect, useRef, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { useBrandingStore } from '@/shared/branding/brandingStore'
import { focusChatInput } from './tourSteps'
import { useTourStore } from './tourStore'
import {
  AnswerMiniature,
  CatalogMiniature,
  InputMiniature,
  SpacesMiniature,
} from './TourMiniatures'

interface WelcomePage {
  title: string
  text: string
  picture: ReactNode
}

function pages(productName: string): WelcomePage[] {
  return [
    {
      title: `Willkommen bei ${productName}`,
      text: `${productName} beantwortet Fragen aus den Dokumenten Ihres Hauses und zeigt mit Ziffern, worauf sich jede Aussage stützt.`,
      picture: <AnswerMiniature />,
    },
    {
      title: 'Fragen stellen',
      text: 'Fragen Sie in eigenen Worten. @Space-Wissen ist das Wissen, das dem Space zugeordnet ist und das Sie lesen dürfen.',
      picture: <InputMiniature />,
    },
    {
      title: 'Privat oder im Team',
      text: 'Im privaten Bereich arbeiten Sie allein. Ein Space teilt Chats und Wissen mit seinen Mitgliedern.',
      picture: <SpacesMiniature />,
    },
    {
      title: 'Wissen finden',
      text: 'Der Katalog zeigt alle Bibliotheken und Prompts, die Sie lesen dürfen. Über Ihr Profil sehen Sie diese Einführung erneut.',
      picture: <CatalogMiniature />,
    },
  ]
}

/**
 * Variant A: a welcome dialog of four pages before the first question. Each page shows an inert
 * copy of the pattern it explains; "Überspringen" is the dialog's one way out (P51), the last page
 * ends in the chat input.
 */
export default function WelcomeDialog() {
  const { open, step, goTo, finish } = useTourStore()
  const productName = useBrandingStore((s) => s.branding.productName)
  const all = pages(productName)
  const page = all[Math.min(step, all.length - 1)]
  const isLast = step >= all.length - 1
  const primaryRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (open) primaryRef.current?.focus()
  }, [open, step])

  const done = () => {
    finish()
    window.setTimeout(focusChatInput, 0)
  }

  return (
    <Dialog
      open={open}
      onClose={finish}
      aria-labelledby="welcome-title"
      aria-describedby="welcome-text"
      slotProps={{ paper: { sx: { width: 520, maxWidth: 'calc(100vw - 32px)', m: 2 } } }}
    >
      <Box sx={{ p: { xs: 2, sm: 3 }, display: 'flex', flexDirection: 'column', gap: 2.5 }}>
        {page.picture}
        <Box sx={{ minHeight: 96 }}>
          <Typography
            id="welcome-title"
            component="h2"
            sx={{ fontSize: 20, fontWeight: 600, lineHeight: 1.25, mb: 1 }}
          >
            {page.title}
          </Typography>
          <Typography id="welcome-text" variant="body2" sx={{ color: 'text.secondary' }}>
            {page.text}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
          <Typography variant="caption" sx={{ color: 'text.secondary', mr: 'auto' }}>
            {step + 1} von {all.length}
          </Typography>
          {!isLast && (
            <Button variant="text" onClick={finish}>
              Überspringen
            </Button>
          )}
          {step > 0 && (
            <Button variant="text" onClick={() => goTo(step - 1)}>
              Zurück
            </Button>
          )}
          <Button
            ref={primaryRef}
            variant="contained"
            onClick={() => (isLast ? done() : goTo(step + 1))}
          >
            {isLast ? 'Erste Frage stellen' : 'Weiter'}
          </Button>
        </Box>
        <Box role="status" aria-live="polite" sx={visuallyHidden}>
          {`Seite ${step + 1} von ${all.length}: ${page.title}. ${page.text}`}
        </Box>
      </Box>
    </Dialog>
  )
}
