package relay

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mooncell/modelhub/internal/config"
	"github.com/mooncell/modelhub/internal/gateway/balancer"
	"github.com/mooncell/modelhub/internal/gateway/pool"
	"github.com/mooncell/modelhub/internal/model"
)

// RelayHandler 中转处理器
type RelayHandler struct {
	loadBalancer  *balancer.LoadBalancer
	instancePool  *pool.InstancePool
	sseConverter  *SSEConverter
	retryTimes    int
	requestTimeout time.Duration
}

// NewRelayHandler 创建中转处理器
func NewRelayHandler(lb *balancer.LoadBalancer, ip *pool.InstancePool) *RelayHandler {
	return &RelayHandler{
		loadBalancer:   lb,
		instancePool:   ip,
		sseConverter:   NewSSEConverter(),
		retryTimes:     config.AppConfig.RetryTimes,
		requestTimeout: time.Duration(config.AppConfig.RequestTimeout) * time.Second,
	}
}

// ChatCompletionRequest 聊天请求
type ChatCompletionRequest struct {
	Model       string                 `json:"model"`
	Messages    []Message              `json:"messages"`
	Temperature float64                `json:"temperature,omitempty"`
	TopP        float64                `json:"top_p,omitempty"`
	MaxTokens   int                    `json:"max_tokens,omitempty"`
	Stream      bool                   `json:"stream"`
	Extra       map[string]interface{} `json:"-"` // 额外字段
}

// Message 消息
type Message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

// HandleChat 处理聊天请求
func (h *RelayHandler) HandleChat(c *gin.Context) {
	// 解析请求
	var req ChatCompletionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request: " + err.Error()})
		return
	}

	// 验证请求
	if req.Model == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "model is required"})
		return
	}
	if len(req.Messages) == 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "messages is required"})
		return
	}

	// 估算 Token
	estimatedTokens := h.estimateTokens(req.Messages)

	// 带重试的请求处理
	var lastErr error
	for retry := 0; retry <= h.retryTimes; retry++ {
		instance, err := h.loadBalancer.GetNextInstance(estimatedTokens)
		if err != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{
				"error": "no available instances",
				"retry": retry,
			})
			return
		}

		// 执行请求
		resp, err := h.doRequest(c, instance, &req)
		if err != nil {
			lastErr = err
			h.loadBalancer.RecordFailure(instance.ID)

			// 判断是否应该重试
			if !h.shouldRetry(err) {
				break
			}
			continue
		}

		// 处理响应
		h.handleResponse(c, resp, instance, req.Stream)
		h.loadBalancer.RecordSuccess(instance.ID)
		return
	}

	// 所有重试都失败
	errMsg := "request failed"
	if lastErr != nil {
		errMsg = lastErr.Error()
	}
	c.JSON(http.StatusInternalServerError, gin.H{"error": errMsg})
}

// doRequest 执行请求
func (h *RelayHandler) doRequest(c *gin.Context, instance *model.Instance, req *ChatCompletionRequest) (*http.Response, error) {
	// 构建请求体
	body, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal request: %w", err)
	}

	// 创建请求
	httpReq, err := http.NewRequestWithContext(
		c.Request.Context(),
		"POST",
		instance.URL+"/chat/completions",
		bytes.NewReader(body),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	// 设置请求头
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Authorization", "Bearer "+instance.APIKey)

	// 获取客户端并发送请求
	client := h.instancePool.GetClient(instance)
	return client.Do(httpReq)
}

// handleResponse 处理响应
func (h *RelayHandler) handleResponse(c *gin.Context, resp *http.Response, instance *model.Instance, isStream bool) {
	defer resp.Body.Close()

	// 检查响应状态
	if resp.StatusCode >= 400 {
		body, _ := io.ReadAll(resp.Body)
		c.JSON(resp.StatusCode, gin.H{
			"error": string(body),
		})
		return
	}

	if isStream {
		// 流式响应
		h.handleStreamResponse(c, resp)
	} else {
		// 非流式响应
		h.handleNonStreamResponse(c, resp)
	}
}

// handleStreamResponse 处理流式响应
func (h *RelayHandler) handleStreamResponse(c *gin.Context, resp *http.Response) {
	// 设置 SSE 响应头
	c.Header("Content-Type", "text/event-stream")
	c.Header("Cache-Control", "no-cache")
	c.Header("Connection", "keep-alive")
	c.Header("Transfer-Encoding", "chunked")

	// 创建 Scanner
	scanner := bufio.NewScanner(resp.Body)
	scanner.Buffer(make([]byte, 64*1024), 10*1024*1024) // 64KB 初始缓冲区，最大 10MB

	for scanner.Scan() {
		line := scanner.Text()

		// 跳过空行
		if line == "" {
			continue
		}

		// 处理 SSE 数据行
		if strings.HasPrefix(line, "data:") {
			data := strings.TrimPrefix(line, "data:")
			data = strings.TrimSpace(data)

			// 检查结束标记
			if data == "[DONE]" {
				c.Writer.WriteString("data: [DONE]\n\n")
				c.Writer.Flush()
				break
			}

			// 归一化并转发
			normalized := h.sseConverter.Normalize(data)
			if normalized != "" {
				c.Writer.WriteString("data: " + normalized + "\n\n")
				c.Writer.Flush()
			}
		}
	}

	if err := scanner.Err(); err != nil {
		// 记录错误但不中断响应
		fmt.Printf("stream scanner error: %v\n", err)
	}
}

// handleNonStreamResponse 处理非流式响应
func (h *RelayHandler) handleNonStreamResponse(c *gin.Context, resp *http.Response) {
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to read response"})
		return
	}

	// 直接转发响应
	c.Data(resp.StatusCode, "application/json", body)
}

// shouldRetry 判断是否应该重试
func (h *RelayHandler) shouldRetry(err error) bool {
	if err == nil {
		return false
	}

	// 网络错误、超时、5xx 错误可以重试
	errStr := err.Error()
	return strings.Contains(errStr, "timeout") ||
		strings.Contains(errStr, "connection refused") ||
		strings.Contains(errStr, "connection reset")
}

// estimateTokens 估算 Token 数量
func (h *RelayHandler) estimateTokens(messages []Message) int {
	totalChars := 0
	for _, msg := range messages {
		totalChars += len(msg.Content)
	}

	// 粗略估算：中文按 1.6 字/Token，英文按 4 字符/Token
	// 取保守值：每 3 个字符约 1 Token
	estimatedInput := totalChars / 3
	if estimatedInput < 1 {
		estimatedInput = 1
	}

	// 预估输出为输入的 2 倍
	estimatedOutput := estimatedInput * 2
	if estimatedOutput < 64 {
		estimatedOutput = 64
	}

	return estimatedInput + estimatedOutput
}