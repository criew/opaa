// Minimal stand-in for Dropbox for the E2E stack: the OAuth code flow (PKCE S256, client_secret_basic,
// refresh token only for token_access_type=offline) and the API v2 routes the connector uses, over
// one fixed home folder. Tokens, codes and the client must be valid, or the answer is an error.
// GET /__state reports how often the consent page was visited, to prove that no redirect happened.
import { createServer } from 'node:http'
import { createHash, randomUUID } from 'node:crypto'

const PORT = Number(process.env.PORT ?? 8090)
const ACCOUNT = 'dienstkonto@example.org'
const BASIC = `Basic ${Buffer.from('e2e-app-key:e2e-app-secret').toString('base64')}`

// code -> what its consent was bound to; issued access tokens; the one valid refresh token
const codes = new Map()
const accessTokens = new Set()
const REFRESH_TOKEN = 'stub-refresh'

const FOLDER = '/E2E-Ablage'
const FILE = `${FOLDER}/dropbox-aktenvermerk.txt`
const CONTENT = 'Aktenvermerk Dropbox: Die Förderzusage für das Bürgerhaus wurde erteilt.\n'

const items = [
  { tag: 'folder', id: 'id:folder1', path: FOLDER },
  { tag: 'file', id: 'id:file1', path: FILE, content: Buffer.from(CONTENT, 'utf8') },
]

let authorizeVisits = 0

function meta(item) {
  const base = {
    '.tag': item.tag,
    name: item.path.slice(item.path.lastIndexOf('/') + 1),
    id: item.id,
    path_lower: item.path.toLowerCase(),
    path_display: item.path,
  }
  if (item.tag === 'file') {
    return {
      ...base,
      rev: '01',
      size: item.content.length,
      is_downloadable: true,
      content_hash: createHash('sha256').update(item.content).digest('hex'),
    }
  }
  return base
}

function normalize(path) {
  const lower = String(path ?? '').toLowerCase()
  return lower === '/' ? '' : lower.replace(/\/$/, '')
}

function find(path) {
  const wanted = String(path ?? '')
  return items.find((item) => item.id === wanted || item.path.toLowerCase() === normalize(wanted))
}

function below(folder, recursive) {
  const prefix = `${normalize(folder)}/`
  return items.filter((item) => {
    const lower = item.path.toLowerCase()
    return lower.startsWith(prefix) && (recursive || !lower.slice(prefix.length).includes('/'))
  })
}

function send(res, status, body, headers = {}) {
  const bytes = Buffer.from(JSON.stringify(body))
  res.writeHead(status, { 'Content-Type': 'application/json', ...headers })
  res.end(bytes)
}

function conflict(res, summary) {
  send(res, 409, { error_summary: summary })
}

async function readBody(req) {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  return Buffer.concat(chunks).toString('utf8')
}

async function rpc(route, args, res) {
  // Dropbox names the root "" and refuses "/".
  if (args.path === '/') return send(res, 400, { error_summary: 'path/malformed_path/..' })
  switch (route) {
    case 'users/get_current_account':
      return send(res, 200, {
        email: ACCOUNT,
        root_info: { '.tag': 'user', root_namespace_id: 'home-1', home_namespace_id: 'home-1' },
      })
    case 'files/get_metadata': {
      const item = find(args.path)
      return item ? send(res, 200, meta(item)) : conflict(res, 'path/not_found/..')
    }
    case 'files/list_folder': {
      const folder = normalize(args.path)
      if (folder !== '' && find(folder)?.tag !== 'folder') return conflict(res, 'path/not_found/..')
      return send(res, 200, {
        entries: below(folder, Boolean(args.recursive)).map(meta),
        cursor: 'stub-listing',
        has_more: false,
      })
    }
    case 'files/list_folder/continue':
      return send(res, 200, { entries: [], cursor: 'stub-listing', has_more: false })
    case 'files/list_folder/get_latest_cursor':
      return send(res, 200, { cursor: 'stub-changes' })
    case 'files/list_revisions':
      return conflict(res, 'path/not_found/..')
    case 'auth/token/revoke':
      return send(res, 200, {})
    default:
      return send(res, 400, { error_summary: 'unknown_route/..' })
  }
}

