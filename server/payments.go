package main

// ==================== Платёжные провайдеры GLEBSBP ====================
// Поддерживаются: CryptoPay (crypto.pay), Bybit Crypto Pay, YooKassa, Telegram Stars.
// Все платежи проходят через единый интерфейс paymentProvider:
//   CreateInvoice -> пользователь платит -> Webhook/опрос -> ConfirmPaid -> выдача подписки.

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ─── Конфиг платежных систем (заполняется из payments.json) ───

type PaymentConfig struct {
	Secret     string `json:"secret,omitempty"` // секрет подписи webhook-а (любой провайдер)
	PublicBase string `json:"public_base,omitempty"`
	CryptoPay  struct {
		Enabled bool   `json:"enabled"`
		Token   string `json:"token"`
	} `json:"cryptopay"`
	Bybit struct {
		Enabled    bool   `json:"enabled"`
		MerchantID string `json:"merchant_id"`
		APIKey     string `json:"api_key"`
		APISecret  string `json:"api_secret"`
	} `json:"bybit"`
	YooKassa struct {
		Enabled       bool   `json:"enabled"`
		ShopID        string `json:"shop_id"`
		SecretKey     string `json:"secret_key"`
		DefaultAmount int64  `json:"default_amount_kopecks,omitempty"` // копейки
	} `json:"yookassa"`
	Stars struct {
		Enabled bool `json:"enabled"`
	} `json:"stars"`
}

var (
	paymentCfg   PaymentConfig
	paymentCfgMu sync.RWMutex
	botTokenRef  string // токен бота (нужен для Bot API Payments)
)

func loadPaymentConfig(dir string) {
	path := dir + "/payments.json"
	data, err := readFileIfAny(path)
	if err != nil || len(data) == 0 {
		log.Printf("[PAY] Конфиг платежей не найден (%s) — приём платежей выключен", path)
		return
	}
	var cfg PaymentConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
		log.Printf("[PAY] Ошибка чтения %s: %v", path, err)
		return
	}
	paymentCfgMu.Lock()
	paymentCfg = cfg
	paymentCfgMu.Unlock()
	log.Printf("[PAY] Конфиг загружен: CryptoPay=%v Bybit=%v YooKassa=%v Stars=%v",
		cfg.CryptoPay.Enabled, cfg.Bybit.Enabled, cfg.YooKassa.Enabled, cfg.Stars.Enabled)
}

func readFileIfAny(path string) ([]byte, error) {
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	return data, err
}

func writeFileAtomic(path string, data []byte) {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".inv-*.tmp")
	if err != nil {
		log.Printf("[PAY] tmp: %v", err)
		return
	}
	name := tmp.Name()
	defer os.Remove(name)
	if _, err := tmp.Write(data); err == nil {
		err = tmp.Sync()
	}
	tmp.Close()
	if err == nil {
		_ = os.Chmod(name, 0600)
		err = os.Rename(name, path)
	}
	if err != nil {
		log.Printf("[PAY] сохранение %s: %v", path, err)
	}
}

func anyPaymentEnabled() bool {
	paymentCfgMu.RLock()
	defer paymentCfgMu.RUnlock()
	return paymentCfg.CryptoPay.Enabled || paymentCfg.Bybit.Enabled ||
		paymentCfg.YooKassa.Enabled || paymentCfg.Stars.Enabled
}

func publicBaseURL() string {
	paymentCfgMu.RLock()
	base := strings.TrimRight(paymentCfg.PublicBase, "/")
	paymentCfgMu.RUnlock()
	if base != "" {
		return base
	}
	return "http://" + getPublicIP() + ":56000"
}

// ─── Хранилище счетов (инвойсов) ───

