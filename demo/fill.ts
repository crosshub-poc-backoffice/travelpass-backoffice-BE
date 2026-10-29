// 데모 데이터 채우기. 빈 DB(또는 scripts/seed-demo.ts가 비운 DB)에 한 번에 넣는다.
// 날짜는 전부 "오늘(JST) 기준 며칠 전/후"라서 언제 돌려도 대기 중인 일(심사·변경요청·문의)이 살아 있다.
// 무작위 값은 고정 시드라 같은 날 돌리면 같은 결과가 나온다.

import type { Locale } from './lib/i18n'
import type { Db, Row } from './db'
import { addDays } from './lib/products/jp-calendar'
import { saleRange, type SaleSchedule } from './lib/products/schedule'
import { sourceMap } from './lib/products/translation'
import { priceFrom, type ProductInput } from './lib/products/types'
import { DEMO_PRODUCTS, IKI_PRICES, ikiBuild, type DemoProduct, type Localized, type PartnerKey } from './products'

const json = (v: unknown) => v

// ── 고정 시드 난수 ──
function rng(seed: number) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const ALL_LOCALES: Locale[] = ['ko', 'en', 'ja', 'zh-CN', 'zh-TW', 'vi']

export type FillResult = Record<string, number>

export async function fillDemo(db: Db, { now = new Date() }: { now?: Date } = {}): Promise<FillResult> {
  const random = rng(20261020)
  const pick = <T>(list: readonly T[]) => list[Math.floor(random() * list.length)]
  const int = (min: number, max: number) => min + Math.floor(random() * (max - min + 1))
  let idSeq = 0
  // cuid 비슷한 id
  const id = () => `cd${(++idSeq).toString(36).padStart(4, '0')}${Math.floor(random() * 36 ** 8).toString(36).padStart(8, '0')}`

  const today = new Date(now.getTime() + 9 * 3600_000).toISOString().slice(0, 10) // JST 오늘
  const d = (n: number) => addDays(today, n)
  /** JST 날짜 + 시각 → Date. 지금보다 늦으면 지금 조금 전으로 당긴다 */
  const at = (day: string, hh = 10, mm = 0) => {
    const t = new Date(`${day}T${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}:00+09:00`)
    return t > now ? new Date(now.getTime() - int(5, 90) * 60_000) : t
  }
  const hoursAgo = (h: number) => new Date(now.getTime() - h * 3600_000)
  const asDate = (day: string) => new Date(`${day}T00:00:00Z`)

  // ── 연맹 ──
  const affiliateId = id()
  await db.affiliate.create({
    data: { id: affiliateId, name: '일본관광연맹', createdAt: at(d(-180), 9) },
  })

  // ── 업체 6곳: 직접 운영 1(계정 있음), 연맹 대행 3, 비활성 1, 계약 전(수수료율 없음) 1 ──
  const partnerIds: Record<PartnerKey | 'sasebo' | 'shimabara', string> = {
    beppu: id(),
    tabinowa: id(),
    yufuin: id(),
    gotoCar: id(),
    sasebo: id(),
    shimabara: id(),
  }
  const proxy = new Set<string>([partnerIds.tabinowa, partnerIds.yufuin, partnerIds.gotoCar])
  await db.partner.createMany({
    data: [
      {
        id: partnerIds.beppu,
        name: '벳푸온천관광(주)',
        corporateName: '別府温泉観光株式会社',
        representativeName: '야마다 다카시',
        manager: '야마다 켄지',
        phone: '+81-977-21-0000',
        email: 'beppu@example.com',
        subManager: '이노우에 유카',
        subManagerPhone: '+81-90-1111-2222',
        businessRegistrationNumber: 'T4320001000001',
        address: '오이타현 벳푸시 기타하마 1-1-1',
        memo: '공방 정기 점검일은 매달 둘째 주 — 판매 일정 예외로 막아 둠',
        createdAt: at(d(-176), 10),
      },
      {
        id: partnerIds.tabinowa,
        name: '규슈 아일랜드 트래블(주)',
        corporateName: '九州アイランドトラベル株式会社',
        representativeName: '마쓰모토 히로시',
        manager: '마쓰모토 아야',
        phone: '+81-92-700-0000',
        email: 'island-travel@example.com',
        businessRegistrationNumber: 'T4290001000002',
        address: '후쿠오카현 후쿠오카시 하카타구 지코마치 1-2',
        proxyAffiliateId: affiliateId,
        memo: '낙도 보조금은 선착순 — 남은 보조금은 매주 월요일 연맹에 공유',
        createdAt: at(d(-175), 10),
      },
      {
        id: partnerIds.yufuin,
        name: '유후인 료칸 하나무라',
        corporateName: '有限会社 花村',
        representativeName: '하나무라 미치코',
        manager: '하나무라 쇼타',
        phone: '+81-977-84-0000',
        email: 'hanamura@example.com',
        businessRegistrationNumber: 'T4320002000003',
        address: '오이타현 유후시 유후인초 가와카미 2-2',
        proxyAffiliateId: affiliateId,
        memo: '정산 계좌 미등록 — 연맹이 통장 사본 받는 중',
        createdAt: at(d(-175), 11),
      },
      {
        id: partnerIds.gotoCar,
        name: '고토 아일랜드 렌터카',
        corporateName: '五島アイランドレンタカー株式会社',
        representativeName: '고토 다이치',
        manager: '고토 하루미',
        phone: '+81-959-72-0000',
        email: 'goto-car@example.com',
        businessRegistrationNumber: 'T3310001000004',
        address: '나가사키현 고토시 후쿠에초 4-4',
        proxyAffiliateId: affiliateId,
        memo: '차량 정비로 판매 중단 (업체 요청으로 숨김) — 연말 재개 예정',
        createdAt: at(d(-174), 10),
      },
      {
        id: partnerIds.sasebo,
        name: '사세보 하버 크루즈',
        corporateName: '佐世保ハーバークルーズ株式会社',
        manager: '나카무라 다이스케',
        phone: '+81-956-22-0000',
        email: 'sasebo@example.com',
        address: '나가사키현 사세보시 미나토마치 3-3',
        active: false,
        memo: '이번 시즌 휴업으로 비활성 처리',
        createdAt: at(d(-170), 10),
      },
      {
        id: partnerIds.shimabara,
        name: '시마바라 체험 공방',
        manager: '모리 아키코',
        phone: '+81-957-62-0000',
        email: 'shimabara@example.com',
        address: '나가사키현 시마바라시 조나이 1-1',
        memo: '계약 협의 중 — 수수료율 정해지면 상품 등록 시작',
        createdAt: at(d(-10), 15),
      },
    ],
  })

  // ── 계정 3개 (test-crosshub / test-federation / test-partner) ──
  const users = { crosshub: id(), federation: id(), partner: id() }
  await db.user.createMany({
    data: [
      { id: users.crosshub, loginId: 'test-crosshub', name: '크로스허브 운영팀', role: 'crosshub', lastLoginAt: hoursAgo(2), createdAt: at(d(-180), 9) },
      { id: users.federation, loginId: 'test-federation', name: '일본관광연맹', role: 'affiliate', affiliateId, lastLoginAt: hoursAgo(5), createdAt: at(d(-176), 9) },
      { id: users.partner, loginId: 'test-partner', name: '벳푸온천관광(주)', role: 'partner', partnerId: partnerIds.beppu, lastLoginAt: hoursAgo(20), createdAt: at(d(-176), 11) },
    ],
  })
  const ownerUser = (partnerId: string) => (proxy.has(partnerId) ? users.federation : users.partner)

  // ── 상품 6개 + 번역 ──
  type Built = { p: DemoProduct; id: string; partnerId: string; src: Localized; schedule: SaleSchedule; status: 'live' | 'review' | 'hidden'; createdAt: Date }
  const STATUS: Record<DemoProduct['key'], Built['status']> = { tour: 'live', yukata: 'review', goto: 'live', iki: 'live', ryokan: 'live', rentacar: 'hidden' }
  const CREATED: Record<DemoProduct['key'], Date> = {
    tour: at(d(-168), 14),
    goto: at(d(-166), 11),
    iki: at(d(-166), 15),
    ryokan: at(d(-165), 10),
    rentacar: at(d(-164), 16),
    yukata: hoursAgo(26),
  }
  // 번역 상태: 검수 완료(관광·상오도) / 검수 완료 뒤 원문 변경(료칸 소개글) / 자동 번역(이키·유카타·렌터카)
  const REVIEWED = new Set<DemoProduct['key']>(['tour', 'goto', 'ryokan'])
  const products: Record<DemoProduct['key'], Built> = {} as Record<DemoProduct['key'], Built>
  for (const p of DEMO_PRODUCTS) {
    const src = p.build(p.strings[p.sourceLocale], p.sourceLocale)
    const schedule = p.schedule(d)
    const range = saleRange(schedule)
    const built: Built = { p, id: id(), partnerId: partnerIds[p.partner], src, schedule, status: STATUS[p.key], createdAt: CREATED[p.key] }
    products[p.key] = built
    await db.product.create({
      data: {
        id: built.id,
        partnerId: built.partnerId,
        status: built.status,
        confirmType: p.confirmType,
        category: p.category,
        name: src.name,
        images: p.images,
        description: src.description,
        sourceLocale: p.sourceLocale,
        schedule: json(schedule),
        saleStart: range ? asDate(range[0]) : null,
        saleEnd: range ? asDate(range[1]) : null,
        deadlineDays: p.deadlineDays,
        deadlineTime: p.deadlineTime,
        cancelRules: json(p.cancelRules),
        priceFrom: priceFrom(src.priceTable),
        priceTable: json(src.priceTable),
        blocks: json(src.blocks),
        createdAt: built.createdAt,
      },
    })
    const sources = sourceMap(src)
    await db.productTranslation.createMany({
      data: ALL_LOCALES.filter((l) => l !== p.sourceLocale).map((locale) => {
        const texts = sourceMap(p.build(p.strings[locale], locale))
        const from = { ...sources }
        // 료칸: 검수한 뒤 소개글을 고쳐서, 번역은 예전 소개글 기준으로 남아 있다 → "원문 변경"
        if (p.key === 'ryokan') {
          from.description = p.strings[p.sourceLocale].oldDesc
          texts.description = p.strings[locale].oldDesc
        }
        return { productId: built.id, locale, texts: json(texts), sources: json(from), reviewed: REVIEWED.has(p.key) }
      }),
    })
  }

  // ── 상품 변경요청: 대기(이키 수정) / 반려(관광 수정) / 승인(렌터카 숨김) ──
  const productInput = (b: Built, src: Localized): ProductInput => ({
    partnerId: b.partnerId,
    category: b.p.category,
    name: src.name,
    images: b.p.images,
    description: src.description,
    deadlineDays: b.p.deadlineDays,
    deadlineTime: b.p.deadlineTime,
    confirmType: b.p.confirmType,
    schedule: b.schedule,
    cancelRules: b.p.cancelRules,
    priceTable: src.priceTable,
    blocks: src.blocks,
  })
  const ikiJa = products.iki.p.strings.ja
  const ikiProposal = ikiBuild(
    { ...ikiJa, priceText: ikiJa.priceText.replace('3,000円', '3,500円') },
    'ja',
    IKI_PRICES.map(([a, c]) => [a + 1000, c + 500]),
  )
  const tourProposal: Localized = JSON.parse(JSON.stringify(products.tour.src))
  tourProposal.priceTable.plans[1].prices['tour-adult'] = 9800
  tourProposal.priceTable.plans[1].prices['tour-child'] = 6200
  const requests = { iki: id(), tour: id(), rentacar: id() }
  await db.productChangeRequest.createMany({
    data: [
      { id: requests.iki, productId: products.iki.id, type: 'edit', proposedData: json(productInput(products.iki, ikiProposal)), status: 'pending', requestedById: users.federation, createdAt: hoursAgo(19) },
      {
        id: requests.tour,
        productId: products.tour.id,
        type: 'edit',
        proposedData: json(productInput(products.tour, tourProposal)),
        status: 'rejected',
        requestedById: users.partner,
        resolvedById: users.crosshub,
        resolvedAt: at(d(-11), 11),
        rejectReason: '공예 포함 요금 인상 폭이 커요(7,800엔 → 9,800엔). 이미 예약한 고객 안내 방법을 같이 적어서 다시 요청해 주세요.',
        createdAt: at(d(-12), 16),
      },
      { id: requests.rentacar, productId: products.rentacar.id, type: 'hide', targetStatus: 'hidden', status: 'approved', requestedById: users.federation, resolvedById: users.crosshub, resolvedAt: at(d(-20), 10), createdAt: at(d(-21), 17) },
    ],
  })

  // ── 문의 (고객 언어 en·zh·ja·vi, 답변대기·답변완료·답변 여러 개·다른 언어로 쓴 답변) ──
  type Q = { product: DemoProduct['key']; name: string; lang: 'en' | 'zh' | 'ja' | 'vi'; q: string; qKo: string; daysAgo: number; answers?: { text: string; translated: string; locale?: string }[] }
  const INQUIRIES: Q[] = [
    { product: 'tour', name: 'Emily Davis', lang: 'en', q: 'Is the bamboo craft suitable for a 7-year-old?', qKo: '7살 아이도 대나무 공예를 할 수 있나요?', daysAgo: 40, answers: [{ text: '네, 젓가락 만들기는 7세부터 충분히 할 수 있어요. 바구니 엮기는 10세 이상을 권해요.', translated: 'Yes, chopstick making is fine from age 7. We recommend the basket weaving for ages 10 and up.' }] },
    { product: 'tour', name: '王芳', lang: 'zh', q: '下雨天也会照常进行吗？', qKo: '비가 와도 진행하나요?', daysAgo: 25, answers: [{ text: '네, 비가 와도 진행하고 우산을 빌려 드려요. 태풍 경보가 나오면 전액 환불해 드립니다.', translated: '是的，雨天照常进行，我们会提供雨伞。如发布台风警报，将全额退款。' }] },
    { product: 'tour', name: 'Tran Thi Mai', lang: 'vi', q: 'Tôi có thể đổi từ gói chỉ tham quan sang gói có làm đồ tre vào ngày hôm đó không?', qKo: '당일에 순례만 하는 플랜에서 대나무 공예 포함 플랜으로 바꿀 수 있나요?', daysAgo: 0.2 },
    { product: 'tour', name: '山本さくら', lang: 'ja', q: 'ベビーカーで参加できますか？', qKo: '유모차로 참여할 수 있나요?', daysAgo: 3 },
    { product: 'goto', name: 'David Kim', lang: 'en', q: 'Can we board the ferry with a large suitcase?', qKo: '큰 캐리어를 가지고 페리를 탈 수 있나요?', daysAgo: 60, answers: [{ text: '네, 2등 자유석에도 짐 두는 곳이 있어요. 30kg이 넘는 짐은 별도 요금이 붙습니다.', translated: 'Yes, there is luggage space even in 2nd class open seating. Items over 30 kg incur an extra fee.' }] },
    {
      product: 'goto',
      name: '陈静',
      lang: 'zh',
      q: '补助金需要提交什么资料吗？',
      qKo: '보조금을 받으려면 제출할 서류가 있나요?',
      daysAgo: 18,
      answers: [
        { text: '예약 후 이름과 주소를 적는 신청서를 메일로 보내 드려요. 여권 사본은 필요 없어요.', translated: '预订后我们会通过邮件发送填写姓名和地址的申请表，无需护照复印件。' },
        { text: '추가 안내: 신청서는 출발 7일 전까지 회신해 주셔야 보조금이 적용됩니다.', translated: '补充说明：申请表须于出发前7天回复，方可享受补助金。' },
      ],
    },
    { product: 'goto', name: 'Le Van Hung', lang: 'vi', q: 'Có thể chọn hai chương trình trải nghiệm không?', qKo: '체험 프로그램을 두 개 고를 수 있나요?', daysAgo: 2 },
    { product: 'goto', name: 'Isabella Rossi', lang: 'en', q: 'Is there a Sunday mass we can attend at the churches?', qKo: '성당에서 일요일 미사에 참석할 수 있나요?', daysAgo: 4 },
    { product: 'iki', name: 'Lucas Martin', lang: 'en', q: 'Is the jetfoil often cancelled in winter?', qKo: '겨울에 제트포일이 자주 결항하나요?', daysAgo: 33, answers: [{ text: '겨울에는 파도 때문에 한 달에 1~2번 결항해요. 결항하면 페리로 바꿔 드리고 차액은 받지 않습니다.', translated: 'In winter it is cancelled once or twice a month due to rough seas. If that happens, we switch you to the ferry at no extra charge.' }] },
    { product: 'iki', name: '高橋美咲', lang: 'ja', q: '焼酎の蔵見学は運転する人も参加できますか？', qKo: '소주 양조장 견학은 운전하는 사람도 참여할 수 있나요?', daysAgo: 5 },
    { product: 'iki', name: 'Pham Minh Chau', lang: 'vi', q: 'Giá đã gồm phụ phí nhiên liệu chưa?', qKo: '요금에 유류할증료가 포함돼 있나요?', daysAgo: 9, answers: [{ text: '유류할증료 3,000엔은 별도이고, 견적을 보낼 때 함께 넣어 드려요.', translated: 'Phụ phí nhiên liệu 3.000 yên được tính riêng và sẽ được cộng vào khi chúng tôi gửi báo giá.' }] },
    { product: 'ryokan', name: 'Olivia Brown', lang: 'en', q: 'Do you have rooms with beds instead of futons?', qKo: '이불 대신 침대가 있는 방이 있나요?', daysAgo: 50, answers: [{ text: '노천탕 딸린 화양실과 별채 스위트에 트윈 침대가 있어요.', translated: 'The Japanese-Western room with open-air bath and the detached suite both have twin beds.' }] },
    { product: 'ryokan', name: '林美玲', lang: 'zh', q: '可以准备素食晚餐吗？', qKo: '채식 저녁 식사를 준비해 줄 수 있나요?', daysAgo: 0.1 },
    // 연맹 담당자가 일본어로 바로 쓴 답변 — 문의 화면에서 "내 언어로 번역" 버튼이 보이는 경우
    { product: 'ryokan', name: '佐藤健', lang: 'ja', q: '駐車場はありますか？', qKo: '주차장이 있나요?', daysAgo: 70, answers: [{ text: 'はい、無料駐車場が8台分あります。予約は不要です。', translated: 'はい、無料駐車場が8台分あります。予約は不要です。', locale: 'ja' }] },
    { product: 'ryokan', name: 'Nguyen Van An', lang: 'vi', q: 'Trẻ em 4 tuổi có tính phí không?', qKo: '4살 아이도 요금이 있나요?', daysAgo: 14, answers: [{ text: '식사와 이불이 필요 없는 미취학 아동은 무료예요. 식사가 필요하면 1인 6,000엔입니다.', translated: 'Trẻ chưa đi học không cần suất ăn và chăn đệm thì miễn phí. Nếu cần suất ăn, phí là 6.000 yên/người.' }] },
    { product: 'rentacar', name: 'Emma Wilson', lang: 'en', q: 'Can I return the car at Goto Airport?', qKo: '고토공항에서 차를 반납할 수 있나요?', daysAgo: 80, answers: [{ text: '공항 반납은 2,200엔을 더 내시면 가능해요. 예약할 때 메모로 알려 주세요.', translated: 'Yes, airport return is available for an extra ¥2,200. Please let us know in the booking notes.' }] },
    { product: 'rentacar', name: '张伟', lang: 'zh', q: '中国驾照可以租车吗？', qKo: '중국 운전면허증으로 빌릴 수 있나요?', daysAgo: 45, answers: [{ text: '중국은 제네바 협약 가입국이 아니라서 중국 면허로는 일본에서 운전할 수 없어요. 죄송합니다.', translated: '中国不是日内瓦公约缔约国，持中国驾照无法在日本驾驶，非常抱歉。' }] },
  ]
  const answerAudits: { inquiryId: string; name: string; partnerId: string; at: Date }[] = []
  for (const q of INQUIRIES) {
    const b = products[q.product]
    const inquiryId = id()
    const createdAt = hoursAgo(q.daysAgo * 24 + int(1, 8))
    const answers = (q.answers ?? []).map((a, i) => ({ ...a, at: new Date(Math.min(createdAt.getTime() + (i + 1) * int(3, 20) * 3600_000, now.getTime() - 600_000)) }))
    const last = answers[answers.length - 1]
    await db.inquiry.create({
      data: {
        id: inquiryId,
        productId: b.id,
        customerName: q.name,
        language: q.lang,
        question: q.q,
        questionTranslated: q.qKo,
        questionTranslatedLocale: 'ko',
        answer: last?.text ?? null,
        answerTranslated: last?.translated ?? null,
        status: last ? 'answered' : 'pending',
        createdAt,
        messages: {
          create: [
            { sender: 'customer', text: q.q, textTranslated: q.qKo, translatedLocale: 'ko', createdAt },
            ...answers.map((a) => ({ sender: 'admin' as const, text: a.text, textTranslated: a.translated, textLocale: a.locale ?? 'ko', createdAt: a.at })),
          ],
        },
      },
    })
    for (const a of answers) answerAudits.push({ inquiryId, name: q.name, partnerId: b.partnerId, at: a.at })
  }

  // ── 알림 ──
  await db.notification.createMany({
    data: [
      {
        userId: users.crosshub,
        type: 'product_change_requested',
        sourceId: requests.iki,
        payload: json({ type: 'product_change_requested', requestId: requests.iki, productId: products.iki.id, productName: products.iki.src.name, changeType: 'edit', requesterName: '일본관광연맹' }),
        read: false,
        createdAt: hoursAgo(19),
      },
      {
        userId: users.partner,
        type: 'product_change_resolved',
        sourceId: `${requests.tour}:rejected`,
        payload: json({ type: 'product_change_resolved', requestId: requests.tour, productId: products.tour.id, productName: products.tour.src.name, changeType: 'edit', result: 'rejected', reason: '공예 포함 요금 인상 폭이 커요(7,800엔 → 9,800엔). 이미 예약한 고객 안내 방법을 같이 적어서 다시 요청해 주세요.' }),
        read: false,
        createdAt: at(d(-11), 11),
      },
      {
        userId: users.federation,
        type: 'product_change_resolved',
        sourceId: `${requests.rentacar}:approved`,
        payload: json({ type: 'product_change_resolved', requestId: requests.rentacar, productId: products.rentacar.id, productName: products.rentacar.src.name, changeType: 'hide', targetStatus: 'hidden', result: 'approved' }),
        read: true,
        createdAt: at(d(-20), 10),
      },
      ...(['goto', 'iki', 'ryokan', 'rentacar'] as const).map((k) => ({
        userId: users.federation,
        type: 'product_status_changed' as const,
        sourceId: `${products[k].id}:live`,
        payload: json({ type: 'product_status_changed', productId: products[k].id, productName: products[k].src.name, status: 'live' }),
        read: true,
        createdAt: new Date(products[k].createdAt.getTime() + 20 * 3600_000),
      })),
      {
        userId: users.partner,
        type: 'product_status_changed',
        sourceId: `${products.tour.id}:live`,
        payload: json({ type: 'product_status_changed', productId: products.tour.id, productName: products.tour.src.name, status: 'live' }),
        read: true,
        createdAt: new Date(products.tour.createdAt.getTime() + 22 * 3600_000),
      },
    ],
  })

  // ── 작업 이력 ──
  type Audit = Row
  const audits: Audit[] = []
  const log = (a: Omit<Audit, 'detail'> & { detail?: Record<string, [unknown, unknown]> }) => audits.push({ ...a, detail: a.detail ? json(a.detail) : undefined })
  const partnerNames: Record<string, string> = {
    [partnerIds.beppu]: '벳푸온천관광(주)',
    [partnerIds.tabinowa]: '규슈 아일랜드 트래블(주)',
    [partnerIds.yufuin]: '유후인 료칸 하나무라',
    [partnerIds.gotoCar]: '고토 아일랜드 렌터카',
    [partnerIds.sasebo]: '사세보 하버 크루즈',
    [partnerIds.shimabara]: '시마바라 체험 공방',
  }
  for (const [pid, name] of Object.entries(partnerNames)) log({ userId: users.crosshub, action: 'partner_create', partnerId: pid, targetId: pid, targetName: name, createdAt: at(d(pid === partnerIds.shimabara ? -10 : -176), 10, 30) })
  log({ userId: users.crosshub, action: 'account_issue', partnerId: null, targetId: users.federation, targetName: 'test-federation', createdAt: at(d(-176), 9, 10) })
  log({ userId: users.crosshub, action: 'account_issue', partnerId: partnerIds.beppu, targetId: users.partner, targetName: 'test-partner', createdAt: at(d(-176), 11, 10) })
  log({ userId: users.crosshub, action: 'partner_deactivate', partnerId: partnerIds.sasebo, targetId: partnerIds.sasebo, targetName: '사세보 하버 크루즈', createdAt: at(d(-30), 17) })
  for (const b of Object.values(products)) {
    log({ userId: ownerUser(b.partnerId), action: 'product_create', partnerId: b.partnerId, targetId: b.id, targetName: b.src.name, createdAt: b.createdAt })
    if (b.status !== 'review') log({ userId: users.crosshub, action: 'product_status', partnerId: b.partnerId, targetId: b.id, targetName: b.src.name, detail: { status: ['review', 'live'] }, createdAt: new Date(b.createdAt.getTime() + 20 * 3600_000) })
    if (REVIEWED.has(b.p.key)) {
      for (const [i, locale] of ALL_LOCALES.filter((l) => l !== b.p.sourceLocale).entries()) {
        log({ userId: ownerUser(b.partnerId), action: 'product_translation', partnerId: b.partnerId, targetId: b.id, targetName: b.src.name, detail: { locale: [null, locale], reviewed: [false, true] }, createdAt: new Date(b.createdAt.getTime() + (2 + i) * 86400_000) })
      }
    }
  }
  log({ userId: users.crosshub, action: 'product_status', partnerId: products.rentacar.partnerId, targetId: products.rentacar.id, targetName: products.rentacar.src.name, detail: { status: ['live', 'hidden'] }, createdAt: at(d(-20), 10) })
  log({ userId: users.federation, action: 'product_update', partnerId: products.ryokan.partnerId, targetId: products.ryokan.id, targetName: products.ryokan.src.name, createdAt: at(d(-6), 13) })
  for (const a of answerAudits) log({ userId: ownerUser(a.partnerId), action: 'inquiry_answer', partnerId: a.partnerId, targetId: a.inquiryId, targetName: a.name, createdAt: a.at })
  await db.auditLog.createMany({ data: audits })

  // ── 로그인·접속 기록 (최근 3주) ──
  const UA = {
    mac: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36',
    win: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0',
    iphone: 'Mozilla/5.0 (iPhone; CPU iPhone OS 19_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/19.0 Mobile/15E148 Safari/604.1',
  }
  const loginLogs: Row[] = []
  const accessLogs: Row[] = []
  const who = [
    { userId: users.crosshub, loginId: 'test-crosshub', ip: '211.45.62.10', ua: UA.mac, menus: ['dashboard', 'products', 'partners', 'auditLogs'] },
    { userId: users.federation, loginId: 'test-federation', ip: '133.32.18.77', ua: UA.win, menus: ['dashboard', 'products', 'inquiries'] },
    { userId: users.partner, loginId: 'test-partner', ip: '126.15.204.3', ua: UA.iphone, menus: ['dashboard', 'inquiries', 'products'] },
  ]
  for (let n = -21; n <= 0; n++) {
    for (const u of who) {
      if (random() < 0.35 && n !== 0) continue
      const loginAt = n === 0 ? hoursAgo(u.loginId === 'test-crosshub' ? 2 : u.loginId === 'test-federation' ? 5 : 20) : at(d(n), int(8, 18), int(0, 59))
      loginLogs.push({ userId: u.userId, loginId: u.loginId, event: 'login', ip: u.ip, userAgent: u.ua, createdAt: loginAt })
      for (const [i, menu] of u.menus.entries()) {
        if (random() < 0.4) continue
        accessLogs.push({ userId: u.userId, menu, kind: i === 0 ? 'list' : pick(['list', 'view', 'update']), path: `/ko/${menu === 'auditLogs' ? 'audit-logs' : menu}`, ip: u.ip, userAgent: u.ua, createdAt: new Date(loginAt.getTime() + (i + 1) * int(2, 15) * 60_000) })
      }
      if (n !== 0 && random() < 0.5) loginLogs.push({ userId: u.userId, loginId: u.loginId, event: 'logout', ip: u.ip, userAgent: u.ua, createdAt: new Date(loginAt.getTime() + int(30, 180) * 60_000) })
    }
  }
  // 비밀번호 틀림·없는 아이디 시도
  loginLogs.push({ userId: users.partner, loginId: 'test-partner', event: 'failed', failReason: 'wrongPassword', ip: '126.15.204.3', userAgent: UA.iphone, createdAt: at(d(-3), 8, 2) })
  loginLogs.push({ userId: users.partner, loginId: 'test-partner', event: 'failed', failReason: 'wrongPassword', ip: '126.15.204.3', userAgent: UA.iphone, createdAt: at(d(-3), 8, 3) })
  loginLogs.push({ userId: null, loginId: 'admin', event: 'failed', failReason: 'unknownId', ip: '45.155.205.12', userAgent: 'python-requests/2.32', createdAt: at(d(-8), 3, 41) })
  await db.loginLog.createMany({ data: loginLogs.filter((l) => (l.createdAt as Date) <= now) })
  await db.accessLog.createMany({ data: accessLogs.filter((l) => (l.createdAt as Date) <= now) })

  return {
    업체: 6,
    계정: 3,
    상품: DEMO_PRODUCTS.length,
    문의: INQUIRIES.length,
    작업이력: audits.length,
  }
}
