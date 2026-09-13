package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	neturl "net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/hkdf"
	"golang.zx2c4.com/wireguard/device"
)

// ==================== База данных и Бот ====================

type ClientDevice struct {
	DeviceID  string `json:"device_id"`
	IP        string `json:"ip"`
	PrivKey   string `json:"priv_key"`
	PubKey    string `json:"pub_key"`
	DownBytes int64  `json:"down_bytes"` // скачано устройством
	UpBytes   int64  `json:"up_bytes"`   // отдано устройством
	OwnerID   string `json:"owner_id,omitempty"`
	// RawIP — адрес в raw-IP (без WireGuard) роутере. Пусто у старых записей —
	// назначается лениво при первом подключении в raw-режиме.
	RawIP      string `json:"raw_ip,omitempty"`
	RawOwnerID string `json:"raw_owner_id,omitempty"`
}

type PasswordEntry struct {
	Label         string   `json:"label,omitempty"` // понятное имя в боте
	DeviceID      string   `json:"device_id"`       // Для обратной совместимости, если нужно
	DeviceIDs     []string `json:"device_ids"`      // Список привязанных deviceID
	MaxDevices    int      `json:"max_devices"`     // Максимальное кол-во устройств (0 или 1 = 1 устройство)
	ExpiresAt     int64    `json:"expires_at"`      // unix timestamp
	DownBytes     int64    `json:"down_bytes"`      // скачано клиентом
	UpBytes       int64    `json:"up_bytes"`        // отдано клиентом
	VkHash        string   `json:"vk_hash,omitempty"`
	Ports         string   `json:"ports,omitempty"` // "dtls,wg,tun"
	IsDeactivated bool     `json:"is_deactivated,omitempty"`
}

func passwordEntryHasDevice(entry *PasswordEntry, deviceID string) bool {
	if entry == nil {
		return false
	}
	if entry.DeviceID == deviceID {
		return true
	}
	for _, id := range entry.DeviceIDs {
		if id == deviceID {
			return true
		}
	}
	return false
}

func deviceOwnerIDLocked(deviceID string) (string, bool) {
	dev := db.Devices[deviceID]
	if dev == nil {
		return "", true
	}
	owners := make(map[string]struct{}, 2)
	if dev.OwnerID != "" {
		owners[dev.OwnerID] = struct{}{}
	}
	if dev.RawOwnerID != "" {
		owners[dev.RawOwnerID] = struct{}{}
	}
	for password, entry := range db.Passwords {
		if passwordEntryHasDevice(entry, deviceID) {
			owners[wrapKeyID(password)] = struct{}{}
		}
	}
	if len(owners) > 1 {
		return "", false
	}
	for ownerID := range owners {
		return ownerID, true
	}
	return "", true
}

func authorizeDeviceOwnerLocked(deviceID, password string, isMain bool, entry *PasswordEntry) bool {
	dev := db.Devices[deviceID]
	if dev == nil {
		return true
	}
	ownerID, consistent := deviceOwnerIDLocked(deviceID)
	if !consistent {
		return false
	}
	requestedOwnerID := wrapKeyID(password)
	if ownerID != "" {
		return ownerID == requestedOwnerID
	}
	if !isMain && !passwordEntryHasDevice(entry, deviceID) {
		return false
	}
	dev.OwnerID = requestedOwnerID
	return true
}

func setDeviceOwner(dev *ClientDevice, password string) {
	if dev != nil {
		dev.OwnerID = wrapKeyID(password)
	}
}

func generatedOwnerEntryLocked(dev *ClientDevice, deviceID string) *PasswordEntry {
	if dev == nil {
		return nil
	}
	if dev.OwnerID != "" {
		for password, entry := range db.Passwords {
			if wrapKeyID(password) == dev.OwnerID && passwordEntryHasDevice(entry, deviceID) {
				return entry
			}
		}
		return nil
	}
	var ownerPassword string
	var ownerEntry *PasswordEntry
	for password, entry := range db.Passwords {
		if !passwordEntryHasDevice(entry, deviceID) {
			continue
		}
		if ownerEntry != nil && password != ownerPassword {
			return nil
		}
		ownerPassword = password
		ownerEntry = entry
	}
	if ownerEntry != nil {
		dev.OwnerID = wrapKeyID(ownerPassword)
	}
	return ownerEntry
}

func entryDeviceIDs(entry *PasswordEntry) []string {
	if entry == nil {
		return nil
	}
	ids := append([]string(nil), entry.DeviceIDs...)
	if len(ids) == 0 && entry.DeviceID != "" && entry.DeviceID != "multi" {
		ids = append(ids, entry.DeviceID)
	}
	return ids
}

func removeEntryDeviceBinding(entry *PasswordEntry, deviceID string) {
	ids := entryDeviceIDs(entry)
	filtered := ids[:0]
	for _, id := range ids {
		if id != deviceID {
			filtered = append(filtered, id)
		}
	}
	entry.DeviceIDs = filtered
	switch len(filtered) {
	case 0:
		entry.DeviceID = ""
	case 1:
		entry.DeviceID = filtered[0]
	default:
		entry.DeviceID = "multi"
	}
}

func reconcileDeviceOwnershipLocked() {
	claims := make(map[string]map[string]struct{})
	for password, entry := range db.Passwords {
		ownerID := wrapKeyID(password)
		for _, deviceID := range entryDeviceIDs(entry) {
			if claims[deviceID] == nil {
				claims[deviceID] = make(map[string]struct{})
			}
			claims[deviceID][ownerID] = struct{}{}
		}
	}
	for deviceID, owners := range claims {
		dev := db.Devices[deviceID]
		if dev == nil {
			for password, entry := range db.Passwords {
				if _, claimed := owners[wrapKeyID(password)]; claimed {
					removeEntryDeviceBinding(entry, deviceID)
				}
			}
			continue
		}
		knownOwner := dev.OwnerID
		if knownOwner == "" {
			knownOwner = dev.RawOwnerID
		}
		if knownOwner == "" && len(owners) == 1 {
			for ownerID := range owners {
				knownOwner = ownerID
			}
			dev.OwnerID = knownOwner
		}
		if knownOwner == "" || len(owners) > 1 {
			for _, entry := range db.Passwords {
				removeEntryDeviceBinding(entry, deviceID)
			}
			delete(db.Devices, deviceID)
			log.Printf("[SECURITY] Удалена неоднозначная привязка устройства %s", deviceID)
			continue
		}
		dev.OwnerID = knownOwner
		for password, entry := range db.Passwords {
			if passwordEntryHasDevice(entry, deviceID) && wrapKeyID(password) != knownOwner {
				removeEntryDeviceBinding(entry, deviceID)
			}
		}
	}
}

