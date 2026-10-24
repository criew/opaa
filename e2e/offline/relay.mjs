// Inbound TCP relay of the offline target: the one container that sits on the isolated network
// and on a regular one, so the browser and the test process on the host can reach the stack.
// It only ever connects to the targets named in RELAYS ("<port>=<host>:<port>,..."), all of them
// services of the isolated stack; it opens no connection of its own anywhere else.
import { createConnection, createServer } from 'node:net'

const relays = (process.env.RELAYS ?? '')
  .split(',')
  .map((entry) => entry.trim())
  .filter(Boolean)
  .map((entry) => {
    const [listen, target] = entry.split('=')
    const [host, port] = target.split(':')
    return { listen: Number(listen), host, port: Number(port) }
  })

if (relays.length === 0) {
  console.error('RELAYS is empty')
  process.exit(1)
}

for (const { listen, host, port } of relays) {
  createServer((client) => {
    const upstream = createConnection({ host, port })
    client.pipe(upstream).pipe(client)
    const close = () => {
      client.destroy()
      upstream.destroy()
    }
    client.on('error', close)
    upstream.on('error', close)
  }).listen(listen, () => console.log(`relay :${listen} -> ${host}:${port}`))
}
