package limiter

import (
	"errors"
	"sync"
	"time"
)

var (
	ErrRPMExceeded       = errors.New("RPM limit exceeded")
	ErrTPMExceeded       = errors.New("TPM limit exceeded")
	ErrDailyQuotaExceeded = errors.New("daily quota exceeded")
)

// UserLimiter 用户限流器
type UserLimiter struct {
	// RPM 限流状态（用户ID -> 预算）
	rpmStates sync.Map

	// TPM 限流状态（用户ID -> 预算）
	tpmStates sync.Map

	// 日配额状态（用户ID -> 计数器）
	dailyStates sync.Map
}

// NewUserLimiter 创建用户限流器
func NewUserLimiter() *UserLimiter {
	return &UserLimiter{}
}

// CheckLimit 检查用户限流
func (l *UserLimiter) CheckLimit(userID uint, rpmLimit, tpmLimit, dailyQuota int, tokens int) error {
	// RPM 检查
	if rpmLimit > 0 {
		rpmState := l.getRPMState(userID)
		if !rpmState.TryAcquire(rpmLimit, 0, 0) {
			return ErrRPMExceeded
		}
	}

	// TPM 检查
	if tpmLimit > 0 {
		tpmState := l.getTPMState(userID)
		if !tpmState.TryAcquire(0, tpmLimit, tokens) {
			return ErrTPMExceeded
		}
	}

	// 日配额检查
	if dailyQuota > 0 {
		dailyState := l.getDailyState(userID)
		if dailyState.Count() >= dailyQuota {
			return ErrDailyQuotaExceeded
		}
		dailyState.Increment()
	}

	return nil
}

// IncrementUsage 增加用量统计
func (l *UserLimiter) IncrementUsage(userID uint, tokens int) {
	// 这里可以添加用量统计逻辑
	// 当前简化实现，不记录详细用量
}

// getRPMState 获取用户 RPM 状态
func (l *UserLimiter) getRPMState(userID uint) *SimpleBudget {
	if state, ok := l.rpmStates.Load(userID); ok {
		return state.(*SimpleBudget)
	}

	state := NewSimpleBudget()
	actual, _ := l.rpmStates.LoadOrStore(userID, state)
	return actual.(*SimpleBudget)
}

// getTPMState 获取用户 TPM 状态
func (l *UserLimiter) getTPMState(userID uint) *SimpleBudget {
	if state, ok := l.tpmStates.Load(userID); ok {
		return state.(*SimpleBudget)
	}

	state := NewSimpleBudget()
	actual, _ := l.tpmStates.LoadOrStore(userID, state)
	return actual.(*SimpleBudget)
}

// getDailyState 获取用户日配额状态
func (l *UserLimiter) getDailyState(userID uint) *DailyCounter {
	if state, ok := l.dailyStates.Load(userID); ok {
		return state.(*DailyCounter)
	}

	state := NewDailyCounter()
	actual, _ := l.dailyStates.LoadOrStore(userID, state)
	return actual.(*DailyCounter)
}

// SimpleBudget 简化预算模型（60秒窗口）
type SimpleBudget struct {
	mu            sync.Mutex
	windowStartMs int64
	usedRpm       int
	usedTpm       int
}

// NewSimpleBudget 创建预算模型
func NewSimpleBudget() *SimpleBudget {
	return &SimpleBudget{
		windowStartMs: time.Now().UnixMilli(),
	}
}

// TryAcquire 尝试获取预算
func (b *SimpleBudget) TryAcquire(rpmLimit, tpmLimit, tokens int) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	now := time.Now().UnixMilli()

	// 60秒窗口滚动
	if now-b.windowStartMs >= 60000 {
		b.windowStartMs = now
		b.usedRpm = 0
		b.usedTpm = 0
	}

	// 预算检查
	if rpmLimit > 0 && b.usedRpm+1 > rpmLimit {
		return false
	}
	if tpmLimit > 0 && b.usedTpm+tokens > tpmLimit {
		return false
	}

	// 扣减预算
	b.usedRpm++
	b.usedTpm += tokens

	return true
}

// GetUsage 获取当前用量
func (b *SimpleBudget) GetUsage() (int, int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.usedRpm, b.usedTpm
}

// DailyCounter 日计数器
type DailyCounter struct {
	mu         sync.Mutex
	date       string // 格式：2006-01-02
	count      int
}

// NewDailyCounter 创建日计数器
func NewDailyCounter() *DailyCounter {
	return &DailyCounter{
		date:  time.Now().Format("2006-01-02"),
		count: 0,
	}
}

// Increment 增加计数
func (d *DailyCounter) Increment() {
	d.mu.Lock()
	defer d.mu.Unlock()

	// 检查日期是否变化
	today := time.Now().Format("2006-01-02")
	if d.date != today {
		d.date = today
		d.count = 0
	}

	d.count++
}

// Count 获取当前计数
func (d *DailyCounter) Count() int {
	d.mu.Lock()
	defer d.mu.Unlock()

	// 检查日期是否变化
	today := time.Now().Format("2006-01-02")
	if d.date != today {
		d.date = today
		d.count = 0
	}

	return d.count
}

// Reset 重置计数
func (d *DailyCounter) Reset() {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.count = 0
}