func (entry *PasswordEntry) canConnectAndBind(deviceID string) bool {
	limit := entry.MaxDevices
	if limit <= 0 {
		limit = 1
	}

	// Сначала проверяем, привязано ли уже это устройство
	for _, id := range entry.DeviceIDs {
		if id == deviceID {
			return true
		}
	}

	// Для обратной совместимости
	if len(entry.DeviceIDs) == 0 && entry.DeviceID != "" {
		if entry.DeviceID == deviceID {
			entry.DeviceIDs = []string{deviceID}
			return true
		}
		if limit == 1 {
			return false
		}
		entry.DeviceIDs = append(entry.DeviceIDs, entry.DeviceID)
	}

	// Если есть свободное место под новое устройство
	if len(entry.DeviceIDs) < limit {
		entry.DeviceIDs = append(entry.DeviceIDs, deviceID)
		if len(entry.DeviceIDs) == 1 {
			entry.DeviceID = deviceID
		} else {
			entry.DeviceID = "multi"
		}
		return true
	}

	return false
}

// Трафик главного пароля (владельца)
var (
	mainPassDown int64
	mainPassUp   int64
)

// Онлайн-статус устройств
var (
	activeDevices   = make(map[string]int32) // deviceID -> кол-во активных коннектов
	activeDevicesMu sync.Mutex
)

type Database struct {
	MainPassword string                    `json:"-"`
	AdminID      string                    `json:"-"`
	BotToken     string                    `json:"-"`
	Passwords    map[string]*PasswordEntry `json:"passwords"`
	Devices      map[string]*ClientDevice  `json:"devices"`
}

var (
	db          *Database
	dbMutex     sync.Mutex
	dbFile      string
	globalWgDev *device.Device
)

var serverWrapKeys = newWrapKeyStore()

const (
	passChars             = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
	generatedPasswordLen  = 16
	maxGeneratedPasswords = 10
)

func generatePassword() (string, error) {
	b := make([]byte, generatedPasswordLen)
	randomBytes := make([]byte, len(b))
	if _, err := rand.Read(randomBytes); err != nil {
		return "", err
	}
	for i, raw := range randomBytes {
		b[i] = passChars[int(raw)%len(passChars)]
	}
	return string(b), nil
}

func passwordEntryLabel(entry *PasswordEntry, pass string, index int) string {
	if entry != nil {
		if label := strings.TrimSpace(entry.Label); label != "" {
			return label
		}
	}
	if len(pass) >= 4 {
		return fmt.Sprintf("Доступ …%s", pass[len(pass)-4:])
	}
	return fmt.Sprintf("Доступ #%d", index)
}

func nextPasswordLabel() string {
	return fmt.Sprintf("Доступ %d", len(db.Passwords)+1)
}

var publicIP string = ""

func getPublicIP() string {
	if publicIP != "" {
		return publicIP
	}
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get("https://api.ipify.org")
	if err != nil {
		return "YOUR_SERVER_IP"
	}
	defer resp.Body.Close()
	ipBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return "YOUR_SERVER_IP"
	}
	publicIP = string(bytes.TrimSpace(ipBytes))
	return publicIP
}

type wrapKeyEntry struct {
	id  string
	key []byte
}

type wrapKeyStore struct {
	mu      sync.RWMutex
	entries []wrapKeyEntry
}

func newWrapKeyStore() *wrapKeyStore {
	return &wrapKeyStore{}
}

func deriveWrapKey(password string) ([]byte, error) {
	if password == "" {
		return nil, errors.New("empty password")
	}
	key := make([]byte, wrapKeyLen)
	reader := hkdf.New(
		sha256.New,
		[]byte(password),
		[]byte("WDTT-WRAP-v1"),
		[]byte("rtp-obfs/chacha20poly1305"),
	)
	if _, err := io.ReadFull(reader, key); err != nil {
		return nil, fmt.Errorf("derive wrap key: %w", err)
	}
	return key, nil
}

func wrapKeyID(password string) string {
	sum := sha256.Sum256([]byte("WDTT-WRAP-ID-v1\x00" + password))
	return hex.EncodeToString(sum[:8])
}

func zeroBytes(b []byte) {
	for i := range b {
		b[i] = 0
	}
}

func (s *wrapKeyStore) SetPasswords(mainPassword string, generated []string) error {
	next := make([]wrapKeyEntry, 0, len(generated)+1)
	seen := make(map[string]struct{}, len(generated)+1)

	if mainPassword != "" {
		key, err := deriveWrapKey(mainPassword)
		if err != nil {
			return err
		}
		id := "pass:" + wrapKeyID(mainPassword)
		next = append(next, wrapKeyEntry{id: id, key: key})
		seen[id] = struct{}{}
	}

	for _, password := range generated {
		if password == "" {
			continue
		}
		id := "pass:" + wrapKeyID(password)
		if _, exists := seen[id]; exists {
			continue
		}
		key, err := deriveWrapKey(password)
		if err != nil {
			for _, entry := range next {
				zeroBytes(entry.key)
			}
			return err
		}
		next = append(next, wrapKeyEntry{id: id, key: key})
		seen[id] = struct{}{}
	}

	s.mu.Lock()
	old := s.entries
	s.entries = next
	s.mu.Unlock()
	for _, entry := range old {
		evictAEAD(entry.key)
		zeroBytes(entry.key)
	}
	return nil
}

