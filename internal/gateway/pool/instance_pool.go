package pool

import (
	"net/http"
	"sync"
	"time"

	"github.com/mooncell/modelhub/internal/model"
)

// InstancePool 实例连接池管理器
type InstancePool struct {
	// 实例客户端缓存（实例ID -> *http.Client）
	clients sync.Map
}

// NewInstancePool 创建实例连接池
func NewInstancePool() *InstancePool {
	return &InstancePool{}
}

// GetClient 获取实例专用的 HTTP 客户端
func (p *InstancePool) GetClient(instance *model.Instance) *http.Client {
	if client, ok := p.clients.Load(instance.ID); ok {
		return client.(*http.Client)
	}

	// 根据实例 RPM 动态计算连接池大小
	maxConns := instance.GetMaxConnections()

	// 创建自定义 Transport
	transport := &http.Transport{
		MaxIdleConns:        maxConns,
		MaxIdleConnsPerHost: maxConns,
		IdleConnTimeout:     20 * time.Second,
		DisableCompression:  false,
		ForceAttemptHTTP2:   true,
	}

	// 创建客户端
	client := &http.Client{
		Transport: transport,
		Timeout:   60 * time.Second,
	}

	// 存储客户端
	actual, _ := p.clients.LoadOrStore(instance.ID, client)
	return actual.(*http.Client)
}

// RemoveClient 移除实例客户端
func (p *InstancePool) RemoveClient(instanceID uint) {
	if client, ok := p.clients.LoadAndDelete(instanceID); ok {
		// 关闭空闲连接
		if c, ok := client.(*http.Client); ok {
			if transport, ok := c.Transport.(*http.Transport); ok {
				transport.CloseIdleConnections()
			}
		}
	}
}

// Clear 清理所有客户端
func (p *InstancePool) Clear() {
	p.clients.Range(func(key, value any) bool {
		if client, ok := value.(*http.Client); ok {
			if transport, ok := client.Transport.(*http.Transport); ok {
				transport.CloseIdleConnections()
			}
		}
		p.clients.Delete(key)
		return true
	})
}

// GetClientCount 获取客户端数量
func (p *InstancePool) GetClientCount() int {
	count := 0
	p.clients.Range(func(key, value any) bool {
		count++
		return true
	})
	return count
}