import { defineConfig } from 'vite';

// The built HUD is served by the Spring core itself, so it lands in its static resources.
export default defineConfig({
  base: '/',
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    assetsInlineLimit: 0,      // no data: URIs for scripts; keeps the CSP strict
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
});
