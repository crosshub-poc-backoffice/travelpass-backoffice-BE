// 접속 주소: DEMO_DATABASE_URL > DATABASE_URL > BE .env 의 DB_* (BE 와 같은 기본값: localhost:5433/travelpass)
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import pg from 'pg'

function beEnv(): Record<string, string> {
  try {
    const text = readFileSync(fileURLToPath(new URL('../.env', import.meta.url)), 'utf8')
    return Object.fromEntries(
      text.split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#') && l.includes('='))
        .map((l) => [l.slice(0, l.indexOf('=')).trim(), l.slice(l.indexOf('=') + 1).trim()]),
    )
  } catch {
    return {}
  }
}

export function databaseUrl(): string {
  if (process.env.DEMO_DATABASE_URL) return process.env.DEMO_DATABASE_URL
  if (process.env.DATABASE_URL) return process.env.DATABASE_URL
  const e = { ...beEnv(), ...process.env }
  const user = encodeURIComponent(e.DB_USER ?? 'postgres')
  const pass = encodeURIComponent(e.DB_PASSWORD ?? 'postgres')
  return `postgresql://${user}:${pass}@${e.DB_HOST ?? 'localhost'}:${e.DB_PORT ?? '5433'}/${e.DB_NAME ?? 'travelpass'}`
}

export const isLocal = (url: string) => /@(localhost|127\.0\.0\.1)[:/]/.test(url)
export const hostOf = (url: string) => url.replace(/^.*@/, '').replace(/[/?].*$/, '')

export async function connect(url = databaseUrl()) {
  const client = new pg.Client({ connectionString: url })
  await client.connect()
  return client
}
