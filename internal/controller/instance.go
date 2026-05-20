package controller

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mooncell/modelhub/internal/model"
)

// ListInstances 列出实例
func ListInstances(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	size, _ := strconv.Atoi(c.DefaultQuery("size", "10"))

	var instances []model.Instance
	var total int64

	model.DB.Model(&model.Instance{}).Count(&total)
	model.DB.Offset((page - 1) * size).Limit(size).Find(&instances)

	// 隐藏 API Key
	for i := range instances {
		instances[i].APIKey = ""
	}

	c.JSON(http.StatusOK, gin.H{
		"data":  instances,
		"total": total,
		"page":  page,
		"size":  size,
	})
}

// CreateInstance 创建实例
func CreateInstance(c *gin.Context) {
	var req struct {
		Name     string `json:"name" binding:"required"`
		URL      string `json:"url" binding:"required"`
		APIKey   string `json:"api_key" binding:"required"`
		Source   int    `json:"source"`
		Weight   int    `json:"weight"`
		Priority int    `json:"priority"`
		RPMLimit int    `json:"rpm_limit"`
		TPMLimit int    `json:"tpm_limit"`
		PoolKey  string `json:"pool_key"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	// 检查名称是否已存在
	var count int64
	model.DB.Model(&model.Instance{}).Where("name = ?", req.Name).Count(&count)
	if count > 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "name already exists"})
		return
	}

	// 设置默认值
	if req.Source == 0 {
		req.Source = model.InstanceSourceOfficial
	}
	if req.Weight == 0 {
		req.Weight = 1
	}
	if req.RPMLimit == 0 {
		req.RPMLimit = 60
	}
	if req.TPMLimit == 0 {
		req.TPMLimit = 60000
	}
	if req.PoolKey == "" {
		req.PoolKey = "default"
	}

	instance := &model.Instance{
		Name:     req.Name,
		URL:      req.URL,
		APIKey:   req.APIKey,
		Source:   req.Source,
		Weight:   req.Weight,
		Priority: req.Priority,
		RPMLimit: req.RPMLimit,
		TPMLimit: req.TPMLimit,
		PoolKey:  req.PoolKey,
		IsActive: true,
	}

	if err := model.DB.Create(instance).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create instance"})
		return
	}

	// 刷新负载均衡器实例列表
	refreshInstances()

	instance.APIKey = ""
	c.JSON(http.StatusOK, instance)
}

// UpdateInstance 更新实例
func UpdateInstance(c *gin.Context) {
	instanceID, _ := strconv.Atoi(c.Param("id"))
	if instanceID == 0 {
		var req struct {
			ID uint `json:"id" binding:"required"`
		}
		c.ShouldBindJSON(&req)
		instanceID = int(req.ID)
	}

	var instance model.Instance
	if err := model.DB.First(&instance, instanceID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "instance not found"})
		return
	}

	var req struct {
		Name     string `json:"name"`
		URL      string `json:"url"`
		APIKey   string `json:"api_key"`
		Source   int    `json:"source"`
		Weight   int    `json:"weight"`
		Priority int    `json:"priority"`
		RPMLimit int    `json:"rpm_limit"`
		TPMLimit int    `json:"tpm_limit"`
		PoolKey  string `json:"pool_key"`
		IsActive bool   `json:"is_active"`
	}
	c.ShouldBindJSON(&req)

	updates := map[string]interface{}{
		"updated_at": time.Now(),
	}
	if req.Name != "" {
		updates["name"] = req.Name
	}
	if req.URL != "" {
		updates["url"] = req.URL
	}
	if req.APIKey != "" {
		updates["api_key"] = req.APIKey
	}
	if req.Source > 0 {
		updates["source"] = req.Source
	}
	if req.Weight > 0 {
		updates["weight"] = req.Weight
	}
	updates["priority"] = req.Priority
	updates["rpm_limit"] = req.RPMLimit
	updates["tpm_limit"] = req.TPMLimit
	if req.PoolKey != "" {
		updates["pool_key"] = req.PoolKey
	}
	updates["is_active"] = req.IsActive

	model.DB.Model(&instance).Updates(updates)

	// 刷新负载均衡器实例列表
	refreshInstances()

	instance.APIKey = ""
	c.JSON(http.StatusOK, instance)
}

// DeleteInstance 删除实例
func DeleteInstance(c *gin.Context) {
	instanceID, _ := strconv.Atoi(c.Param("id"))
	if instanceID == 0 {
		var req struct {
			ID uint `json:"id" binding:"required"`
		}
		c.ShouldBindJSON(&req)
		instanceID = int(req.ID)
	}

	model.DB.Delete(&model.Instance{}, instanceID)

	// 刷新负载均衡器实例列表
	refreshInstances()

	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}

// ResetInstanceCircuit 重置实例熔断状态
func ResetInstanceCircuit(c *gin.Context) {
	var req struct {
		ID uint `json:"id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var instance model.Instance
	if err := model.DB.First(&instance, req.ID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "instance not found"})
		return
	}

	instance.ResetCircuit()
	c.JSON(http.StatusOK, gin.H{"message": "circuit reset"})
}

// ListLogs 列出日志
func ListLogs(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	size, _ := strconv.Atoi(c.DefaultQuery("size", "10"))
	userID, _ := strconv.Atoi(c.Query("user_id"))
	instanceID, _ := strconv.Atoi(c.Query("instance_id"))
	modelName := c.Query("model_name")

	var logs []model.Log
	var total int64

	query := model.DB.Model(&model.Log{})
	if userID > 0 {
		query = query.Where("user_id = ?", userID)
	}
	if instanceID > 0 {
		query = query.Where("instance_id = ?", instanceID)
	}
	if modelName != "" {
		query = query.Where("model_name = ?", modelName)
	}

	query.Count(&total)
	query.Order("created_at DESC").Offset((page - 1) * size).Limit(size).Find(&logs)

	c.JSON(http.StatusOK, gin.H{
		"data":  logs,
		"total": total,
		"page":  page,
		"size":  size,
	})
}

// ListOptions 列出系统配置
func ListOptions(c *gin.Context) {
	var options []model.Option
	model.DB.Find(&options)

	c.JSON(http.StatusOK, gin.H{"data": options})
}

// UpdateOption 更新系统配置
func UpdateOption(c *gin.Context) {
	var req struct {
		Key   string `json:"key" binding:"required"`
		Value string `json:"value" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := model.SetOption(req.Key, req.Value); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to update option"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "updated"})
}

// GetDashboardStats 获取仪表盘统计
func GetDashboardStats(c *gin.Context) {
	var userCount, tokenCount, instanceCount, logCount int64
	var healthyInstanceCount int

	model.DB.Model(&model.User{}).Count(&userCount)
	model.DB.Model(&model.Token{}).Count(&tokenCount)
	model.DB.Model(&model.Instance{}).Count(&instanceCount)
	model.DB.Model(&model.Log{}).Count(&logCount)

	// 获取健康实例数量
	healthyInstanceCount = loadBalancer.GetHealthyInstanceCount()

	// 获取今日请求量
	today := time.Now().Format("2006-01-02")
	var todayRequests int64
	model.DB.Model(&model.Log{}).
		Where("created_at >= ?", today+" 00:00:00").
		Where("created_at <= ?", today+" 23:59:59").
		Count(&todayRequests)

	c.JSON(http.StatusOK, gin.H{
		"user_count":           userCount,
		"token_count":          tokenCount,
		"instance_count":       instanceCount,
		"healthy_instance_count": healthyInstanceCount,
		"log_count":            logCount,
		"today_requests":       todayRequests,
	})
}