# Bekçi

Eski bir Android telefonu ev kamerasına çevirir. Hareket olunca yanındaki telefona fotoğraflı bildirim gelir; istediğinde evi canlı izler, ortam sesini dinlersin. Aynı uygulama iki telefona da kurulur.

## GitHub’da derleme

1. GitHub’da yeni bir depo (repository) oluştur.
2. Bu klasörün içindekileri depoya yükle. `.github/workflows/build.yml` dosyası da gitmeli; bilgisayar gizli klasörü göstermiyorsa GitHub’da “Add file → Create new file” ile adı `.github/workflows/build.yml` yaz ve içeriğini yapıştır.
3. Yükleme bitince derleme kendiliğinden başlar (Actions sekmesi). Birkaç dakika sürer.
4. Bitince depo sayfasında sağdaki “Releases” bölümünde `bekci.apk` çıkar. İki telefonda da bu sayfayı aç, APK’yı indir ve kur. Android “bilinmeyen kaynak” izni ister, ver.

## Kullanım

- Evde kalacak telefonda “QR kodu göster”e dokun. Yanındaki telefonda “QR kodu tara”ya dokunup bu kodu okut. (İstersen kodu elle de yazabilirsin.)
- Evdeki telefonda “Bu telefon kamera olsun”a dokun, şarja tak.
- Yanındaki telefonda “Bu telefon uyarı alsın”a dokun.
- İki telefonda da “Pil kısıtlamasını kaldır”a dokun.
- Kamera başlayınca diğer telefona “Kamera izlemeye başladı” bildirimi gelir; eşleştirme çalışıyor demektir.

## Gizlilik

Bildirimler ücretsiz ntfy.sh sunucusu üzerinden gider. Konu adı ve şifreleme anahtarı eşleştirme kodundan üretilir; fotoğraflar telefondan çıkmadan önce şifrelenir, sunucu yalnızca okunamaz veri görür. Kodu kimseyle paylaşma.

## Güncelleme

2.1 ve sonraki sürümler sabit bir imzayla derlenir; yeni sürüm eskisinin üstüne kurulur. (İmza anahtarı deponun gizli ayarlarında, KEYSTORE_B64 ve KEYSTORE_PASSWORD olarak durur.)
