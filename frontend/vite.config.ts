import { defineConfig, loadEnv } from 'vite';
import { devtools } from '@tanstack/devtools-vite';
import viteReact from '@vitejs/plugin-react';
import basicSsl from '@vitejs/plugin-basic-ssl';
import tailwindcss from '@tailwindcss/vite';
import { tanstackRouter } from '@tanstack/router-plugin/vite';

function isPackage(id: string, pkg: string): boolean {
  return id.includes(`/node_modules/${pkg}/`) || id.includes(`\\node_modules\\${pkg}\\`);
}

const devEnv = loadEnv('development', process.cwd(), '');
const devServerUrl = devEnv.DEV_SERVER_URL;
const devHttps = devEnv.DEV_HTTPS === 'true';
// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    ...(devHttps ? [basicSsl()] : []),
    devtools(),
    tanstackRouter({
      target: 'react',
      autoCodeSplitting: true,
    }),
    viteReact(),
    tailwindcss(),
  ],
  resolve: {
    tsconfigPaths: true,
  },
  server: devServerUrl
    ? {
        // The forwarded headers give the server the scheme and the port of the page.
        proxy: {
          '/proxy': { target: devServerUrl, xfwd: true },
          '/config.js': { target: devServerUrl, xfwd: true },
          '/fhir': { target: devServerUrl, xfwd: true },
          '/auth': { target: devServerUrl, xfwd: true },
        },
      }
    : undefined,
  build: {
    rolldownOptions: {
      output: {
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return;
          if (isPackage(id, 'react') || isPackage(id, 'react-dom')) return 'react';
          if (
            isPackage(id, '@tanstack/react-query') ||
            isPackage(id, '@tanstack/react-router') ||
            isPackage(id, '@tanstack/react-table')
          ) {
            return 'tanstack';
          }
          if (isPackage(id, 'radix-ui')) return 'radix';
        },
      },
    },
  },
});
