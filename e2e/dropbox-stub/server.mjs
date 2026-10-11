// Minimal stand-in for Dropbox for the E2E stack: the OAuth authorization and token endpoints and
// the few API v2 routes the connector uses against one fixed home folder. No checks beyond the
// shape of the requests; it answers every consent as the same account. GET /__state reports how
// often the authorization endpoint was visited, so a scenario can prove that no redirect happened.
import { createServer } from 'node:http'
import { createHash } from 'node:crypto'

const PORT = Number(process.env.PORT ?? 8090)
const ACCOUNT = 'dienstkonto@example.org'

const FOLDER = '/E2E-Ablage'
const FILE = `${FOLDER}/dropbox-aktenvermerk.txt`
const CONTENT = 'Aktenvermerk Dropbox: Die Förderzusage für das Bürgerhaus wurde erteilt.\n'

const items = [
  { tag: 'folder', id: 'id:folder1', path: FOLDER },
  { tag: 'file', id: 'id:file1', path: FILE, content: Buffer.from(CONTENT, 'utf8') },
]

let authorizeVisits = 0
let tokenCounter = 0

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

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://stub')
  if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { status: 'ok' })
  if (req.method === 'GET' && url.pathname === '/__state') return send(res, 200, { authorizeVisits })
  if (req.method === 'GET' && url.pathname === '/oauth2/authorize') {
    authorizeVisits++
    const redirect = new URL(url.searchParams.get('redirect_uri') ?? '')
    redirect.searchParams.set('code', 'stub-code')
    redirect.searchParams.set('state', url.searchParams.get('state') ?? '')
    res.writeHead(302, { Location: redirect.toString() })
    return res.end()
  }
  if (req.method === 'POST' && url.pathname === '/oauth2/token') {
    await readBody(req)
    tokenCounter++
    return send(res, 200, {
      access_token: `stub-access-${tokenCounter}`,
      token_type: 'bearer',
      expires_in: 14400,
      refresh_token: 'stub-refresh',
      scope: 'account_info.read files.metadata.read files.content.read',
    })
  }
  if (req.method === 'POST' && url.pathname.startsWith('/2/')) {
    const route = url.pathname.slice('/2/'.length)
    if (route === 'files/download') return download(req, res)
    const body = await readBody(req)
    return rpc(route, body ? JSON.parse(body) : {}, res)
  }
  return send(res, 404, { error: 'not_found' })
})

server.listen(PORT, () => console.log(`Dropbox stub listening on :${PORT}`))
