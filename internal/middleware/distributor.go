package middleware

import (
	"github.com/gin-gonic/gin"
)

// Distribute 分发中间件（设置请求上下文）
func Distribute() gin.HandlerFunc {
	return func(c *gin.Context) {
		// 设置请求开始时间
		c.Set("request_start_time", c.GetHeader("X-Request-Start-Time"))

		// 设置请求 ID
		requestID := c.GetHeader("X-Request-ID")
		if requestID == "" {
			requestID = generateRequestID()
		}
		c.Set("request_id", requestID)
		c.Header("X-Request-ID", requestID)

		c.Next()
	}
}

func generateRequestID() string {
	// 简单的请求 ID 生成
	return "req-" + randomString(16)
}

func randomString(length int) string {
	const charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, length)
	for i := range b {
		b[i] = charset[i%len(charset)]
	}
	return string(b)
}