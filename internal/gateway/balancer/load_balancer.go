package balancer

import (
	"errors"
	"sync"
	"sync/atomic"
	"time"

	"github.com/mooncell/modelhub/internal/model"
)

var (
	ErrNoAvailableInstance = errors.New("no available instances")
	ErrQueueFull           = errors.New("queue is full")
)

// LoadBalancer 负载均衡器
type LoadBalancer struct {
	// 实例包装器（实例ID -> *InstanceWrapper）
	instances sync.Map

	// 策略运行时
	poolRuntimes map[string]*StrategyRuntime
	orderedPools []string

	// 配置
	settings *LoadBalancingSettings

	// 队列控制
	queueDepth   int32
	queueCapacity int32

	// 状态
	acceptingRequests atomic.Bool
}

// LoadBalancingSettings 负载均衡配置
type LoadBalancingSettings struct {
	SampleCount     int
	QueueCapacity   int
	SamplingRounds  int
}

// DefaultSettings 默认配置
func DefaultSettings() *LoadBalancingSettings {
	return &LoadBalancingSettings{
		SampleCount:    5,
		QueueCapacity:  128,
		SamplingRounds: 3,
	}
}

// StrategyRuntime 策略运行时
type StrategyRuntime struct {
	poolKey    string
	state      RuntimeState
	strategy   LoadBalancingStrategy
	activatedAt int64

	// 队列控制
	queueDepth   int32
	queueCapacity int32
}

// RuntimeState 运行时状态
type RuntimeState int

const (
	StateActive   RuntimeState = iota
	StateDraining RuntimeState = iota
	StateRetired  RuntimeState = iota
)

// InstanceWrapper 实例包装器
type InstanceWrapper struct {
	instance   *model.Instance
	budget     *SimpleBudget
	httpClient any // *http.Client，避免循环依赖

	// 占用状态（对象池模式）
	occupied       int32
	occupiedAtMs   int64
	expireAtMs     int64
}

// LoadBalancingStrategy 负载均衡策略接口
type LoadBalancingStrategy interface {
	Acquire(estimatedTokens int, wrappers []*InstanceWrapper) *model.Instance
	GetInstances() []*model.Instance
	RefreshInstances(instances []*model.Instance)
}

// NewLoadBalancer 创建负载均衡器
func NewLoadBalancer(settings *LoadBalancingSettings) *LoadBalancer {
	if settings == nil {
		settings = DefaultSettings()
	}

	lb := &LoadBalancer{
		settings:      settings,
		poolRuntimes:  make(map[string]*StrategyRuntime),
		orderedPools:  []string{"default"},
		queueCapacity: int32(settings.QueueCapacity),
	}
	lb.acceptingRequests.Store(true)

	return lb
}

// GetNextInstance 获取下一个可用实例
func (lb *LoadBalancer) GetNextInstance(estimatedTokens int) (*model.Instance, error) {
	if !lb.acceptingRequests.Load() {
		return nil, ErrNoAvailableInstance
	}

	// 尝试进入队列
	if !lb.tryEnterQueue() {
		return nil, ErrQueueFull
	}
	defer lb.leaveQueue()

	// 获取所有健康实例
	var healthyWrappers []*InstanceWrapper
	lb.instances.Range(func(key, value any) bool {
		wrapper := value.(*InstanceWrapper)
		if wrapper.instance.IsHealthy() {
			healthyWrappers = append(healthyWrappers, wrapper)
		}
		return true
	})

	if len(healthyWrappers) == 0 {
		return nil, ErrNoAvailableInstance
	}

	// 随机采样 + 预算检查
	for round := 0; round < lb.settings.SamplingRounds; round++ {
		samples := lb.sampleWrappers(healthyWrappers, lb.settings.SampleCount)
		for _, wrapper := range samples {
			// 检查实例预算
			if wrapper.budget.TryAcquire(
				wrapper.instance.GetEffectiveRPMLimit(),
				wrapper.instance.GetEffectiveTPMLimit(),
				estimatedTokens,
			) {
				return wrapper.instance, nil
			}
		}
	}

	return nil, ErrNoAvailableInstance
}

// RefreshInstances 刷新实例列表
func (lb *LoadBalancer) RefreshInstances(instances []*model.Instance) {
	// 清理旧实例
	lb.instances = sync.Map{}

	// 添加新实例
	for _, instance := range instances {
		wrapper := &InstanceWrapper{
			instance: instance,
			budget:   NewSimpleBudget(),
		}
		lb.instances.Store(instance.ID, wrapper)
	}
}

// RecordSuccess 记录成功
func (lb *LoadBalancer) RecordSuccess(instanceID uint) {
	if value, ok := lb.instances.Load(instanceID); ok {
		wrapper := value.(*InstanceWrapper)
		wrapper.instance.RecordSuccess()
	}
}

// RecordFailure 记录失败
func (lb *LoadBalancer) RecordFailure(instanceID uint) {
	if value, ok := lb.instances.Load(instanceID); ok {
		wrapper := value.(*InstanceWrapper)
		wrapper.instance.RecordFailure()
	}
}

// GetInstanceCount 获取实例数量
func (lb *LoadBalancer) GetInstanceCount() int {
	count := 0
	lb.instances.Range(func(key, value any) bool {
		count++
		return true
	})
	return count
}

// GetHealthyInstanceCount 获取健康实例数量
func (lb *LoadBalancer) GetHealthyInstanceCount() int {
	count := 0
	lb.instances.Range(func(key, value any) bool {
		wrapper := value.(*InstanceWrapper)
		if wrapper.instance.IsHealthy() {
			count++
		}
		return true
	})
	return count
}

// tryEnterQueue 尝试进入队列（CAS 操作）
func (lb *LoadBalancer) tryEnterQueue() bool {
	for {
		current := atomic.LoadInt32(&lb.queueDepth)
		if current >= lb.queueCapacity {
			return false
		}
		if atomic.CompareAndSwapInt32(&lb.queueDepth, current, current+1) {
			return true
		}
	}
}

// leaveQueue 离开队列
func (lb *LoadBalancer) leaveQueue() {
	for {
		current := atomic.LoadInt32(&lb.queueDepth)
		if current <= 0 {
			return
		}
		if atomic.CompareAndSwapInt32(&lb.queueDepth, current, current-1) {
			return
		}
	}
}

// sampleWrappers 随机采样实例
func (lb *LoadBalancer) sampleWrappers(wrappers []*InstanceWrapper, count int) []*InstanceWrapper {
	if len(wrappers) <= count {
		return wrappers
	}

	// Fisher-Yates 洗牌
	result := make([]*InstanceWrapper, len(wrappers))
	copy(result, wrappers)

	for i := 0; i < count; i++ {
		j := i + int(time.Now().UnixNano())%(len(result)-i)
		result[i], result[j] = result[j], result[i]
	}

	return result[:count]
}

// StopAcceptingRequests 停止接受请求
func (lb *LoadBalancer) StopAcceptingRequests() {
	lb.acceptingRequests.Store(false)
}

// StartAcceptingRequests 开始接受请求
func (lb *LoadBalancer) StartAcceptingRequests() {
	lb.acceptingRequests.Store(true)
}