// Package mobile exposes the WebDAV tunnel client as a gomobile library.
//
// Build an Android AAR:
//
//	go install golang.org/x/mobile/cmd/gomobile@latest
//	gomobile init
//	gomobile bind -target android -o webdav-tunnel.aar webdav-tunnel/mobile
package mobile

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"webdav-tunnel/tunnel"
)

var (
	running  atomic.Bool
	cancelFn context.CancelFunc
)

// ── log capture ─────────────────────────────────────────────────────────────
// The tunnel logs WebDAV errors, 429 rate-limit backoffs, dial failures, etc.
// via the standard logger. We tee those lines into a bounded ring buffer so the
// Android UI can surface them (otherwise they only reach logcat).

const maxLogLines = 200

var (
	logMu  sync.Mutex
	logBuf []string
)

type logSink struct{}

func (logSink) Write(p []byte) (int, error) {
	logMu.Lock()
	for _, line := range strings.Split(strings.TrimRight(string(p), "\n"), "\n") {
		if line == "" {
			continue
		}
		logBuf = append(logBuf, line)
		if len(logBuf) > maxLogLines {
			logBuf = logBuf[len(logBuf)-maxLogLines:]
		}
	}
	logMu.Unlock()
	return len(p), nil
}

func init() {
	log.SetOutput(io.MultiWriter(os.Stderr, logSink{}))
}

// RecentLogs returns recent tunnel log lines (oldest first), newline-joined,
// for display in the UI. Includes WebDAV errors and 429 rate-limit notices.
func RecentLogs() string {
	logMu.Lock()
	defer logMu.Unlock()
	return strings.Join(logBuf, "\n")
}

// Start starts the WebDAV SOCKS5 tunnel client.
//
// webdavURL, login, password: WebDAV server credentials.
// socksListen: local address for the SOCKS5 proxy, e.g. "127.0.0.1:1080".
// socksUser, socksPass: optional SOCKS5 authentication (pass empty strings to disable).
//
// The call verifies WebDAV connectivity before returning. Returns an error on
// failure; the tunnel must be stopped with Stop() before calling Start() again.
func Start(webdavURL, login, password, socksListen, socksUser, socksPass string) error {
	if running.Swap(true) {
		return errors.New("tunnel already running")
	}

	dav := tunnel.NewWebDAV(webdavURL, login, password, 60*time.Second)

	pingCtx, pingCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer pingCancel()
	if err := dav.Ping(pingCtx); err != nil {
		running.Store(false)
		return fmt.Errorf("WebDAV connection failed: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancelFn = cancel

	go func() {
		defer running.Store(false)
		if err := tunnel.RunProxy(ctx, dav, socksListen, socksUser, socksPass); err != nil {
			log.Printf("tunnel proxy error: %v", err)
		}
	}()

	return nil
}

// Stop stops the tunnel. Safe to call multiple times.
func Stop() {
	if cancelFn != nil {
		cancelFn()
		cancelFn = nil
	}
	running.Store(false)
}

// IsRunning reports whether the tunnel proxy is active.
func IsRunning() bool {
	return running.Load()
}

// ── tuning ────────────────────────────────────────────────────────────────────
// Call these before Start() to override the defaults.

// SetPollMaxMs sets the maximum poll interval in milliseconds (default 500).
func SetPollMaxMs(ms int) { tunnel.PollInterval = time.Duration(ms) * time.Millisecond }

// SetPollMinMs sets the starting poll interval in milliseconds (default 200).
func SetPollMinMs(ms int) { tunnel.MinPollInterval = time.Duration(ms) * time.Millisecond }

// SetCoalesceMs sets the write coalescing window in milliseconds (default 10).
func SetCoalesceMs(ms int) { tunnel.CoalesceDelay = time.Duration(ms) * time.Millisecond }

// SetChunkSize sets the chunk size in bytes (default 131071).
func SetChunkSize(n int) { tunnel.ChunkDataSize = n }

// SetConcurrentPuts sets the parallel upload limit (default 8).
func SetConcurrentPuts(n int) { tunnel.MaxConcurrentPuts = n }

// SetReadAheadMin sets the minimum concurrent prefetch GETs (default 3).
func SetReadAheadMin(n int) { tunnel.MinReadAheadWindow = n }

// SetReadAheadMax sets the maximum concurrent prefetch GETs (default 8).
func SetReadAheadMax(n int) { tunnel.MaxReadAheadWindow = n }

// SetDialTimeoutSec sets the target connection establishment timeout in seconds (default 15).
func SetDialTimeoutSec(s int) { tunnel.DialTimeout = time.Duration(s) * time.Second }

// SetIdleTimeoutSec sets the per-stream idle timeout in seconds (default 90).
func SetIdleTimeoutSec(s int) { tunnel.IdleTimeout = time.Duration(s) * time.Second }

