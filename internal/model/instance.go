package model

import (
	"sync/atomic"
	"time"
)

// Instance GLM 实例模型（替代 new-api 的 Channel）
type Instance struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	Name      string    `gorm:"uniqueIndex;size:64;not null" json:"name"`
	URL       string    `gorm:"size:256;not null" json:"url"`       // API 端点
	APIKey    string    `gorm:"size:128;not null" json:"api_key"`   // 密钥（不暴露给前端）

	// 来源类型
	Source    int       `gorm:"default:0" json:"source"` // 0=官方API, 1=第三方中转

	// 负载均衡参数
	Weight    int       `gorm:"default:1" json:"weight"`   // 权重（加权随机）
	Priority  int       `gorm:"default:0" json:"priority"` // 优先级（重试降级）

	// 实例级限流配置
	RPMLimit  int       `gorm:"default:60" json:"rpm_limit"`   // 每分钟请求上限
	TPMLimit  int       `gorm:"default:60000" json:"tpm_limit"` // 每分钟 Token 上限

	// 资源池管理
	PoolKey   string    `gorm:"size:32;default:'default'" json:"pool_key"` // 资源池键

	// 状态
	IsActive  bool      `gorm:"default:true" json:"is_active"`

	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`

	// 运行时状态（不持久化）
	FailureCount    atomic.Int32   `gorm:"-" json:"failure_count"`    // 失败计数
	RequestCount    atomic.Int32   `gorm:"-" json:"request_count"`    // 请求计数
	CircuitOpen     atomic.Bool    `gorm:"-" json:"circuit_open"`     // 熔断状态
	LastUsedTime    atomic.Int64   `gorm:"-" json:"last_used_time"`   // 最后使用时间
	LastFailureTime atomic.Int64   `gorm:"-" json:"last_failure_time"` // 最后失败时间
}

// InstanceSource 实例来源常量
const (
	InstanceSourceOfficial = 0    // 智谱官方 API
	InstanceSourceThirdParty = 1  // 第三方中转
)

// IsHealthy 判断实例是否健康（配置启用 + 未熔断）
func (i *Instance) IsHealthy() bool {
	return i.IsActive && !i.CircuitOpen.Load()
}

// GetEffectiveRPMLimit 获取有效的 RPM 限制
func (i *Instance) GetEffectiveRPMLimit() int {
	if i.RPMLimit > 0 {
		return i.RPMLimit
	}
	return 60 // 默认值
}

// GetEffectiveTPMLimit 获取有效的 TPM 限制
func (i *Instance) GetEffectiveTPMLimit() int {
	if i.TPMLimit > 0 {
		return i.TPMLimit
	}
	return 60000 // 默认值
}

// RecordFailure 记录失败（更新熔断状态）
func (i *Instance) RecordFailure() {
	failures := i.FailureCount.Add(1)
	i.LastFailureTime.Store(time.Now().UnixMilli())

	// 连续 3 次失败触发熔断
	if failures >= 3 {
		i.CircuitOpen.Store(true)
	}
}

// RecordSuccess 记录成功（重置熔断状态）
func (i *Instance) RecordSuccess() {
	i.CircuitOpen.Store(false)
	i.FailureCount.Store(0)
	i.RequestCount.Add(1)
	i.LastUsedTime.Store(time.Now().UnixMilli())
}

// ResetCircuit 重置熔断状态（管理员手动恢复）
func (i *Instance) ResetCircuit() {
	i.CircuitOpen.Store(false)
	i.FailureCount.Store(0)
}

// GetMaxConnections 根据实例 RPM 计算连接池大小
func (i *Instance) GetMaxConnections() int {
	rpm := i.GetEffectiveRPMLimit()
	// 公式：连接数 = max(10, min(200, rpm / 10))
	maxConn := rpm / 10
	if maxConn < 10 {
		return 10
	}
	if maxConn > 200 {
		return 200
	}
	return maxConn
}