func (s *wrapKeyStore) AddPassword(password string) error {
	key, err := deriveWrapKey(password)
	if err != nil {
		return err
	}
	id := "pass:" + wrapKeyID(password)

	s.mu.Lock()
	defer s.mu.Unlock()
	for _, entry := range s.entries {
		if entry.id == id {
			zeroBytes(key)
			return nil
		}
	}
	s.entries = append(s.entries, wrapKeyEntry{id: id, key: key})
	return nil
}

func (s *wrapKeyStore) RemovePassword(password string) {
	id := "pass:" + wrapKeyID(password)

	s.mu.Lock()
	defer s.mu.Unlock()
	for i, entry := range s.entries {
		if entry.id != id {
			continue
		}
		evictAEAD(entry.key)
		zeroBytes(entry.key)
		copy(s.entries[i:], s.entries[i+1:])
		s.entries[len(s.entries)-1] = wrapKeyEntry{}
		s.entries = s.entries[:len(s.entries)-1]
		return
	}
}

func (s *wrapKeyStore) Count() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.entries)
}

func (s *wrapKeyStore) Unwrap(raw, dst []byte) ([]byte, string, int, error) {
	if !obfsIsRTPPacket(raw) {
		return nil, "", 0, errors.New("wrap: non-obfs packet")
	}

	s.mu.RLock()
	defer s.mu.RUnlock()
	if len(s.entries) == 0 {
		return nil, "", 0, errors.New("wrap: no active keys")
	}
	for _, entry := range s.entries {
		m, err := obfsUnwrapPacket(entry.key, raw, dst)
		if err == nil {
			return append([]byte(nil), entry.key...), entry.id, m, nil
		}
	}
	return nil, "", 0, errors.New("wrap: auth failed")
}

func refreshWrapKeysFromDBLocked() error {
	passwords := make([]string, 0, len(db.Passwords))
	for password, entry := range db.Passwords {
		if !isPasswordExpired(entry) && !entry.IsDeactivated {
			passwords = append(passwords, password)
		}
	}
	return serverWrapKeys.SetPasswords(db.MainPassword, passwords)
}

func reloadDB(wgDev *device.Device) error {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	data, err := os.ReadFile(dbFile)
	if err != nil {
		return fmt.Errorf("read db file: %w", err)
	}

	oldDB := db

	newDB := &Database{
		Passwords: make(map[string]*PasswordEntry),
		Devices:   make(map[string]*ClientDevice),
	}
	if err := json.Unmarshal(data, newDB); err != nil {
		return fmt.Errorf("parse db json: %w", err)
	}

	newDB.MainPassword = oldDB.MainPassword
	newDB.AdminID = oldDB.AdminID
	newDB.BotToken = oldDB.BotToken

	db = newDB

	// Очищаем истёкшие
	cleanupExpiredPasswordsLocked(wgDev)

	// Находим удаленные устройства и удаляем их из WireGuard
	for devID, oldDev := range oldDB.Devices {
		if _, exists := db.Devices[devID]; !exists {
			removePeerFromWG(wgDev, oldDev)
		}
	}

	// Синхронизируем новые/оставшиеся устройства с WireGuard
	for _, dev := range db.Devices {
		upsertPeerInWG(wgDev, dev)
	}

	// Обновляем криптографические WRAP-ключи в памяти
	if err := refreshWrapKeysFromDBLocked(); err != nil {
		return fmt.Errorf("refresh wrap keys: %w", err)
	}

	return nil
}

func initDB(dir, mainPass, adminID, botToken string) {
	if err := os.MkdirAll(dir, 0700); err != nil {
		log.Fatalf("[DB] Не удалось создать каталог: %v", err)
	}
	if err := os.Chmod(dir, 0700); err != nil {
		log.Fatalf("[DB] Не удалось защитить каталог: %v", err)
	}
	dbFile = filepath.Join(dir, "passwords.json")
	db = &Database{
		Passwords: make(map[string]*PasswordEntry),
		Devices:   make(map[string]*ClientDevice),
	}
	data, err := os.ReadFile(dbFile)
	if err == nil {
		if err := json.Unmarshal(data, db); err != nil {
			log.Fatalf("[DB] Повреждён %s: %v", dbFile, err)
		}
	} else if !os.IsNotExist(err) {
		log.Fatalf("[DB] Не удалось прочитать %s: %v", dbFile, err)
	}
	if db.Passwords == nil {
		db.Passwords = make(map[string]*PasswordEntry)
	}
	if db.Devices == nil {
		db.Devices = make(map[string]*ClientDevice)
	}
	db.MainPassword = mainPass
	db.AdminID = adminID
	db.BotToken = botToken
	if err := saveDB(); err != nil {
		log.Fatalf("[DB] Не удалось сохранить базу: %v", err)
	}
	if err := refreshWrapKeysFromDBLocked(); err != nil {
		log.Fatalf("[WRAP] init keys: %v", err)
	}
}

func saveDB() error {
	data, err := json.MarshalIndent(db, "", "  ")
	if err != nil {
		log.Printf("[DB] Ошибка сериализации: %v", err)
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(dbFile), ".passwords-*.tmp")
	if err != nil {
		log.Printf("[DB] Ошибка временного файла: %v", err)
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)
	if err = tmp.Chmod(0600); err == nil {
		_, err = tmp.Write(data)
	}
	if err == nil {
		err = tmp.Sync()
	}
	closeErr := tmp.Close()
	if err == nil {
		err = closeErr
	}
	if err == nil {
		err = os.Rename(tmpName, dbFile)
	}
	if err == nil {
		if dir, openErr := os.Open(filepath.Dir(dbFile)); openErr == nil {
			err = dir.Sync()
			dir.Close()
		} else {
			err = openErr
		}
	}
	if err != nil {
		log.Printf("[DB] Ошибка атомарного сохранения: %v", err)
	}
	return err
}

func isPasswordExpired(entry *PasswordEntry) bool {
	if entry == nil {
		return true
	}
	if entry.ExpiresAt == 0 {
		return false // бессрочный
	}
	return time.Now().Unix() > entry.ExpiresAt
}

