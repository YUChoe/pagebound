import { ref } from 'vue'

// 문서 생성·이동·삭제 후 트리를 다시 불러오기 위한 신호
export const treeVersion = ref(0)

export function bumpTree() {
  treeVersion.value++
}
