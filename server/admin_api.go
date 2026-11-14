package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

var adminTokenHash [32]byte
var adminTokenReady bool
var adminTokenMu sync.RWMutex

type adminAuthAttempt struct {
	count       int
	windowStart time.Time
	blockedTill time.Time
}

var adminAuthMu sync.Mutex
var adminAuthAttempts = map[string]adminAuthAttempt{}

func setAdminAPIToken(token string) {
	adminTokenMu.Lock()
	adminTokenHash = sha256.Sum256([]byte(token))
	adminTokenReady = token != ""
	adminTokenMu.Unlock()
}

func adminAuthorized(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	now := time.Now()
	adminAuthMu.Lock()
	attempt := adminAuthAttempts[host]
	if now.Before(attempt.blockedTill) {
		adminAuthMu.Unlock()
		return false
	}
	adminAuthMu.Unlock()

	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		recordAdminAuthFailure(host, now)
		return false
	}
	tokenHash := sha256.Sum256([]byte(strings.TrimSpace(strings.TrimPrefix(header, "Bearer "))))
	adminTokenMu.RLock()
	ready := adminTokenReady
	expected := adminTokenHash
	adminTokenMu.RUnlock()
	if !ready || subtle.ConstantTimeCompare(tokenHash[:], expected[:]) != 1 {
		recordAdminAuthFailure(host, now)
		return false
	}
	adminAuthMu.Lock()
	delete(adminAuthAttempts, host)
	adminAuthMu.Unlock()
	return true
}

func recordAdminAuthFailure(host string, now time.Time) {
	adminAuthMu.Lock()
	for address, existing := range adminAuthAttempts {
		if now.Sub(existing.windowStart) > 10*time.Minute && now.After(existing.blockedTill) {
			delete(adminAuthAttempts, address)
		}
	}
	attempt := adminAuthAttempts[host]
	if attempt.windowStart.IsZero() || now.Sub(attempt.windowStart) > time.Minute {
		attempt = adminAuthAttempt{windowStart: now}
	}
	attempt.count++
	if attempt.count >= 5 {
		attempt.blockedTill = now.Add(5 * time.Minute)
	}
	adminAuthAttempts[host] = attempt
	adminAuthMu.Unlock()
}