func getNextIP() string {
	used := make(map[string]bool)
	for _, dev := range db.Devices {
		used[dev.IP] = true
	}
	for b3 := 0; b3 <= 255; b3++ {
		for b4 := 1; b4 <= 254; b4++ {
			ip := fmt.Sprintf("10.66.%d.%d", b3, b4)
			if ip == "10.66.66.1" {
				continue
			}
			if !used[ip] {
				return ip
			}
		}
	}
	return ""
}

func getNextRawIP() string {
	used := make(map[string]bool)
	for _, dev := range db.Devices {
		if dev.RawIP != "" {
			used[dev.RawIP] = true
		}
	}
	for b3 := 0; b3 <= 255; b3++ {
		for b4 := 1; b4 <= 254; b4++ {
			ip := fmt.Sprintf("10.70.%d.%d", b3, b4)
			if ip == rawServerAddr {
				continue
			}
			if !used[ip] {
				return ip
			}
		}
	}
	return ""
}

func botLoop(token string, adminIDstr string, wgDev *device.Device) {
	if token == "" || adminIDstr == "" {
		return
	}
	adminID, _ := strconv.ParseInt(adminIDstr, 10, 64)
	if adminID == 0 {
		return
	}

	// Устанавливаем команды для синей кнопки Menu
	go func() {
		cmds := `{"commands":[{"command":"new","description":"Создать временный пароль"},{"command":"list","description":"Управление доступами"}]}`
		resp, err := http.Post(fmt.Sprintf("https://api.telegram.org/bot%s/setMyCommands", token), "application/json", strings.NewReader(cmds))
		if err == nil {
			resp.Body.Close()
		}
	}()

	offset := 0
	client := &http.Client{Timeout: 65 * time.Second}

	// Состояние ожидания ввода
	var waitingForDays bool
	var waitingForPorts bool
	var waitingForHash bool
	var targetPassword string

	var tempDays int
	var tempMaxDevs int
	var tempPorts string // "dtls,wg,tun"

	for {
		url := fmt.Sprintf("https://api.telegram.org/bot%s/getUpdates?timeout=60&offset=%d", token, offset)
		resp, err := client.Get(url)
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}

		var res struct {
			Ok     bool `json:"ok"`
			Result []struct {
				UpdateID int `json:"update_id"`
				Message  *struct {
					Chat struct {
						ID int64 `json:"id"`
					} `json:"chat"`
					Text string `json:"text"`
				} `json:"message"`
				CallbackQuery *struct {
					ID      string `json:"id"`
					Data    string `json:"data"`
					Message struct {
						MessageID int `json:"message_id"`
						Chat      struct {
							ID int64 `json:"id"`
						} `json:"chat"`
					} `json:"message"`
				} `json:"callback_query"`
			} `json:"result"`
		}

		err = json.NewDecoder(resp.Body).Decode(&res)
		resp.Body.Close()
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}

		for _, u := range res.Result {
			offset = u.UpdateID + 1

			// ═══ Callback кнопки ═══
			if u.CallbackQuery != nil && u.CallbackQuery.Message.Chat.ID == adminID {
				data := u.CallbackQuery.Data
				answerCallback(token, u.CallbackQuery.ID)

				if strings.HasPrefix(data, "viewpass_") {
					// Просмотр деталей пароля
					pass := strings.TrimPrefix(data, "viewpass_")
					dbMutex.Lock()
					entry, exists := db.Passwords[pass]
					if !exists || entry == nil {
						dbMutex.Unlock()
						sendTelegram(token, adminID, "❌ Пароль не найден", nil)
						continue
					}
					txt := fmt.Sprintf("👤 *Имя:* %s\n🔑 *Пароль:* `%s`\n", passwordEntryLabel(entry, pass, 0), pass)
					if entry.VkHash != "" {
						ports := entry.Ports
						if ports == "" {
							ports = "56000,56001,9000"
						}
						pts := strings.Split(ports, ",")
						srvIP := getPublicIP()
						link := fmt.Sprintf("wdtt://%s:%s:%s:%s:%s:%s", srvIP, pts[0], pts[1], pts[2], pass, entry.VkHash)
						txt += fmt.Sprintf("🔗 *Быстрая ссылка:* `%s`\n", link)
					}
					if entry.IsDeactivated {
						txt += "🔴 Статус: *ДЕАКТИВИРОВАН*\n"
					} else {
						txt += "🟢 Статус: *АКТИВЕН*\n"
					}

					if entry.ExpiresAt > 0 {
						expireTime := time.Unix(entry.ExpiresAt, 0)
						remaining := time.Until(expireTime)
						if remaining > 0 {
							txt += fmt.Sprintf("⏰ Истекает: %s (через %dd)\n", expireTime.Format("02.01.2006"), int(remaining.Hours()/24))
						} else {
							txt += "⏰ *ИСТЁК* ❌\n"
						}
					} else {
						txt += "⏰ Бессрочный ♾\n"
					}

					txt += fmt.Sprintf("\n📊 *Трафик:*\n• Скачано: %.2f MB\n• Отдано: %.2f MB\n", float64(entry.DownBytes)/(1024*1024), float64(entry.UpBytes)/(1024*1024))

					limit := entry.MaxDevices
					if limit <= 0 {
						limit = 1
					}
					boundCount := len(entry.DeviceIDs)
					if boundCount == 0 && entry.DeviceID != "" {
						boundCount = 1
					}

					txt += fmt.Sprintf("\n📱 *Привязанные устройства* (%d/%d):\n", boundCount, limit)
					hasDevices := false

					// Legacy
					if entry.DeviceID != "" && len(entry.DeviceIDs) == 0 {
						hasDevices = true
						dev, devExists := db.Devices[entry.DeviceID]
						if devExists {
							txt += fmt.Sprintf("• ID: `%s`\n  IP: `%s`\n  📊 ↑%.1f MB / ↓%.1f MB\n", entry.DeviceID, dev.IP, float64(dev.UpBytes)/(1024*1024), float64(dev.DownBytes)/(1024*1024))
						} else {
							txt += fmt.Sprintf("• ID: `%s` (удалено)\n", entry.DeviceID)
						}
					}

					// Array
					for i, id := range entry.DeviceIDs {
						hasDevices = true
						dev, devExists := db.Devices[id]
						if devExists {
							txt += fmt.Sprintf("• [%d] ID: `%s`\n  IP: `%s`\n  📊 ↑%.1f MB / ↓%.1f MB\n", i+1, id, dev.IP, float64(dev.UpBytes)/(1024*1024), float64(dev.DownBytes)/(1024*1024))
						} else {
							txt += fmt.Sprintf("• [%d] ID: `%s` (удалено)\n", i+1, id)
						}
					}

					var kb []map[string]interface{}
					kb = append(kb, map[string]interface{}{
						"text":          "📂 Получить .conf файл",
						"callback_data": "getfile_" + pass,
					})
					if !hasDevices {
						txt += "_Ожидает первого подключения..._\n"
					} else {
						kb = append(kb, map[string]interface{}{
							"text":          "🗑 Отвязать ВСЕ устройства",
							"callback_data": "unbind_" + pass,
						})
					}

					dbMutex.Unlock()
					if entry.IsDeactivated {
						kb = append(kb, map[string]interface{}{
							"text":          "✅ Активировать",
							"callback_data": "react_" + pass,
						})
					} else {
						kb = append(kb, map[string]interface{}{
							"text":          "⏸ Деактивировать",
							"callback_data": "deact_" + pass,
						})
					}
					kb = append(kb, map[string]interface{}{
						"text":          "❌ Удалить пароль",
						"callback_data": "delpass_" + pass,
					})
					kb = append(kb, map[string]interface{}{
						"text":          "◀️ Назад к списку",
						"callback_data": "backlist",
					})
					var keyboard [][]map[string]interface{}
					for _, btn := range kb {
						keyboard = append(keyboard, []map[string]interface{}{btn})
					}
					sendTelegram(token, adminID, txt, map[string]interface{}{"inline_keyboard": keyboard})

				} else if strings.HasPrefix(data, "deact_") {
					pass := strings.TrimPrefix(data, "deact_")
					dbMutex.Lock()
					entry, exists := db.Passwords[pass]
					if exists && entry != nil {
						entry.IsDeactivated = true
						disconnectCredentialConnections(pass)
						serverWrapKeys.RemovePassword(pass)
						// Отключаем активные устройства от WG
						if entry.DeviceID != "" {
							dev, devExists := db.Devices[entry.DeviceID]
							if devExists {
								pubHex, _ := b64ToHex(dev.PubKey)
								wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
							}
						}
						for _, id := range entry.DeviceIDs {
							dev, devExists := db.Devices[id]
							if devExists {
								pubHex, _ := b64ToHex(dev.PubKey)
								wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
							}
						}
						saveDB()
					}
					dbMutex.Unlock()
					sendTelegram(token, adminID, fmt.Sprintf("⏸ Пароль `%s` деактивирован", pass), nil)

				} else if strings.HasPrefix(data, "react_") {
					pass := strings.TrimPrefix(data, "react_")
					dbMutex.Lock()
					entry, exists := db.Passwords[pass]
					if exists && entry != nil {
						if err := serverWrapKeys.AddPassword(pass); err == nil {
							entry.IsDeactivated = false
							saveDB()
						}
					}
					dbMutex.Unlock()
					sendTelegram(token, adminID, fmt.Sprintf("✅ Пароль `%s` активирован", pass), nil)

				} else if data == "mainlink" {
					targetPassword = "main"
					var keyboard [][]map[string]interface{}
					keyboard = append(keyboard, []map[string]interface{}{
						{"text": "Да", "callback_data": "ports_def"},
						{"text": "Нет", "callback_data": "ports_custom"},
					})
					sendTelegram(token, adminID, "⚙️ Использовать стандартные порты для главного пароля (56000, 56001, 9000)?", map[string]interface{}{"inline_keyboard": keyboard})

				} else if data == "ports_def" {
					tempPorts = "56000,56001,9000"
					waitingForHash = true
					sendTelegram(token, adminID, "🔑 Укажите VK хеш (или несколько через запятую):", nil)

				} else if data == "ports_custom" {
					waitingForPorts = true
					sendTelegram(token, adminID, "⚙️ Укажите через запятую 3 порта (DTLS,WG,TUN):\nНапример: 56000,56001,9000", nil)

				} else if strings.HasPrefix(data, "getfile_") {
					pass := strings.TrimPrefix(data, "getfile_")
					dbMutex.Lock()
					entry, exists := db.Passwords[pass]
					if exists && entry != nil {
						srvIP := getPublicIP()
						configJSON := fmt.Sprintf(`{
  "name": "qWDTT - %s",
  "peer": "%s",
  "vkHashes": "%s",
  "workersPerHash": 9,
  "listenPort": 9000,
  "password": "%s"
}`, srvIP, srvIP, entry.VkHash, pass)
						dbMutex.Unlock()

						fileName := fmt.Sprintf("qwdtt_%s.conf", pass)
						sendTelegramFile(token, adminID, fileName, []byte(configJSON))
					} else {
						dbMutex.Unlock()
						sendTelegram(token, adminID, "❌ Пароль не найден", nil)
					}

				} else if strings.HasPrefix(data, "unbind_") {
					pass := strings.TrimPrefix(data, "unbind_")
					dbMutex.Lock()
					entry, exists := db.Passwords[pass]
					if exists && entry != nil {
						disconnectCredentialConnections(pass)
						// Удаляем все привязанные устройства из WG и из хранилища
						if entry.DeviceID != "" {
							dev, devExists := db.Devices[entry.DeviceID]
							if devExists {
								pubHex, _ := b64ToHex(dev.PubKey)
								wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
								delete(db.Devices, entry.DeviceID)
							}
							entry.DeviceID = ""
						}
						for _, id := range entry.DeviceIDs {
							dev, devExists := db.Devices[id]
							if devExists {
								pubHex, _ := b64ToHex(dev.PubKey)
								wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
								delete(db.Devices, id)
							}
						}
						entry.DeviceIDs = nil
						saveDB()
					}
					dbMutex.Unlock()
					sendTelegram(token, adminID, fmt.Sprintf("✅ Все устройства отвязаны от пароля `%s`", pass), nil)

				} else if strings.HasPrefix(data, "delpass_") {
					pass := strings.TrimPrefix(data, "delpass_")
					dbMutex.Lock()
					entry, exists := db.Passwords[pass]
					if exists && entry != nil {
						if entry.DeviceID != "" {
							dev, devExists := db.Devices[entry.DeviceID]
							if devExists {
								pubHex, _ := b64ToHex(dev.PubKey)
								wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
								delete(db.Devices, entry.DeviceID)
							}
						}
						for _, id := range entry.DeviceIDs {
							dev, devExists := db.Devices[id]
							if devExists {
								pubHex, _ := b64ToHex(dev.PubKey)
								wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
								delete(db.Devices, id)
							}
						}
					}
					delete(db.Passwords, pass)
					disconnectCredentialConnections(pass)
					serverWrapKeys.RemovePassword(pass)
					saveDB()
					dbMutex.Unlock()
					sendTelegram(token, adminID, fmt.Sprintf("✅ Пароль `%s` и его устройства удалены", pass), nil)

				} else if strings.HasPrefix(data, "deldev_") {
					devID := strings.TrimPrefix(data, "deldev_")
					dbMutex.Lock()
					dev, exists := db.Devices[devID]
					if exists {
						delete(db.Devices, devID)
						pubHex, _ := b64ToHex(dev.PubKey)
						wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
						// Очищаем привязку из пароля
						for _, entry := range db.Passwords {
							if entry != nil {
								if entry.DeviceID == devID {
									entry.DeviceID = ""
								}
								newIDs := []string{}
								for _, id := range entry.DeviceIDs {
									if id != devID {
										newIDs = append(newIDs, id)
									}
								}
								entry.DeviceIDs = newIDs
							}
						}
						saveDB()
					}
					dbMutex.Unlock()
					sendTelegram(token, adminID, fmt.Sprintf("✅ Устройство `%s` удалено", devID), nil)

				} else if data == "backlist" {
					sendPasswordList(token, adminID, wgDev)
				}
			}

			// ═══ Текстовые команды ═══
			msg := u.Message
			if msg == nil || msg.Chat.ID != adminID {
				continue
			}

			cmd := strings.TrimSpace(msg.Text)

			// Обработка ввода количества дней
			if waitingForDays {
				waitingForDays = false
				parts := strings.Fields(cmd)
				if len(parts) == 0 {
					sendTelegram(token, adminID, "❌ Неверное значение. Укажите число от 1 до 365, или отправьте /new заново.", nil)
					continue
				}
				days, parseErr := strconv.Atoi(parts[0])
				if parseErr != nil || days < 1 || days > 365 {
					sendTelegram(token, adminID, "❌ Неверное значение. Укажите число от 1 до 365, или отправьте /new заново.", nil)
					continue
				}

				maxDevs := 1
				if len(parts) > 1 {
					if devs, err := strconv.Atoi(parts[1]); err == nil && devs >= 1 {
						maxDevs = devs
					}
				}

				tempDays = days
				tempMaxDevs = maxDevs

				var keyboard [][]map[string]interface{}
				keyboard = append(keyboard, []map[string]interface{}{
					{"text": "Да", "callback_data": "ports_def"},
					{"text": "Нет", "callback_data": "ports_custom"},
				})
				sendTelegram(token, adminID, "⚙️ Использовать стандартные порты (56000, 56001, 9000)?", map[string]interface{}{"inline_keyboard": keyboard})
				continue
			}

			if waitingForPorts {
				parts := strings.Split(cmd, ",")
				if len(parts) != 3 {
					sendTelegram(token, adminID, "❌ Неверный формат. Укажите 3 порта через запятую (например: 56000,56001,9000):", nil)
					continue
				}
				p1 := strings.TrimSpace(parts[0])
				p2 := strings.TrimSpace(parts[1])
				p3 := strings.TrimSpace(parts[2])

				if _, err := strconv.Atoi(p1); err != nil {
					sendTelegram(token, adminID, "❌ Неверный порт. Повторите ввод:", nil)
					continue
				}
				if _, err := strconv.Atoi(p2); err != nil {
					sendTelegram(token, adminID, "❌ Неверный порт. Повторите ввод:", nil)
					continue
				}
				if _, err := strconv.Atoi(p3); err != nil {
					sendTelegram(token, adminID, "❌ Неверный порт. Повторите ввод:", nil)
					continue
				}

				waitingForPorts = false
				tempPorts = fmt.Sprintf("%s,%s,%s", p1, p2, p3)
				waitingForHash = true
				sendTelegram(token, adminID, "🔑 Укажите VK хеш (или несколько через запятую):", nil)
				continue
			}

			if waitingForHash {
				hash := strings.ReplaceAll(cmd, " ", "")
				if strings.Contains(hash, "http") || strings.Contains(hash, "/") {
					sendTelegram(token, adminID, "❌ Пожалуйста, отправьте только хеш (или несколько хешей через запятую). Ссылки не поддерживаются.", nil)
					continue
				}
				if hash == "" {
					sendTelegram(token, adminID, "❌ Хеш не должен быть пустым.", nil)
					continue
				}
				waitingForHash = false

				if targetPassword == "main" {
					targetPassword = ""
					srvIP := getPublicIP()
					pts := strings.Split(tempPorts, ",")
					link := fmt.Sprintf("wdtt://%s:%s:%s:%s:%s:%s", srvIP, pts[0], pts[1], pts[2], db.MainPassword, hash)

					nameEsc := neturl.QueryEscape(fmt.Sprintf("qWDTT - Main (%s)", srvIP))
					peerEsc := neturl.QueryEscape(srvIP)
					hashesEsc := neturl.QueryEscape(hash)
					passEsc := neturl.QueryEscape(db.MainPassword)
					qwdttLink := fmt.Sprintf("qwdtt://config?name=%s&peer=%s&hashes=%s&workers=9&port=9000&pass=%s", nameEsc, peerEsc, hashesEsc, passEsc)

					msgText := fmt.Sprintf("🔗 *Ссылка для главного пароля:*\n`%s`\n\n🔗 *Быстрая ссылка qWDTT:* `%s`", link, qwdttLink)
					sendTelegram(token, adminID, msgText, nil)

					configJSON := fmt.Sprintf(`{
  "name": "qWDTT - Main (%s)",
  "peer": "%s",
  "vkHashes": "%s",
  "workersPerHash": 9,
  "listenPort": 9000,
  "password": "%s"
}`, srvIP, srvIP, hash, db.MainPassword)
					fileName := fmt.Sprintf("qwdtt_main_%s.conf", srvIP)
					sendTelegramFile(token, adminID, fileName, []byte(configJSON))
					continue
				}

				dbMutex.Lock()
				if cleanupExpiredPasswordsLocked(wgDev) > 0 {
					saveDB()
				}
				if len(db.Passwords) >= maxGeneratedPasswords {
					dbMutex.Unlock()
					sendTelegram(token, adminID, fmt.Sprintf("❌ Лимит паролей: максимум %d активных. Удалите ненужный пароль через /list.", maxGeneratedPasswords), nil)
					continue
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
					sendTelegram(token, adminID, "❌ Не удалось создать уникальный пароль. Повторите /new.", nil)
					continue
				}
				if err := serverWrapKeys.AddPassword(newPass); err != nil {
					dbMutex.Unlock()
					sendTelegram(token, adminID, "❌ Не удалось создать WRAP-ключ для пароля. Повторите /new.", nil)
					continue
				}
				expiresAt := time.Now().Add(time.Duration(tempDays) * 24 * time.Hour).Unix()
				newLabel := nextPasswordLabel()
				db.Passwords[newPass] = &PasswordEntry{
					Label:      newLabel,
					ExpiresAt:  expiresAt,
					MaxDevices: tempMaxDevs,
					VkHash:     hash,
					Ports:      tempPorts,
				}
				saveDB()
				dbMutex.Unlock()

				expDate := time.Unix(expiresAt, 0).Format("02.01.2006")
				srvIP := getPublicIP()
				pts := strings.Split(tempPorts, ",")
				link := fmt.Sprintf("wdtt://%s:%s:%s:%s:%s:%s", srvIP, pts[0], pts[1], pts[2], newPass, hash)

				nameEsc := neturl.QueryEscape(newLabel)
				peerEsc := neturl.QueryEscape(srvIP)
				hashesEsc := neturl.QueryEscape(hash)
				passEsc := neturl.QueryEscape(newPass)
				qwdttLink := fmt.Sprintf("qwdtt://config?name=%s&peer=%s&hashes=%s&workers=9&port=9000&pass=%s", nameEsc, peerEsc, hashesEsc, passEsc)

				msgText := fmt.Sprintf("👤 Имя: *%s*\n🔑 Новый пароль:\n`%s`\n\n⏰ Действует %d дн. (до %s)\n📱 Лимит: %d устройств\nОжидает первого подключения\n\n🔗 *Быстрая ссылка qWDTT:* `%s`\n\n🔗 *Legacy ссылка:* `%s`", newLabel, newPass, tempDays, expDate, tempMaxDevs, qwdttLink, link)
				sendTelegram(token, adminID, msgText, nil)

				configJSON := fmt.Sprintf(`{
  "name": "%s",
  "peer": "%s",
  "vkHashes": "%s",
  "workersPerHash": 9,
  "listenPort": 9000,
  "password": "%s"
}`, newLabel, srvIP, hash, newPass)
				fileName := fmt.Sprintf("qwdtt_%s.conf", newPass)
				sendTelegramFile(token, adminID, fileName, []byte(configJSON))
				continue
			}

			if cmd == "/start" || cmd == "/help" {
				sendTelegram(token, adminID, "🤖 *qWDTT VPN Manager*\n\n/new — Создать пароль\n/list — Список паролей", nil)

			} else if strings.HasPrefix(cmd, "/new ") || cmd == "/new" {
				args := strings.Fields(strings.TrimPrefix(cmd, "/new"))
				if len(args) >= 1 {
					days, parseErr := strconv.Atoi(args[0])
					if parseErr == nil && days >= 1 && days <= 365 {
						maxDevs := 1
						if len(args) >= 2 {
							if devs, err := strconv.Atoi(args[1]); err == nil && devs >= 1 {
								maxDevs = devs
							}
						}

						tempDays = days
						tempMaxDevs = maxDevs

						var keyboard [][]map[string]interface{}
						keyboard = append(keyboard, []map[string]interface{}{
							{"text": "Да", "callback_data": "ports_def"},
							{"text": "Нет", "callback_data": "ports_custom"},
						})
						sendTelegram(token, adminID, "⚙️ Использовать стандартные порты (56000, 56001, 9000)?", map[string]interface{}{"inline_keyboard": keyboard})
						continue
					}
				}
				dbMutex.Lock()
				if cleanupExpiredPasswordsLocked(wgDev) > 0 {
					saveDB()
				}
				if len(db.Passwords) >= maxGeneratedPasswords {
					dbMutex.Unlock()
					sendTelegram(token, adminID, fmt.Sprintf("❌ Лимит паролей: максимум %d активных. Удалите ненужный пароль через /list.", maxGeneratedPasswords), nil)
					continue
				}
				dbMutex.Unlock()
				waitingForDays = true
				sendTelegram(token, adminID, "📅 Введите срок действия пароля в днях (1–365) и (опционально) лимит устройств через пробел:\n\n_Примеры:_\n`30` — месяц, 1 устройство\n`30 3` — месяц, до 3 устройств", nil)

			} else if cmd == "/list" {
				sendPasswordList(token, adminID, wgDev)
			}
		}
	}
}