type PaymentInvoice struct {
	ID           string    `json:"id"`
	Provider     string    `json:"provider"` // cryptopay | bybit | yookassa | stars
	TelegramID   int64     `json:"telegram_id"`
	Plan         string    `json:"plan"`  // trial|1m|3m|12m|unlim
	Days         int       `json:"days"`  // срок подписки в днях (0 = бессрочно)
	MaxDevices   int       `json:"max_devices"`
	Currency     string    `json:"currency"`
	Amount       string    `json:"amount"` // сумма в валюте провайдера (USDT / RUB / XTR)
	Paid         bool      `json:"paid"`
	Delivered    bool      `json:"delivered"` // подписка уже выдана
	PayURL       string    `json:"pay_url"`
	Payload      string    `json:"payload"`
	CreatedAt    int64     `json:"created_at"`
	ExpiresAtUnix int64    `json:"expires_at"`
}

var (
	invoices      = map[string]*PaymentInvoice{}
	invoicesMutex sync.Mutex
	invoicesFile  string
)

func initInvoicesStore(dir string) {
	invoicesFile = dir + "/invoices.json"
	data, err := readFileIfAny(invoicesFile)
	if err == nil && len(data) > 0 {
		var list []*PaymentInvoice
		if err := json.Unmarshal(data, &list); err == nil {
			for _, inv := range list {
				invoices[inv.ID] = inv
			}
			log.Printf("[PAY] Восстановлено инвойсов: %d", len(list))
		}
	}
}

func saveInvoicesLocked() {
	list := make([]*PaymentInvoice, 0, len(invoices))
	for _, inv := range invoices {
		list = append(list, inv)
	}
	data, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return
	}
	writeFileAtomic(invoicesFile, data)
}

func newInvoiceID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// ─── HTTP хелперы ───

type apiClient struct {
	http *http.Client
}

var payHTTP = &apiClient{http: &http.Client{Timeout: 15 * time.Second}}

func (c *apiClient) postJSON(url string, headers map[string]string, payload interface{}, out interface{}) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequest("POST", url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(data))
	}
	if out != nil {
		return json.Unmarshal(data, out)
	}
	return nil
}

func (c *apiClient) getJSON(url string, headers map[string]string, out interface{}) error {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return err
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(data))
	}
	if out != nil {
		return json.Unmarshal(data, out)
	}
	return nil
}

// ─── Провайдер: CryptoPay (https://crypto-pay.eu) ───

func createInvoiceCryptoPay(inv *PaymentInvoice) error {
	paymentCfgMu.RLock()
	token := paymentCfg.CryptoPay.Token
	paymentCfgMu.RUnlock()
	payload := map[string]interface{}{
		"currency":         "USDT",
		"assets":           []string{"USDT"},
		"amount":           inv.Amount,
		"description":      fmt.Sprintf("GLEBSBP VPN — подписка %s", inv.Plan),
		"payload":          inv.ID,
		"expires_in":       3600,
		"allow_comments":   false,
		"allow_nft":        false,
		"callback_url":     publicBaseURL() + "/api/payment/webhook/cryptopay",
		"success_redirect": "https://t.me/",
	}
	var res struct {
		OK   bool `json:"ok"`
		Result struct {
			ID       string `json:"id"`
			BotUsername string `json:"bot_username"`
			PayLink  string `json:"pay_link"`
			InvoiceLink string `json:"invoice_link"`
		} `json:"result"`
	}
	url := fmt.Sprintf("https://crypto-pay.eu/api/v1/invoices/create/%s", token)
	if err := payHTTP.postJSON(url, nil, payload, &res); err != nil {
		return err
	}
	link := res.Result.PayLink
	if link == "" {
		link = res.Result.InvoiceLink
	}
	if link == "" {
		return fmt.Errorf("cryptopay: пустая ссылка оплаты")
	}
	inv.Provider = "cryptopay"
	inv.Payload = strconv.FormatInt(0, 10)
	setExternalInvoiceID(inv, res.Result.ID)
	inv.PayURL = link
	return nil
}

