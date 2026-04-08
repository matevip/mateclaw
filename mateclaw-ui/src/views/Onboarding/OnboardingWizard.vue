<template>
  <div class="onboarding-overlay">
    <div class="onboarding-card">
      <!-- Step indicator -->
      <div class="step-indicator">
        <span
          v-for="s in steps"
          :key="s"
          class="step-dot"
          :class="{ active: s === step }"
        ></span>
      </div>

      <!-- Header -->
      <div class="onboarding-header">
        <h2 class="onboarding-title">{{ t('onboarding.title') }}</h2>
        <p class="onboarding-subtitle">{{ t('onboarding.subtitle') }}</p>
      </div>

      <!-- Step labels -->
      <div class="step-labels">
        <span
          v-for="(s, i) in steps"
          :key="s"
          class="step-label"
          :class="{ active: s === step }"
        >{{ i + 1 }}. {{ t(`onboarding.step${s.charAt(0).toUpperCase() + s.slice(1)}`) }}</span>
      </div>

      <!-- Step content -->
      <div class="step-content">
        <StepPathSelect
          v-if="step === 'path'"
          :ollama-online="ollamaOnline"
          @select="onPathSelect"
        />
        <StepConfigure
          v-else-if="step === 'configure'"
          :path="selectedPath"
          @done="step = 'verify'"
        />
        <StepVerify
          v-else-if="step === 'verify'"
          @complete="onComplete"
        />
      </div>

      <!-- Footer -->
      <div class="onboarding-footer">
        <button
          v-if="step !== 'path'"
          class="btn-back"
          @click="goBack"
        >{{ t('onboarding.back') }}</button>
        <div class="footer-spacer"></div>
        <button class="btn-skip" @click="onSkip">{{ t('onboarding.skip') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { http, setupApi } from '@/api/index'
import StepPathSelect from './StepPathSelect.vue'
import StepConfigure from './StepConfigure.vue'
import StepVerify from './StepVerify.vue'

const { t } = useI18n()
const emit = defineEmits<{ (e: 'close'): void }>()

const steps = ['path', 'configure', 'verify'] as const
const step = ref<'path' | 'configure' | 'verify'>('path')
const selectedPath = ref<'local' | 'cloud'>('local')
const ollamaOnline = ref(false)

onMounted(async () => {
  try {
    const res: any = await setupApi.onboardingStatus()
    const data = res?.data || res
    ollamaOnline.value = !!data?.ollamaOnline
  } catch {
    ollamaOnline.value = false
  }
})

function onPathSelect(path: 'local' | 'cloud') {
  selectedPath.value = path
  step.value = 'configure'
}

function goBack() {
  if (step.value === 'verify') {
    step.value = 'configure'
  } else if (step.value === 'configure') {
    step.value = 'path'
  }
}

function onSkip() {
  localStorage.setItem('mc-onboarding-done', 'true')
  emit('close')
}

function onComplete() {
  localStorage.setItem('mc-onboarding-done', 'true')
  emit('close')
}
</script>

<style scoped>
.onboarding-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
}

.onboarding-card {
  background: var(--mc-bg-elevated);
  border-radius: 16px;
  border: 1px solid var(--mc-border);
  max-width: 600px;
  width: 90%;
  max-height: 90vh;
  overflow-y: auto;
  padding: 32px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}

.step-indicator {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-bottom: 24px;
}

.step-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--mc-border);
  transition: background 0.2s ease;
}

.step-dot.active {
  background: var(--mc-primary);
}

.onboarding-header {
  text-align: center;
  margin-bottom: 16px;
}

.onboarding-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 0 0 6px 0;
}

.onboarding-subtitle {
  font-size: 14px;
  color: var(--mc-text-secondary);
  margin: 0;
}

.step-labels {
  display: flex;
  justify-content: center;
  gap: 20px;
  margin-bottom: 24px;
}

.step-label {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  transition: color 0.2s ease;
}

.step-label.active {
  color: var(--mc-primary);
  font-weight: 600;
}

.step-content {
  min-height: 200px;
}

.onboarding-footer {
  display: flex;
  align-items: center;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--mc-border-light);
}

.footer-spacer {
  flex: 1;
}

.btn-back {
  padding: 8px 16px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg);
  color: var(--mc-text-secondary);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-back:hover {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
}

.btn-skip {
  padding: 8px 16px;
  border: none;
  background: none;
  color: var(--mc-text-tertiary);
  font-size: 13px;
  cursor: pointer;
  transition: color 0.15s ease;
}

.btn-skip:hover {
  color: var(--mc-text-secondary);
}
</style>
