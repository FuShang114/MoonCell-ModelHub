package controller

import (
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/mooncell/modelhub/internal/model"
)

// ListUsers 列出用户
func ListUsers(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	size, _ := strconv.Atoi(c.DefaultQuery("size", "10"))

	var users []model.User
	var total int64

	model.DB.Model(&model.User{}).Count(&total)
	model.DB.Offset((page - 1) * size).Limit(size).Find(&users)

	// 隐藏密码字段
	for i := range users {
		users[i].Password = ""
	}

	c.JSON(http.StatusOK, gin.H{
		"data":  users,
		"total": total,
		"page":  page,
		"size":  size,
	})
}

// CreateUser 创建用户
func CreateUser(c *gin.Context) {
	var req struct {
		Username    string `json:"username" binding:"required"`
		Password    string `json:"password" binding:"required,min=8"`
		DisplayName string `json:"display_name"`
		Email       string `json:"email"`
		Role        int    `json:"role"`
		Group       string `json:"group"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	// 检查用户名是否已存在
	var count int64
	model.DB.Model(&model.User{}).Where("username = ?", req.Username).Count(&count)
	if count > 0 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "username already exists"})
		return
	}

	// 设置默认角色
	if req.Role == 0 {
		req.Role = model.RoleCommonUser
	}
	if req.Group == "" {
		req.Group = "default"
	}

	user := &model.User{
		Username:    req.Username,
		DisplayName: req.DisplayName,
		Email:       req.Email,
		Role:        req.Role,
		Status:      model.UserStatusEnabled,
		Group:       req.Group,
	}
	if err := user.SetPassword(req.Password); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to set password"})
		return
	}

	if err := model.DB.Create(user).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to create user"})
		return
	}

	user.Password = ""
	c.JSON(http.StatusOK, user)
}

// UpdateUser 更新用户
func UpdateUser(c *gin.Context) {
	userID, _ := strconv.Atoi(c.Param("id"))
	if userID == 0 {
		var req struct {
			ID uint `json:"id" binding:"required"`
		}
		c.ShouldBindJSON(&req)
		userID = int(req.ID)
	}

	var user model.User
	if err := model.DB.First(&user, userID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	var req struct {
		DisplayName string `json:"display_name"`
		Email       string `json:"email"`
		Role        int    `json:"role"`
		Status      int    `json:"status"`
		Group       string `json:"group"`
		RPMLimit    int    `json:"rpm_limit"`
		TPMLimit    int    `json:"tpm_limit"`
		DailyQuota  int    `json:"daily_quota"`
		Remark      string `json:"remark"`
	}
	c.ShouldBindJSON(&req)

	updates := map[string]interface{}{}
	if req.DisplayName != "" {
		updates["display_name"] = req.DisplayName
	}
	if req.Email != "" {
		updates["email"] = req.Email
	}
	if req.Role > 0 {
		updates["role"] = req.Role
	}
	if req.Status > 0 {
		updates["status"] = req.Status
	}
	if req.Group != "" {
		updates["group"] = req.Group
	}
	updates["rpm_limit"] = req.RPMLimit
	updates["tpm_limit"] = req.TPMLimit
	updates["daily_quota"] = req.DailyQuota

	model.DB.Model(&user).Updates(updates)

	user.Password = ""
	c.JSON(http.StatusOK, user)
}

// DeleteUser 删除用户
func DeleteUser(c *gin.Context) {
	userID, _ := strconv.Atoi(c.Param("id"))
	if userID == 0 {
		var req struct {
			ID uint `json:"id" binding:"required"`
		}
		c.ShouldBindJSON(&req)
		userID = int(req.ID)
	}

	// 不能删除超级管理员
	var user model.User
	if err := model.DB.First(&user, userID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}
	if user.Role == model.RoleRootUser {
		c.JSON(http.StatusBadRequest, gin.H{"error": "cannot delete root user"})
		return
	}

	model.DB.Delete(&user)
	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}

// AdjustUserQuota 调整用户配额
func AdjustUserQuota(c *gin.Context) {
	var req struct {
		UserID uint `json:"user_id" binding:"required"`
		Quota  int  `json:"quota" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var user model.User
	if err := model.DB.First(&user, req.UserID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "user not found"})
		return
	}

	// 更新配额
	model.DB.Model(&user).Update("used_quota", req.Quota)

	c.JSON(http.StatusOK, gin.H{"message": "quota adjusted"})
}

