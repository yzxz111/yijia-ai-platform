/// <reference types="vite/client" />

declare module 'vue' {
  export interface GlobalComponents {
    TopErrorMessage: typeof import('./components/feedback/TopErrorMessage.vue')['default']
  }
}

export {}
