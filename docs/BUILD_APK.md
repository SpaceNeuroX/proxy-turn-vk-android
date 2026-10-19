# Как собрать APK (GLEBSBP)

Есть два способа: **на своём ПК** и **автоматически на GitHub Actions** (без Android Studio и SDK у вас).

---

## Способ 1. Сборка на GitHub Actions (рекомендуется — ничего ставить не надо)

В репозитории уже настроены два workflow:

| Файл | Когда запускается | Результат |
|---|---|---|
| `.github/workflows/android-debug.yml` | push в `develop`, pull request, или вручную | debug-APK (архив во вкладке Actions) |
| `.github/workflows/android-release.yml` | тег вида `v1.4.4` или вручную | подписанный release-APK + GitHub Release |

### 1.1. Быстрый debug-тест за ~20 минут

1. Запушьте ветку `develop` в свой репозиторий на GitHub.
2. Откройте вкладку **Actions → Android debug APK → Run workflow**.
3. Дождитесь зелёной галочки, пролистайте страницу job'а вниз до раздела
   **Artifacts** и скачайте `app-arm64-v8a-debug`.
4. Внутри будет `app-arm64-v8a-debug.apk` — ставится на телефон как обычное приложение.

> Debug-APK можно распространять только «вручную»: он не подойдёт для Google Play
> и его нельзя обновлять поверх релизной подписи.

### 1.2. Релизный подписанный APK (то, что отдают пользователям)

**Шаг A. Один раз создать signing-ключ** (можно прямо на ПК, `keytool` входит в JDK):

```bash
./scripts/make-release-key.sh     # создаст release.jks и пропишет пароли в local.properties
```

**Шаг B. Завести секреты в репозитории GitHub**
(Settings → Secrets and variables → Actions → New repository secret):

| Имя | Значение |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | вывод команды `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | пароль от ключа |
| `ANDROID_KEY_ALIAS` | `glebsbp` |
| `ANDROID_KEY_PASSWORD` | пароль от ключа (тот же) |

**Шаг C. Выпустить версию:**

```bash
git tag v1.4.4
git push origin v1.4.4
```

CI соберёт APK для `arm64-v8a`, `armeabi-v7a`, `x86_64`, проверит подпись, посчитает
SHA-256 и сам создаст **Release** с файлами вида `glebsbp-1.4.4-arm64-v8a.apk`.
Пользователи скачивают их со страницы Releases.

⚠️ Важно:
* Тег должен совпадать с `versionName` в `app/build.gradle.kts` (CI это проверяет).
* На каждый новый релиз увеличивайте `versionCode` и `versionName`.
* Никогда не теряйте `release.jks` и пароль: без них пользователи не смогут обновляться.
* **Не коммитьте** `release.jks` и `local.properties` (они уже в `.gitignore`).

---

## Способ 2. Сборка на своём ПК

### 2.1. Что установить

1. **JDK 17** (`sudo apt install openjdk-17-jdk` или [Adoptium](https://adoptium.net)).
2. **Android Studio** — вместе с Android SDK (при первом запуске согласите установку компонентов).
3. **Go 1.24+** — нужен для сборки нативной библиотеки клиента (`scripts/build-native-libs.sh`).
4. **NDK r27** — Android Studio → SDK Manager → SDK Tools → *NDK (Side by side)* → `27.2.12479018`,
   плюс **CMake**.

Проверьте, что в `local.properties` есть путь к SDK (Android Studio пишет его сам):

```
sdk.dir=/home/ВАШ_ЮЗЕР/Android/Sdk
```

### 2.2. Debug-сборка (тестовый ключ, ставится только вам)

```bash
./gradlew :app:assembleDebug -PtargetAbis=arm64-v8a
# результат: app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Все архитектуры сразу:

```bash
./gradlew :app:assembleDebug -PtargetAbis=arm64-v8a,armeabi-v7a,x86_64
```

### 2.3. Релизная (подписанная) сборка

```bash
./scripts/make-release-key.sh          # один раз — создаёт release.jks + local.properties
./gradlew :app:assembleRelease -PtargetAbis=arm64-v8a,armeabi-v7a,x86_64
# результат: app/build/outputs/apk/release/*.apk
```

Проверить подпись:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

### 2.4. Через интерфейс Android Studio (без терминала)

1. **File → Open** → выберите корень проекта (тот, где лежит `settings.gradle.kts`).
2. Дождитесь окончания Gradle Sync.
3. **Build → Generate Signed Bundle / APK…** → выбрать **APK** → Next.
4. **Key store path** → `.../release.jks` (или *Create new…* для первого раза),
   пароли, alias `glebsbp`.
5. Выбрать вариант **release** → Finish. Готовый APK появится в
   `app/build/outputs/apk/release/`.

---

## Установка APK на телефон

* Перекиньте файл на телефон (Telegram-файлом, USB, ссылкой) и откройте его.
* Android попросит разрешить установку из неизвестных источников — разрешите.
* Если стоит версия с другой подписью — сначала удалите старое приложение.

## Типичные ошибки

| Ошибка | Решение |
|---|---|
| `SDK location not found` | создайте `local.properties` со строкой `sdk.dir=/путь/к/Android/Sdk` |
| `go: command not found`, падение задачи `buildNativeLibs` | установите Go и повторите сборку |
| `Keystore file not found: ...` | проверьте `KEYSTORE_FILE` в `local.properties` |
| Плагин Compose/Kotlin не загружается | убедитесь, что Gradle использует JDK 17 |
| CI: `test "$version" = "$gradle_version"` упал | поставьте тег, равный `versionName` из `app/build.gradle.kts` |
