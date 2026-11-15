// Yalnızca yerel uçtan uca test için: Synapse ve Tayfa servisini aynı adres altında birleştirir.
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const proxy = {
  "/_matrix": { target: "http://127.0.0.1:18108", changeOrigin: true },
  "/bayx": { target: "http://127.0.0.1:18030", changeOrigin: true },
};

export default defineConfig({ plugins: [react()], preview: { port: 4173, strictPort: true, proxy }, server: { proxy } });
