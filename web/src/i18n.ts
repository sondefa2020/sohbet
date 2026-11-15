// Basit çeviri: anahtar Türkçe metnin kendisidir; çeviri yoksa Türkçe gösterilir
// (telefondaki uygulamayla aynı yaklaşım).

export type Lang = "tr" | "en" | "ar";

const EN: Record<string, string> = {
  "Tayfa'yı bilgisayarında kullan": "Use Tayfa on your computer",
  "Şifre gerekmez. Telefonundaki Tayfa ile bu kodu okut; sohbetlerin uçtan uca şifreli olarak burada açılır.":
    "No password needed. Scan this code with Tayfa on your phone; your chats open here, end-to-end encrypted.",
  "Telefonunda Tayfa'yı aç.": "Open Tayfa on your phone.",
  "Profil › Web'e bağlan bölümüne gir.": "Go to Profile › Link web.",
  "Kodu okut ya da altındaki 6 haneli kodu yaz, sonra telefonda Onayla'ya dokun.":
    "Scan the code or type the 6-digit code below, then tap Approve on your phone.",
  "Bu oturum yalnızca bu tarayıcıda açılır. Ortak bir bilgisayardaysan işin bitince çıkış yap.":
    "This session opens only in this browser. On a shared computer, sign out when you are done.",
  "ya da telefonda bu kodu yaz": "or type this code on your phone",
  "Telefonun bekleniyor": "Waiting for your phone",
  "kod %s içinde yenilenir": "code refreshes in %s",
  "Yeni kod al": "Get a new code",
  "Kod alınamadı. İnternet bağlantını kontrol et.": "Couldn't get a code. Check your connection.",
  "Onaylandı, giriş yapılıyor…": "Approved, signing in…",
  "Giriş yapılamadı. Yeni kod al ve tekrar dene.": "Sign-in failed. Get a new code and try again.",
  "Dil": "Language",
  "Sohbetler": "Chats",
  "Ara": "Search",
  "Sohbetlerde ara": "Search chats",
  "Henüz sohbet yok.": "No chats yet.",
  "Eşleşen sohbet yok.": "No matching chats.",
  "Sohbetler yükleniyor…": "Loading chats…",
  "Bir sohbet seç": "Select a chat",
  "Mesajlar uçtan uca şifreli. Tayfa dahil kimse okuyamaz.": "Messages are end-to-end encrypted. Nobody, not even Tayfa, can read them.",
  "Mesaj yaz": "Write a message",
  "Mesaj": "Message",
  "Gönder": "Send",
  "Dosya ekle": "Attach file",
  "Çevrimiçi": "Online",
  "şifreli": "encrypted",
  "Bugün": "Today",
  "Dün": "Yesterday",
  "Fotoğraf": "Photo",
  "Video": "Video",
  "Sesli mesaj": "Voice message",
  "Dosya": "File",
  "Konum": "Location",
  "Anket": "Poll",
  "Titreşim gönderdi": "Sent a nudge",
  "Efekt gönderdi": "Sent an effect",
  "Bu mesaj silindi": "This message was deleted",
  "Şifreli mesaj — okumak için yedek anahtarını gir": "Encrypted message — enter your backup key to read it",
  "Gönderilemedi": "Not sent",
  "Gönderiliyor…": "Sending…",
  "Daha eski mesajlar": "Older messages",
  "Ayarlar": "Settings",
  "Karanlık mod": "Dark mode",
  "Çıkış yap": "Sign out",
  "Eski mesajları aç": "Unlock old messages",
  "Yedek anahtarı": "Backup key",
  "Eski şifreli mesajları bu tarayıcıda okumak için telefonda oluşturduğun yedek anahtarını yaz.":
    "To read older encrypted messages in this browser, enter the backup key you created on your phone.",
  "Anahtar doğru değil. Boşlukları dahil, kaydettiğin gibi yaz.": "That key isn't right. Type it exactly as you saved it, spaces included.",
  "Mesajlar açılıyor…": "Unlocking messages…",
  "%s mesaj anahtarı geri yüklendi.": "%s message keys restored.",
  "Şimdi değil": "Not now",
  "Aç": "Unlock",
  "Kapat": "Close",
  "Bağlantı koptu, yeniden bağlanılıyor…": "Connection lost, reconnecting…",
  "Oturumun kapandı. Lütfen yeniden giriş yap.": "Your session ended. Please sign in again.",
  "Yükleniyor…": "Loading…",
  "Dosya çok büyük (en fazla 100 MB).": "File is too large (max 100 MB).",
  "Geri": "Back",
  "Gizli sohbet": "Secret chat",
  "Grup": "Group",
  "kişi": "members",
  "İndir": "Download",
  "yazıyor…": "is typing…",
  "Ana menü": "Main menu",
  "Sohbet listesi": "Chat list",
  "Açık sohbet": "Open chat",
  "Masaüstü bildirimleri": "Desktop notifications",
  "Mesaj sesi": "Message sound",
  "Eski mesajlar kilitli": "Older messages are locked",
  "Okumak için yedek anahtarını gir": "Enter your backup key to read them",
  "Okundu": "Read",
  "Gönderildi": "Sent",
  "Göndermek için bırak": "Drop to send",
  "Yeni mesaj": "New message",
};

