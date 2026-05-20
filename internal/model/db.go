package model

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/mooncell/modelhub/internal/config"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

var DB *gorm.DB

// InitDB 初始化数据库连接
func InitDB() error {
	dbPath := config.AppConfig.DatabasePath

	// 确保数据目录存在
	dir := filepath.Dir(dbPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("failed to create data directory: %w", err)
	}

	// 打开数据库连接
	var err error
	DB, err = gorm.Open(sqlite.Open(dbPath), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Info),
	})
	if err != nil {
		return fmt.Errorf("failed to connect to database: %w", err)
	}

	// 自动迁移表结构
	if err := autoMigrate(); err != nil {
		return fmt.Errorf("failed to migrate database: %w", err)
	}

	// 初始化默认数据
	if err := initDefaultData(); err != nil {
		return fmt.Errorf("failed to initialize default data: %w", err)
	}

	return nil
}

// autoMigrate 自动迁移表结构
func autoMigrate() error {
	return DB.AutoMigrate(
		&User{},
		&Token{},
		&Instance{},
		&Log{},
		&Option{},
	)
}

// initDefaultData 初始化默认数据
func initDefaultData() error {
	// 检查是否已存在超级管理员
	var count int64
	DB.Model(&User{}).Where("role = ?", RoleRootUser).Count(&count)
	if count > 0 {
		return nil // 已存在，跳过
	}

	// 创建默认超级管理员
	rootUser := &User{
		Username:    "root",
		DisplayName: "Root Administrator",
		Role:        RoleRootUser,
		Status:      UserStatusEnabled,
		Group:       "default",
	}
	if err := rootUser.SetPassword("root123456"); err != nil {
		return err
	}
	if err := DB.Create(rootUser).Error; err != nil {
		return err
	}

	// 创建默认实例（GLM 官方 API）
	defaultInstance := &Instance{
		Name:     "glm-official",
		URL:      "https://open.bigmodel.cn/api/paas/v4",
		Source:   InstanceSourceOfficial,
		Weight:   1,
		Priority: 0,
		RPMLimit: 60,
		TPMLimit: 60000,
		PoolKey:  "default",
		IsActive: true,
	}
	if err := DB.Create(defaultInstance).Error; err != nil {
		return err
	}

	// 初始化系统配置
	defaultOptions := []Option{
		{Key: OptionSystemName, Value: "MoonCell ModelHub"},
		{Key: OptionDefaultUserRPM, Value: "60"},
		{Key: OptionDefaultUserTPM, Value: "60000"},
		{Key: OptionDefaultUserDaily, Value: "1000"},
		{Key: OptionQueueCapacity, Value: "128"},
		{Key: OptionSampleCount, Value: "5"},
		{Key: OptionRetryTimes, Value: "3"},
	}
	for _, opt := range defaultOptions {
		DB.FirstOrCreate(&opt, Option{Key: opt.Key})
	}

	return nil
}

// CloseDB 关闭数据库连接
func CloseDB() error {
	sqlDB, err := DB.DB()
	if err != nil {
		return err
	}
	return sqlDB.Close()
}

// GetOption 获取系统配置值
func GetOption(key string) string {
	var opt Option
	if err := DB.First(&opt, "key = ?", key).Error; err != nil {
		return ""
	}
	return opt.Value
}

// SetOption 设置系统配置值
func SetOption(key, value string) error {
	return DB.Save(&Option{Key: key, Value: value}).Error
}