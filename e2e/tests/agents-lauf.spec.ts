import { expect, test } from '../fixtures/auth'
import { apiAs } from '../fixtures/externalAccess'
import {
  expectAnswerSettled,
  expectCitedSource,
  isChatAnswer,
  startFreshChat,
} from '../fixtures/chat'
import { assignToDefaultSpace, defaultSpaceIdOf } from '../fixtures/spaces'
import { createSpaceWithMember, deleteSpaceViaApi } from '../fixtures/privateLibraries'
import type { APIRequestContext } from '@playwright/test'
import {
  accountId,
  addSpaceMember,
  answers,
  askAgent,
  createAgentViaApi,
  createIndexedLibrary,
  assignToSpace,
  deleteAgentViaApi,
  grantViewer,
  heldAgentCalls,
  releaseAgentCalls,
  restoreAgentSettings,
  setAgentSettings,
  setToolCapability,
  type AgentSettings,
} from '../fixtures/agents'

/**
 * Die Szenarien 5 bis 8: der Lauf eines Agenten mit seinen Schritten,
 * Stopp, Rückfrage, Grenze, Platzlimit und ein Modell ohne Werkzeugfähigkeit. Die Oberfläche
 * erreicht das Backend wie überall in der Suite durch den nginx des Frontend-Containers; dass die
 * Schritte während des Laufs ankommen, belegt Szenario 5 damit auch für den Antwortstrom durch den
 * Proxy. ai-stub spielt das Modell (siehe `plainAgentStep`), die Auslöser stehen in der Frage.
 */

const ADMIN = 'dev-admin'
const MEMBER = 'dev-user'
const OUTSIDER = 'dev-outsider'
const SUFFIX = Date.now().toString(36)
const PAUSE = 'E2E-AGENT-PAUSE'
const FIRST_FILE = `fristen-${SUFFIX}.txt`
const SECOND_FILE = `widersprueche-${SUFFIX}.txt`

/** Starts a run through the API that waits at ai-stub until the calls are released. */
function startBlockedRun(
  api: APIRequestContext,
  spaceId: string,
  agentId: string,
  question: string,
): Promise<unknown> {
  return (async () => {
    const chat = await api.post(`/api/v1/spaces/${spaceId}/chats`, { data: {} })
    expect(chat.status(), await chat.text()).toBe(201)
    const { id: chatId } = (await chat.json()) as { id: string }
    return api.post('/api/v1/query', {
      timeout: 60_000,
      data: { question, chatId, agentId },
    })
  })()
}

/** Waits until a run of the admin is accepted again, i.e. the places of earlier runs are free. */
async function expectPlaceFree(agentId: string): Promise<void> {
  const api = await apiAs(ADMIN)
  try {
    const spaceId = await defaultSpaceIdOf(ADMIN)
    await expect
      .poll(
        async () => {
          const chat = await api.post(`/api/v1/spaces/${spaceId}/chats`, { data: {} })
          const { id: chatId } = (await chat.json()) as { id: string }
          const run = await api.post('/api/v1/query', {
            data: {
              question: `Was gilt für „Wohngeld“? (${SUFFIX}-frei)`,
              chatId,
              agentId,
            },
          })
          return run.status()
        },
        { timeout: 30_000, intervals: [500, 1000] },
      )
      .toBe(200)
  } finally {
    await api.dispose()
  }
}

