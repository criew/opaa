// Stand-in for an OIDC identity provider in the "installation" target, for the set-up assistant's
// step "Anmeldeverfahren" with a provider (tests/installation-assistant.spec.ts). It speaks only what
// the check of that step and the sign-in page need, never a real sign-in:
//
//   GET /.well-known/openid-configuration  the discovery document, issuer = ISSUER (with CORS, the
//                                          browser's OIDC client reads it from the sign-in page)
//   GET /jwks                              a JWK set with one RSA key generated at start
//   GET /authorize                         answers like a provider without a session answers
//                                          prompt=none: back to redirect_uri with
//                                          error=login_required and the request's state
//   GET /health                            for the Compose healthcheck
//
// The browser reaches the stub under ISSUER (a loopback port published by Compose), the backend
// under its service name: the assistant enters the JWK-Set-Adresse as that override.
import { generateKeyPairSync } from 'node:crypto'
import { createServer } from 'node:http'

const PORT = Number(process.env.PORT ?? 8095)
const ISSUER = (process.env.ISSUER ?? `http://localhost:${PORT}`).replace(/\/$/, '')

const { publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
const jwks = {
  keys: [{ ...publicKey.export({ format: 'jwk' }), kid: 'oidc-stub', use: 'sig', alg: 'RS256' }],
}

const discovery = {
  issuer: ISSUER,
  authorization_endpoint: `${ISSUER}/authorize`,
  token_endpoint: `${ISSUER}/token`,
  userinfo_endpoint: `${ISSUER}/userinfo`,
  jwks_uri: `${ISSUER}/jwks`,
  response_types_supported: ['code'],
  subject_types_supported: ['public'],
  id_token_signing_alg_values_supported: ['RS256'],
  code_challenge_methods_supported: ['S256'],
}

function json(res, status, body) {
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*',
  })
  res.end(JSON.stringify(body))
}

createServer((req, res) => {
  const url = new URL(req.url ?? '/', ISSUER)
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Headers': '*',
    })
    res.end()
    return
  }
  switch (url.pathname) {
    case '/health':
      json(res, 200, { status: 'ok' })
      return
    case '/.well-known/openid-configuration':
      json(res, 200, discovery)
      return
    case '/jwks':
      json(res, 200, jwks)
      return
    case '/authorize': {
      // Only back to a loopback address: the stub is no open redirector, even in a test stack.
      const redirectUri = url.searchParams.get('redirect_uri') ?? ''
      if (!/^http:\/\/localhost(:\d+)?\//.test(redirectUri)) {
        json(res, 400, { error: 'invalid_request' })
        return
      }
      const back = new URL(redirectUri)
      back.searchParams.set('error', 'login_required')
      const state = url.searchParams.get('state')
      if (state) back.searchParams.set('state', state)
      res.writeHead(302, { Location: back.toString() })
      res.end()
      return
    }
    default:
      json(res, 404, { error: 'not_found' })
  }
}).listen(PORT, () => console.log(`oidc-stub listening on ${PORT}, issuer ${ISSUER}`))
