package main

// Диагностика подключения через VK (клиентская сторона — go_client/netdiag.go).
//
// Клиент, у которого не поднимается туннель, шлёт через тот же TURN-relay VK
// пробу — обычный RTP-obfs кадр, неотличимый на проводе от остального
// трафика. Проба зашифрована одним из двух ключей:
//
//   - ключом пароля (как весь трафик). Ответ доказывает, что VK пропускает
//     трафик до сервера и пароль верный;
//   - ключом диагностики сервера (diag.key, выдаётся ботом в ссылке как dk=).
//     Он не зависит от пароля, поэтому сервер может ответить и тогда, когда
//     пароль неверный, истёк или упёрся в лимит устройств, — и сказать, что
//     именно с ним не так.
//
// На всё, что не расшифровывается ни одним ключом, сервер по-прежнему молчит:
// без ссылки активным сканированием сервер не найти.
//
// Совместимость: старые клиенты проб не шлют. Старый сервер пробу ключом
// пароля примет за мусор в начале сессии и отбросит, пробу ключом
// диагностики — не расшифрует.

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/hkdf"
)

const (
	diagRequestMagic = "QWDTT-DIAG/1\n"
	diagReplyMagic   = "QWDTT-DIAG-OK/1\n"
	diagProtoVersion = 1

	// diagMaxReply — потолок размера ответа (открытый текст). Клиент просит
	// крупный ответ, чтобы проверить, проходят ли через VK большие пакеты
	// (сертификат в DTLS-рукопожатии идёт именно крупными пакетами вниз).
	diagMaxReply = 1300
	// diagClockSkew — допустимое расхождение часов клиента и сервера.
	// Пробы старше отбрасываются: перехваченную пробу нельзя переиграть,
	// чтобы проверить, qWDTT ли это.
	diagClockSkew = 15 * time.Minute
	// Глобальный лимит ответов: защита от того, кто знает ключ диагностики
	// (у всех пользователей сервера он один).
	diagRatePerSec = 30
	diagRateBurst  = 60

	diagJournalEvents = 64
	diagJournalWindow = 15 * time.Minute
	diagLogInterval   = time.Minute
)

var errDiagnosticProbe = errors.New("wrap: diagnostic probe answered")

// diagRequest — тело пробы после diagRequestMagic.
type diagRequest struct {
	Nonce     string `json:"n"`
	Time      int64  `json:"t"`
	PassID    string `json:"p,omitempty"`  // wrapKeyID(пароль) — сам пароль не передаётся
	Device    string `json:"d,omitempty"`  // device ID клиента, для проверки лимита устройств
	ReplySize int    `json:"rs,omitempty"` // желаемый размер ответа (проверка крупных пакетов вниз)
	Pad       string `json:"pad,omitempty"`
}

// diagReply — тело ответа после diagReplyMagic.
type diagReply struct {
	Nonce    string         `json:"n"`
	Status   string         `json:"st"`
	Listener string         `json:"l"`
	Proto    int            `json:"v"`
	Ports    map[string]int `json:"ports,omitempty"`
	Pad      string         `json:"pad,omitempty"`
}

// Статусы пароля в ответе.
const (
	diagStatusOK          = "ok"
	diagStatusUnknown     = "unknown"
	diagStatusExpired     = "expired"
	diagStatusDeactivated = "deactivated"
	diagStatusDeviceLimit = "device_limit"
	diagStatusNoPassword  = "no_password"
)

func parseDiagRequest(plain []byte) (*diagRequest, bool) {
	if len(plain) <= len(diagRequestMagic) || string(plain[:len(diagRequestMagic)]) != diagRequestMagic {
		return nil, false
	}
	var req diagRequest
	if err := json.Unmarshal(plain[len(diagRequestMagic):], &req); err != nil {
		return nil, false
	}
	if len(req.Nonce) != 32 {
		return nil, false
	}
	if _, err := hex.DecodeString(req.Nonce); err != nil {
		return nil, false
	}
	return &req, true
}