func removePeerFromWG(wgDev *device.Device, dev *ClientDevice) {
	if wgDev == nil || dev == nil || dev.PubKey == "" {
		return
	}
	pubHex, err := b64ToHex(dev.PubKey)
	if err != nil {
		return
	}
	wgDev.IpcSet(fmt.Sprintf("public_key=%s\nremove=true\n", pubHex))
}

func upsertPeerInWG(wgDev *device.Device, dev *ClientDevice) {
	if wgDev == nil || dev == nil || dev.PubKey == "" || dev.IP == "" {
		return
	}
	pubHex, err := b64ToHex(dev.PubKey)
	if err != nil {
		return
	}
	wgDev.IpcSet(fmt.Sprintf("public_key=%s\nallowed_ip=%s/32\n", pubHex, dev.IP))
}

func cleanupExpiredPasswordsLocked(wgDev *device.Device) int {
	removed := 0
	for p, entry := range db.Passwords {
		if isPasswordExpired(entry) {
			disconnectCredentialConnections(p)
			if entry != nil {
				deviceIDs := append([]string(nil), entry.DeviceIDs...)
				if len(deviceIDs) == 0 && entry.DeviceID != "" && entry.DeviceID != "multi" {
					deviceIDs = append(deviceIDs, entry.DeviceID)
				}
				for _, deviceID := range deviceIDs {
					removePeerFromWG(wgDev, db.Devices[deviceID])
					delete(db.Devices, deviceID)
				}
			}
			delete(db.Passwords, p)
			serverWrapKeys.RemovePassword(p)
			removed++
		}
	}
	return removed
}

