import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import WizardLayout, { WIZARD_WIDTH } from './WizardLayout'
import type { WizardSteps } from './wizardSteps'

const STEPS = ['Stammdaten', 'Freigaben', 'Prüfen'] as const

function renderLayout(props: Partial<Parameters<typeof WizardLayout>[0]> = {}) {
  const handlers = {
    onCancel: vi.fn(),
    onBack: vi.fn(),
    onNext: vi.fn(),
    onCreate: vi.fn(),
  }
  const view = renderWithProviders(
    <WizardLayout
      object="Neue Prompt-Bibliothek"
      steps={STEPS}
      activeStep={0}
      submitting={false}
      createAnnouncement="Prompt-Bibliothek wird angelegt"
      {...handlers}
      {...props}
    >
      <div>Felder</div>
    </WizardLayout>,
  )
  return { ...view, handlers }
}

describe('WizardLayout (guidelines 5.14)', () => {
  it('names the object above and the step as the title, at the assistant width', () => {
    renderLayout()

    expect(
      screen.getByRole('heading', { level: 1, name: 'Neue Prompt-Bibliothek' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Stammdaten' })).toBeInTheDocument()
    expect(document.title).toMatch(/^Stammdaten – Schritt 1 von 3 – Neue Prompt-Bibliothek/)
    expect(WIZARD_WIDTH).toBe(720)
  })

  it('offers „Abbrechen“ and an active „Weiter“ in the first step, without „Zurück“', async () => {
    const user = userEvent.setup()
    const { handlers } = renderLayout()

    expect(screen.queryByRole('button', { name: 'Zurück' })).not.toBeInTheDocument()
    const next = screen.getByRole('button', { name: 'Weiter' })
    expect(next).toBeEnabled()
    expect(next).not.toHaveAttribute('aria-disabled')
    await user.click(next)
    expect(handlers.onNext).toHaveBeenCalledOnce()
    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))
    expect(handlers.onCancel).toHaveBeenCalledOnce()
  })

  it('calls the action of the last step „Anlegen“ and starts the title with „Fehler: “ on a failure', async () => {
    const user = userEvent.setup()
    const { handlers } = renderLayout({ activeStep: 2, failed: true })

    expect(document.title).toMatch(/^Fehler: Prüfen – Schritt 3 von 3 – Neue Prompt-Bibliothek/)
    expect(screen.queryByRole('button', { name: 'Weiter' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    expect(handlers.onBack).toHaveBeenCalledOnce()
    await user.click(screen.getByRole('button', { name: 'Anlegen' }))
    expect(handlers.onCreate).toHaveBeenCalledOnce()
  })

  it('leads „Anlegen“ to the missing right instead of creating, without disabling it', async () => {
    const user = userEvent.setup()
    const { handlers } = renderLayout({ activeStep: 2, createBlockedBy: 'Ihnen fehlt das Recht.' })

    const create = screen.getByRole('button', { name: 'Anlegen' })
    expect(create).toBeEnabled()
    expect(create).not.toHaveAttribute('aria-disabled')
    expect(create).toHaveAccessibleDescription('Ihnen fehlt das Recht.')
    await user.click(create)
    expect(handlers.onCreate).not.toHaveBeenCalled()
    expect(screen.getByText('Ihnen fehlt das Recht.').closest('[role="alert"]')).toHaveFocus()
  })

  it('moves the focus to the title of the step just entered', () => {
    const { rerender, handlers } = renderLayout()
    rerender(
      <WizardLayout
        object="Neue Prompt-Bibliothek"
        steps={STEPS}
        activeStep={1}
        submitting={false}
        createAnnouncement="Prompt-Bibliothek wird angelegt"
        {...handlers}
      >
        <div>Felder</div>
      </WizardLayout>,
    )

    expect(screen.getByRole('heading', { level: 2, name: 'Freigaben' })).toHaveFocus()
  })

  it('takes only steps that begin with „Stammdaten“ and end with „Prüfen“', () => {
    const valid: WizardSteps[] = [
      ['Stammdaten', 'Mitglieder', 'Inhalte', 'Prüfen'],
      ['Quellart', 'Stammdaten', 'Quelle', 'Freigaben', 'Prüfen'],
      ['Quellart', 'Stammdaten', 'Prüfen'],
    ]
    // @ts-expect-error the first step is „Stammdaten“, not „Grunddaten“
    const renamedFirst: WizardSteps = ['Grunddaten', 'Mitglieder', 'Prüfen']
    // @ts-expect-error the last step is „Prüfen“, not „Zusammenfassung“
    const renamedLast: WizardSteps = ['Stammdaten', 'Mitglieder', 'Zusammenfassung']
    // @ts-expect-error fewer than three steps make a dialog, not an assistant
    const tooShort: WizardSteps = ['Stammdaten', 'Prüfen']

    expect(valid).toHaveLength(3)
    expect([renamedFirst, renamedLast, tooShort]).toHaveLength(3)
  })
})

/** Every creation assistant of a topic, read as source text at build time. */
const CREATE_PAGES = import.meta.glob('../../../features/*/pages/*CreatePage.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

describe('creation assistants', () => {
  it('finds the four assistants', () => {
    expect(Object.keys(CREATE_PAGES).map((path) => path.split('/').pop())).toEqual(
      expect.arrayContaining([
        'SpaceCreatePage.tsx',
        'LibraryCreatePage.tsx',
        'PromptLibraryCreatePage.tsx',
        'DocumentTemplateCreatePage.tsx',
      ]),
    )
  })

  it.each(Object.entries(CREATE_PAGES))(
    '%s renders WizardLayout and no step bar of its own',
    (_, source) => {
      expect(source).toContain('<WizardLayout')
      expect(source).not.toContain('WizardStepBar')
    },
  )
})
