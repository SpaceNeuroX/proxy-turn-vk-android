#!/usr/bin/env bash
#
# Создать (или показать существующий) signing-ключ для релизной сборки APK.
#
# Использование:
#   ./scripts/make-release-key.sh                      # создаст release.jks в корне проекта
#   ./scripts/make-release-key.sh ~/keys/glebsbp.jks   # по своему пути
#
# Пароль: если не задан через GLEBSBP_KEYPASS, будет запрошен интерактивно.
# НЕ коммитьте .jks файл и пароли в git!
set -euo pipefail

cd "$(dirname "$0")/.."

KEYSTORE="${1:-release.jks}"
ALIAS="glebsbp"
DAYS=10000
VALIDITY_YEAR=$(date -d "+27 years" +%Y 2>/dev/null || echo 2053)

if [ -f "$KEYSTORE" ]; then
    echo "Ключ уже существует: $KEYSTORE"
    echo "Используйте тот же пароль, что задавали при создании."
else
    if [ -z "${GLEBSBP_KEYPASS:-}" ]; then
        read -rsp "Придумайте пароль для ключа (не забудьте его): " GLEBSBP_KEYPASS; echo
        read -rsp "Повторите пароль:                       " GLEBSBP_KEYPASS2; echo
        [ "$GLEBSBP_KEYPASS" = "$GLEBSBP_KEYPASS2" ] || { echo "Пароли не совпадают"; exit 1; }
    fi

    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 \
        -validity $DAYS \
        -storepass "$GLEBSBP_KEYPASS" \
        -keypass "$GLEBSBP_KEYPASS" \
        -dname "CN=GLEBSBP, OU=Mobile, O=GLEBSBP, L=Internet, ST=None, C=RU"

    chmod 600 "$KEYSTORE"
    echo "Создан ключ: $KEYSTORE (alias: $ALIAS)"
fi

# Прописать пути/пароли в local.properties (он в .gitignore)
PROPS="local.properties"
touch "$PROPS"
for k in KEYSTORE_FILE KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD ANDROID_HOME; do
    grep -v "^$k=" "$PROPS" > "$PROPS.tmp" 2>/dev/null || true
    mv "$PROPS.tmp" "$PROPS" 2>/dev/null || true
done

# Если ключ уже существовал и пароль не был введён — спросить его сейчас,
# чтобы корректно заполнить local.properties.
if [ -z "${GLEBSBP_KEYPASS:-}" ]; then
    read -rsp "Введите пароль от существующего ключа $KEYSTORE: " GLEBSBP_KEYPASS; echo
fi

ABS_KEY="$(cd "$(dirname "$KEYSTORE")" && pwd)/$(basename "$KEYSTORE")"
{
    echo "KEYSTORE_FILE=$ABS_KEY"
    echo "KEY_ALIAS=$ALIAS"
    echo "KEYSTORE_PASSWORD=$GLEBSBP_KEYPASS"
    echo "KEY_PASSWORD=$GLEBSBP_KEYPASS"
} >> "$PROPS"

echo ""
echo "Готово. В файл $PROPS (он в .gitignore) записаны:"
echo "  KEYSTORE_FILE=$ABS_KEY"
echo "  KEY_ALIAS=$ALIAS"
echo "  KEYSTORE_PASSWORD / KEY_PASSWORD — сохранены."
echo ""
echo "Сборка подписанного релиза:"
echo "  ./gradlew :app:assembleRelease -PtargetAbis=arm64-v8a,armeabi-v7a,x86_64"
echo ""
echo "APK появится в: app/build/outputs/apk/release/"
