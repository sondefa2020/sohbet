# Tayfa Web

Tayfa'nın tarayıcı sürümü: QR ile giriş (şifresiz), uçtan uca şifreli sohbet (Matrix + Rust şifreleme modülü), karanlık mod, Türkçe/İngilizce/Arapça.

- Geliştirme: `npm install` ve `npm run dev`
- Derleme: `npm run build` → `dist/`
- Sunucu adresi çalışma anında `config.json`'dan okunur (`{"homeserver": "https://sohbet.ornek.com"}`); kurulum betiği yazar.
- Yayın paketi: `dist/` içeriği `server/web/tayfa-web.tar.gz` olarak paketlenir; `server/kur.sh` bunu web alt alan adına kurar.