func checkInvoiceCryptoPay(inv *PaymentInvoice) (bool, error) {
	paymentCfgMu.RLock()
	token := paymentCfg.CryptoPay.Token
	paymentCfgMu.RUnlock()
	var res struct {
		OK     bool `json:"ok"`
		Result struct {
			Status string `json:"status"` // active | paid | canceled
		} `json:"result"`
	}
	extID := externalInvoiceID(inv)
	url := fmt.Sprintf("https://crypto-pay.eu/api/v1/invoices/get/%s?id=%s", token, extID)
	if err := payHTTP.getJSON(url, nil, &res); err != nil {
		return false, err
	}
	return res.Result.Status == "paid", nil
}

// ─── Провайдер: Bybit Crypto Pay ───

func bybitSign(secret, query string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(query))
	return hex.EncodeToString(mac.Sum(nil))
}

func createInvoiceBybit(inv *PaymentInvoice) error {
	paymentCfgMu.RLock()
	merchantID := paymentCfg.Bybit.MerchantID
	apiKey := paymentCfg.Bybit.APIKey
	apiSecret := paymentCfg.Bybit.APISecret
	paymentCfgMu.RUnlock()

	rubAmt, _ := strconv.ParseFloat(inv.Amount, 64)
	usdtAmt := fmt.Sprintf("%.2f", rubAmt/90.0) // грубый пересчёт RUB→USDT; при USDT-тарифах Amount уже в USDT
	if inv.Currency == "USDT" {
		usdtAmt = inv.Amount
	}
	params := map[string]string{
		"merchantId":     merchantID,
		"orderAmount":    usdtAmt,
		"currency":       "USDT",
		"successTime":    strconv.FormatInt(time.Now().Add(time.Hour).UnixMilli(), 10),
		"referenceOrderId": inv.ID,
		"description":    fmt.Sprintf("GLEBSBP VPN %s", inv.Plan),
	}
	query := signParamsBybit(params, apiKey, apiSecret)
	var res struct {
		RetCode int    `json:"retCode"`
		RetMsg  string `json:"retMsg"`
		Result  struct {
			PayTradeSN string `json:"payTradeSN"`
			PayURL     string `json:"payURL"`
		} `json:"result"`
	}
	if err := payHTTP.postJSON("https://api.bybit.com/v2/btc-pay/invoice-order/create?"+query,
		map[string]string{"X-BtcPay-Access-Key": apiKey}, params, &res); err != nil {
		return err
	}
	if res.RetCode != 0 {
		return fmt.Errorf("bybit: %s", res.RetMsg)
	}
	inv.Provider = "bybit"
	setExternalInvoiceID(inv, res.Result.PayTradeSN)
	inv.PayURL = res.Result.PayURL
	return nil
}

func signParamsBybit(params map[string]string, apiKey, apiSecret string) string {
	keys := make([]string, 0, len(params)+3)
	for k := range params {
		keys = append(keys, k)
	}
	keys = append(keys, "apiKey", "timestamp", "nonce")
	// сортировка ключей
	for i := 1; i < len(keys); i++ {
		for j := i; j > 0 && keys[j] < keys[j-1]; j-- {
			keys[j], keys[j-1] = keys[j-1], keys[j]
		}
	}
	var sb strings.Builder
	ts := strconv.FormatInt(time.Now().UnixMilli(), 10)
	nonce := ts
	for _, k := range keys {
		v := params[k]
		switch k {
		case "apiKey":
			v = apiKey
		case "timestamp":
			v = ts
		case "nonce":
			v = nonce
		}
		sb.WriteString(k)
		sb.WriteString("=")
		sb.WriteString(v)
		sb.WriteString("&")
	}
	q := strings.TrimSuffix(sb.String(), "&")
	return q + "&signature=" + bybitSign(apiSecret, q)
}

func checkInvoiceBybit(inv *PaymentInvoice) (bool, error) {
	paymentCfgMu.RLock()
	apiKey := paymentCfg.Bybit.APIKey
	apiSecret := paymentCfg.Bybit.APISecret
	paymentCfgMu.RUnlock()
	params := map[string]string{"referenceOrderId": inv.ID}
	query := signBybitSimple(params, apiKey, apiSecret)
	var res struct {
		RetCode int `json:"retCode"`
		Result  struct {
			Status string `json:"status"` // Paid | Pending | Canceled
		} `json:"result"`
	}
	url := "https://api.bybit.com/v2/btc-pay/invoice-order/query?" + query
	if err := payHTTP.getJSON(url, map[string]string{"X-BtcPay-Access-Key": apiKey}, &res); err != nil {
		return false, err
	}
	return strings.EqualFold(res.Result.Status, "Paid"), nil
}

