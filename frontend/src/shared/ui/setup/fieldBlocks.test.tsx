import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { useState, type ReactElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { OPAA_BRANDING } from '../../branding/brandingStore'
import { createAppTheme } from '../../theme/theme'
import { NO_REALTIME, type RealtimeDraft } from '../transcription/realtimeTranscription'
import BrandingFields from './BrandingFields'
import MalwareScanFields from './MalwareScanFields'
import ModelEndpointFields from './ModelEndpointFields'
import { ChatModelFields, EmbeddingModelFields } from './ModelParameterFields'
import SmtpFields from './SmtpFields'
import TextRecognitionFields from './TextRecognitionFields'
import RealtimeTranscriptionFields, { TranscriptionLanguageField } from './TranscriptionFields'
import {
  malwareScanDraftOf,
  smtpDraftOf,
  textRecognitionDraftOf,
  type BrandingDraft,
  type StoredSmtp,
} from './setupDrafts'

/**
 * Each block is what the assistant's step and the administration page both render; these tests
 * are the one place where its fields, their order and their controls are pinned.
 */

function renderInTheme(ui: ReactElement) {
  return render(<ThemeProvider theme={createAppTheme('light')}>{ui}</ThemeProvider>)
}

/** The labels of the block in the order of the page; a group counts with its own label. */
function labels(): string[] {
  return Array.from(document.querySelectorAll('label, [data-field-label]'))
    .map((element) => element.textContent ?? '')
    .filter((text) => text !== '')
}

function expectOrder(expected: string[]) {
  const found = labels().filter((text) => expected.includes(text))
  expect(found).toEqual(expected)
}

const STORED_SMTP: StoredSmtp = {
  host: 'smtp.intern.example',
  port: 587,
  username: 'opaa',
  encryption: 'STARTTLS',
  fromAddress: 'opaa@stadt.example',
  fromName: 'OPAA',
  passwordSet: true,
}

function Smtp({ stored }: { stored: StoredSmtp | null }) {
  const [draft, setDraft] = useState(() => smtpDraftOf(stored))
  return (
    <>
      <SmtpFields
        idPrefix="smtp"
        value={draft}
        onChange={(patch) => setDraft((current) => ({ ...current, ...patch }))}
        stored={stored}
      />
      <output data-testid="draft">{JSON.stringify(draft)}</output>
    </>
  )
}

function draft<T>(): T {
  return JSON.parse(screen.getByTestId('draft').textContent ?? '{}') as T
}

describe('SmtpFields', () => {
  it('asks for server and port, encryption, sign-in and sender in that order', () => {
    renderInTheme(<Smtp stored={null} />)

    expectOrder([
      'Server',
      'Port',
      'Benutzername (optional)',
      'Passwort (optional)',
      'Absenderadresse',
      'Absendername (optional)',
    ])
    // Encryption is a choice of three, never a select.
    const encryption = screen.getByRole('radiogroup', { name: 'Verschlüsselung' })
    expect(within(encryption).getAllByRole('radio')).toHaveLength(3)
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Passwort (optional)', { selector: 'input' })).toHaveAttribute(
      'type',
      'password',
    )
  })

  it('names a stored password as set and deletes it through a text action', async () => {
    const user = userEvent.setup()
    renderInTheme(<Smtp stored={STORED_SMTP} />)

    expect(screen.getByText('Gesetzt')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Gespeichertes Passwort löschen' }))

    expect(draft<{ removePassword: boolean }>().removePassword).toBe(true)
    expect(screen.getByRole('button', { name: 'Passwort behalten' })).toBeInTheDocument()
  })

  it('asks for the password anew once the stored one belongs to another target', async () => {
    const user = userEvent.setup()
    renderInTheme(<Smtp stored={STORED_SMTP} />)

    await user.type(screen.getByRole('textbox', { name: 'Server' }), '.neu')

    expect(screen.getByRole('button', { name: 'Passwort ersetzen' })).toHaveAttribute(
      'data-invalid',
      'true',
    )
  })
})

describe('MalwareScanFields', () => {
  function Scan({ portLater = false }: { portLater?: boolean }) {
    const [value, setValue] = useState(() => malwareScanDraftOf(null))
    return (
      <MalwareScanFields
        idPrefix="scan"
        value={value}
        onChange={(patch) => setValue((current) => ({ ...current, ...patch }))}
        errors={portLater ? {} : { host: 'Bitte den Host des Virenscanners angeben.' }}
        portLater={portLater}
      />
    )
  }

  it('leaves the port out for the assistant and says where it is entered later', () => {
    renderInTheme(<Scan portLater />)

    expect(screen.queryByLabelText('Port')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Host des Virenscanners')).toHaveAccessibleDescription(
      /Port 3310; einen anderen tragen Sie später unter „Administration → Virenprüfung“ ein\./,
    )
    expect(screen.getByLabelText('Zeitlimit je Datei (Sekunden)')).toHaveValue('30')
  })

  it('asks for host and port, the time limit and the policy, with the defaults filled in', () => {
    renderInTheme(<Scan />)

    expectOrder(['Host des Virenscanners', 'Port', 'Zeitlimit je Datei (Sekunden)'])
    expect(screen.getByLabelText('Port')).toHaveValue('3310')
    expect(screen.getByLabelText('Zeitlimit je Datei (Sekunden)')).toHaveValue('30')
    expect(
      screen.getByRole('radiogroup', { name: 'Wenn der Scanner eine Datei nicht prüfen kann' }),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Host des Virenscanners')).toHaveAccessibleDescription(
      'Bitte den Host des Virenscanners angeben.',
    )
  })
})

describe('BrandingFields', () => {
  function Branding() {
    const [value, setValue] = useState<BrandingDraft>({
      productName: '',
      claim: '',
      aiNotice: 'Eigener Hinweis',
      primaryColor: '',
    })
    return (
      <>
        <BrandingFields
          idPrefix="branding"
          value={value}
          onChange={(patch) => setValue((current) => ({ ...current, ...patch }))}
          aiNoticeDefault={OPAA_BRANDING.aiNoticeDefault}
        />
        <output data-testid="draft">{JSON.stringify(value)}</output>
      </>
    )
  }

  it('asks for name, claim, AI notice and colour, and warns about a colour without refusing it', async () => {
    const user = userEvent.setup()
    renderInTheme(<Branding />)

    expectOrder([
      'Produktname (optional)',
      'Claim (optional)',
      'Hinweis zur KI-Nutzung',
      'Primärfarbe (optional)',
    ])
    await user.click(screen.getByRole('button', { name: 'Hinweis auf Standard zurücksetzen' }))
    expect(draft<BrandingDraft>().aiNotice).toBe(OPAA_BRANDING.aiNoticeDefault)

    const color = screen.getByLabelText('Primärfarbe (optional)')
    await user.type(color, 'blau')
    expect(color).toHaveAttribute('aria-invalid', 'true')
    await user.clear(color)
    await user.type(color, '#FFF176')
    expect(color).not.toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('Kontrast unterschritten')).toBeInTheDocument()
  })
})

