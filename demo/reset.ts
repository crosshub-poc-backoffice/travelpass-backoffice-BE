// 데모 데이터로 DB를 통째로 바꾼다: 모든 데이터 표를 비우고(TRUNCATE) fill.ts 로 다시 채운다.
// 마이그레이션 기록(flyway_schema_history, 옛 _prisma_migrations)과 표 구조는 그대로 둔다. 한 트랜잭션이라 실패하면 원래대로.
//
//   로컬:      npm run reset
//   Supabase:  DATABASE_URL="<Supabase 주소>" npm run reset -- --target=supabase
//              (실행 중에 "supabase"를 한 번 더 입력해야 진행된다)
import { createInterface } from 'node:readline/promises'
import { connect, databaseUrl, hostOf, isLocal } from './connect'
import { makeDb } from './db'
import { fillDemo } from './fill'

const url = databaseUrl()
if (!isLocal(url)) {
  if (!process.argv.includes('--target=supabase')) {
    console.error(`로컬 DB가 아닙니다 (${hostOf(url)}). 배포 DB를 바꾸려면 --target=supabase 를 붙여 주세요.`)
    process.exit(1)
  }
  const rl = createInterface({ input: process.stdin, output: process.stdout })
  const answer = await rl.question(`${hostOf(url)} 의 데이터를 모두 지우고 데모 데이터로 바꿉니다. 계속하려면 supabase 를 입력하세요: `)
  rl.close()
  if (answer.trim() !== 'supabase') {
    console.error('취소했습니다.')
    process.exit(1)
  }
}

const client = await connect(url)
try {
  await client.query('begin')
  const { rows } = await client.query<{ tablename: string }>(
    `select tablename from pg_tables where schemaname = current_schema() and tablename not in ('flyway_schema_history', '_prisma_migrations')`,
  )
  await client.query(`truncate ${rows.map((t) => `"${t.tablename}"`).join(', ')} restart identity cascade`)
  const result = await fillDemo(await makeDb(client))
  await client.query('commit')
  console.log(`데모 데이터로 바꿨습니다 (${isLocal(url) ? '로컬' : hostOf(url)})`)
  console.table(result)
} catch (e) {
  await client.query('rollback').catch(() => {})
  console.error(e)
  process.exitCode = 1
} finally {
  await client.end()
}
