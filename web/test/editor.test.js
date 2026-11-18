import { describe, expect, it } from 'vitest'
import { fileMarkdown, pasteName, placeholder } from '../src/editor/paste.js'
import { diffLines } from '../src/editor/diff.js'

describe('붙여넣기 규칙', () => {
  const at = new Date(2026, 10, 18, 9, 5, 7)

  it('클립보드 스크린샷(image.png)은 paste-타임스탬프, 일반 파일은 원래 이름', () => {
    expect(pasteName({ name: 'image.png', type: 'image/png' }, at)).toBe('paste-20261118-090507.png')
    expect(pasteName({ name: '', type: 'image/jpeg' }, at)).toBe('paste-20261118-090507.jpg')
    expect(pasteName({ name: '설계서.pdf', type: 'application/pdf' }, at)).toBe('설계서.pdf')
  })

  it('플레이스홀더와 최종 마크다운', () => {
    expect(placeholder('a.png', true)).toBe('![업로드 중 a.png…]()')
    expect(placeholder('a.pdf', false)).toBe('[업로드 중 a.pdf…]()')
    expect(fileMarkdown({ filename: '화면 1.png', url: '/p/3/files/%ED%99%94%EB%A9%B4%201.png' }, true))
      .toBe('![화면 1.png](/p/3/files/%ED%99%94%EB%A9%B4%201.png)')
  })
})

describe('줄 diff', () => {
  it('서버에만 있는 줄은 del, 내 쪽에만 있는 줄은 add', () => {
    expect(diffLines('a\nb\nc', 'a\nx\nc')).toEqual([
      { type: 'same', text: 'a' },
      { type: 'del', text: 'b' },
      { type: 'add', text: 'x' },
      { type: 'same', text: 'c' },
    ])
  })
})
