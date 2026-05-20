package controller

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mooncell/modelhub/internal/gateway/balancer"
	"github.com/mooncell/modelhub/internal/gateway/pool"
	"github.com/mooncell/modelhub/internal/gateway/relay"
	"github.com/mooncell/modelhub/internal/model"
)

var (
	relayHandler  *relay.RelayHandler
	loadBalancer  *balancer.LoadBalancer
	instancePool  *pool.InstancePool
)

// InitRelay 初始化中转组件
func InitRelay() {
	loadBalancer = balancer.NewLoadBalancer(nil)
	instancePool = pool.NewInstancePool()
	relayHandler = relay.NewRelayHandler(loadBalancer, instancePool)

	// 加载实例
	refreshInstances()
}

// refreshInstances 刷新实例列表
func refreshInstances() {
	var instances []*model.Instance
	model.DB.Where("is_active = ?", true).Find(&instances)
	loadBalancer.RefreshInstances(instances)
}

// RelayChat 中转聊天请求
func RelayChat(c *gin.Context) {
	relayHandler.HandleChat(c)
}

// ListModels 列出可用模型
func ListModels(c *gin.Context) {
	// GLM 模型列表
	models := []gin.H{
		{"id": "glm-4", "object": "model", "owned_by": "zhipu"},
		{"id": "glm-4-flash", "object": "model", "owned_by": "zhipu"},
		{"id": "glm-4-plus", "object": "model", "owned_by": "zhipu"},
		{"id": "glm-4-air", "object": "model", "owned_by": "zhipu"},
		{"id": "glm-4-airx", "object": "model", "owned_by": "zhipu"},
		{"id": "glm-4-long", "object": "model", "owned_by": "zhipu"},
		{"id": "glm-4v", "object": "model", "owned_by": "zhipu"},
		{"id": "glm-4v-plus", "object": "model", "owned_by": "zhipu"},
		{"id": "glm-z1-air", "object": "model", "owned_by": "zhipu"},
		{"id": "glm-z1-airx", "object": "model", "owned_by": "zhipu"},
		{"id": "glm-z1-flash", "object": "model", "owned_by": "zhipu"},
	}

	c.JSON(http.StatusOK, gin.H{
		"object": "list",
		"data":   models,
	})
}

// GetSystemStatus 获取系统状态
func GetSystemStatus(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"version":         "1.0.0",
		"system_name":     model.GetOption(model.OptionSystemName),
		"instance_count":  loadBalancer.GetInstanceCount(),
		"healthy_count":   loadBalancer.GetHealthyInstanceCount(),
		"start_time":      time.Now().Unix(),
	})
}

// SetupSystem 系统初始化
func SetupSystem(c *gin.Context) {
	// 检查是否已初始化
	var count int64
	model.DB.Model(&model.User{}).Count(&count)
	if count > 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "system already initialized"})
		return
	}

	// 创建默认管理员
	var req struct {
		Username string `json:"username" binding:"required"`
		Password string `json:"password" binding:"required,min=8"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	rootUser := &model.User{
		Username:    req.Username,
		DisplayName: "Administrator",
		Role:        model.RoleRootUser,
		Status:      model.UserStatusEnabled,
		Group:       "default",
	}
	if err := rootUser.SetPassword(req.Password); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to set password"})
		return
	}

	if err := model.DB.Create(rootUser).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create user"})
		return
	}

	// 创建默认 Token
	token := &model.Token{
		UserID:      rootUser.ID,
		Key:         model.GenerateKey(),
		Name:        "Default Token",
		Status:      model.TokenStatusEnabled,
		ExpiredTime: -1,
	}
	model.DB.Create(token)

	c.JSON(http.StatusOK, gin.H{
		"message":   "system initialized",
		"user_id":   rootUser.ID,
		"token_key": token.Key,
	})
}

// GetUserInfo 获取用户信息
func GetUserInfo(c *gin.Context) {
	user := GetUserFromContext(c)
	if user == nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"id":            user.ID,
		"username":      user.Username,
		"display_name":  user.DisplayName,
		"email":         user.Email,
		"role":          user.Role,
		"status":        user.Status,
		"group":         user.Group,
		"rpm_limit":     user.RPMLimit,
		"tpm_limit":     user.TPMLimit,
		"daily_quota":   user.DailyQuota,
		"used_quota":    user.UsedQuota,
		"request_count": user.RequestCount,
	})
}

// UpdateUserInfo 更新用户信息
func UpdateUserInfo(c *gin.Context) {
	user := GetUserFromContext(c)
	if user == nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
		return
	}

	var req struct {
		DisplayName string `json:"display_name"`
		Email       string `json:"email"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	updates := map[string]interface{}{
		"display_name": req.DisplayName,
		"email":        req.Email,
	}
	model.DB.Model(user).Updates(updates)

	c.JSON(http.StatusOK, gin.H{"message": "updated"})
}

// GetUserFromContext 从上下文获取用户
func GetUserFromContext(c *gin.Context) *model.User {
	if user, exists := c.Get("user"); exists {
		return user.(*model.User)
	}
	return nil
}