// 백오피스 화면 언어 6개 (FE i18n/routing.ts 와 같은 목록). 데모 번역을 언어마다 만드는 데만 쓴다
export const LOCALES = ['ko', 'en', 'ja', 'zh-CN', 'zh-TW', 'vi'] as const
export type Locale = (typeof LOCALES)[number]
