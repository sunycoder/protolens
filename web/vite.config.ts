import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
const backend =
  loadEnv("development", ".", "PROTOLENS_").PROTOLENS_BACKEND_ORIGIN ||
  "http://127.0.0.1:18766";
export default defineConfig({
  plugins: [
    vue(),
    {
      name: "protolens-development-readiness",
      configureServer(server) {
        server.middlewares.use(
          "/__packet_analyzer_dev__",
          (_request, response) => {
            response.setHeader(
              "Content-Type",
              "application/json; charset=utf-8",
            );
            response.end(
              JSON.stringify({
                service: "protolens-vite",
                root: server.config.root.replaceAll("\\", "/"),
                backend,
              }),
            );
          },
        );
      },
    },
  ],
  server: {
    port: 5174,
    strictPort: true,
    proxy: { "/api": backend },
  },
  build: { chunkSizeWarningLimit: 1500 },
});
