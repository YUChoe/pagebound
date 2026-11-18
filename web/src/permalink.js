// 영구 링크 (spec/features/F5-page-view.md 2.1절): 문서 id 기반이라 이동 후에도 유효.
// 외부 주소는 설정 site.base-url, 비어 있으면 현재 접속 주소
import { session } from './session.js'

export function permalinkUrl(page) {
  return `${session.baseUrl || window.location.origin}/p/${page.id}`
}