test.describe.serial('Agenten: Lauf, Stopp, Grenze und Platzlimit', () => {
  test.describe.configure({ timeout: 90_000 })

  let agentsBefore: AgentSettings | null = null
  let capabilityBefore: Awaited<ReturnType<typeof setToolCapability>> | null = null
  let libraryId = ''
  const agentIds: string[] = []
  const agentName = `E2E-Lauf-${SUFFIX}`
  const limitedName = `E2E-Grenze-${SUFFIX}`

  test.beforeAll(async () => {
    agentsBefore = await setAgentSettings(true)
    capabilityBefore = await setToolCapability('CAPABLE')
    libraryId = await createIndexedLibrary(ADMIN, `E2E-Lauf-Wissen-${SUFFIX}`, [
      {
        name: FIRST_FILE,
        text: 'Dienstanweisung Fristen: Anträge auf Wohngeld werden binnen zehn Tagen bearbeitet.',
      },
      {
        name: SECOND_FILE,
        text: 'Dienstanweisung Rechtsbehelfe: Widersprüche werden binnen vier Wochen beschieden.',
      },
    ])
    await assignToDefaultSpace(ADMIN, 'KNOWLEDGE_LIBRARY', libraryId)
    for (const config of [
      { name: agentName, toolNames: ['search_knowledge', 'read_document', 'ask_user'] },
      {
        name: limitedName,
        toolNames: ['search_knowledge'],
        limits: { maxToolCalls: 2 },
      },
    ]) {
      const id = await createAgentViaApi(ADMIN, { ...config, libraryIds: [libraryId] })
      agentIds.push(id)
      await assignToDefaultSpace(ADMIN, 'AGENT', id)
    }
  })

  test.afterAll(async () => {
    await releaseAgentCalls()
    for (const id of agentIds) await deleteAgentViaApi(ADMIN, id)
    const api = await apiAs(ADMIN)
    try {
      if (libraryId) {
        const documents = (await (
          await api.get(`/api/v1/libraries/${libraryId}/documents?size=100`)
        ).json()) as { items: Array<{ id: string }> }
        for (const document of documents.items) {
          await api.delete(`/api/v1/libraries/${libraryId}/documents/${document.id}`)
        }
        await api.delete(`/api/v1/libraries/${libraryId}`)
      }
    } finally {
      await api.dispose()
    }
    await restoreAgentSettings(agentsBefore)
    if (capabilityBefore && capabilityBefore !== 'CAPABLE') {
      await setToolCapability(capabilityBefore)
    }
  })

  test('5. Mehrschrittiger Lauf: Schritte im Strom, Belege aus beiden Schritten, Stempel, Entwurf', async ({
    authenticatedPage: page,
  }) => {
    await startFreshChat(page)
    const [stream] = await Promise.all([
      page.waitForResponse(isChatAnswer),
      askAgent(
        page,
        agentName,
        `${PAUSE} Nenne die Fristen für „Wohngeld“ und für „Widersprüche“. (${SUFFIX})`,
      ),
    ])
    expect(stream.headers()['content-type']).toContain('text/event-stream')

    // The model call after the first step waits at ai-stub: what the page shows now has come
    // through the nginx of the stack while the run is still open.
    await expect.poll(heldAgentCalls, { timeout: 30_000 }).toBe(1)
    const steps = page.getByTestId('live-agent-steps')
    await expect(steps).toContainText(/Suche: Wohngeld · \d+ Treffer/)
    await expect(page.getByTestId('chat-status-line')).toContainText('Sucht nach „Wohngeld“')
    await expect(answers(page)).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Antwort abbrechen' })).toBeVisible()

    await releaseAgentCalls()
    await expect(answers(page)).toHaveCount(1, { timeout: 30_000 })
    await expectAnswerSettled(page)
    const answer = answers(page).first()
    await expect(answer).toContainText('Ergebnis des Agenten.')

    // Steps folded as "2 Schritte"; a click opens them.
    await answer.getByRole('button', { name: '2 Schritte' }).click()
    await expect(answer.getByRole('listitem').filter({ hasText: 'Suche: Wohngeld' })).toBeVisible()
    await expect(
      answer.getByRole('listitem').filter({ hasText: 'Suche: Widersprüche' }),
    ).toBeVisible()

    // Draft mark and stamp: the agent's revision, the libraries searched and the model.
    const stamp = answer.getByTestId('agent-stamp')
    await expect(stamp).toContainText('Entwurf')
    await stamp.getByRole('button', { name: /^Fassung \d+$/ }).click()
    await expect(stamp).toContainText(new RegExp(`Bibliotheken:\\s*E2E-Lauf-Wissen-${SUFFIX}`))
    await expect(stamp).toContainText(/Modell:\s*\S/)
    await expect(stamp).toContainText('Entwurf, nicht geprüft')

    // Citations of both steps, one document each; copying is there once the answer is kept.
    await expectCitedSource(page, FIRST_FILE)
    await expectCitedSource(page, SECOND_FILE)
    await expect(answer.getByRole('button', { name: 'Antwort kopieren' })).toBeEnabled()
    await expect(page.getByTestId('question-agent')).toHaveText(`Agent: ${agentName}`)
  })

  test('5b. Stopp: der Lauf endet vor dem nächsten Schritt, nichts wird gespeichert', async ({
    authenticatedPage: page,
  }) => {
    const question = `${PAUSE} Was gilt für „Wohngeld“? (${SUFFIX}-stopp)`
    await startFreshChat(page)
    await askAgent(page, agentName, question)
    await expect.poll(heldAgentCalls, { timeout: 30_000 }).toBe(1)

    const cancelled = page.waitForResponse((response) =>
      response.url().includes('/api/v1/query/stream/cancel'),
    )
    await page.getByRole('button', { name: 'Antwort abbrechen' }).click()
    expect((await cancelled).ok()).toBe(true)
    await expect(page.getByText('Antwort abgebrochen.')).toBeVisible()
    await expect(page.getByRole('combobox', { name: 'Nachricht eingeben' })).toHaveValue(question)
    // Let go of the call the stopped run waited in, so it cannot count as a place later on.
    await releaseAgentCalls()
    await expect.poll(heldAgentCalls, { timeout: 15_000 }).toBe(0)
    await expectPlaceFree(agentIds[0])

    await page.reload()
    await expect(page.getByRole('combobox', { name: 'Nachricht eingeben' })).toBeVisible()
    await expect(page.getByText(question)).toHaveCount(0)
  })

  test('6. Rückfrage: der Lauf endet mit der Frage, die Antwort setzt die Arbeit in einem neuen Lauf fort', async ({
    authenticatedPage: page,
  }) => {
    await startFreshChat(page)
    await askAgent(page, agentName, `E2E-AGENT-RUECKFRAGE Wie viele Anträge gab es? (${SUFFIX})`)
    const asked = answers(page).first()
    await expect(asked).toContainText('Welchen Zeitraum meinen Sie?')
    await expect(asked).toContainText('Rückfrage')
    await expect(asked.getByRole('button', { name: /Schritte?$/ })).toBeVisible()

    // The reply is a new message and a new run; the agent has to be chosen again.
    await askAgent(page, agentName, `Für „Wohngeld“ im Jahr 2025. (${SUFFIX})`)
    await expect(answers(page)).toHaveCount(2)
    await expect(answers(page).nth(1)).toContainText('Ergebnis des Agenten.')
    await expect(answers(page).nth(1)).not.toContainText('Rückfrage')
  })

  test('7. Grenze: das Erreichte kommt mit abgesetztem Zustand', async ({
    authenticatedPage: page,
  }) => {
    await startFreshChat(page)
    await askAgent(page, limitedName, `E2E-AGENT-GRENZE Fasse alles zusammen. (${SUFFIX})`)
    const answer = answers(page).first()
    await expect(answer).toBeVisible({ timeout: 30_000 })
    await expect(answer).toContainText('nicht geprüft')
    await expect(answer).toContainText('Der Agent hat die Höchstzahl an Schritten erreicht.')
    await answer.getByRole('button', { name: /Schritte$/ }).click()
    await expect(answer.getByRole('listitem').filter({ hasText: /^Suche · Grenze$/ })).toBeVisible()
  })

  test('7b. Platzlimit: bei vollem Arbeitsplatz wird die Anfrage ohne Teilergebnis abgewiesen', async ({
    authenticatedPage: page,
  }) => {
    // Two other people fill the workspace (two places, one per person) and wait at ai-stub.
    const spaceId = await createSpaceWithMember(`E2E-Lauf-Raum-${SUFFIX}`, ADMIN, MEMBER)
    const blockers: Array<Promise<unknown>> = []
    const apis = []
    try {
      await addSpaceMember(ADMIN, spaceId, OUTSIDER)
      for (const person of [MEMBER, OUTSIDER]) {
        const personId = await accountId(person)
        for (const [type, id] of [
          ['KNOWLEDGE_LIBRARY', libraryId],
          ['AGENT', agentIds[0]],
        ] as const) {
          await grantViewer(ADMIN, type, id, { subjectType: 'USER', subjectId: personId })
        }
      }
      await assignToSpace(ADMIN, spaceId, 'KNOWLEDGE_LIBRARY', libraryId)
      await assignToSpace(ADMIN, spaceId, 'AGENT', agentIds[0])
      for (const person of [MEMBER, OUTSIDER]) {
        const api = await apiAs(person)
        apis.push(api)
        blockers.push(
          startBlockedRun(api, spaceId, agentIds[0], `${PAUSE} Blocker ${person} (${SUFFIX})`),
        )
      }
      await expect.poll(heldAgentCalls, { timeout: 30_000 }).toBe(2)

      const question = `Was gilt für „Wohngeld“? (${SUFFIX}-voll)`
      await startFreshChat(page)
      await askAgent(page, agentName, question)
      await expect(
        page.getByText(
          'Gerade arbeiten zu viele Agenten gleichzeitig. Bitte versuchen Sie es in Kürze erneut.',
        ),
      ).toBeVisible()
      await expect(answers(page)).toHaveCount(0)
      await expect(page.getByTestId('provisional-answer')).toHaveCount(0)
      await expect(page.getByRole('combobox', { name: 'Nachricht eingeben' })).toHaveValue(question)
    } finally {
      await releaseAgentCalls()
      await Promise.allSettled(blockers)
      for (const api of apis) await api.dispose()
      await deleteSpaceViaApi(ADMIN, spaceId)
    }
    await expectPlaceFree(agentIds[0])
  })

  test('7c. Personenlimit: wer schon einen Lauf hat, startet keinen zweiten', async ({
    authenticatedPage: page,
  }) => {
    const api = await apiAs(ADMIN)
    let blocker: Promise<unknown> | null = null
    try {
      blocker = startBlockedRun(
        api,
        await defaultSpaceIdOf(ADMIN),
        agentIds[0],
        `${PAUSE} Blocker eigener Lauf (${SUFFIX})`,
      )
      await expect.poll(heldAgentCalls, { timeout: 30_000 }).toBe(1)

      const question = `Was gilt für „Wohngeld“? (${SUFFIX}-person)`
      await startFreshChat(page)
      await askAgent(page, agentName, question)
      await expect(
        page.getByText(
          /Sie haben bereits so viele Agentenläufe gestartet, wie gleichzeitig möglich sind/,
        ),
      ).toBeVisible()
      await expect(answers(page)).toHaveCount(0)
    } finally {
      await releaseAgentCalls()
      if (blocker) await Promise.allSettled([blocker])
      await api.dispose()
    }
    await expectPlaceFree(agentIds[0])
  })

  test('8. Modell nicht werkzeugfähig: die Agenten-Anfrage wird abgewiesen, der gewöhnliche Chat läuft', async ({
    authenticatedPage: page,
  }) => {
    await setToolCapability('UNVERIFIED')
    try {
      const question = `Was gilt für „Wohngeld“? (${SUFFIX}-modell)`
      await startFreshChat(page)
      await askAgent(page, agentName, question)
      await expect(page.getByText(/Diese Anfrage braucht Werkzeugaufrufe/)).toBeVisible()
      await expect(answers(page)).toHaveCount(0)
      // Without the agent the same question is answered as before.
      await expect(page.getByTestId('chosen-agent-chip')).toHaveCount(0)
      await page.getByRole('combobox', { name: 'Nachricht eingeben' }).fill(question)
      await page.getByRole('button', { name: 'Senden' }).click()
      await expect(answers(page)).toHaveCount(1)
      await expect(page.getByTestId('agent-stamp')).toHaveCount(0)
    } finally {
      await setToolCapability('CAPABLE')
    }
  })
})
