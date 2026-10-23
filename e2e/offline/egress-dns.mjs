// DNS sentinel of the offline target: the only upstream resolver of the isolated stack.
//
// Docker's embedded DNS answers every Compose service name itself and forwards only the rest here,
// so every query that arrives is a name outside the stack - an attempt to reach something the
// installation cannot reach without the internet. Each one is recorded and answered with NXDOMAIN;
// nothing is ever forwarded. GET /queries returns the recorded queries as JSON.
import { createSocket } from 'node:dgram'
import { createServer } from 'node:http'

const DNS_PORT = Number(process.env.DNS_PORT ?? 53)
const HTTP_PORT = Number(process.env.HTTP_PORT ?? 8053)

const TYPES = { 1: 'A', 2: 'NS', 5: 'CNAME', 12: 'PTR', 15: 'MX', 16: 'TXT', 28: 'AAAA', 33: 'SRV', 65: 'HTTPS' }

/** @type {{ at: string, name: string, type: string, from: string }[]} */
const queries = []

/** The first question of a DNS message: name and type, or null for a malformed message. */
function question(message) {
  if (message.length < 12 || message.readUInt16BE(4) < 1) return null
  const labels = []
  let offset = 12
  while (offset < message.length) {
    const length = message[offset]
    if (length === 0) break
    if (length > 63 || offset + 1 + length > message.length) return null
    labels.push(message.subarray(offset + 1, offset + 1 + length).toString('latin1'))
    offset += 1 + length
  }
  if (offset + 5 > message.length) return null
  const code = message.readUInt16BE(offset + 1)
  return { name: labels.join('.').toLowerCase(), type: TYPES[code] ?? String(code), end: offset + 5 }
}

/** Header and question of the query, flagged as a response with RCODE 3 (NXDOMAIN). */
function nxdomain(message, end) {
  const answer = Buffer.from(message.subarray(0, end))
  const recursionDesired = message[2] & 0x01
  answer[2] = 0x80 | (message[2] & 0x78) | recursionDesired
  answer[3] = 0x80 | 0x03
  answer.writeUInt16BE(1, 4)
  answer.writeUInt32BE(0, 6)
  answer.writeUInt16BE(0, 10)
  return answer
}

const socket = createSocket('udp4')
socket.on('message', (message, remote) => {
  const parsed = question(message)
  if (!parsed) return
  const entry = { at: new Date().toISOString(), name: parsed.name, type: parsed.type, from: remote.address }
  queries.push(entry)
  console.log(`EGRESS-DNS ${JSON.stringify(entry)}`)
  socket.send(nxdomain(message, parsed.end), remote.port, remote.address)
})
socket.bind(DNS_PORT)

createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/queries') {
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify(queries))
    return
  }
  if (request.method === 'GET' && request.url === '/health') {
    response.writeHead(200).end('ok')
    return
  }
  response.writeHead(404).end()
}).listen(HTTP_PORT)