func signBybitSimple(params map[string]string, apiKey, apiSecret string) string {
	var sb strings.Builder
	ts := strconv.FormatInt(time.Now().UnixMilli(), 10)
	nonce := ts
	for k, v := range params {
		sb.WriteString(k)
		sb.WriteString("=")
		sb.WriteString(v)
		sb.WriteString("&")
	}
	sb.WriteString("apiKey=" + apiKey + "&timestamp=" + ts + "&nonce=" + nonce)
	q := sb.String()
	return q + "&signature=" + bybitSign(apiSecret, q)
}

// ─── Провайдер: YooKassa ───

func createInvoiceYooKassa(inv *PaymentInvoice) error {
	paymentCfgMu.RLock()
	shopID := paymentCfg.YooKassa.ShopID
	secret := paymentCfg.YooKassa.SecretKey
	paymentCfgMu.RUnlock()

	payload := map[string]interface{}{
		"amount": map[string]interface{}{
			"value":     inv.Amount,
			"currency":  "RUB",
		},
		"capture":             true,
		"confirmation":        map[string]interface{}{"type": "redirect", "return_url": "https://t.me/"},
		"description":         fmt.Sprintf("GLEBSBP VPN — подписка %s", inv.Plan),
		"metadata":            map[string]string{"invoice_id": inv.ID, "tg": strconv.FormatInt(inv.TelegramID, 10)},
		"expires_at":          time.Now().Add(time.Hour).UTC().Format("2006-01-02T15:04:05Z"),
	}
	var res struct {
		ID           string `json:"id"`
		Status       string `json:"status"`
		Confirmation struct {
			ConfirmationURL string `json:"confirmation_url"`
		} `json:"confirmation"`
	}
	auth := basicAuthHeader(shopID, secret)
	err := payHTTP.postJSON("https://api.yookassa.ru/v3/payments",
		map[string]string{"Authorization": auth, "Idempotence-Key": inv.ID}, payload, &res)
	if err != nil {
		return err
	}
	inv.Provider = "yookassa"
	setExternalInvoiceID(inv, res.ID)
	inv.PayURL = res.Confirmation.ConfirmationURL
	return nil
}

func checkInvoiceYooKassa(inv *PaymentInvoice) (bool, error) {
	paymentCfgMu.RLock()
	shopID := paymentCfg.YooKassa.ShopID
	secret := paymentCfg.YooKassa.SecretKey
	paymentCfgMu.RUnlock()
	var res struct {
		Status string `json:"status"` // succeeded | pending | canceled
	}
	url := "https://api.yookassa.ru/v3/payments/" + externalInvoiceID(inv)
	err := payHTTP.getJSON(url, map[string]string{"Authorization": basicAuthHeader(shopID, secret)}, &res)
	if err != nil {
		return false, err
	}
	return res.Status == "succeeded", nil
}

func basicAuthHeader(user, pass string) string {
	const b64alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
	src := []byte(user + ":" + pass)
	var sb strings.Builder
	for i := 0; i < len(src); i += 3 {
		var n uint32
		rem := len(src) - i
		if rem > 3 {
			rem = 3
		}
		for j := 0; j < 3; j++ {
			n <<= 8
			if j < rem {
				n |= uint32(src[i+j])
			}
		}
		for j := 0; j < 4; j++ {
			if j <= rem {
				sb.WriteByte(b64alpha[(n>>uint(18-j*6))&0x3F])
			} else {
				sb.WriteByte('=')
			}
		}
	}
	return "Basic " + sb.String()
}

// ─── Провайдер: Telegram Stars (pre_checkout -> sendInvoice -> successful_payment) ───

