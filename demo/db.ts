// Prisma 없이 pg 로 데모 데이터를 넣는 작은 어댑터. fill.ts 는 원래 Prisma 트랜잭션(db.<모델>.create/createMany)을 썼는데,
// 그 호출 모양을 그대로 받아 INSERT 로 바꾼다 — fill.ts 를 고치지 않고 FE 에서 옮겨 오려고.
//
// - 표 이름 = 모델 이름 첫 글자 대문자 ("productTranslation" → "ProductTranslation"), 열 이름 = 키 그대로
// - 열 종류는 information_schema 에서 읽어서 맞춘다: jsonb → JSON 문자열, date → 'YYYY-MM-DD'(JST 기준 아님, UTC 날짜 — fill 이 UTC 자정 Date 로 준다)
// - Prisma 가 앱에서 채우던 값: id(@default(cuid()))가 없으면 만들고, updatedAt(@updatedAt)이 없으면 createdAt 또는 지금
// - 중첩 생성은 fill.ts 가 쓰는 것 하나만: inquiry.create({ data: { messages: { create: [...] } } })
import { randomBytes } from 'node:crypto'
import type pg from 'pg'

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Row = Record<string, any>
type Model = { create(args: { data: Row }): Promise<Row>; createMany(args: { data: Row[] }): Promise<{ count: number }> }
export type Db = Record<string, Model>

/** 중첩 생성: 모델.키 → [자식 표, 부모 id 를 넣을 열] */
const NESTED: Record<string, Record<string, [string, string]>> = {
  Inquiry: { messages: ['InquiryMessage', 'inquiryId'] },
}

const cuidLike = () => `c${Date.now().toString(36)}${randomBytes(8).toString('hex').slice(0, 12)}`

export async function makeDb(client: pg.ClientBase): Promise<Db> {
  const { rows } = await client.query<{ table_name: string; column_name: string; data_type: string }>(
    `select table_name, column_name, data_type from information_schema.columns where table_schema = current_schema()`,
  )
  const columns = new Map<string, Map<string, string>>()
  for (const r of rows) {
    if (!columns.has(r.table_name)) columns.set(r.table_name, new Map())
    columns.get(r.table_name)!.set(r.column_name, r.data_type)
  }

  async function insert(table: string, data: Row): Promise<Row> {
    const cols = columns.get(table)
    if (!cols) throw new Error(`표가 없습니다: ${table} (BE 를 먼저 한 번 띄워 Flyway 로 표를 만드세요)`)
    const row: Row = { ...data }
    const nested = NESTED[table] ?? {}
    const children: [string, string, Row[]][] = []
    for (const [key, [childTable, fk]] of Object.entries(nested)) {
      if (row[key]?.create) children.push([childTable, fk, ([] as Row[]).concat(row[key].create)])
      delete row[key]
    }
    if (cols.has('id') && row.id == null) row.id = cuidLike()
    if (cols.has('updatedAt') && row.updatedAt == null) row.updatedAt = row.createdAt ?? new Date()

    const keys = Object.keys(row).filter((k) => row[k] !== undefined)
    for (const k of keys) if (!cols.has(k)) throw new Error(`${table} 에 ${k} 열이 없습니다`)
    const values = keys.map((k) => {
      const v = row[k]
      const type = cols.get(k)
      if (v === null) return null
      if (type === 'jsonb' || type === 'json') return JSON.stringify(v)
      if (type === 'date' && v instanceof Date) return v.toISOString().slice(0, 10)
      return v
    })
    await client.query(
      `insert into "${table}" (${keys.map((k) => `"${k}"`).join(', ')}) values (${keys.map((_, i) => `$${i + 1}`).join(', ')})`,
      values,
    )
    for (const [childTable, fk, list] of children) for (const c of list) await insert(childTable, { ...c, [fk]: row.id })
    return row
  }

  return new Proxy({} as Db, {
    get(_t, model: string) {
      const table = model.charAt(0).toUpperCase() + model.slice(1)
      return {
        create: ({ data }: { data: Row }) => insert(table, data),
        createMany: async ({ data }: { data: Row[] }) => {
          for (const d of data) await insert(table, d)
          return { count: data.length }
        },
      } satisfies Model
    },
  })
}