// buildDiagReply собирает ответ и добивает его до size байт (если просили).
func buildDiagReply(r diagReply, size int) []byte {
	base, _ := json.Marshal(r)
	out := append([]byte(diagReplyMagic), base...)
	if size > diagMaxReply {
		size = diagMaxReply
	}
	// ,"pad":"" — 9 байт служебного текста.
	if need := size - len(out) - 9; need > 0 {
		r.Pad = strings.Repeat("0", need)
		base, _ = json.Marshal(r)
		out = append([]byte(diagReplyMagic), base...)
	}
	return out
}

// ─── Ключ диагностики сервера ───

// diagKeyFile — 32 случайных байта в hex, создаётся при первом старте.
const diagKeyFile = "diag.key"

func loadOrCreateDiagSecret(dir string) ([]byte, error) {
	path := filepath.Join(dir, diagKeyFile)
	if data, err := os.ReadFile(path); err == nil {
		secret, decErr := hex.DecodeString(strings.TrimSpace(string(data)))
		if decErr == nil && len(secret) == 32 {
			_ = os.Chmod(path, 0600)
			return secret, nil
		}
		log.Printf("[DIAG] %s повреждён, создаю новый ключ диагностики", path)
	}
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	if err := os.WriteFile(path, []byte(hex.EncodeToString(secret)+"\n"), 0600); err != nil {
		return nil, err
	}
	return secret, nil
}

// diagTokenFromSecret — то, что уходит в ссылку (dk=): base64url без '='.
func diagTokenFromSecret(secret []byte) string {
	return base64.RawURLEncoding.EncodeToString(secret)
}

// deriveDiagKey — AEAD-ключ проб из секрета. Должно совпадать с go_client.
func deriveDiagKey(secret []byte) ([]byte, error) {
	if len(secret) != 32 {
		return nil, errors.New("diag secret must be 32 bytes")
	}
	key := make([]byte, wrapKeyLen)
	reader := hkdf.New(sha256.New, secret, []byte("QWDTT-DIAG-v1"), []byte("rtp-obfs/chacha20poly1305"))
	if _, err := io.ReadFull(reader, key); err != nil {
		return nil, err
	}
	return key, nil
}

// ─── Состояние диагностики ───

type diagEvent struct {
	At       time.Time
	Listener string
	Source   string
	Text     string
}

type diagAuthFail struct {
	Listener string
	Source   string
	Count    int
	Logged   int
	First    time.Time
	Last     time.Time
	LastLog  time.Time
}

type diagState struct {
	mu sync.Mutex

	key   []byte // AEAD-ключ проб; nil — диагностика ключом сервера выключена
	token string // dk= для ссылок

	ports map[string]int // dtls / direct / raw → порт

	seen map[string]time.Time // nonce → когда видели (антиповтор)

	tokens     float64
	lastRefill time.Time

	events    []diagEvent
	authFails map[string]*diagAuthFail
	probes    map[string]int // статус → сколько проб за окно журнала
	probeLog  []time.Time
}

var serverDiag = &diagState{
	ports:     map[string]int{},
	seen:      map[string]time.Time{},
	authFails: map[string]*diagAuthFail{},
	probes:    map[string]int{},
	tokens:    diagRateBurst,
}

func (s *diagState) configure(secret []byte) error {
	key, err := deriveDiagKey(secret)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.key = key
	s.token = diagTokenFromSecret(secret)
	s.mu.Unlock()
	return nil
}

func (s *diagState) setPort(listener, addr string) {
	_, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return
	}
	var port int
	if _, err := fmt.Sscanf(portStr, "%d", &port); err != nil || port <= 0 {
		return
	}
	s.mu.Lock()
	s.ports[listener] = port
	s.mu.Unlock()
}

// Token — dk= для ссылок; пусто, если ключ не загружен.
func (s *diagState) Token() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.token
}

func (s *diagState) Port(listener string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ports[listener]
}

