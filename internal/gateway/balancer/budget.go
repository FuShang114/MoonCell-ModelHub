package balancer

import (
	"sync"
	"time"
)

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
func (b *SimpleBudget) GetUsage() (rpm int, tpm int) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.usedRpm, b.usedTpm
}

// Reset 重置预算
func (b *SimpleBudget) Reset() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.usedRpm = 0
	b.usedTpm = 0
	b.windowStartMs = time.Now().UnixMilli()
}