function download(req, res) {
  const arg = JSON.parse(req.headers['dropbox-api-arg'] ?? '{}')
  const item = find(arg.path)
  if (!item || item.tag !== 'file') return conflict(res, 'path/not_found/..')
  // a header carries ASCII only
  const result = JSON.stringify(meta(item)).replace(
    /[\u0080-￿]/g,
    (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`,
  )
  res.writeHead(200, {
    'Content-Type': 'application/octet-stream',
    'Dropbox-API-Result': result,
    'Content-Length': item.content.length,
  })
  res.end(item.content)
}

function oauthError(res, error) {
  send(res, 400, { error, error_description: error })
}

function authorize(url, res) {
  authorizeVisits++
  const redirectUri = url.searchParams.get('redirect_uri') ?? ''
  const challenge = url.searchParams.get('code_challenge')
  if (
    url.searchParams.get('response_type') !== 'code' ||
    url.searchParams.get('client_id') !== 'e2e-app-key' ||
    url.searchParams.get('code_challenge_method') !== 'S256' ||
    !challenge ||
    !redirectUri
  ) {
    return send(res, 400, { error: 'invalid_request' })
  }
  const code = randomUUID()
  codes.set(code, {
    challenge,
    redirectUri,
    offline: url.searchParams.get('token_access_type') === 'offline',
  })
  const redirect = new URL(redirectUri)
  redirect.searchParams.set('code', code)
  redirect.searchParams.set('state', url.searchParams.get('state') ?? '')
  res.writeHead(302, { Location: redirect.toString() })
  res.end()
}

async function token(req, res) {
  const form = new URLSearchParams(await readBody(req))
  if (req.headers.authorization !== BASIC) return send(res, 401, { error: 'invalid_client' })
  const grant = form.get('grant_type')
  let offline = true
  if (grant === 'authorization_code') {
    const code = form.get('code') ?? ''
    const consent = codes.get(code)
    codes.delete(code) // a code is good once
    const verifier = form.get('code_verifier') ?? ''
    const challenge = createHash('sha256').update(verifier).digest('base64url')
    if (
      !consent ||
      consent.challenge !== challenge ||
      consent.redirectUri !== form.get('redirect_uri')
    ) {
      return oauthError(res, 'invalid_grant')
    }
    offline = consent.offline
  } else if (grant === 'refresh_token') {
    if (form.get('refresh_token') !== REFRESH_TOKEN) return oauthError(res, 'invalid_grant')
  } else {
    return oauthError(res, 'unsupported_grant_type')
  }
  const access = `stub-access-${randomUUID()}`
  accessTokens.add(access)
  send(res, 200, {
    access_token: access,
    token_type: 'bearer',
    expires_in: 14400,
    ...(grant === 'authorization_code' && offline ? { refresh_token: REFRESH_TOKEN } : {}),
    scope: 'account_info.read files.metadata.read files.content.read',
  })
}

async function handle(req, res) {
  const url = new URL(req.url ?? '/', 'http://stub')
  if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { status: 'ok' })
  if (req.method === 'GET' && url.pathname === '/__state') {
    return send(res, 200, { authorizeVisits })
  }
  if (req.method === 'GET' && url.pathname === '/oauth2/authorize') return authorize(url, res)
  if (req.method === 'POST' && url.pathname === '/oauth2/token') return token(req, res)
  if (req.method === 'POST' && url.pathname.startsWith('/2/')) {
    const bearer = (req.headers.authorization ?? '').replace(/^Bearer /, '')
    if (!accessTokens.has(bearer)) {
      return send(res, 401, {
        error_summary: 'invalid_access_token/..',
        error: { '.tag': 'invalid_access_token' },
      })
    }
    const route = url.pathname.slice('/2/'.length)
    if (route === 'files/download') return download(req, res)
    const body = await readBody(req)
    // routes without arguments are called with the body "null"
    return rpc(route, (body ? JSON.parse(body) : null) ?? {}, res)
  }
  return send(res, 404, { error: 'not_found' })
}

const server = createServer((req, res) => {
  handle(req, res).catch((error) => {
    console.error(error)
    if (res.headersSent) res.end()
    else send(res, 400, { error_summary: 'bad_request/..' })
  })
})

server.listen(PORT, () => console.log(`Dropbox stub listening on :${PORT}`))