func createInvoiceStars(inv *PaymentInvoice) (*StarsInvoiceInfo, error) {
	paymentCfgMu.RLock()
	enabled := paymentCfg.Stars.Enabled
	paymentCfgMu.RUnlock()
	if !enabled {
		return nil, fmt.Errorf("Telegram Stars отключён")
	}
	rubAmt, _ := strconv.ParseFloat(inv.Amount, 64)
	stars := int(rubAmt * 2) // ~0.5 RUB за звезду; точный курс задаётся в тарифах StarsXTR
	if inv.Currency == "XTR" {
		stars, _ = strconv.Atoi(inv.Amount)
	}
	return &StarsInvoiceInfo{
		Title:       "GLEBSBP VPN",
		Description: fmt.Sprintf("Подписка %s", inv.Plan),
		Payload:     inv.ID,
		Stars:       stars,
	}, nil
}

type StarsInvoiceInfo struct {
	Title       string
	Description string
	Payload     string
	Stars       int
}

// ─── Единая точка создания инвойса ───

func createSubscriptionInvoice(telegramID int64, planName string, days, maxDevs int, currency, amount string) (*PaymentInvoice, error) {
	inv := &PaymentInvoice{
		ID:          newInvoiceID(),
		TelegramID:  telegramID,
		Plan:        planName,
		Days:        days,
		MaxDevices:  maxDevs,
		Currency:    currency,
		Amount:      amount,
		CreatedAt:   time.Now().Unix(),
		ExpiresAtUnix: time.Now().Add(time.Hour).Unix(),
	}
	paymentCfgMu.RLock()
	cp, bb, yk, st := paymentCfg.CryptoPay.Enabled, paymentCfg.Bybit.Enabled, paymentCfg.YooKassa.Enabled, paymentCfg.Stars.Enabled
	paymentCfgMu.RUnlock()

	var err error
	switch {
	case cp:
		err = createInvoiceCryptoPay(inv)
	case bb:
		err = createInvoiceBybit(inv)
	case yk:
		err = createInvoiceYooKassa(inv)
	case st:
		// Stars не создаёт внешний инвойс — оплата идёт через Bot API прямо в чате
	default:
		return nil, fmt.Errorf("ни одна платёжная система не настроена")
	}
	if err != nil {
		return nil, err
	}
	invoicesMutex.Lock()
	invoices[inv.ID] = inv
	saveInvoicesLocked()
	invoicesMutex.Unlock()
	return inv, nil
}

// ─── Webhooks ───

func verifyWebhookSignature(provider string, body []byte, signature string) bool {
	paymentCfgMu.RLock()
	secret := paymentCfg.Secret
	paymentCfgMu.RUnlock()
	if secret == "" {
		return false
	}
	switch provider {
	case "cryptopay":
		want := hmacSHA256Hex([]byte(secret), body)
		return subtle.ConstantTimeCompare([]byte(want), []byte(strings.TrimSpace(signature))) == 1
	case "yookassa":
		// YooKassa проверяется запросом к API (checkInvoiceYooKassa), подпись не обязательна
		return true
	default:
		return false
	}
}

func hmacSHA256Hex(key, data []byte) string {
	mac := hmac.New(sha256.New, key)
	mac.Write(data)
	return hex.EncodeToString(mac.Sum(nil))
}

func registerPaymentRoutes(mux *http.ServeMux, wgDev interface{ Close() error }) {
	_ = wgDev
	mux.HandleFunc("/api/payment/webhook/cryptopay", handleWebhookCryptoPay)
	mux.HandleFunc("/api/payment/webhook/yookassa", handleWebhookYooKassa)
	mux.HandleFunc("/api/payment/status/", handlePaymentStatus)
}

