<template>
  <div class="trust-bar" :title="`${t('memory.facts.trust')}: ${(trust * 100).toFixed(0)}%`">
    <div class="trust-bar__fill" :style="{ width: (trust * 100) + '%' }" :class="trustLevel" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
const props = defineProps<{ trust: number }>()
const trustLevel = computed(() => {
  if (props.trust >= 0.7) return 'high'
  if (props.trust >= 0.4) return 'mid'
  return 'low'
})
</script>

<style scoped>
.trust-bar {
  width: 48px; height: 4px; border-radius: 2px;
  background: var(--mc-border-light); overflow: hidden; flex-shrink: 0;
}
.trust-bar__fill {
  height: 100%; border-radius: 2px; transition: width 0.3s ease;
}
.trust-bar__fill.high { background: #34c759; }
.trust-bar__fill.mid { background: #ff9f0a; }
.trust-bar__fill.low { background: #ff3b30; }
</style>
