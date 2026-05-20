package main

import (
	"log"

	"github.com/gin-gonic/gin"
	"github.com/mooncell/modelhub/internal/config"
	"github.com/mooncell/modelhub/internal/controller"
	"github.com/mooncell/modelhub/internal/model"
	"github.com/mooncell/modelhub/internal/middleware"
	"github.com/mooncell/modelhub/internal/router"
)

func main() {
	// 初始化配置
	config.Init()

	// 初始化数据库
	if err := model.InitDB(); err != nil {
		log.Fatalf("Failed to initialize database: %v", err)
	}
	defer model.CloseDB()

	// 初始化用户限流器
	middleware.InitLimiter()

	// 初始化中转组件
	controller.InitRelay()

	// 设置 Gin 模式
	gin.SetMode(config.AppConfig.ServerMode)

	// 创建 Gin 引擎
	r := gin.New()
	r.Use(gin.Logger())
	r.Use(gin.Recovery())

	// 注册路由
	router.SetupRouter(r)

	// 启动服务器
	log.Printf("MoonCell ModelHub starting on port %s", config.AppConfig.ServerPort)
	if err := r.Run(":" + config.AppConfig.ServerPort); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}