interface EndpointProps {
  purpose: 'chat' | 'embedding' | 'rerank' | 'transcription'
  stored?: { baseUrl: string; apiKeySet: boolean } | null
  discover?: Parameters<typeof ModelEndpointFields>[0]['discover']
}

function Endpoint({ purpose, stored = null, discover }: EndpointProps) {
  const [baseUrl, setBaseUrl] = useState(stored?.baseUrl ?? '')
  const [apiKey, setApiKey] = useState('')
  const [removeKey, setRemoveKey] = useState(false)
  const [model, setModel] = useState('')
  return (
    <>
      <ModelEndpointFields
        purpose={purpose}
        stored={stored}
        baseUrl={baseUrl}
        onBaseUrlChange={setBaseUrl}
        apiKey={apiKey}
        onApiKeyChange={setApiKey}
        removeKey={removeKey}
        onRemoveKeyChange={setRemoveKey}
        model={model}
        onModelChange={setModel}
        errors={{}}
        discover={discover}
      >
        <ChatModelFields
          idPrefix="chat"
          temperature=""
          onTemperatureChange={() => undefined}
          maxTokens="8000"
          onMaxTokensChange={() => undefined}
          contextLength={40960}
          errors={{}}
        />
      </ModelEndpointFields>
      <output data-testid="draft">{JSON.stringify({ baseUrl, apiKey, removeKey, model })}</output>
    </>
  )
}

