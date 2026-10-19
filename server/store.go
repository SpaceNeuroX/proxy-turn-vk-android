package main

// ==================== GLEBSBP Store — продажа подписок в боте ====================
// Пользовательский (не админский) поток: /start -> выбор тарифа -> оплата
// (CryptoPay/Bybit/YooKassa ссылкой или Telegram Stars прямо в чате) ->
// автоматическая выдача VPN-подписки (пароль + ссылка glebsbp:// + .conf файл).

import (
	"fmt"
	"log"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ─── Тарифы (правь цены под себя) ───

type Plan struct {
	Key        string // "1m"
	Title      string // "1 месяц"
	Days       int    // 30; 0 = бессрочно
	MaxDevices int
	RubPrice   int // цена в рублях (YooKassa / звёзды считаются отсюда)
	CryptoUSDT string // цена в USDT для крипто-провайдеров
	StarsXTR   int // цена в Telegram Stars (XTR); 0 = посчитать из RubPrice*2
}

var storePlans = []Plan{
	{Key: "trial", Title: "Пробный 3 дня", Days: 3, MaxDevices: 1, RubPrice: 99, CryptoUSDT: "1.00", StarsXTR: 50},
	{Key: "1m", Title: "1 месяц", Days: 30, MaxDevices: 2, RubPrice: 199, CryptoUSDT: "2.50", StarsXTR: 150},
	{Key: "3m", Title: "3 месяца", Days: 90, MaxDevices: 3, RubPrice: 499, CryptoUSDT: "5.50", StarsXTR: 400},
	{Key: "12m", Title: "12 месяцев", Days: 365, MaxDevices: 5, RubPrice: 1499, CryptoUSDT: "15.00", StarsXTR: 1200},
	{Key: "unlim", Title: "Бессрочно", Days: 0, MaxDevices: 5, RubPrice: 2999, CryptoUSDT: "30.00", StarsXTR: 2500},
}

func findPlan(key string) *Plan {
	for i := range storePlans {
		if storePlans[i].Key == key {
			return &storePlans[i]
		}
	}
	return nil
}

// ─── Выдача подписки после оплаты ───

// deliverPaidInvoice создаёт PasswordEntry на купленный тариф и отправляет
// покупателю пароль, быструю ссылку glebsbp:// и .conf файл. Идемпотентно.
func deliverPaidInvoice(invoiceID string) {
	invoicesMutex.Lock()
	inv := invoices[invoiceID]
	if inv == nil || !inv.Paid || inv.Delivered {
		invoicesMutex.Unlock()
		return
	}
	inv.Delivered = true
	saveInvoicesLocked()
	invoicesMutex.Unlock()

	token := botTokenRef
	if token == "" {
		log.Printf("[STORE] Нет токена бота — некому выдать подписку %s", invoiceID)
		return
	}

	plan := findPlan(inv.Plan)
	days := inv.Days
	maxDevs := inv.MaxDevices
	if maxDevs <= 0 {
		maxDevs = 1
	}

	dbMutex.Lock()
	if cleanupExpiredPasswordsLocked(globalWgDev) > 0 {
		saveDB()
	}
	newPass := ""
	for i := 0; i < 10; i++ {
		candidate, err := generatePassword()
		if err != nil {
			break
		}
		if _, exists := db.Passwords[candidate]; !exists {
			newPass = candidate
			break
		}
	}
	if newPass == "" {
		dbMutex.Unlock()
		sendTelegram(token, inv.TelegramID, "❌ Не удалось создать ключ доступа. Напиши админу.", nil)
		return
	}
	if err := serverWrapKeys.AddPassword(newPass); err != nil {
		dbMutex.Unlock()
		log.Printf("[STORE] WRAP-ключ: %v", err)
		sendTelegram(token, inv.TelegramID, "❌ Ошибка сервера. Напиши админу.", nil)
		return
	}
	expiresAt := int64(0) // 0 = бессрочно
	if days > 0 {
		expiresAt = time.Now().Add(time.Duration(days) * 24 * time.Hour).Unix()
	}
	label := nextPasswordLabel()
	hash := defaultVkHash()
	db.Passwords[newPass] = &PasswordEntry{
		Label:      label,
		ExpiresAt:  expiresAt,
		MaxDevices: maxDevs,
		VkHash:     hash,
		Ports:      "56000,56001,9000",
	}
	saveDB()
	dbMutex.Unlock()

	srvIP := getPublicIP()
	nameEsc := url.QueryEscape(fmt.Sprintf("GLEBSBP - %s", label))
	link := fmt.Sprintf("glebsbp://config?name=%s&peer=%s&hashes=%s&workers=9&port=9000&pass=%s",
		nameEsc, srvIP, hash, url.QueryEscape(newPass))

	expText := "♾ Бессрочно"
	if expiresAt > 0 {
		expText = fmt.Sprintf("до %s", time.Unix(expiresAt, 0).Format("02.01.2006"))
	}
	title := "месяц"
	if plan != nil {
		title = plan.Title
	}

	txt := fmt.Sprintf(
		"✅ *Оплата получена! Подписка «%s» активирована.*\n\n"+
			"🔑 *Пароль:* `%s`\n"+
			"⏰ *Срок:* %s\n"+
			"📱 *Устройств:* %d\n\n"+
			"🚀 *Быстрое подключение (открой на телефоне):*\n`%s`\n\n"+
			"📄 Файл конфигурации — ниже 👇\n\n"+
			"📢 Наш канал: https://t.me/glebsbp\n" +
			"❓ Вопросы: напиши в бот @GLEBSPB_bot.",
		title, newPass, expText, maxDevs, link)
	sendTelegram(token, inv.TelegramID, txt, nil)

	configJSON := fmt.Sprintf(`{
  "name": "GLEBSBP - %s",
  "peer": "%s",
  "vkHashes": "%s",
  "workersPerHash": 9,
  "listenPort": 9000,
  "password": "%s"
}`, label, srvIP, hash, newPass)
	sendTelegramFile(token, inv.TelegramID, fmt.Sprintf("glebsbp_%s.conf", newPass), []byte(configJSON))

		// Кнопка «Открыть в приложении» — импорт подписки одним тапом.
		openBtn := map[string]interface{}{
			"inline_keyboard": [][]map[string]interface{}{
				{{
					"text": "📲 Открыть в приложении GLEBSBP",
					"url":  link,
				}},
			},
		}
		sendTelegram(token, inv.TelegramID,
			"👆 Нажми кнопку — Android откроет приложение GLEBSBP и импортирует подписку.\n\n"+
				"⚠️ Если приложения ещё нет — установи его из канала: https://t.me/glebsbp",
			openBtn)

	// Уведомление админу о продаже
	if adminIDStr := db.AdminID; adminIDStr != "" {
		adminID64, _ := strconv.ParseInt(adminIDStr, 10, 64)
		if adminID64 != 0 {
			sendTelegram(token, adminID64, fmt.Sprintf(
				"💰 *Продажа!* TG=`%d` тариф=*%s* (%s %s) инвойс=`%s`",
				inv.TelegramID, title, inv.Amount, inv.Currency, inv.ID), nil)
		}
	}
	log.Printf("[STORE] Подписка выдана: инвойс=%s tg=%d план=%s", inv.ID, inv.TelegramID, inv.Plan)
}

func defaultVkHash() string {
	// VK-хеш сервера по умолчанию; задаётся переменной окружения GLEBSBP_VK_HASH
	if h := os.Getenv("GLEBSBP_VK_HASH"); h != "" {
		return h
	}
	return "779127897"
}

// ─── Пользовательский диалог магазина (внутри botLoop обрабатывается storeLoop) ───

type storeState struct {
	waitingPlan map[int64]string // chatID -> ожидаемое действие
}

var storeSess = storeState{waitingPlan: map[int64]string{}}

func storeMenuKeyboard() map[string]interface{} {
	var rows [][]map[string]interface{}
	var row []map[string]interface{}
	for _, p := range storePlans {
		row = append(row, map[string]interface{}{
			"text":          fmt.Sprintf("%s — %d₽", p.Title, p.RubPrice),
			"callback_data": "buy_" + p.Key,
		})
		if len(row) == 2 {
			rows = append(rows, row)
			row = nil
		}
	}
	if row != nil {
		rows = append(rows, row)
	}
	rows = append(rows, []map[string]interface{}{
		{"text": "🪙 Оплатить криптой", "callback_data": "paycrypto_"},
		{"text": "🆓 Пробный период", "callback_data": "buy_trial"},
	})
	return map[string]interface{}{"inline_keyboard": rows}
}

func sendStoreWelcome(token string, chatID int64) {
	txt := "🛡 *GLEBSBP VPN* — быстрый и анонимный интернет.\n\n" +
		"Выбери тариф и подключись за 1 минуту ⤵️\n\n" +
		"🟢 Без логов\n🟢 Обфускация трафика\n🟢 До 5 устройств\n\n" +
		"Оплата: 💳 карта (YooKassa), 🪙 крипта (USDT), ⭐ Telegram Stars.\n\n" +
		"📢 Канал и приложение: https://t.me/glebsbp"
	sendTelegram(token, chatID, txt, storeMenuKeyboard())
}

// handleStoreCallback обрабатывает callback'и пользователя (не админа).
// Возвращает true, если callback наш.
func handleStoreCallback(token string, chatID int64, callbackID, data string, msgChat bool) bool {
	answerCallback(token, callbackID)

	if data == "buymenu" {
		sendStoreWelcome(token, chatID)
		return true
	}

	if strings.HasPrefix(data, "buy_") {
		key := strings.TrimPrefix(data, "buy_")
		plan := findPlan(key)
		if plan == nil {
			sendTelegram(token, chatID, "❌ Тариф не найден", nil)
			return true
		}
		offerStarsPayment(token, chatID, plan)
		return true
	}

	if data == "paycrypto_" || strings.HasPrefix(data, "paycrypto2_") {
		key := strings.TrimPrefix(data, "paycrypto2_")
		if key == "" || strings.HasPrefix(data, "paycrypto_") {
			key = lastPlanOf(chatID)
		}
		plan := findPlan(key)
		if plan == nil {
			sendTelegram(token, chatID, "Сначала выбери тариф: /buy", nil)
			return true
		}
		setLastPlan(chatID, plan.Key)
		inv, err := createSubscriptionInvoice(chatID, plan.Key, plan.Days, plan.MaxDevices, "USDT", plan.CryptoUSDT)
		if err != nil {
			log.Printf("[STORE] cryptopay invoice: %v", err)
			sendTelegram(token, chatID, "❌ Крипто-оплата временно недоступна. Попробуй Stars или напиши админу.", nil)
			return true
		}
		txt := fmt.Sprintf("🪙 *Оплата криптой (USDT)* — тариф %s.\n\n"+
			"Сумма: `%s USDT`\n\n"+
			"[Нажмите для оплаты](%s)\n\n"+
			"После оплаты подписка придёт сюда автоматически (до 10 минут).",
			plan.Title, plan.CryptoUSDT, inv.PayURL)
		sendTelegram(token, chatID, txt, nil)
		return true
	}

	if strings.HasPrefix(data, "paycard_") {
		key := strings.TrimPrefix(data, "paycard_")
		plan := findPlan(key)
		if plan == nil {
			sendTelegram(token, chatID, "Сначала выбери тариф: /buy", nil)
			return true
		}
		amount := strconv.Itoa(plan.RubPrice)
		inv, err := createSubscriptionInvoice(chatID, plan.Key, plan.Days, plan.MaxDevices, "RUB", amount)
		if err != nil {
			log.Printf("[STORE] yookassa invoice: %v", err)
			sendTelegram(token, chatID, "❌ Карточная оплата временно недоступна. Попробуй Stars/крипту.", nil)
			return true
		}
		txt := fmt.Sprintf("💳 *Оплата картой* — тариф %s.\n\nСумма: `%s ₽`\n\n[Нажмите для оплаты](%s)",
			plan.Title, amount, inv.PayURL)
		sendTelegram(token, chatID, txt, nil)
		return true
	}

	return false
}

// ─── Telegram Stars: выставить счёт прямо в чате через Bot API sendInvoice ───

func offerStarsPayment(token string, chatID int64, plan *Plan) {
	setLastPlan(chatID, plan.Key)
	paymentCfgMu.RLock()
	starsEnabled := paymentCfg.Stars.Enabled
	ykEnabled := paymentCfg.YooKassa.Enabled
	cpEnabled := paymentCfg.CryptoPay.Enabled
	paymentCfgMu.RUnlock()

	xtr := plan.StarsXTR
	if xtr <= 0 {
		xtr = plan.RubPrice * 2
	}

	if starsEnabled {
		// Реальный Stars-платёж: Bot API сам покажет кнопку "Pay ⭐".
		go sendStarsInvoice(token, chatID, plan, xtr)
		return
	}

	// Если Stars выключен — предлагаем доступные альтернативы
	var rows [][]map[string]interface{}
	if cpEnabled {
		rows = append(rows, []map[string]interface{}{
			{"text": "🪅 Оплатить USDT", "callback_data": "paycrypto2_" + plan.Key},
		})
	}
	if ykEnabled {
		rows = append(rows, []map[string]interface{}{
			{"text": fmt.Sprintf("💳 Карта — %d₽", plan.RubPrice), "callback_data": "paycard_" + plan.Key},
		})
	}
	if len(rows) == 0 {
		sendTelegram(token, chatID, "❌ Платежные системы не настроены. Напиши админу.", nil)
		return
	}
	txt := fmt.Sprintf("🛡 Тариф *%s*\n\n• Срок: %s\n• Устройств: %d\n• Цена: %d₽ (~%d⭐)\n\nВыбери способ оплаты ⤵️",
		plan.Title, planDaysText(plan.Days), plan.MaxDevices, plan.RubPrice, xtr)
	sendTelegram(token, chatID, txt, map[string]interface{}{"inline_keyboard": rows})
}

func planDaysText(days int) string {
	if days == 0 {
		return "бессрочно ♾"
	}
	return fmt.Sprintf("%d дн.", days)
}

func sendStarsInvoice(token string, chatID int64, plan *Plan, xtr int) {
	inv := &PaymentInvoice{
		ID:            newInvoiceID(),
		TelegramID:    chatID,
		Plan:          plan.Key,
		Days:          plan.Days,
		MaxDevices:    plan.MaxDevices,
		Currency:      "XTR",
		Amount:        strconv.Itoa(xtr),
		Provider:      "stars",
		CreatedAt:     time.Now().Unix(),
		ExpiresAtUnix: time.Now().Add(24 * time.Hour).Unix(),
	}
	invoicesMutex.Lock()
	invoices[inv.ID] = inv
	saveInvoicesLocked()
	invoicesMutex.Unlock()

	payload := map[string]interface{}{
		"chat_id":                  chatID,
		"currency":                 "XTR",
		"description":              fmt.Sprintf("GLEBSBP VPN — %s", plan.Title),
		"payload":                  inv.ID,
		"provider_token":           "",
		"allow_sending_without_reply": true,
		"invoice": map[string]interface{}{
			"title":                "GLEBSBP VPN",
			"description":          fmt.Sprintf("Подписка %s (%s)", plan.Title, planDaysText(plan.Days)),
			"currency":             "XTR",
			"prices":               []map[string]interface{}{{"label": "GLEBSBP VPN", "amount": xtr}},
		},
	}
	var res struct {
		Ok bool `json:"ok"`
	}
	urlApi := fmt.Sprintf("https://api.telegram.org/bot%s/sendInvoice", token)
	if err := payHTTP.postJSON(urlApi, nil, payload, &res); err != nil {
		// fallback: внешние провайдеры
		fallbackToExternalPayment(token, chatID, plan)
	}
}

func fallbackToExternalPayment(token string, chatID int64, plan *Plan) {
	inv, err := createSubscriptionInvoice(chatID, plan.Key, plan.Days, plan.MaxDevices, "USDT", plan.CryptoUSDT)
	if err != nil {
		sendTelegram(token, chatID, "❌ Оплата недоступна. Напиши админу.", nil)
		return
	}
	sendTelegram(token, chatID, fmt.Sprintf("🪙 Оплата USDT: [%s](%s)", plan.Title, inv.PayURL), nil)
}

// ConfirmStarPayment вызывается из botLoop при successful_payment (Stars).
func confirmStarPayment(token string, successfulPayload string) {
	invoicesMutex.Lock()
	inv := invoices[successfulPayload]
	if inv != nil && !inv.Paid {
		inv.Paid = true
		saveInvoicesLocked()
	}
	invoicesMutex.Unlock()
	if inv != nil {
		log.Printf("[STORE] ⭐ Stars-оплата подтверждена: %s tg=%d", inv.ID, inv.TelegramID)
		go deliverPaidInvoice(inv.ID)
	}
}

// ─── Память последнего выбранного тарифа на чат ───

var lastPlanByChat sync.Map

func setLastPlan(chatID int64, key string) { lastPlanByChat.Store(chatID, key) }
func lastPlanOf(chatID int64) string {
	if v, ok := lastPlanByChat.Load(chatID); ok {
		s, _ := v.(string)
		return s
	}
	return ""
}
