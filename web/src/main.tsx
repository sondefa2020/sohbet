import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import "./styles.css";

// Şifreleme modülü (WASM) bazı sunucularda yanlış içerik türüyle gelebilir; o zaman akış
// yerine bayt bayt yüklenir. Böylece web sunucusu ayarından bağımsız çalışır.
const streaming = WebAssembly.instantiateStreaming?.bind(WebAssembly);
if (streaming) {
  WebAssembly.instantiateStreaming = async (source, imports) => {
    const response = await source;
    try {
      return await streaming(response.clone(), imports);
    } catch {
      return WebAssembly.instantiate(await response.arrayBuffer(), imports);
    }
  };
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
