# GLEBSBP

**GLEBSBP — Система Быстрого Подключения.** Android-приложение и сервер для быстрого подключения к собственному VPS через TURN-инфраструктуру звонков VK.

Приложение создаёт системный VPN-туннель на телефоне, передаёт зашифрованный трафик через VK TURN и выпускает его в интернет через ваш сервер. Для сети такое соединение похоже на медиатрафик звонка WebRTC, а не на обычное прямое подключение к VPN-серверу.

## Быстрый старт

1. Скачайте APK в разделе [Releases](https://github.com/GLEBSBP/GLEBSBP/releases).
2. Добавьте VPS на вкладке «Серверы» и выполните установку серверной части.
3. Создайте профиль подключения или импортируйте готовую ссылку (`glebsbp://config...`).
4. Добавьте хеш звонка VK.
5. Нажмите «Подключить» и разрешите Android создать VPN-соединение.

## Сборки

- стабильные подписанные версии публикуются в [GitHub Releases](https://github.com/GLEBSBP/GLEBSBP/releases);
- тестовые debug-сборки ветки `develop` доступны в [GitHub Actions](https://github.com/GLEBSBP/GLEBSBP/actions/workflows/android-debug.yml).

## Telegram-бот и оплата

Серверная часть включает встроенного Telegram-бота управления доступами (`/new`, `/list`, `/pay`):

- выдача временных паролей и конфиков прямо в чат бота;
- **CryptoPay (crypto.bot / CryptoBot API)** — оплата подписок криптовалютой;
- **ЮKassa** — оплата картами и СБП;
- **Telegram Stars** — оплата звёздами через Bot Payments API.

Настройка платежей — в файле `payments.json` в директории `/etc/glebsbp` (см. `server/payments.example.json`).

## Обсуждение и поддержка

- [Канал GLEBSBP в Telegram](https://t.me/glebsbp_channel)
- [Бот поддержки и покупки доступа](https://t.me/glebsbp_bot)

## Лицензия

Проект распространяется по лицензии [GNU GPL v3](LICENSE).
