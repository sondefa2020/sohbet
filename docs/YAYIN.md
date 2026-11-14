# Tayfa yayın sürümü (müşteriye / mağazaya verilecek APK)

Şu an indirdiğin `tayfa-debug-apk` bir **test sürümü**: depoda duran, herkese açık bir test anahtarıyla imzalı.
Kendi telefonunda denemek için uygundur. Başkalarına dağıtmadan önce **yalnızca senin elinde duran gizli bir anahtarla**
imzalanmış yayın sürümüne geçmelisin. Bu anahtarı kaybedersen uygulamayı bir daha güncelleyemezsin; o yüzden iki ayrı
güvenli yerde sakla. Anahtarı ve şifresini kimseye (Claude dahil) gönderme.

## 1. Anahtarı oluştur (bir kez)

Sunucuna bağlan ve şunları çalıştır:

```
apt-get install -y openjdk-17-jre-headless
keytool -genkeypair -keystore tayfa-yayin.jks -alias bayx -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Tayfa"
```

İki kez şifre sorar. En az 12 karakterlik güçlü bir şifre seç ve not et.

## 2. GitHub'a gizli olarak ekle

```
base64 -w0 tayfa-yayin.jks > tayfa-yayin.b64
cat tayfa-yayin.b64
```

Çıkan uzun satırın tamamını kopyala. Sonra tarayıcıda:

1. https://github.com/sondefa2020/sohbet/settings/secrets/actions adresini aç.
2. **New repository secret** › Ad: `BAYX_KEYSTORE_B64` › Değer: kopyaladığın satır › **Add secret**.
3. Tekrar **New repository secret** › Ad: `BAYX_KEYSTORE_PASS` › Değer: 1. adımdaki şifre › **Add secret**.

## 3. Anahtarı sakla, sunucudan sil

`tayfa-yayin.jks` dosyasını ve şifresini iki ayrı güvenli yerde sakla (örneğin şifreli bir USB bellek ve bir şifre
yöneticisi). Sonra sunucudaki kopyaları sil:

```
shred -u tayfa-yayin.b64 tayfa-yayin.jks
```

## 4. Sonuç

Bundan sonraki her derlemede Actions sayfasında **iki** APK çıkar:

- `tayfa-debug-apk`: test sürümü (eskisi gibi, telefonundakinin üstüne kurulur).
- `tayfa-release-apk`: yayın sürümü. Müşterilere ve mağazaya bunu ver.

Yayın sürümü farklı anahtarla imzalı olduğu için test sürümünün üstüne kurulmaz. Kendi telefonunda yayın sürümüne
geçersen bir kez kaldırıp kurman gerekir; girişten sonra **mesaj yedek anahtarını** girersen eski mesajların geri gelir.
