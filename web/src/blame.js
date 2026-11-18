// 행별 blame 표시 데이터 (spec/features/F13-blame.md 3절)

// hunks [{start, count, version}] + 본문 → 줄 목록 [{no, text, version, first}] (first: 묶음 첫 줄)
export function blameRows(content, hunks) {
  const lines = content.split('\n')
  const rows = []
  for (const h of hunks) {
    for (let i = 0; i < h.count; i++) {
      const no = h.start + i
      rows.push({ no, text: lines[no - 1] ?? '', version: h.version, first: i === 0 })
    }
  }
  return rows
}

// 버전별 나이 단계 0(가장 오래됨)~1(가장 최근): 작성 시각 순위 기준
export function ageLevels(revisions) {
  const versions = Object.keys(revisions).map(Number)
    .sort((a, b) => (revisions[a].createdAt ?? '').localeCompare(revisions[b].createdAt ?? '') || a - b)
  const levels = {}
  versions.forEach((v, i) => { levels[v] = versions.length > 1 ? i / (versions.length - 1) : 1 })
  return levels
}

export const SOURCE_LABELS = { web: '웹', mcp: '에이전트', api: 'API', system: '시스템', migration: '이전' }