describe('ModelEndpointFields', () => {
  it('asks for provider, address and key under „Endpunkt“ and the model under „Modell“', () => {
    renderInTheme(<Endpoint purpose="chat" discover={vi.fn()} />)

    expect(screen.getByRole('heading', { name: 'Endpunkt' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Modell' })).toBeInTheDocument()
    const providers = screen.getByRole('radiogroup', { name: 'Anbieter' })
    expect(within(providers).getAllByRole('radio')).toHaveLength(4)
    expectOrder([
      'Basis-Adresse',
      'API-Schlüssel (optional)',
      'Modell-Kennung',
      'Temperatur (optional)',
      'Maximale Antwortlänge (Token)',
    ])
    expect(screen.getByLabelText('Maximale Antwortlänge (Token)')).toHaveAccessibleDescription(
      /Kontextfenster des Modells: 40\.960 Token/,
    )
  })

  it('fills the address of a chosen provider and fetches nothing by that alone', async () => {
    const discover = vi.fn()
    const user = userEvent.setup()
    renderInTheme(<Endpoint purpose="chat" discover={discover} />)

    await user.click(screen.getByRole('radio', { name: /^OpenAI/ }))

    expect(draft<{ baseUrl: string }>().baseUrl).toBe('https://api.openai.com/v1')
    expect(discover).not.toHaveBeenCalled()
  })

  it('keeps „Modelle abrufen“ active and leads to the address while none is entered', async () => {
    const discover = vi.fn()
    const user = userEvent.setup()
    renderInTheme(<Endpoint purpose="rerank" discover={discover} />)

    // A role without presets starts at the address.
    expect(screen.queryByRole('radiogroup', { name: 'Anbieter' })).not.toBeInTheDocument()
    const fetch = screen.getByRole('button', { name: 'Modelle abrufen' })
    expect(fetch).toBeEnabled()
    await user.click(fetch)

    expect(screen.getByLabelText('Basis-Adresse')).toHaveAttribute('aria-invalid', 'true')
    expect(discover).not.toHaveBeenCalled()
  })

  it('shows no fetch where the role has no discovery, and a stored key as set', () => {
    renderInTheme(
      <Endpoint purpose="rerank" stored={{ baseUrl: 'http://reranker:80', apiKeySet: true }} />,
    )

    expect(screen.queryByRole('button', { name: 'Modelle abrufen' })).not.toBeInTheDocument()
    expect(screen.getByText('Gesetzt')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Gespeicherten Schlüssel entfernen' }),
    ).toBeInTheDocument()
  })
})

describe('EmbeddingModelFields', () => {
  it('asks for the input limit and names the default while the endpoint reports none', () => {
    renderInTheme(
      <EmbeddingModelFields
        idPrefix="embedding"
        maxInputTokens=""
        onMaxInputTokensChange={() => undefined}
        contextLength={null}
        errors={{}}
      />,
    )

    expect(screen.getByLabelText('Eingabegrenze (Token) (optional)')).toHaveAccessibleDescription(
      /Leer lassen für 2048\./,
    )
  })
})

describe('TranscriptionFields', () => {
  function Live() {
    const [value, setValue] = useState<RealtimeDraft>(NO_REALTIME)
    const [key, setKey] = useState('')
    const [remove, setRemove] = useState(false)
    return (
      <>
        <TranscriptionLanguageField id="language" value="de" onChange={() => undefined} />
        <RealtimeTranscriptionFields
          idPrefix="live"
          value={value}
          onChange={setValue}
          liveKey={key}
          onLiveKeyChange={setKey}
          removeLiveKey={remove}
          onRemoveLiveKeyChange={setRemove}
          liveKeyStored={false}
        />
      </>
    )
  }

  it('offers live transcription as a checkbox that opens service, address, model and key', async () => {
    const user = userEvent.setup()
    renderInTheme(<Live />)

    expect(screen.getByLabelText('Sprache (optional)')).toHaveValue('de')
    // A value that takes effect with the form is a checkbox, never a switch.
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
    const live = screen.getByRole('checkbox', { name: 'Live-Transkript einschalten' })
    expect(live).not.toBeChecked()
    expect(screen.queryByRole('radiogroup', { name: 'Live-Dienst' })).not.toBeInTheDocument()

    await user.click(live)

    expect(screen.getByRole('radiogroup', { name: 'Live-Dienst' })).toBeInTheDocument()
    expectOrder(['Live-Adresse', 'Live-Modell', 'Live-Schlüssel (optional)'])
    expect(screen.getByLabelText('Live-Adresse')).toHaveValue(
      'wss://api.openai.com/v1/realtime?intent=transcription',
    )
  })
})

describe('TextRecognitionFields', () => {
  function Recognition() {
    const [value, setValue] = useState(() => textRecognitionDraftOf(null))
    return (
      <>
        <TextRecognitionFields
          value={value}
          onChange={(patch) => setValue((current) => ({ ...current, ...patch }))}
          apiKeyStored={false}
        />
        <output data-testid="draft">{JSON.stringify(value)}</output>
      </>
    )
  }

  it('starts at the bundled service and asks an own vision model for its identifier', async () => {
    const user = userEvent.setup()
    renderInTheme(<Recognition />)

    expect(screen.getByRole('radio', { name: /Mitgelieferter Dienst/ })).toBeChecked()
    expect(screen.getByLabelText('Basis-Adresse')).toHaveValue('http://opaa-ocr:5001')
    expect(screen.queryByRole('radiogroup', { name: 'Anbindung' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Modell-Kennung')).not.toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /Eigener Endpunkt/ }))
    // The bundled address does not follow to an endpoint of one's own.
    expect(screen.getByLabelText('Basis-Adresse')).toHaveValue('')
    await user.click(
      within(screen.getByRole('radiogroup', { name: 'Anbindung' })).getByRole('radio', {
        name: /Vision-Modell/,
      }),
    )

    expectOrder(['Basis-Adresse', 'Modell-Kennung', 'API-Schlüssel (optional)'])
    expect(draft<{ connectionType: string }>().connectionType).toBe('OPENAI_VISION')
  })
})