const AR: Record<string, string> = {
  "Tayfa'yı bilgisayarında kullan": "استخدم Tayfa على حاسوبك",
  "Şifre gerekmez. Telefonundaki Tayfa ile bu kodu okut; sohbetlerin uçtan uca şifreli olarak burada açılır.":
    "لا حاجة لكلمة مرور. امسح هذا الرمز بتطبيق Tayfa على هاتفك؛ ستُفتح محادثاتك هنا مشفرة من طرف إلى طرف.",
  "Telefonunda Tayfa'yı aç.": "افتح Tayfa على هاتفك.",
  "Profil › Web'e bağlan bölümüne gir.": "اذهب إلى الملف الشخصي › ربط الويب.",
  "Kodu okut ya da altındaki 6 haneli kodu yaz, sonra telefonda Onayla'ya dokun.":
    "امسح الرمز أو اكتب الرمز المكون من 6 أرقام، ثم اضغط موافقة على هاتفك.",
  "Bu oturum yalnızca bu tarayıcıda açılır. Ortak bir bilgisayardaysan işin bitince çıkış yap.":
    "تُفتح هذه الجلسة في هذا المتصفح فقط. إذا كنت على حاسوب مشترك فسجّل الخروج عند الانتهاء.",
  "ya da telefonda bu kodu yaz": "أو اكتب هذا الرمز على هاتفك",
  "Telefonun bekleniyor": "بانتظار هاتفك",
  "kod %s içinde yenilenir": "يتجدد الرمز خلال %s",
  "Yeni kod al": "احصل على رمز جديد",
  "Kod alınamadı. İnternet bağlantını kontrol et.": "تعذر الحصول على رمز. تحقق من الاتصال.",
  "Onaylandı, giriş yapılıyor…": "تمت الموافقة، جارٍ تسجيل الدخول…",
  "Giriş yapılamadı. Yeni kod al ve tekrar dene.": "تعذر تسجيل الدخول. احصل على رمز جديد وحاول مرة أخرى.",
  "Dil": "اللغة",
  "Sohbetler": "المحادثات",
  "Ara": "بحث",
  "Sohbetlerde ara": "ابحث في المحادثات",
  "Henüz sohbet yok.": "لا توجد محادثات بعد.",
  "Eşleşen sohbet yok.": "لا توجد محادثات مطابقة.",
  "Sohbetler yükleniyor…": "جارٍ تحميل المحادثات…",
  "Bir sohbet seç": "اختر محادثة",
  "Mesajlar uçtan uca şifreli. Tayfa dahil kimse okuyamaz.": "الرسائل مشفرة من طرف إلى طرف. لا يستطيع أحد قراءتها، ولا حتى Tayfa.",
  "Mesaj yaz": "اكتب رسالة",
  "Mesaj": "رسالة",
  "Gönder": "إرسال",
  "Dosya ekle": "إرفاق ملف",
  "Çevrimiçi": "متصل",
  "şifreli": "مشفرة",
  "Bugün": "اليوم",
  "Dün": "أمس",
  "Fotoğraf": "صورة",
  "Video": "فيديو",
  "Sesli mesaj": "رسالة صوتية",
  "Dosya": "ملف",
  "Konum": "موقع",
  "Anket": "استطلاع",
  "Titreşim gönderdi": "أرسل اهتزازًا",
  "Efekt gönderdi": "أرسل تأثيرًا",
  "Bu mesaj silindi": "تم حذف هذه الرسالة",
  "Şifreli mesaj — okumak için yedek anahtarını gir": "رسالة مشفرة — أدخل مفتاح النسخ الاحتياطي لقراءتها",
  "Gönderilemedi": "لم تُرسل",
  "Gönderiliyor…": "جارٍ الإرسال…",
  "Daha eski mesajlar": "رسائل أقدم",
  "Ayarlar": "الإعدادات",
  "Karanlık mod": "الوضع الداكن",
  "Çıkış yap": "تسجيل الخروج",
  "Eski mesajları aç": "فتح الرسائل القديمة",
  "Yedek anahtarı": "مفتاح النسخ الاحتياطي",
  "Eski şifreli mesajları bu tarayıcıda okumak için telefonda oluşturduğun yedek anahtarını yaz.":
    "لقراءة الرسائل المشفرة القديمة في هذا المتصفح، اكتب مفتاح النسخ الاحتياطي الذي أنشأته على هاتفك.",
  "Anahtar doğru değil. Boşlukları dahil, kaydettiğin gibi yaz.": "المفتاح غير صحيح. اكتبه كما حفظته تمامًا مع المسافات.",
  "Mesajlar açılıyor…": "جارٍ فتح الرسائل…",
  "%s mesaj anahtarı geri yüklendi.": "تمت استعادة %s من مفاتيح الرسائل.",
  "Şimdi değil": "ليس الآن",
  "Aç": "فتح",
  "Kapat": "إغلاق",
  "Bağlantı koptu, yeniden bağlanılıyor…": "انقطع الاتصال، جارٍ إعادة الاتصال…",
  "Oturumun kapandı. Lütfen yeniden giriş yap.": "انتهت جلستك. يرجى تسجيل الدخول مرة أخرى.",
  "Yükleniyor…": "جارٍ التحميل…",
  "Dosya çok büyük (en fazla 100 MB).": "الملف كبير جدًا (الحد الأقصى 100 ميغابايت).",
  "Geri": "رجوع",
  "Gizli sohbet": "محادثة سرية",
  "Grup": "مجموعة",
  "kişi": "أعضاء",
  "İndir": "تنزيل",
  "yazıyor…": "يكتب…",
  "Ana menü": "القائمة الرئيسية",
  "Sohbet listesi": "قائمة المحادثات",
  "Açık sohbet": "المحادثة المفتوحة",
  "Masaüstü bildirimleri": "إشعارات سطح المكتب",
  "Mesaj sesi": "صوت الرسائل",
  "Eski mesajlar kilitli": "الرسائل القديمة مقفلة",
  "Okumak için yedek anahtarını gir": "أدخل مفتاح النسخ الاحتياطي لقراءتها",
  "Okundu": "مقروءة",
  "Gönderildi": "مرسلة",
  "Göndermek için bırak": "أفلت للإرسال",
  "Yeni mesaj": "رسالة جديدة",
};

let current: Lang = (localStorage.getItem("tayfa_dil") as Lang) || "tr";

export function getLang(): Lang {
  return current;
}

export function setLang(lang: Lang) {
  current = lang;
  localStorage.setItem("tayfa_dil", lang);
  document.documentElement.lang = lang;
  document.documentElement.dir = lang === "ar" ? "rtl" : "ltr";
}

/** Saat ve tarih biçimi için yerel ayar (Türkçe: 19:02). */
export function locale(): string {
  return current === "en" ? "en-GB" : current === "ar" ? "ar" : "tr-TR";
}

export function t(text: string): string {
  if (current === "en") return EN[text] ?? text;
  if (current === "ar") return AR[text] ?? text;
  return text;
}

/** Biçimli çeviri: "%s" yerine değerler konur. */
export function tf(text: string, ...args: (string | number)[]): string {
  let out = t(text);
  for (const a of args) out = out.replace("%s", String(a));
  return out;
}

setLang(current);