func handleWebhookCryptoPay(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	sig := r.Header.Get("X-Pay-Signature")
	if !verifyWebhookSignature("cryptopay", body, sig) {
		http.Error(w, "bad signature", http.StatusUnauthorized)
		return
	}
	var evt struct {
		Type    string `json:"type"` // INVOICE_PAID
		Payload struct {
			ID      string `json:"id"`
			Status  string `json:"status"`
			Payload string `json:"payload"`
		} `json:"payload"`
	}
	if err := json.Unmarshal(body, &evt); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return
	}
	w.WriteHeader(http.StatusOK)
	if evt.Type == "INVOICE_PAID" || evt.Payload.Status == "paid" {
		markInvoicePaidByExternalOrPayload(evt.Payload.ID, evt.Payload.Payload)
	}
}

func handleWebhookYooKassa(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	w.WriteHeader(http.StatusOK)
	var evt struct {
		Type    string `json:"event"`
		Object  struct {
			ID       string            `json:"id"`
			Status   string            `json:"status"`
			Metadata map[string]string `json:"metadata"`
		} `json:"object"`
	}
	if err := json.Unmarshal(body, &evt); err != nil {
		return
	}
	if evt.Type == "payment.succeeded" || evt.Object.Status == "succeeded" {
		invID := evt.Object.Metadata["invoice_id"]
		if invID == "" {
			invID = evt.Object.ID
		}
		markInvoicePaidByExternalOrPayload(evt.Object.ID, invID)
	}
}

func handlePaymentStatus(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/api/payment/status/")
	invoicesMutex.Lock()
	inv := invoices[id]
	invoicesMutex.Unlock()
	if inv == nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	out, _ := json.Marshal(map[string]interface{}{
		"id": inv.ID, "paid": inv.Paid, "delivered": inv.Delivered, "plan": inv.Plan,
	})
	w.Header().Set("Content-Type", "application/json")
	w.Write(out)
}

func markInvoicePaidByExternalOrPayload(extID, payload string) {
	invoicesMutex.Lock()
	var target *PaymentInvoice
	for _, inv := range invoices {
		if (extID != "" && externalInvoiceID(inv) == extID) || (payload != "" && inv.ID == payload) {
			target = inv
			break
		}
	}
	if target != nil && !target.Paid {
		target.Paid = true
		saveInvoicesLocked()
	}
	invoicesMutex.Unlock()
	if target != nil && target.Paid {
		go deliverPaidInvoice(target.ID)
	}
}

// ─── Учёт внешних ID инвойсов без изменения структуры БД-файла ───

var extInvoiceIDs sync.Map // invoice.ID -> external id

func setExternalInvoiceID(inv *PaymentInvoice, extID string) {
	extInvoiceIDs.Store(inv.ID, extID)
}

func externalInvoiceID(inv *PaymentInvoice) string {
	if v, ok := extInvoiceIDs.Load(inv.ID); ok {
		s, _ := v.(string)
		return s
	}
	return ""
}

// ─── Периодическая проверка статуса (polling как fallback для webhook-ов) ───

func paymentJanitorLoop() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		now := time.Now().Unix()
		invoicesMutex.Lock()
		var pending []*PaymentInvoice
		for _, inv := range invoices {
			if inv.Paid || now > inv.ExpiresAtUnix+86400*7 {
				continue
			}
			pending = append(pending, inv)
		}
		invoicesMutex.Unlock()

		for _, inv := range pending {
			var paid bool
			var err error
			switch inv.Provider {
			case "cryptopay":
				paid, err = checkInvoiceCryptoPay(inv)
			case "bybit":
				paid, err = checkInvoiceBybit(inv)
			case "yookassa":
				paid, err = checkInvoiceYooKassa(inv)
			default:
				continue
			}
			if err != nil {
				log.Printf("[PAY] Проверка %s (%s): %v", inv.ID, inv.Provider, err)
				continue
			}
			if paid {
				log.Printf("[PAY] Оплата подтверждена: инвойс %s, TG=%d", inv.ID, inv.TelegramID)
				invoicesMutex.Lock()
				inv.Paid = true
				saveInvoicesLocked()
				invoicesMutex.Unlock()
				deliverPaidInvoice(inv.ID)
			}
		}
	}
}