func (s *diagState) diagKey() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.key
}

func (s *diagState) portsCopy() map[string]int {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make(map[string]int, len(s.ports))
	for k, v := range s.ports {
		out[k] = v
	}
	return out
}

// admit проверяет лимит частоты, часы и повтор nonce.
func (s *diagState) admit(req *diagRequest, now time.Time) bool {
	if d := now.Sub(time.Unix(req.Time, 0)); d > diagClockSkew || d < -diagClockSkew {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.lastRefill.IsZero() {
		s.tokens += now.Sub(s.lastRefill).Seconds() * diagRatePerSec
		if s.tokens > diagRateBurst {
			s.tokens = diagRateBurst
		}
	}
	s.lastRefill = now
	if s.tokens < 1 {
		return false
	}

	if _, dup := s.seen[req.Nonce]; dup {
		return false
	}
	if len(s.seen) >= 8192 {
		for n, at := range s.seen {
			if now.Sub(at) > 2*diagClockSkew {
				delete(s.seen, n)
			}
		}
		if len(s.seen) >= 8192 {
			return false
		}
	}
	s.seen[req.Nonce] = now
	s.tokens--
	return true
}

func sourceIP(addr net.Addr) string {
	if addr == nil {
		return "?"
	}
	if host, _, err := net.SplitHostPort(addr.String()); err == nil {
		return host
	}
	return addr.String()
}

func (s *diagState) addEventLocked(e diagEvent) {
	s.events = append(s.events, e)
	if len(s.events) > diagJournalEvents {
		s.events = append(s.events[:0], s.events[len(s.events)-diagJournalEvents:]...)
	}
}

// noteAuthFailure — RTP-похожий пакет от relay VK, который не расшифровался
// ни одним ключом. Почти всегда это клиент с неверным/удалённым паролем.
// В лог — не чаще раза в минуту на источник.
func (s *diagState) noteAuthFailure(listener string, addr net.Addr) {
	src := sourceIP(addr)
	now := time.Now()
	k := listener + "|" + src

	s.mu.Lock()
	f := s.authFails[k]
	if f == nil || now.Sub(f.Last) > diagJournalWindow {
		f = &diagAuthFail{Listener: listener, Source: src, First: now}
		s.authFails[k] = f
	}
	f.Count++
	f.Last = now
	var logLine string
	if now.Sub(f.LastLog) >= diagLogInterval {
		n := f.Count - f.Logged
		f.Logged = f.Count
		f.LastLog = now
		s.addEventLocked(diagEvent{At: now, Listener: listener, Source: src,
			Text: fmt.Sprintf("не расшифровано пакетов: %d", n)})
		logLine = fmt.Sprintf("[DIAG] %s ← %s: %d пакетов не расшифровано ни одним паролем — у клиента неверный или удалённый пароль", strings.ToUpper(listener), src, n)
	}
	if len(s.authFails) > 512 {
		for key, v := range s.authFails {
			if now.Sub(v.Last) > diagJournalWindow {
				delete(s.authFails, key)
			}
		}
	}
	s.mu.Unlock()
	if logLine != "" {
		log.Print(logLine)
	}
}

func (s *diagState) noteProbe(listener string, addr net.Addr, keyKind, status string, replyLen int) {
	now := time.Now()
	src := sourceIP(addr)
	s.mu.Lock()
	s.probes[status]++
	s.probeLog = append(s.probeLog, now)
	s.addEventLocked(diagEvent{At: now, Listener: listener, Source: src,
		Text: fmt.Sprintf("проба (%s): %s", keyKind, diagStatusText(status))})
	s.mu.Unlock()
	log.Printf("[DIAG] Проба через VK → %s от %s (ключ: %s): %s, ответ %d Б", strings.ToUpper(listener), src, keyKind, diagStatusText(status), replyLen)
}

func diagStatusText(status string) string {
	switch status {
	case diagStatusOK:
		return "пароль в порядке"
	case diagStatusUnknown:
		return "пароль не найден"
	case diagStatusExpired:
		return "срок пароля истёк"
	case diagStatusDeactivated:
		return "пароль деактивирован"
	case diagStatusDeviceLimit:
		return "лимит устройств"
	case diagStatusNoPassword:
		return "без пароля"
	}
	return status
}

// ─── Проверка пароля без побочных эффектов ───

// wouldBindDevice — то же решение, что canConnectAndBind, но без записи
// привязки: диагностика не должна занимать слот устройства.
func (entry *PasswordEntry) wouldBindDevice(deviceID string) bool {
	limit := entry.MaxDevices
	if limit <= 0 {
		limit = 1
	}
	for _, id := range entry.DeviceIDs {
		if id == deviceID {
			return true
		}
	}
	bound := len(entry.DeviceIDs)
	if bound == 0 && entry.DeviceID != "" {
		if entry.DeviceID == deviceID {
			return true
		}
		if limit == 1 {
			return false
		}
		bound = 1
	}
	return bound < limit
}

// diagPasswordStatusLocked — статус пароля для ответа. Под dbMutex.
func diagPasswordStatusLocked(password, deviceID string) string {
	if password == "" {
		return diagStatusUnknown
	}
	if db.MainPassword != "" && password == db.MainPassword {
		return diagStatusOK
	}
	entry, ok := db.Passwords[password]
	if !ok || entry == nil {
		return diagStatusUnknown
	}
	if entry.IsDeactivated {
		return diagStatusDeactivated
	}
	if isPasswordExpired(entry) {
		return diagStatusExpired
	}
	if deviceID != "" && !entry.wouldBindDevice(deviceID) {
		return diagStatusDeviceLimit
	}
	return diagStatusOK
}

// passwordByKeyIDLocked ищет пароль по wrapKeyID. Под dbMutex.
func passwordByKeyIDLocked(id string) string {
	id = strings.TrimPrefix(id, "pass:")
	if id == "" {
		return ""
	}
	if db.MainPassword != "" && wrapKeyID(db.MainPassword) == id {
		return db.MainPassword
	}
	for password := range db.Passwords {
		if wrapKeyID(password) == id {
			return password
		}
	}
	return ""
}

// ─── Обработка пробы в wrapPacketConn ───

// answerDiagProbe отвечает на пробу, расшифрованную ключом key.
// passwordKeyID — id ключа пароля ("pass:..."), если проба пришла ключом
// пароля; пусто — проба ключом диагностики сервера.
func (s *diagState) answerDiagProbe(c *wrapPacketConn, raw []byte, req *diagRequest, key []byte, passwordKeyID string, addr net.Addr) {
	if !s.admit(req, time.Now()) {
		return
	}

	keyKind := "сервера"
	passID := req.PassID
	if passwordKeyID != "" {
		keyKind = "пароля"
		passID = passwordKeyID
	}

	status := diagStatusNoPassword
	if passID != "" {
		dbMutex.Lock()
		if db != nil {
			status = diagPasswordStatusLocked(passwordByKeyIDLocked(passID), req.Device)
		}
		dbMutex.Unlock()
	}

	reply := buildDiagReply(diagReply{
		Nonce:    req.Nonce,
		Status:   status,
		Listener: c.listener,
		Proto:    diagProtoVersion,
		Ports:    s.portsCopy(),
	}, req.ReplySize)

	cfg, err := NewObfsConfig("audio")
	if err != nil {
		return
	}
	if len(raw) > 1 {
		cfg.PayloadType = raw[1] & 0x7F
		if cfg.PayloadType == 96 {
			cfg.PaddingMax = 60
		}
	}
	state, err := NewObfsState()
	if err != nil {
		return
	}
	wrapped, err := obfsWrapPacket(key, reply, cfg, state, nil)
	if err != nil {
		return
	}
	if _, err := c.inner.WriteTo(wrapped, addr); err != nil {
		return
	}
	s.noteProbe(c.listener, addr, keyKind, status, len(reply))
}

// tryDiagKey пробует расшифровать первый пакет соединения ключом
// диагностики. true — это была проба (ответ уже отправлен или проба
// отклонена антиповтором): соединение нужно закрыть.
func (s *diagState) tryDiagKey(c *wrapPacketConn, raw, scratch []byte, addr net.Addr) bool {
	key := s.diagKey()
	if key == nil {
		return false
	}
	m, err := obfsUnwrapPacket(key, raw, scratch)
	if err != nil {
		return false
	}
	req, ok := parseDiagRequest(scratch[:m])
	if !ok {
		// Расшифровалось ключом диагностики, но это не проба — такого
		// клиента не бывает. Сессию ключом диагностики не открываем.
		return true
	}
	s.answerDiagProbe(c, raw, req, key, "", addr)
	return true
}

// ─── Отчёт для бота ───

func (s *diagState) Report() string {
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()

	var b strings.Builder
	b.WriteString("🩺 *Диагностика подключений* (за 15 мин)\n\n")

	portText := func(name string) string {
		if p := s.ports[name]; p > 0 {
			return fmt.Sprintf("%d", p)
		}
		return "—"
	}
	fmt.Fprintf(&b, "Порты: DTLS %s · Direct %s · Raw %s\n", portText("dtls"), portText("direct"), portText("raw"))
	if s.key != nil {
		b.WriteString("Ключ диагностики: ✅ (кладётся в новые ссылки)\n")
	} else {
		b.WriteString("Ключ диагностики: ❌ не загружен\n")
	}

	recent := 0
	for _, at := range s.probeLog {
		if now.Sub(at) <= diagJournalWindow {
			recent++
		}
	}
	// Срезаем старые отметки, чтобы probeLog не рос бесконечно.
	kept := s.probeLog[:0]
	for _, at := range s.probeLog {
		if now.Sub(at) <= diagJournalWindow {
			kept = append(kept, at)
		}
	}
	s.probeLog = kept
	fmt.Fprintf(&b, "\n*Пробы клиентов через VK:* %d\n", recent)
	if recent == 0 {
		b.WriteString("_нет — значит, ни один клиент не дошёл до сервера с проверкой_\n")
	}

	var fails []*diagAuthFail
	for _, f := range s.authFails {
		if now.Sub(f.Last) <= diagJournalWindow {
			fails = append(fails, f)
		}
	}
	sort.Slice(fails, func(i, j int) bool { return fails[i].Last.After(fails[j].Last) })
	if len(fails) > 0 {
		b.WriteString("\n*Не расшифровано (неверный пароль у клиента):*\n")
		for i, f := range fails {
			if i == 8 {
				break
			}
			fmt.Fprintf(&b, "• %s ← `%s`: %d шт., %s назад\n", strings.ToUpper(f.Listener), f.Source, f.Count, humanAgo(now.Sub(f.Last)))
		}
	}

	var events []diagEvent
	for _, e := range s.events {
		if now.Sub(e.At) <= diagJournalWindow {
			events = append(events, e)
		}
	}
	if len(events) > 0 {
		b.WriteString("\n*Последние события:*\n")
		start := len(events) - 10
		if start < 0 {
			start = 0
		}
		for _, e := range events[start:] {
			fmt.Fprintf(&b, "`%s` %s ← `%s`: %s\n", e.At.Format("15:04:05"), strings.ToUpper(e.Listener), e.Source, e.Text)
		}
	} else if len(fails) == 0 {
		b.WriteString("\nОт relay VK за это время ничего не приходило.\nЕсли клиент при этом не может подключиться — VK не доставляет трафик до сервера (IP или порт заблокирован), либо порт закрыт файрволом.\n")
	}
	return b.String()
}

func humanAgo(d time.Duration) string {
	switch {
	case d < time.Minute:
		return fmt.Sprintf("%d с", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%d мин", int(d.Minutes()))
	default:
		return fmt.Sprintf("%d ч", int(d.Hours()))
	}
}
