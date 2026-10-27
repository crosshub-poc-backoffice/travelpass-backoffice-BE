// 빈 DB에만 데모 데이터를 넣는다. 이미 계정이 있으면 아무것도 안 한다 — 손으로 바꾼 데이터를 덮어쓰지 않게.
// 데이터를 통째로 데모로 되돌리려면 npm run reset.
//   cd demo && npm install && npm run seed     (BE 를 먼저 한 번 띄워 Flyway 로 표를 만들어 둘 것)
// 테스트 계정(test-crosshub/test-federation/test-partner)의 비밀번호는 Keycloak 에 있다 — keycloak-auth/scripts/reset-password.sh
import { connect, databaseUrl, hostOf, isLocal } from './connect'
import { makeDb } from './db'
import { fillDemo } from './fill'

const url = databaseUrl()
const client = await connect(url)
try {
  const { rows } = await client.query<{ n: string }>('select count(*) as n from "User"')
  if (Number(rows[0].n) > 0) {
    console.log('이미 데이터가 있어 시드를 건너뜁니다. 데모 데이터로 바꾸려면 npm run reset')
  } else {
    if (!isLocal(url)) console.log('로컬이 아닌 DB에 데모 데이터를 넣습니다:', hostOf(url))
    await client.query('begin')
    const result = await fillDemo(await makeDb(client))
    await client.query('commit')
    console.log('빈 DB에 데모 데이터를 넣었습니다')
    console.table(result)
  }
} catch (e) {
  await client.query('rollback').catch(() => {})
  console.error(e)
  process.exitCode = 1
} finally {
  await client.end()
}