// ListTokens 列出 Token
func ListTokens(c *gin.Context) {
	userID, _ := c.Get("user_id")

	var tokens []model.Token
	model.DB.Where("user_id = ?", userID).Find(&tokens)

	c.JSON(http.StatusOK, gin.H{"data": tokens})
}

// CreateToken 创建 Token
func CreateToken(c *gin.Context) {
	userID, _ := c.Get("user_id")

	var req struct {
		Name        string `json:"name"`
		ExpiredTime int64  `json:"expired_time"`
		RPMLimit    int    `json:"rpm_limit"`
		TPMLimit    int    `json:"tpm_limit"`
		DailyQuota  int    `json:"daily_quota"`
		ModelLimits string `json:"model_limits"`
		Count       int    `json:"count"` // 批量创建数量
	}
	c.ShouldBindJSON(&req)

	if req.Name == "" {
		req.Name = "Token-" + time.Now().Format("20060102")
	}
	if req.ExpiredTime == 0 {
		req.ExpiredTime = -1 // 永不过期
	}

	count := req.Count
	if count < 1 {
		count = 1
	}
	if count > 10 {
		count = 10 // 最多批量创建 10 个
	}

	tokens := make([]model.Token, 0, count)
	for i := 0; i < count; i++ {
		token := &model.Token{
			UserID:      userID.(uint),
			Key:         model.GenerateKey(),
			Name:        req.Name,
			Status:      model.TokenStatusEnabled,
			ExpiredTime: req.ExpiredTime,
			RPMLimit:    req.RPMLimit,
			TPMLimit:    req.TPMLimit,
			DailyQuota:  req.DailyQuota,
			ModelLimits: req.ModelLimits,
		}
		model.DB.Create(token)
		tokens = append(tokens, *token)
	}

	c.JSON(http.StatusOK, gin.H{"data": tokens})
}

// UpdateToken 更新 Token
func UpdateToken(c *gin.Context) {
	var req struct {
		ID          uint   `json:"id" binding:"required"`
		Name        string `json:"name"`
		Status      int    `json:"status"`
		ExpiredTime int64  `json:"expired_time"`
		RPMLimit    int    `json:"rpm_limit"`
		TPMLimit    int    `json:"tpm_limit"`
		DailyQuota  int    `json:"daily_quota"`
		ModelLimits string `json:"model_limits"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var token model.Token
	if err := model.DB.First(&token, req.ID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "token not found"})
		return
	}

	// 验证所有权
	userID, _ := c.Get("user_id")
	if token.UserID != userID.(uint) {
		c.JSON(http.StatusForbidden, gin.H{"error": "not your token"})
		return
	}

	updates := map[string]interface{}{
		"name":         req.Name,
		"status":       req.Status,
		"expired_time": req.ExpiredTime,
		"rpm_limit":    req.RPMLimit,
		"tpm_limit":    req.TPMLimit,
		"daily_quota":  req.DailyQuota,
		"model_limits": req.ModelLimits,
	}
	model.DB.Model(&token).Updates(updates)

	c.JSON(http.StatusOK, token)
}

// DeleteToken 删除 Token
func DeleteToken(c *gin.Context) {
	var req struct {
		ID uint `json:"id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var token model.Token
	if err := model.DB.First(&token, req.ID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "token not found"})
		return
	}

	// 验证所有权
	userID, _ := c.Get("user_id")
	if token.UserID != userID.(uint) {
		c.JSON(http.StatusForbidden, gin.H{"error": "not your token"})
		return
	}

	model.DB.Delete(&token)
	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}

// ResetRootPassword 重置超级管理员密码
func ResetRootPassword(c *gin.Context) {
	var req struct {
		Username    string `json:"username" binding:"required"`
		NewPassword string `json:"new_password" binding:"required,min=8"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var user model.User
	if err := model.DB.Where("username = ? AND role = ?", req.Username, model.RoleRootUser).First(&user).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "root user not found"})
		return
	}

	if err := user.SetPassword(req.NewPassword); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to set password"})
		return
	}

	model.DB.Save(&user)
	c.JSON(http.StatusOK, gin.H{"message": "password reset"})
}