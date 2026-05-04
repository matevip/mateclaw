<template>
  <span
    class="skill-icon"
    :class="[`skill-icon--${parsed.kind}`, sizeClass]"
    :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.7)}px` }"
    :title="title"
  >
    <!-- Pixelart: inlined SVG inherits color via fill="currentColor". -->
    <span
      v-if="parsed.kind === 'pixelart' && parsed.svg"
      class="skill-icon__svg"
      v-html="parsed.svg"
    />
    <!-- Pixelart name we don't recognize (icon was renamed across
         pixelarticons versions, or string is malformed). Show a
         neutral placeholder so the layout doesn't shift. -->
    <span
      v-else-if="parsed.kind === 'pixelart'"
      class="skill-icon__missing"
      :title="`pi:${parsed.name}（未找到）`"
    >?</span>
    <img
      v-else-if="parsed.kind === 'url'"
      class="skill-icon__img"
      :src="parsed.url"
      :alt="title || ''"
      loading="lazy"
    />
    <span
      v-else-if="parsed.kind === 'emoji'"
      class="skill-icon__glyph"
    >{{ parsed.value }}</span>
    <span
      v-else
      class="skill-icon__fallback"
      aria-hidden="true"
    >{{ fallback }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { parseIconValue } from '@/composables/usePixelarticons'

const props = withDefaults(defineProps<{
  /** Persisted icon string (emoji glyph / `pi:name` / http(s) URL). */
  value: string | null | undefined
  /** Icon side length in pixels. Drives both font-size and box. */
  size?: number
  /** Glyph shown when value is empty. */
  fallback?: string
  /** Tooltip / a11y label. */
  title?: string
}>(), {
  size: 20,
  fallback: '🛠️',
  title: undefined,
})

const parsed = computed(() => parseIconValue(props.value))

/** Coarse size bucket so callers can target with CSS instead of inline
 *  styles when they need to (eg. card hover transforms). */
const sizeClass = computed(() => {
  if (props.size <= 16) return 'skill-icon--xs'
  if (props.size <= 22) return 'skill-icon--sm'
  if (props.size <= 32) return 'skill-icon--md'
  return 'skill-icon--lg'
})
</script>

<style scoped>
.skill-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
  flex-shrink: 0;
  /* Inherit color so parents (e.g. an agent card avatar) can tint the
   * pixelart SVG via `currentColor`. Without parental color, the icon
   * still picks up text-primary through normal CSS inheritance. */
  color: inherit;
}

/* Pixelart SVGs ship with viewBox=0 0 24 24 + fill=currentColor — they
 * scale crisply at any integer multiple. We force display:block on the
 * inner svg so the wrapper's flex centering works. */
.skill-icon__svg {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
}
.skill-icon__svg :deep(svg) {
  width: 100%;
  height: 100%;
  display: block;
  /* image-rendering: pixelated keeps the chunky pixel-art aesthetic
   * even when the box is rendered at non-integer device pixels. */
  image-rendering: pixelated;
  shape-rendering: crispEdges;
}

.skill-icon__img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}

.skill-icon__glyph,
.skill-icon__fallback {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
}

.skill-icon__missing {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  border-radius: 6px;
  background: rgba(123, 88, 67, 0.10);
  color: var(--mc-text-tertiary);
  font-size: 0.7em;
  font-weight: 700;
}
:global(html.dark .skill-icon__missing) {
  background: rgba(255, 255, 255, 0.08);
}
</style>
