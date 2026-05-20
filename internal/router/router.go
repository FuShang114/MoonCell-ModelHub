package router

import (
	"github.com/gin-gonic/gin"
	"github.com/mooncell/modelhub/internal/controller"
	"github.com/mooncell/modelhub/internal/middleware"
)

func SetupRouter(r *gin.Engine) {
	// 全局中间件
	r.Use(middleware.CORS())
	r.Use(middleware.StatsMiddleware())

	// 健康检查
	r.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "ok"})
	})

	// API 路由组
	setupAPIRouter(r)

	// Relay 路由组
	setupRelayRouter(r)

	// 静态文件服务（前端）
	// TODO: 添加前端静态文件服务
}

func setupAPIRouter(r *gin.Engine) {
	api := r.Group("/api")
	api.Use(middleware.CORS())

	// 无需认证的路由
	api.GET("/status", controller.GetSystemStatus)
	api.POST("/setup", controller.SetupSystem)

	// 需要用户认证的路由
	userGroup := api.Group("/user")
	userGroup.Use(middleware.UserAuth())
	{
		userGroup.GET("/info", controller.GetUserInfo)
		userGroup.POST("/update", controller.UpdateUserInfo)
	}

	// Token 管理
	tokenGroup := api.Group("/token")
	tokenGroup.Use(middleware.UserAuth())
	{
		tokenGroup.GET("/list", controller.ListTokens)
		tokenGroup.POST("/create", controller.CreateToken)
		tokenGroup.POST("/update", controller.UpdateToken)
		tokenGroup.POST("/delete", controller.DeleteToken)
	}

	// 管理员路由
	adminGroup := api.Group("/admin")
	adminGroup.Use(middleware.AdminAuth())
	{
		// 用户管理
		adminGroup.GET("/users", controller.ListUsers)
		adminGroup.POST("/user/create", controller.CreateUser)
		adminGroup.POST("/user/update", controller.UpdateUser)
		adminGroup.POST("/user/delete", controller.DeleteUser)
		adminGroup.POST("/user/quota", controller.AdjustUserQuota)

		// 实例管理
		adminGroup.GET("/instances", controller.ListInstances)
		adminGroup.POST("/instance/create", controller.CreateInstance)
		adminGroup.POST("/instance/update", controller.UpdateInstance)
		adminGroup.POST("/instance/delete", controller.DeleteInstance)
		adminGroup.POST("/instance/reset-circuit", controller.ResetInstanceCircuit)

		// 日志查询
		adminGroup.GET("/logs", controller.ListLogs)

		// 系统配置
		adminGroup.GET("/options", controller.ListOptions)
		adminGroup.POST("/option/update", controller.UpdateOption)

		// 仪表盘
		adminGroup.GET("/dashboard/stats", controller.GetDashboardStats)
	}

	// 超级管理员路由
	rootGroup := api.Group("/root")
	rootGroup.Use(middleware.RootAuth())
	{
		rootGroup.POST("/reset-password", controller.ResetRootPassword)
	}
}

func setupRelayRouter(r *gin.Engine) {
	relay := r.Group("/v1")
	relay.Use(middleware.CORS())
	relay.Use(middleware.TokenAuth())
	relay.Use(middleware.UserRateLimit())
	relay.Use(middleware.Distribute())
	{
		// OpenAI 格式的聊天接口
		relay.POST("/chat/completions", controller.RelayChat)

		// 模型列表
		relay.GET("/models", controller.ListModels)
	}
}