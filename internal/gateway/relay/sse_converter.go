package relay

import (
	"encoding/json"
	"strings"
)

// SSEConverter SSE 转换器
type SSEConverter struct{}

// NewSSEConverter 创建 SSE 转换器
func NewSSEConverter() *SSEConverter {
	return &SSEConverter{}
}

// Normalize 归一化 SSE 数据
func (c *SSEConverter) Normalize(data string) string {
	// 去除前后空白
	data = strings.TrimSpace(data)
	if data == "" {
		return ""
	}

	// 尝试解析 JSON
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(data), &obj); err != nil {
		// 不是有效 JSON，原样返回
		return data
	}

	// GLM 格式转换（如果需要）
	// GLM 的响应格式与 OpenAI 基本兼容，这里做简单的归一化

	// 重新序列化，确保格式一致
	normalized, err := json.Marshal(obj)
	if err != nil {
		return data
	}

	return string(normalized)
}

// NormalizeLine 归一化单行 SSE 数据
func (c *SSEConverter) NormalizeLine(line string) string {
	// 去除 data: 前缀
	if strings.HasPrefix(line, "data:") {
		line = strings.TrimPrefix(line, "data:")
	}

	return c.Normalize(line)
}

// IsDone 检查是否为结束标记
func (c *SSEConverter) IsDone(data string) bool {
	return strings.TrimSpace(data) == "[DONE]"
}

// ExtractContent 从 SSE 数据中提取内容
func (c *SSEConverter) ExtractContent(data string) string {
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(data), &obj); err != nil {
		return ""
	}

	// 尝试从 OpenAI 格式中提取
	if choices, ok := obj["choices"].([]interface{}); ok && len(choices) > 0 {
		if choice, ok := choices[0].(map[string]interface{}); ok {
			if delta, ok := choice["delta"].(map[string]interface{}); ok {
				if content, ok := delta["content"].(string); ok {
					return content
				}
			}
		}
	}

	return ""
}

// ExtractUsage 从 SSE 数据中提取用量信息
func (c *SSEConverter) ExtractUsage(data string) (promptTokens, completionTokens int) {
	var obj map[string]interface{}
	if err := json.Unmarshal([]byte(data), &obj); err != nil {
		return 0, 0
	}

	if usage, ok := obj["usage"].(map[string]interface{}); ok {
		if pt, ok := usage["prompt_tokens"].(float64); ok {
			promptTokens = int(pt)
		}
		if ct, ok := usage["completion_tokens"].(float64); ok {
			completionTokens = int(ct)
		}
	}

	return promptTokens, completionTokens
}