func cleanupExpiredPasswords(wgDev *device.Device) int {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	removed := cleanupExpiredPasswordsLocked(wgDev)
	if removed > 0 {
		saveDB()
	}
	return removed
}

func expiredPasswordJanitor(ctx context.Context, wgDev *device.Device) {
	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if removed := cleanupExpiredPasswords(wgDev); removed > 0 {
				log.Printf("[DB] Удалено истёкших паролей: %d", removed)
			}
		}
	}
}

func syncPersistedPeersToWG(wgDev *device.Device) {
	dbMutex.Lock()
	defer dbMutex.Unlock()
	count := 0
	for _, dev := range db.Devices {
		upsertPeerInWG(wgDev, dev)
		count++
	}
	if count > 0 {
		log.Printf("[WG] Восстановлено сохранённых устройств: %d", count)
	}
}

func sendPasswordList(token string, adminID int64, wgDev *device.Device) {
	dbMutex.Lock()
	defer dbMutex.Unlock()

	// Очистка истёкших
	if cleanupExpiredPasswordsLocked(wgDev) > 0 {
		saveDB()
	}

	txt := "🔐 *Пароли:*\n\n"
	txt += fmt.Sprintf("🔒 Главный: `%s` (владелец)\n\n", db.MainPassword)

	var inlineKb []map[string]interface{}
	inlineKb = append(inlineKb, map[string]interface{}{
		"text":          "🔗 Ссылка на главный пароль",
		"callback_data": "mainlink",
	})

	if len(db.Passwords) == 0 {
		txt += "_Нет сгенерированных паролей._\n"
	} else {
		txt += fmt.Sprintf("_Активно: %d/%d_\n\n", len(db.Passwords), maxGeneratedPasswords)
		index := 0
		for p, entry := range db.Passwords {
			index++
			label := passwordEntryLabel(entry, p, index)
			status := "🟢"
			if entry.DeviceID != "" || len(entry.DeviceIDs) > 0 {
				status = "🔗"
			}
			expiry := "♾"
			if entry.ExpiresAt > 0 {
				remaining := time.Until(time.Unix(entry.ExpiresAt, 0))
				if remaining > 0 {
					expiry = fmt.Sprintf("%dd", int(remaining.Hours()/24)+1)
				} else {
					expiry = "❌"
				}
			}
			txt += fmt.Sprintf("%s *%s* (%s)\n", status, label, expiry)
			inlineKb = append(inlineKb, map[string]interface{}{
				"text":          "🔍 " + label,
				"callback_data": "viewpass_" + p,
			})
		}
	}

	txt += "\n🟢 = свободен | 🔗 = привязан"

	var replyMarkup interface{}
	if len(inlineKb) > 0 {
		var keyboard [][]map[string]interface{}
		for _, btn := range inlineKb {
			keyboard = append(keyboard, []map[string]interface{}{btn})
		}
		replyMarkup = map[string]interface{}{"inline_keyboard": keyboard}
	}
	sendTelegram(token, adminID, txt, replyMarkup)
}

