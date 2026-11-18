// 코드블록 info string 파싱 (spec/features/F9-code-blocks.md 2절)
// 예: java title="UserService.java" collapsed {3,5-7} start=120 nolinenums
// 첫 토큰이 속성 형식이면 언어 미지정

const TOKEN = /(\w+)="([^"]*)"|\{[^}]*\}|\S+/g

function isAttribute(token) {
  return token.includes('=') || token.startsWith('{') || token === 'collapsed' || token === 'nolinenums'
}

export function parseInfo(info) {
  const result = { lang: '', title: '', collapsed: false, lineNumbers: true, start: 1, highlight: '' }
  const tokens = (info ?? '').trim().match(TOKEN) ?? []
  tokens.forEach((token, i) => {
    if (i === 0 && !isAttribute(token)) {
      result.lang = token.toLowerCase()
      return
    }
    const kv = /^(\w+)="([^"]*)"$/.exec(token) ?? /^(\w+)=(\S+)$/.exec(token)
    if (kv) {
      if (kv[1] === 'title') result.title = kv[2]
      if (kv[1] === 'start' && /^\d+$/.test(kv[2])) result.start = Number(kv[2])
    } else if (token === 'collapsed') {
      result.collapsed = true
    } else if (token === 'nolinenums') {
      result.lineNumbers = false
    } else if (token.startsWith('{')) {
      result.highlight = token.slice(1, -1)
    }
  })
  return result
}
