package middleware

import (
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/mooncell/modelhub/internal/model"
)

// ContextKey 上下文键
type ContextKey string

const (
	ContextUserID    ContextKey = "user_id"
	ContextUser      ContextKey = "user"
	ContextTokenID   ContextKey = "token_id"
	ContextToken     ContextKey = "token"
	ContextUserGroup ContextKey = "user_group"
)

// TokenAuth Token 认证中间件
func TokenAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		// 从 Header 获取 Token
		authHeader := c.GetHeader("Authorization")
		var tokenKey string

		if authHeader != "" {
			// Bearer Token 格式
			if strings.HasPrefix(authHeader, "Bearer ") {
				tokenKey = strings.TrimPrefix(authHeader, "Bearer ")
			} else {
				tokenKey = authHeader
			}
		} else {
			// 尝试从其他 Header 获取
			tokenKey = c.GetHeader("X-API-Key")
			if tokenKey == "" {
				tokenKey = c.GetHeader("api-key")
			}
		}

		if tokenKey == "" {
			c.JSON(401, gin.H{"error": "missing api key"})
			c.Abort()
			return
		}

		// 去掉 sk- 前缀
		tokenKey = strings.TrimPrefix(tokenKey, "sk-")

		// 查询 Token
		var token model.Token
		if err := model.DB.Where("key = ?", tokenKey).First(&token).Error; err != nil {
			c.JSON(401, gin.H{"error": "invalid api key"})
			c.Abort()
			return
		}

		// 验证 Token 状态
		if !token.IsValid() {
			c.JSON(401, gin.H{"error": "api key is disabled, expired or exhausted"})
			c.Abort()
			return
		}

		// 查询用户
		var user model.User
		if err := model.DB.First(&user, token.UserID).Error; err != nil {
			c.JSON(401, gin.H{"error": "user not found"})
			c.Abort()
			return
		}

		// 验证用户状态
		if !user.IsActive() {
			c.JSON(403, gin.H{"error": "user is disabled"})
			c.Abort()
			return
		}

		// 设置上下文
		c.Set(string(ContextUserID), user.ID)
		c.Set(string(ContextUser), &user)
		c.Set(string(ContextTokenID), token.ID)
		c.Set(string(ContextToken), &token)
		c.Set(string(ContextUserGroup), user.Group)

		c.Next()
	}
}

// UserAuth 用户认证中间件（Session 或 Token）
func UserAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		// 尝试从 Session 获取用户 ID
		userID, exists := c.Get("session_user_id")
		if exists {
			var user model.User
			if err := model.DB.First(&user, userID).Error; err == nil && user.IsActive() {
				c.Set(string(ContextUserID), user.ID)
				c.Set(string(ContextUser), &user)
				c.Next()
				return
			}
		}

		// 尝试 Token 认证
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.JSON(401, gin.H{"error": "unauthorized"})
			c.Abort()
			return
		}

		// 使用 TokenAuth 逻辑
		TokenAuth()(c)
	}
}

// AdminAuth 管理员认证中间件
func AdminAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		UserAuth()(c)

		if c.IsAborted() {
			return
		}

		user, exists := c.Get(string(ContextUser))
		if !exists {
			c.JSON(401, gin.H{"error": "unauthorized"})
			c.Abort()
			return
		}

		if !user.(*model.User).IsAdmin() {
			c.JSON(403, gin.H{"error": "admin access required"})
			c.Abort()
			return
		}

		c.Next()
	}
}

// RootAuth 超级管理员认证中间件
func RootAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		UserAuth()(c)

		if c.IsAborted() {
			return
		}

		user, exists := c.Get(string(ContextUser))
		if !exists {
			c.JSON(401, gin.H{"error": "unauthorized"})
			c.Abort()
			return
		}

		if !user.(*model.User).IsRoot() {
			c.JSON(403, gin.H{"error": "root access required"})
			c.Abort()
			return
		}

		c.Next()
	}
}