func answerCallback(token, callbackID string) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/answerCallbackQuery", token)
	payload := map[string]interface{}{"callback_query_id": callbackID}
	body, _ := json.Marshal(payload)
	http.Post(url, "application/json", bytes.NewBuffer(body))
}

func maskPassword(pass string) string {
	if len(pass) <= 3 {
		return pass
	}
	return pass[:3] + "****"
}

func sendTelegram(token string, chatID int64, text string, replyMarkup interface{}) {
	url := fmt.Sprintf("https://api.telegram.org/bot%s/sendMessage", token)
	payload := map[string]interface{}{
		"chat_id":    chatID,
		"text":       text,
		"parse_mode": "Markdown",
	}
	if replyMarkup != nil {
		payload["reply_markup"] = replyMarkup
	}
	body, _ := json.Marshal(payload)
	http.Post(url, "application/json", bytes.NewBuffer(body))
}

func sendTelegramFile(token string, chatID int64, fileName string, fileContent []byte) {
	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	part, err := writer.CreateFormFile("document", fileName)
	if err != nil {
		log.Println("[BOT] Error creating form file:", err)
		return
	}
	_, err = part.Write(fileContent)
	if err != nil {
		log.Println("[BOT] Error writing file content:", err)
		return
	}
	err = writer.WriteField("chat_id", strconv.FormatInt(chatID, 10))
	if err != nil {
		log.Println("[BOT] Error writing chat_id field:", err)
		return
	}
	err = writer.Close()
	if err != nil {
		log.Println("[BOT] Error closing writer:", err)
		return
	}

	url := fmt.Sprintf("https://api.telegram.org/bot%s/sendDocument", token)
	req, err := http.NewRequest("POST", url, body)
	if err != nil {
		log.Println("[BOT] Error creating HTTP request:", err)
		return
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		log.Println("[BOT] Error sending file to Telegram:", err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		respBody, _ := io.ReadAll(resp.Body)
		log.Printf("[BOT] sendTelegramFile failed with status %d: %s\n", resp.StatusCode, string(respBody))
	}
}
