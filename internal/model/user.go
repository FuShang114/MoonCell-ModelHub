package model

import (
	"time"

	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
)

// User 用户模型
type User struct {
	ID           uint           `gorm:"primaryKey" json:"id"`
	Username     string         `gorm:"uniqueIndex;size:20;not null" json:"username"`
	Password     string         `gorm:"size:64;not null" json:"-"` // bcrypt 哈希，不暴露给前端
	DisplayName  string         `gorm:"size:20" json:"display_name"`
	Email        string         `gorm:"index;size:50" json:"email"`
	Role         int            `gorm:"default:1" json:"role"`      // 1=普通用户, 10=管理员, 100=超级管理员
	Status       int            `gorm:"default:1" json:"status"`    // 1=启用, 2=禁用
	Group        string         `gorm:"size:32;default:'default'" json:"group"`

	// 用户限流配置（后台配置）
	RPMLimit     int            `gorm:"default:0" json:"rpm_limit"`   // 0=使用分组默认
	TPMLimit     int            `gorm:"default:0" json:"tpm_limit"`   // 0=使用分组默认
	DailyQuota   int            `gorm:"default:0" json:"daily_quota"` // 日配额（请求数），0=无限制

	// 用量统计
	UsedQuota    int            `gorm:"default:0" json:"used_quota"`
	RequestCount int            `gorm:"default:0" json:"request_count"`

	CreatedAt    time.Time      `json:"created_at"`
	LastLoginAt  time.Time      `json:"last_login_at"`
	DeletedAt    gorm.DeletedAt `gorm:"index" json:"-"`
}

// UserRole 角色常量
const (
	RoleCommonUser = 1
	RoleAdminUser  = 10
	RoleRootUser   = 100
)

// UserStatus 状态常量
const (
	UserStatusEnabled  = 1
	UserStatusDisabled = 2
)

// SetPassword 设置密码（bcrypt 哈希）
func (u *User) SetPassword(password string) error {
	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	u.Password = string(hashedPassword)
	return nil
}

// CheckPassword 验证密码
func (u *User) CheckPassword(password string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(u.Password), []byte(password))
	return err == nil
}

// IsAdmin 判断是否为管理员
func (u *User) IsAdmin() bool {
	return u.Role >= RoleAdminUser
}

// IsRoot 判断是否为超级管理员
func (u *User) IsRoot() bool {
	return u.Role >= RoleRootUser
}

// IsActive 判断用户是否激活
func (u *User) IsActive() bool {
	return u.Status == UserStatusEnabled
}

// GetEffectiveRPMLimit 获取有效的 RPM 限制
func (u *User) GetEffectiveRPMLimit(defaultRPM int) int {
	if u.RPMLimit > 0 {
		return u.RPMLimit
	}
	return defaultRPM
}

// GetEffectiveTPMLimit 获取有效的 TPM 限制
func (u *User) GetEffectiveTPMLimit(defaultTPM int) int {
	if u.TPMLimit > 0 {
		return u.TPMLimit
	}
	return defaultTPM
}

// GetEffectiveDailyQuota 获取有效的日配额
func (u *User) GetEffectiveDailyQuota(defaultDaily int) int {
	if u.DailyQuota > 0 {
		return u.DailyQuota
	}
	return defaultDaily
}