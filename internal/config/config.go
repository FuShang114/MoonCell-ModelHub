package config

import (
	"os"
	"strconv"
)

type Config struct {
	// Server
	ServerPort string
	ServerMode string // debug, release, test

	// Database
	DatabasePath string

	// Auth
	SessionSecret string
	JWTSecret     string

	// Gateway
	QueueCapacity   int
	SampleCount     int
	RetryTimes      int
	RequestTimeout  int // seconds

	// Default limits
	DefaultUserRPM   int
	DefaultUserTPM   int
	DefaultUserDaily int
}

var AppConfig *Config

func Init() {
	AppConfig = &Config{
		ServerPort:      getEnv("SERVER_PORT", "3000"),
		ServerMode:      getEnv("SERVER_MODE", "release"),
		DatabasePath:    getEnv("DATABASE_PATH", "./data/mooncell.db"),
		SessionSecret:   getEnv("SESSION_SECRET", "mooncell-secret-key"),
		JWTSecret:       getEnv("JWT_SECRET", "mooncell-jwt-key"),
		QueueCapacity:   getEnvInt("QUEUE_CAPACITY", 128),
		SampleCount:     getEnvInt("SAMPLE_COUNT", 5),
		RetryTimes:      getEnvInt("RETRY_TIMES", 3),
		RequestTimeout:  getEnvInt("REQUEST_TIMEOUT", 60),
		DefaultUserRPM:  getEnvInt("DEFAULT_USER_RPM", 60),
		DefaultUserTPM:  getEnvInt("DEFAULT_USER_TPM", 60000),
		DefaultUserDaily: getEnvInt("DEFAULT_USER_DAILY", 1000),
	}
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		if intValue, err := strconv.Atoi(value); err == nil {
			return intValue
		}
	}
	return defaultValue
}
