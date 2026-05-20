package model

import (
	"time"
)

// Log 日志模型
type Log struct {
	ID             uint      `gorm:"primaryKey" json:"id"`
	UserID         uint      `gorm:"index" json:"user_id"`
	TokenID        uint      `gorm:"index" json:"token_id"`
	InstanceID     uint      `gorm:"index" json:"instance_id"`

	// 请求信息
	ModelName      string    `gorm:"size:64" json:"model_name"`
	RequestID      string    `gorm:"size:32" json:"request_id"`

	// Token 使用量
	PromptTokens     int     `gorm:"default:0" json:"prompt_tokens"`
	CompletionTokens int     `gorm:"default:0" json:"completion_tokens"`
	TotalTokens      int     `gorm:"default:0" json:"total_tokens"`

	// 配额消耗
	Quota          int       `gorm:"default:0" json:"quota"`

	// 响应状态
	StatusCode     int       `gorm:"default:0" json:"status_code"`
	LatencyMs      int       `gorm:"default:0" json:"latency_ms"` // 响应延迟（毫秒）

	// 类型标记
	Type           int       `gorm:"default:0" json:"type"` // 0=消费, 1=充值, 2=管理, 3=系统, 4=错误

	// 时间戳
	CreatedAt      time.Time `json:"created_at"`
}

// LogType 日志类型常量
const (
	LogTypeConsume   = 0 // 消费日志
	LogTypeTopUp     = 1 // 充值日志
	LogTypeManage    = 2 // 管理日志
	LogTypeSystem    = 3 // 系统日志
	LogTypeError     = 4 // 错误日志
)

// Option 系统配置模型
type Option struct {
	Key   string `gorm:"primaryKey;size:64" json:"key"`
	Value string `gorm:"size:256" json:"value"`
}

// 系统配置键
const (
	OptionSystemName       = "system_name"
	OptionFooter           = "footer"
	OptionLogo             = "logo"
	OptionServerPort       = "server_port"
	OptionQueueCapacity    = "queue_capacity"
	OptionSampleCount      = "sample_count"
	OptionRetryTimes       = "retry_times"
	OptionDefaultUserRPM   = "default_user_rpm"
	OptionDefaultUserTPM   = "default_user_tpm"
	OptionDefaultUserDaily = "default_user_daily"
)