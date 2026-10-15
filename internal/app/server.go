package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"honoka-chan/config"
	_ "honoka-chan/internal/handler"
	systemhandler "honoka-chan/internal/handler/system"
	"honoka-chan/internal/router"
	"honoka-chan/internal/startup"
	"honoka-chan/pkg/db"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

const dateTimeFormat = "2006-01-02 15:04:05"

type Status struct {
	Running    bool   `json:"running"`
	WorkDir    string `json:"work_dir"`
	ListenPort string `json:"listen_port"`
	LocalURL   string `json:"local_url"`
	StartedAt  string `json:"started_at,omitempty"`
	LastError  string `json:"last_error,omitempty"`
}

var (
	mu         sync.Mutex
	isStarting bool
	httpServer *http.Server
	listener   net.Listener
	state      Status
)

func Start(workDir string) error {
	if workDir == "" {
		workDir = "."
	}

	absWorkDir, err := filepath.Abs(workDir)
	if err != nil {
		return err
	}

	mu.Lock()
	if state.Running || isStarting {
		mu.Unlock()
		return errors.New("server is already running or starting")
	}
	isStarting = true
	mu.Unlock()

	defer func() {
		mu.Lock()
		isStarting = false
		mu.Unlock()
	}()

	if err := os.Chdir(absWorkDir); err != nil {
		setLastError(err)
		return err
	}

	config.ReloadConfig()
	if err := db.Init("assets/main.db", "assets/data.db"); err != nil {
		setLastError(err)
		return err
	}
	if err := startup.StartUp(); err != nil {
		_ = db.Close()
		setLastError(err)
		return err
	}

	gin.SetMode(gin.ReleaseMode)
	engine := gin.New()
	engine.Use(gin.LoggerWithConfig(gin.LoggerConfig{
		SkipPaths: []string{
			"/agreement/all",
			"/integration/appReport/initialize",
			"/report/ge/app",
			"/v1/account/reportRole",
		},
	}))
	router.SifRouter(engine)

	ln, err := net.Listen("tcp", ":"+config.Conf.Settings.ListenPort)
	if err != nil {
		_ = db.Close()
		setLastError(err)
		return err
	}

	srv := &http.Server{
		Handler: engine,
	}
	now := time.Now()
	systemhandler.MarkStarted(now)

	mu.Lock()
	httpServer = srv
	listener = ln
	state = Status{
		Running:    true,
		WorkDir:    absWorkDir,
		ListenPort: config.Conf.Settings.ListenPort,
		LocalURL:   fmt.Sprintf("http://127.0.0.1:%s", config.Conf.Settings.ListenPort),
		StartedAt:  now.Format(dateTimeFormat),
	}
	mu.Unlock()

	go serveLoop(srv, ln)
	return nil
}

func serveLoop(srv *http.Server, ln net.Listener) {
	err := srv.Serve(ln)

	mu.Lock()
	defer mu.Unlock()

	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		state.LastError = err.Error()
	}
	state.Running = false
	httpServer = nil
	listener = nil
	_ = db.Close()
}

func Stop(ctx context.Context) error {
	mu.Lock()
	srv := httpServer
	running := state.Running
	mu.Unlock()

	if !running || srv == nil {
		return nil
	}
	if ctx == nil {
		ctx = context.Background()
	}
	return srv.Shutdown(ctx)
}

func GetStatus() Status {
	mu.Lock()
	defer mu.Unlock()
	return state
}

func GetStatusJSON() string {
	status := GetStatus()
	data, err := json.Marshal(status)
	if err != nil {
		return `{"running":false,"last_error":"failed to encode status"}`
	}
	return string(data)
}

func setLastError(err error) {
	mu.Lock()
	defer mu.Unlock()
	state.LastError = err.Error()
	state.Running = false
}
