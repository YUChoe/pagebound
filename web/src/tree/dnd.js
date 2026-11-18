// 트리 드래그 앤 드롭 위치 계산 (spec/features/F3-tree-links.md 5절)
import { parentOf } from '../markdown/wikiPaths.js'

// 행 높이 기준 위 25% = 앞, 아래 25% = 뒤, 가운데 = 하위로
export function dropZone(offsetY, height) {
  if (offsetY < height * 0.25) return 'before'
  if (offsetY > height * 0.75) return 'after'
  return 'inside'
}

/**
 * dragPath를 target 기준 zone 위치로 옮길 때 서버 move 요청 본문.
 * siblingPaths: target과 같은 부모의 자식 경로(정렬 순). 자기 자신·자기 하위로는 null.
 */
export function moveRequest(dragPath, targetPath, zone, siblingPaths) {
  if (dragPath === targetPath || targetPath.startsWith(dragPath + '/')) return null
  if (zone === 'inside') return { parentPath: targetPath, position: null }
  const others = siblingPaths.filter((p) => p !== dragPath)
  const index = others.indexOf(targetPath)
  if (index < 0) return null
  return { parentPath: parentOf(targetPath), position: zone === 'before' ? index : index + 1 }
}
