package middleware

import (
	"github.com/gin-gonic/gin"
	"github.com/mooncell/modelhub/internal/config"
	"github.com/mooncell/modelhub/internal/gateway/limiter"
	"github.com/mooncell/modelhub/internal/model"
)

var userLimiter *limiter.UserLimiter

// InitLimiter 初始化限流器
func InitLimiter() {
	userLimiter = limiter.NewUserLimiter()
}

// UserRateLimit 用户限流中间件
func UserRateLimit() gin.HandlerFunc {
	return func(c *gin.Context) {
		userID, exists := c.Get(string(ContextUserID))
		if !exists {
			c.Next()
			return
		}

		user, exists := c.Get(string(ContextUser))
		if !exists {
			c.Next()
			return
		}

		token, exists := c.Get(string(ContextToken))
		if !exists {
			c.Next()
			return
		}

		u := user.(*model.User)
		t := token.(*model.Token)

		// 获取有效的限流配置
		rpmLimit := t.GetEffectiveRPMLimit(u.GetEffectiveRPMLimit(config.AppConfig.DefaultUserRPM))
		tpmLimit := t.GetEffectiveTPMLimit(u.GetEffectiveTPMLimit(config.AppConfig.DefaultUserTPM))
		dailyQuota := t.GetEffectiveDailyQuota(u.GetEffectiveDailyQuota(config.AppConfig.DefaultUserDaily))

		// 检查限流
		if err := userLimiter.CheckLimit(userID.(uint), rpmLimit, tpmLimit, dailyQuota, 0); err != nil {
			c.JSON(429, gin.H{
				"error": err.Error(),
				"type":  "rate_limit_exceeded",
			})
			c.Abort()
			return
		}

		c.Next()
	}
}

// GetUserLimiter 获取用户限流器实例
func GetUserLimiter() *limiter.UserLimiter {
	return userLimiter
}