package model

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

// Token API 令牌模型
type Token struct {
	ID           uint           `gorm:"primaryKey" json:"id"`
	UserID       uint           `gorm:"index;not null" json:"user_id"`
	Key          string         `gorm:"uniqueIndex;size:64;not null" json:"key"`
	Name         string         `gorm:"size:32" json:"name"`
	Status       int            `gorm:"default:1" json:"status"` // 1=启用, 2=禁用, 3=过期, 4=耗尽
	ExpiredTime  int64          `gorm:"default:-1" json:"expired_time"` // -1=永不过期

	// Token 限流配置（覆盖用户配置）
	RPMLimit     int            `gorm:"default:0" json:"rpm_limit"`   // 0=使用用户配置
	TPMLimit     int            `gorm:"default:0" json:"tpm_limit"`   // 0=使用用户配置
	DailyQuota   int            `gorm:"default:0" json:"daily_quota"` // 0=使用用户配置

	// 模型限制
	ModelLimits  string         `gorm:"size:256" json:"model_limits"` // 允许的模型列表（逗号分隔）

	// 用量统计
	UsedQuota    int            `gorm:"default:0" json:"used_quota"`
	RequestCount int            `gorm:"default:0" json:"request_count"`

	CreatedAt    time.Time      `json:"created_at"`
	AccessedAt   time.Time      `json:"accessed_at"`
	DeletedAt    gorm.DeletedAt `gorm:"index" json:"-"`
}

// TokenStatus 令牌状态常量
const (
	TokenStatusEnabled   = 1
	TokenStatusDisabled  = 2
	TokenStatusExpired   = 3
	TokenStatusExhausted = 4
)

// GenerateKey 生成令牌密钥
func GenerateKey() string {
	return "sk-" + uuid.New().String()[:32]
}

// IsEnabled 判断令牌是否启用
func (t *Token) IsEnabled() bool {
	return t.Status == TokenStatusEnabled
}

// IsExpired 判断令牌是否过期
func (t *Token) IsExpired() bool {
	if t.ExpiredTime == -1 {
		return false // 永不过期
	}
	return t.ExpiredTime < time.Now().Unix()
}

// IsExhausted 判断令牌是否耗尽额度
func (t *Token) IsExhausted() bool {
	// 简化版：不检查额度，仅检查状态
	return t.Status == TokenStatusExhausted
}

// IsValid 判断令牌是否有效（启用 + 未过期）
func (t *Token) IsValid() bool {
	return t.IsEnabled() && !t.IsExpired() && !t.IsExhausted()
}

// CanAccessModel 判断令牌是否可以访问指定模型
func (t *Token) CanAccessModel(modelName string) bool {
	if t.ModelLimits == "" {
		return true // 无限制
	}
	// 简单检查：逗号分隔的模型列表
	models := splitString(t.ModelLimits, ",")
	for _, m := range models {
		if m == modelName {
			return true
		}
	}
	return false
}

// GetEffectiveRPMLimit 获取有效的 RPM 限制
func (t *Token) GetEffectiveRPMLimit(userRPM int) int {
	if t.RPMLimit > 0 {
		return t.RPMLimit
	}
	return userRPM
}

// GetEffectiveTPMLimit 获取有效的 TPM 限制
func (t *Token) GetEffectiveTPMLimit(userTPM int) int {
	if t.TPMLimit > 0 {
		return t.TPMLimit
	}
	return userTPM
}

// GetEffectiveDailyQuota 获取有效的日配额
func (t *Token) GetEffectiveDailyQuota(userDaily int) int {
	if t.DailyQuota > 0 {
		return t.DailyQuota
	}
	return userDaily
}

// splitString 分割字符串
func splitString(s, sep string) []string {
	if s == "" {
		return nil
	}
	result := make([]string, 0)
	for _, part := range splitBySeparator(s, sep) {
		if part != "" {
			result = append(result, part)
		}
	}
	return result
}

func splitBySeparator(s, sep string) []string {
	// 简化的分割逻辑
	result := make([]string, 0)
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i:i+len(sep)] == sep {
			result = append(result, s[start:i])
			start = i + len(sep)
			i += len(sep) - 1
		}
	}
	result = append(result, s[start:])
	return result
}