<script setup>
import { computed } from 'vue'
import { encodePath } from '../markdown/wikiPaths.js'

const props = defineProps({
  node: { type: Object, required: true },
  current: { type: String, default: '' },
  expanded: { type: Set, required: true },
  forceOpen: { type: Boolean, default: false },
})
const emit = defineEmits(['toggle'])
const open = computed(() => props.forceOpen || props.expanded.has(props.node.path))
</script>

<template>
  <li class="tree-node">
    <div class="tree-row" :class="{ active: node.path === current }">
      <button v-if="node.childCount" type="button" class="tree-caret" @click="emit('toggle', node.path)">
        {{ open ? '▾' : '▸' }}
      </button>
      <span v-else class="tree-caret" />
      <router-link :to="'/w/' + encodePath(node.path)" :title="node.path">{{ node.title }}</router-link>
    </div>
    <ul v-if="open && node.children.length" class="tree-list">
      <TreeNode v-for="c in node.children" :key="c.id" :node="c" :current="current" :expanded="expanded"
                :force-open="forceOpen" @toggle="(p) => emit('toggle', p)" />
    </ul>
  </li>
</template>