func writeAdminJSON(w http.ResponseWriter, status int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func writeAdminError(w http.ResponseWriter, status int, msg string) {
	writeAdminJSON(w, status, map[string]string{"error": msg})
}

func setAdminCORSHeaders(w http.ResponseWriter, methods string) {
	w.Header().Set("Access-Control-Allow-Methods", methods+", OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
	w.Header().Set("Cache-Control", "no-store")
}

type adminPasswordView struct {
	Password      string   `json:"password"`
	Label         string   `json:"label"`
	VkHash        string   `json:"vk_hash"`
	Ports         string   `json:"ports"`
	MaxDevices    int      `json:"max_devices"`
	DeviceIDs     []string `json:"device_ids"`
	ExpiresAt     int64    `json:"expires_at"`
	IsDeactivated bool     `json:"is_deactivated"`
	DownBytes     int64    `json:"down_bytes"`
	UpBytes       int64    `json:"up_bytes"`
	ActiveDevices int      `json:"active_devices"`
	// Для ссылок qwdtt://, которые приложение собирает само (см. bot_links.go).
	// Старые версии приложения эти поля игнорируют.
	DiagKey    string `json:"diag_key,omitempty"`
	RawPort    int    `json:"raw_port,omitempty"`
	DirectPort int    `json:"direct_port,omitempty"`
}

func toAdminPasswordView(pass string, entry *PasswordEntry) adminPasswordView {
	deviceIDs := entry.DeviceIDs
	if len(deviceIDs) == 0 && entry.DeviceID != "" {
		deviceIDs = []string{entry.DeviceID}
	}
	maxDevs := entry.MaxDevices
	if maxDevs <= 0 {
		maxDevs = 1
	}

	activeDevicesMu.Lock()
	active := 0
	for _, id := range deviceIDs {
		if activeDevices[id] > 0 {
			active++
		}
	}
	activeDevicesMu.Unlock()

	return adminPasswordView{
		Password:      pass,
		Label:         entry.Label,
		VkHash:        entry.VkHash,
		Ports:         entry.Ports,
		MaxDevices:    maxDevs,
		DeviceIDs:     deviceIDs,
		ExpiresAt:     entry.ExpiresAt,
		IsDeactivated: entry.IsDeactivated,
		DownBytes:     entry.DownBytes,
		UpBytes:       entry.UpBytes,
		ActiveDevices: active,
		DiagKey:       serverDiag.Token(),
		RawPort:       serverDiag.Port("raw"),
		DirectPort:    serverDiag.Port("direct"),
	}
}

// GET /admin/passwords — список всех сгенерированных паролей
func handleAdminListPasswords(w http.ResponseWriter, r *http.Request) {
	setAdminCORSHeaders(w, "GET")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusOK)
		return
	}
	if !adminAuthorized(r) {
		writeAdminError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	dbMutex.Lock()
	if globalWgDev != nil {
		cleanupExpiredPasswordsLocked(globalWgDev)
	}
	views := make([]adminPasswordView, 0, len(db.Passwords))
	for pass, entry := range db.Passwords {
		if entry == nil {
			continue
		}
		views = append(views, toAdminPasswordView(pass, entry))
	}
	saveDB()
	dbMutex.Unlock()

	writeAdminJSON(w, http.StatusOK, map[string]interface{}{"passwords": views})
}

// POST /admin/passwords — создать новый пароль
// Form: vk_hash (required), days (optional, default 30), max_devices (optional, default 1),
// ports (optional), label (optional — имя человека/заметка, если пусто — авто "Доступ N")
func handleAdminCreatePassword(w http.ResponseWriter, r *http.Request) {
	setAdminCORSHeaders(w, "POST")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusOK)
		return
	}
	if !adminAuthorized(r) {
		writeAdminError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if r.Method != http.MethodPost {
		writeAdminError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	vkHash := r.FormValue("vk_hash")
	if vkHash == "" {
		writeAdminError(w, http.StatusBadRequest, "vk_hash is required")
		return
	}
	days := 30
	if v := r.FormValue("days"); v != "" {
		parsed, err := strconv.Atoi(v)
		if err != nil || parsed < 1 || parsed > 365 {
			writeAdminError(w, http.StatusBadRequest, "days must be between 1 and 365")
			return
		}
		days = parsed
	}
	maxDevices := 1
	if v := r.FormValue("max_devices"); v != "" {
		parsed, err := strconv.Atoi(v)
		if err != nil || parsed < 1 {
			writeAdminError(w, http.StatusBadRequest, "max_devices must be a positive number")
			return
		}
		maxDevices = parsed
	}
	ports := r.FormValue("ports")
	label := r.FormValue("label")

	dbMutex.Lock()
	if cleanupExpiredPasswordsLocked(globalWgDev) > 0 {
		saveDB()
	}
	if len(db.Passwords) >= maxGeneratedPasswords {
		dbMutex.Unlock()
		writeAdminError(w, http.StatusConflict, fmt.Sprintf("password limit reached (max %d)", maxGeneratedPasswords))
		return
	}

	newPass := ""
	for i := 0; i < 10; i++ {
		candidate, generateErr := generatePassword()
		if generateErr != nil {
			break
		}
		if _, exists := db.Passwords[candidate]; !exists {
			newPass = candidate
			break
		}
	}
	if newPass == "" {
		dbMutex.Unlock()
		writeAdminError(w, http.StatusInternalServerError, "failed to generate a unique password")
		return
	}
	if err := serverWrapKeys.AddPassword(newPass); err != nil {
		dbMutex.Unlock()
		writeAdminError(w, http.StatusInternalServerError, "failed to create WRAP key")
		return
	}

	if label == "" {
		label = nextPasswordLabel()
	}
	entry := &PasswordEntry{
		Label:      label,
		ExpiresAt:  time.Now().Add(time.Duration(days) * 24 * time.Hour).Unix(),
		MaxDevices: maxDevices,
		VkHash:     vkHash,
		Ports:      ports,
	}
	db.Passwords[newPass] = entry
	saveDB()
	view := toAdminPasswordView(newPass, entry)
	dbMutex.Unlock()

	writeAdminJSON(w, http.StatusOK, view)
}

// POST /admin/passwords/update — редактирование уже существующего пароля.
// Form: password (required), label/vk_hash/max_devices/days — любые из них,
// только присланные поля меняются, остальные остаются как были. days, если
// передан, пересчитывает expires_at от текущего момента (как /new в боте).
func handleAdminUpdatePassword(w http.ResponseWriter, r *http.Request) {
	setAdminCORSHeaders(w, "POST")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusOK)
		return
	}
	if !adminAuthorized(r) {
		writeAdminError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if r.Method != http.MethodPost {
		writeAdminError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	if err := r.ParseForm(); err != nil {
		writeAdminError(w, http.StatusBadRequest, "invalid form data")
		return
	}

	pass := r.FormValue("password")
	if pass == "" {
		writeAdminError(w, http.StatusBadRequest, "password is required")
		return
	}

	dbMutex.Lock()
	entry, exists := db.Passwords[pass]
	if !exists || entry == nil {
		dbMutex.Unlock()
		writeAdminError(w, http.StatusNotFound, "password not found")
		return
	}

	if r.Form.Has("label") {
		entry.Label = r.FormValue("label")
	}
	if r.Form.Has("vk_hash") {
		vkHash := r.FormValue("vk_hash")
		if vkHash == "" {
			dbMutex.Unlock()
			writeAdminError(w, http.StatusBadRequest, "vk_hash cannot be empty")
			return
		}
		entry.VkHash = vkHash
	}
	if r.Form.Has("max_devices") {
		parsed, err := strconv.Atoi(r.FormValue("max_devices"))
		if err != nil || parsed < 1 {
			dbMutex.Unlock()
			writeAdminError(w, http.StatusBadRequest, "max_devices must be a positive number")
			return
		}
		entry.MaxDevices = parsed
	}
	if r.Form.Has("days") {
		parsed, err := strconv.Atoi(r.FormValue("days"))
		if err != nil || parsed < 1 || parsed > 365 {
			dbMutex.Unlock()
			writeAdminError(w, http.StatusBadRequest, "days must be between 1 and 365")
			return
		}
		entry.ExpiresAt = time.Now().Add(time.Duration(parsed) * 24 * time.Hour).Unix()
	}

	saveDB()
	view := toAdminPasswordView(pass, entry)
	dbMutex.Unlock()

	writeAdminJSON(w, http.StatusOK, view)
}

// POST /admin/passwords/deactivate — Form: password
func handleAdminDeactivatePassword(w http.ResponseWriter, r *http.Request) {
	setAdminCORSHeaders(w, "POST")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusOK)
		return
	}
	if !adminAuthorized(r) {
		writeAdminError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if r.Method != http.MethodPost {
		writeAdminError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	pass := r.FormValue("password")
	if pass == "" {
		writeAdminError(w, http.StatusBadRequest, "password is required")
		return
	}

	dbMutex.Lock()
	entry, exists := db.Passwords[pass]
	if !exists || entry == nil {
		dbMutex.Unlock()
		writeAdminError(w, http.StatusNotFound, "password not found")
		return
	}
	entry.IsDeactivated = true
	disconnectCredentialConnections(pass)
	serverWrapKeys.RemovePassword(pass)
	disconnectPasswordDevicesLocked(entry)
	saveDB()
	view := toAdminPasswordView(pass, entry)
	dbMutex.Unlock()

	writeAdminJSON(w, http.StatusOK, view)
}

// POST /admin/passwords/activate — Form: password
func handleAdminActivatePassword(w http.ResponseWriter, r *http.Request) {
	setAdminCORSHeaders(w, "POST")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusOK)
		return
	}
	if !adminAuthorized(r) {
		writeAdminError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if r.Method != http.MethodPost {
		writeAdminError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	pass := r.FormValue("password")
	if pass == "" {
		writeAdminError(w, http.StatusBadRequest, "password is required")
		return
	}

	dbMutex.Lock()
	entry, exists := db.Passwords[pass]
	if !exists || entry == nil {
		dbMutex.Unlock()
		writeAdminError(w, http.StatusNotFound, "password not found")
		return
	}
	if err := serverWrapKeys.AddPassword(pass); err != nil {
		dbMutex.Unlock()
		writeAdminError(w, http.StatusInternalServerError, "failed to activate password")
		return
	}
	entry.IsDeactivated = false
	saveDB()
	view := toAdminPasswordView(pass, entry)
	dbMutex.Unlock()

	writeAdminJSON(w, http.StatusOK, view)
}

// POST /admin/passwords/delete — Form: password
func handleAdminDeletePassword(w http.ResponseWriter, r *http.Request) {
	setAdminCORSHeaders(w, "POST")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusOK)
		return
	}
	if !adminAuthorized(r) {
		writeAdminError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if r.Method != http.MethodPost {
		writeAdminError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	pass := r.FormValue("password")
	if pass == "" {
		writeAdminError(w, http.StatusBadRequest, "password is required")
		return
	}

	dbMutex.Lock()
	entry, exists := db.Passwords[pass]
	if !exists || entry == nil {
		dbMutex.Unlock()
		writeAdminError(w, http.StatusNotFound, "password not found")
		return
	}
	deviceIDs := entry.DeviceIDs
	if len(deviceIDs) == 0 && entry.DeviceID != "" {
		deviceIDs = []string{entry.DeviceID}
	}
	for _, id := range deviceIDs {
		if dev, devExists := db.Devices[id]; devExists {
			if globalWgDev != nil {
				if pubHex, err := b64ToHex(dev.PubKey); err == nil {
					globalWgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
				}
			}
			delete(db.Devices, id)
		}
	}
	delete(db.Passwords, pass)
	disconnectCredentialConnections(pass)
	serverWrapKeys.RemovePassword(pass)
	saveDB()
	dbMutex.Unlock()

	writeAdminJSON(w, http.StatusOK, map[string]bool{"success": true})
}

// disconnectPasswordDevicesLocked отключает от WG все устройства, привязанные
// к паролю (используется при деактивации). Вызывающий должен уже держать
// dbMutex.
func disconnectPasswordDevicesLocked(entry *PasswordEntry) {
	if globalWgDev == nil {
		return
	}
	deviceIDs := entry.DeviceIDs
	if len(deviceIDs) == 0 && entry.DeviceID != "" {
		deviceIDs = []string{entry.DeviceID}
	}
	for _, id := range deviceIDs {
		dev, devExists := db.Devices[id]
		if !devExists {
			continue
		}
		pubHex, err := b64ToHex(dev.PubKey)
		if err != nil {
			continue
		}
		globalWgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
	}
}

// POST /admin/passwords/unbind-device — открепить одно устройство от пароля
// (снимает WG-пир и слот занятого устройства, не трогая сам пароль).
// Form: password (required), device_id (required).
func handleAdminUnbindDevice(w http.ResponseWriter, r *http.Request) {
	setAdminCORSHeaders(w, "POST")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusOK)
		return
	}
	if !adminAuthorized(r) {
		writeAdminError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if r.Method != http.MethodPost {
		writeAdminError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	pass := r.FormValue("password")
	if pass == "" {
		writeAdminError(w, http.StatusBadRequest, "password is required")
		return
	}
	deviceID := r.FormValue("device_id")
	if deviceID == "" {
		writeAdminError(w, http.StatusBadRequest, "device_id is required")
		return
	}

	dbMutex.Lock()
	entry, exists := db.Passwords[pass]
	if !exists || entry == nil {
		dbMutex.Unlock()
		writeAdminError(w, http.StatusNotFound, "password not found")
		return
	}
	disconnectCredentialDeviceConnections(pass, deviceID)
	unbindDevices(entry, deviceID)
	saveDB()
	view := toAdminPasswordView(pass, entry)
	dbMutex.Unlock()

	writeAdminJSON(w, http.StatusOK, view)
}

func registerAdminAPIRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/admin/passwords", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			handleAdminListPasswords(w, r)
		case http.MethodPost:
			handleAdminCreatePassword(w, r)
		case http.MethodOptions:
			setAdminCORSHeaders(w, "GET, POST")
			w.WriteHeader(http.StatusOK)
		default:
			writeAdminError(w, http.StatusMethodNotAllowed, "method not allowed")
		}
	})
	mux.HandleFunc("/admin/passwords/deactivate", handleAdminDeactivatePassword)
	mux.HandleFunc("/admin/passwords/activate", handleAdminActivatePassword)
	mux.HandleFunc("/admin/passwords/delete", handleAdminDeletePassword)
	mux.HandleFunc("/admin/passwords/update", handleAdminUpdatePassword)
	mux.HandleFunc("/admin/passwords/unbind-device", handleAdminUnbindDevice)
}
