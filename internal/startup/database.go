package startup

import (
	"fmt"
	"honoka-chan/internal/constant"
	ghomemodel "honoka-chan/internal/model/ghome"
	loginmodel "honoka-chan/internal/model/login"
	unitmodel "honoka-chan/internal/model/unit"
	usermodel "honoka-chan/internal/model/user"
	"honoka-chan/pkg/db"
	"log"
	"sort"
	"time"

	"xorm.io/xorm"
)

var (
	userEng *xorm.Session
)

func CreateTables() error {
	models := []any{
		new(ghomemodel.DeviceKey),
		new(loginmodel.AuthKey),
		new(usermodel.UserAccessoryWear),
		new(usermodel.UserDeck),
		new(usermodel.UserDeckUnit),
		new(usermodel.UserKey),
		new(usermodel.UserLiveGoal),
		new(usermodel.UserLiveStatus),
		new(usermodel.UserLiveInProgress),
		new(usermodel.UserLiveRandom),
		new(usermodel.UserLiveRecord),
		new(usermodel.UserFriend),
		new(usermodel.UserGreet),
		new(usermodel.UserPref),
		new(usermodel.Users),
		new(usermodel.UserUnit),
		new(usermodel.UserUnitSkillEquip),
	}

	for _, model := range models {
		if err := db.UserEng.Sync2(model); err != nil {
			return fmt.Errorf("同步表失败: %w", err)
		}
	}

	if err := MigrateUserPref(); err != nil {
		return err
	}
	if err := MigrateUserLiveData(); err != nil {
		return err
	}
	return nil
}

func MigrateUserPref() error {
	session := db.UserEng.NewSession()
	defer session.Close()

	prefList := []usermodel.UserPref{}
	err := session.Table(new(usermodel.UserPref)).
		Where("profile_version < ?", usermodel.CurrentUserPrefProfileVersion).
		Or("profile_version IS NULL").
		Find(&prefList)
	if err != nil {
		return fmt.Errorf("迁移 user_pref 失败: %w", err)
	}

	for _, pref := range prefList {
		pref.ApplyProfileDefaults()
		_, err = session.Table(new(usermodel.UserPref)).
			ID(pref.ID).
			Cols(usermodel.UserPrefProfileColumns()...).
			Update(&pref)
		if err != nil {
			return fmt.Errorf("迁移 user_pref 失败: %w", err)
		}
	}
	return nil
}

type liveGoalRewardMigrationRow struct {
	LiveGoalRewardID int `xorm:"live_goal_reward_id"`
	LiveDifficultyID int `xorm:"live_difficulty_id"`
	LiveGoalType     int `xorm:"live_goal_type"`
	Rank             int `xorm:"rank"`
}

type liveGoalInfoMigrationRow struct {
	LiveDifficultyID int `xorm:"live_difficulty_id"`
	CRankScore       int `xorm:"c_rank_score"`
	BRankScore       int `xorm:"b_rank_score"`
	ARankScore       int `xorm:"a_rank_score"`
	SRankScore       int `xorm:"s_rank_score"`
	CRankCombo       int `xorm:"c_rank_combo"`
	BRankCombo       int `xorm:"b_rank_combo"`
	ARankCombo       int `xorm:"a_rank_combo"`
	SRankCombo       int `xorm:"s_rank_combo"`
	CRankComplete    int `xorm:"c_rank_complete"`
	BRankComplete    int `xorm:"b_rank_complete"`
	ARankComplete    int `xorm:"a_rank_complete"`
	SRankComplete    int `xorm:"s_rank_complete"`
}

func liveRankForMigration(value int, cRank int, bRank int, aRank int, sRank int) int {
	switch {
	case value >= sRank:
		return 1
	case value >= aRank:
		return 2
	case value >= bRank:
		return 3
	case value >= cRank:
		return 4
	default:
		return 5
	}
}

func MigrateUserLiveData() error {
	session := db.UserEng.NewSession()
	defer session.Close()

	recordRows := []usermodel.UserLiveRecord{}
	if err := session.Table(new(usermodel.UserLiveRecord)).Find(&recordRows); err != nil {
		return fmt.Errorf("迁移 user_live_status 失败: %w", err)
	}

	existingStatusRows := []usermodel.UserLiveStatus{}
	if err := session.Table(new(usermodel.UserLiveStatus)).Find(&existingStatusRows); err != nil {
		return fmt.Errorf("迁移 user_live_status 失败: %w", err)
	}

	type liveStatusKey struct {
		UserID           int
		LiveDifficultyID int
	}

	statusMap := make(map[liveStatusKey]usermodel.UserLiveStatus, len(existingStatusRows))
	for _, row := range existingStatusRows {
		statusMap[liveStatusKey{UserID: row.UserID, LiveDifficultyID: row.LiveDifficultyID}] = row
	}

	now := time.Now().Unix()
	for _, row := range recordRows {
		key := liveStatusKey{UserID: row.UserID, LiveDifficultyID: row.LiveDifficultyID}
		status, ok := statusMap[key]
		if !ok {
			status = usermodel.UserLiveStatus{
				UserID:           row.UserID,
				LiveDifficultyID: row.LiveDifficultyID,
				HiScore:          row.TotalScore,
				HiComboCount:     row.MaxCombo,
				ClearCnt:         1,
				InsertDate:       now,
				UpdateDate:       now,
			}
			if _, err := session.Insert(&status); err != nil {
				return fmt.Errorf("迁移 user_live_status 失败: %w", err)
			}
			statusMap[key] = status
			continue
		}

		updated := false
		if row.TotalScore > status.HiScore {
			status.HiScore = row.TotalScore
			updated = true
		}
		if row.MaxCombo > status.HiComboCount {
			status.HiComboCount = row.MaxCombo
			updated = true
		}
		if status.ClearCnt <= 0 {
			status.ClearCnt = 1
			updated = true
		}
		if updated {
			status.UpdateDate = now
			if _, err := session.Table(new(usermodel.UserLiveStatus)).
				Where("user_id = ? AND live_difficulty_id = ?", status.UserID, status.LiveDifficultyID).
				Cols("hi_score", "hi_combo_count", "clear_cnt", "update_date").
				Update(&status); err != nil {
				return fmt.Errorf("迁移 user_live_status 失败: %w", err)
			}
			statusMap[key] = status
		}
	}

	liveGoalInfoMap := map[int]liveGoalInfoMigrationRow{}
	loadLiveGoalInfoRows := func(table string) error {
		rows := []liveGoalInfoMigrationRow{}
		err := db.MainEng.Table(table).Alias("live").
			Join("LEFT", "live_setting_m setting", "live.live_setting_id = setting.live_setting_id").
			Select(`
				live.live_difficulty_id,
				setting.c_rank_score,
				setting.b_rank_score,
				setting.a_rank_score,
				setting.s_rank_score,
				setting.c_rank_combo,
				setting.b_rank_combo,
				setting.a_rank_combo,
				setting.s_rank_combo,
				live.c_rank_complete,
				live.b_rank_complete,
				live.a_rank_complete,
				live.s_rank_complete
			`).
			Find(&rows)
		if err != nil {
			return fmt.Errorf("迁移 user_live_goal 失败: %w", err)
		}
		for _, row := range rows {
			liveGoalInfoMap[row.LiveDifficultyID] = row
		}
		return nil
	}
	if err := loadLiveGoalInfoRows("normal_live_m"); err != nil {
		return err
	}
	if err := loadLiveGoalInfoRows("special_live_m"); err != nil {
		return err
	}

	goalRewardRows := []liveGoalRewardMigrationRow{}
	if err := db.MainEng.Table("live_goal_reward_m").
		OrderBy("live_difficulty_id ASC, live_goal_type ASC, rank ASC, live_goal_reward_id ASC").
		Find(&goalRewardRows); err != nil {
		return fmt.Errorf("迁移 user_live_goal 失败: %w", err)
	}

	goalRewardMap := map[int][]liveGoalRewardMigrationRow{}
	for _, row := range goalRewardRows {
		goalRewardMap[row.LiveDifficultyID] = append(goalRewardMap[row.LiveDifficultyID], row)
	}

	existingGoalRows := []usermodel.UserLiveGoal{}
	if err := session.Table(new(usermodel.UserLiveGoal)).
		Where("live_goal_reward_id > 0").
		Find(&existingGoalRows); err != nil {
		return fmt.Errorf("迁移 user_live_goal 失败: %w", err)
	}

	existingGoalMap := make(map[liveStatusKey]map[int]struct{})
	for _, row := range existingGoalRows {
		key := liveStatusKey{UserID: row.UserID, LiveDifficultyID: row.LiveDifficultyID}
		if existingGoalMap[key] == nil {
			existingGoalMap[key] = map[int]struct{}{}
		}
		existingGoalMap[key][row.LiveGoalRewardID] = struct{}{}
	}

	statusKeys := make([]liveStatusKey, 0, len(statusMap))
	for key := range statusMap {
		statusKeys = append(statusKeys, key)
	}
	sort.Slice(statusKeys, func(i, j int) bool {
		if statusKeys[i].UserID == statusKeys[j].UserID {
			return statusKeys[i].LiveDifficultyID < statusKeys[j].LiveDifficultyID
		}
		return statusKeys[i].UserID < statusKeys[j].UserID
	})

	for _, key := range statusKeys {
		status := statusMap[key]
		liveInfo, ok := liveGoalInfoMap[key.LiveDifficultyID]
		if !ok {
			continue
		}

		scoreRank := liveRankForMigration(status.HiScore, liveInfo.CRankScore, liveInfo.BRankScore, liveInfo.ARankScore, liveInfo.SRankScore)
		comboRank := liveRankForMigration(status.HiComboCount, liveInfo.CRankCombo, liveInfo.BRankCombo, liveInfo.ARankCombo, liveInfo.SRankCombo)
		clearRank := liveRankForMigration(status.ClearCnt, liveInfo.CRankComplete, liveInfo.BRankComplete, liveInfo.ARankComplete, liveInfo.SRankComplete)

		for _, reward := range goalRewardMap[key.LiveDifficultyID] {
			achieved := false
			switch reward.LiveGoalType {
			case 1:
				achieved = scoreRank <= reward.Rank
			case 2:
				achieved = comboRank <= reward.Rank
			case 3:
				achieved = clearRank <= reward.Rank
			}
			if !achieved {
				continue
			}

			if existingGoalMap[key] != nil {
				if _, ok := existingGoalMap[key][reward.LiveGoalRewardID]; ok {
					continue
				}
			}

			if _, err := session.Insert(&usermodel.UserLiveGoal{
				UserID:           key.UserID,
				LiveDifficultyID: key.LiveDifficultyID,
				LiveGoalRewardID: reward.LiveGoalRewardID,
				GoalType:         constant.LiveGoalType(reward.LiveGoalType),
				Rank:             reward.Rank,
				CompletedAt:      now,
			}); err != nil {
				return fmt.Errorf("迁移 user_live_goal 失败: %w", err)
			}
			if existingGoalMap[key] == nil {
				existingGoalMap[key] = map[int]struct{}{}
			}
			existingGoalMap[key][reward.LiveGoalRewardID] = struct{}{}
		}
	}
	return nil
}

func LoadUnitData() (err error) {
	userEng = db.UserEng.NewSession()
	defer func() {
		if recovered := recover(); recovered != nil {
			if userEng != nil {
				_ = userEng.Rollback()
			}
			switch v := recovered.(type) {
			case error:
				err = fmt.Errorf("同步失败: %w", v)
			default:
				err = fmt.Errorf("同步失败: %v", v)
			}
		}
		if userEng != nil {
			userEng.Close()
		}
	}()

	err = userEng.Begin()
	CheckErr(err)

	commonUnitExist, err := userEng.IsTableExist(new(unitmodel.CommonUnitData))
	CheckErr(err)
	userUnitExist, err := userEng.IsTableExist(new(usermodel.UserUnitData))
	CheckErr(err)

	if commonUnitExist && userUnitExist {
		_ = userEng.Rollback()
		return nil
	}

	log.Println("卡片数据不存在，正在同步...")

	CheckErr(userEng.DropTable(new(unitmodel.CommonUnitData)))
	CheckErr(userEng.CreateTable(new(unitmodel.CommonUnitData)))
	CheckErr(userEng.DropTable(new(usermodel.UserUnitData)))
	CheckErr(userEng.CreateTable(new(usermodel.UserUnitData)))

	// 1. 预加载 level_up_pattern 到内存
	type levelUpRow struct {
		UnitLevelUpPatternId int `xorm:"unit_level_up_pattern_id"`
		UnitLevel            int `xorm:"unit_level"`
		NextExp              int `xorm:"next_exp"`
	}
	var patternRows []levelUpRow
	err = db.MainEng.Table("unit_level_up_pattern_m").OrderBy("unit_level_up_pattern_id ASC, unit_level ASC").Find(&patternRows)
	CheckErr(err)

	type patternInfo struct {
		maxLevel int
		nextExp  int
		sumExp   int
	}
	patternMap := make(map[int]patternInfo, 100)
	for _, row := range patternRows {
		cur := patternMap[row.UnitLevelUpPatternId]
		if row.UnitLevel > cur.maxLevel {
			cur.sumExp = cur.nextExp
			cur.maxLevel = row.UnitLevel
			cur.nextExp = row.NextExp
			patternMap[row.UnitLevelUpPatternId] = cur
		}
	}

	// 2. 预加载 limit pattern (常量)
	var limitSumExp int
	_, err = db.MainEng.Table("unit_level_limit_pattern_m").
		Where("unit_level_limit_id = 1 AND unit_level = 349").
		Cols("next_exp").Get(&limitSumExp)
	CheckErr(err)

	// 3. 预加载签名卡列表到内存集合
	var signUnitIDs []int
	err = db.MainEng.Table("unit_sign_asset_m").Cols("unit_id").Find(&signUnitIDs)
	CheckErr(err)
	signedMap := make(map[int]bool, len(signUnitIDs))
	for _, id := range signUnitIDs {
		signedMap[id] = true
	}

	// 4. 一次性获取当前存在的 user_id (启动时通常为空)
	var userID []int
	err = userEng.Table(new(usermodel.Users)).Cols("user_id").Find(&userID)
	CheckErr(err)

	// 5. 获取所有卡片数据
	var unitData []unitmodel.UnitM
	err = db.MainEng.Table(new(unitmodel.UnitM)).OrderBy("unit_id ASC").Find(&unitData)
	CheckErr(err)

	commonList := make([]unitmodel.CommonUnitData, 0, len(unitData))
	userUnitList := make([]usermodel.UserUnitData, 0, len(unitData)*len(userID))

	checked := false
	nowTs := time.Now().Unix()

	for _, u := range unitData {
		pInfo := patternMap[u.UnitLevelUpPatternId]
		unitMaxLevel := pInfo.maxLevel
		nextExp := pInfo.nextExp
		sumExp := pInfo.sumExp

		smileMax := u.SmileMax
		pureMax := u.PureMax
		coolMax := u.CoolMax

		if nextExp != 0 {
			sumExp = limitSumExp
			unitMaxLevel = 350
			smileMax += 6000
			pureMax += 6000
			coolMax += 6000
		}

		var maxLove, skillLevel, skillExp, removableSkillCapacity, levelLimitID int
		switch u.Rarity {
		case 1:
			maxLove = 50
			skillExp = 0
			skillLevel = 0
			removableSkillCapacity = 0
			levelLimitID = 0
		case 2:
			maxLove = 200
			skillExp = 490
			skillLevel = 8
			removableSkillCapacity = 1
			levelLimitID = 0
		case 3:
			maxLove = 500
			skillExp = 4900
			skillLevel = 8
			removableSkillCapacity = 2
			levelLimitID = 0
		case 4:
			maxLove = 1000
			skillExp = 29900
			skillLevel = 8
			removableSkillCapacity = 8
			levelLimitID = 1
		case 5:
			maxLove = 750
			skillExp = 12700
			skillLevel = 8
			removableSkillCapacity = 3
			levelLimitID = 0
		}

		if smileMax == 1 {
			maxLove = 0
			skillExp = 0
			skillLevel = 0
			removableSkillCapacity = 0
		}

		isSigned := signedMap[u.UnitId]

		nameStr := ""
		if u.NameEn != nil {
			nameStr = *u.NameEn
		}

		unitCommon := unitmodel.CommonUnitData{
			UnitNumber:                  u.UnitNumber,
			UnitID:                      u.UnitId,
			UnitTypeID:                  u.UnitTypeId,
			Name:                        nameStr,
			Eponym:                      u.EponymEn,
			Rarity:                      u.Rarity,
			Attribute:                   u.AttributeId,
			Smile:                       smileMax,
			Cute:                        pureMax,
			Cool:                        coolMax,
			Exp:                         sumExp,
			Level:                       unitMaxLevel,
			MaxLevel:                    unitMaxLevel,
			LevelLimitID:                levelLimitID,
			Rank:                        u.RankMin,
			MaxRank:                     u.RankMax,
			Love:                        maxLove,
			MaxLove:                     maxLove,
			UnitSkillExp:                skillExp,
			UnitSkillLevel:              skillLevel,
			MaxHp:                       u.HpMax,
			UnitRemovableSkillCapacity:  removableSkillCapacity,
			IsRankMax:                   true,
			IsLoveMax:                   true,
			IsLevelMax:                  true,
			IsSigned:                    isSigned,
			IsSkillLevelMax:             true,
			IsRemovableSkillCapacityMax: true,
			InsertDate:                  nowTs,
		}
		commonList = append(commonList, unitCommon)

		for _, id := range userID {
			userUnit := usermodel.UserUnitData{
				UnitID:       u.UnitId,
				FavoriteFlag: false,
				DisplayRank:  u.RankMax,
				UserID:       id,
				InsertDate:   nowTs,
			}
			if !checked {
				userUnit.UnitOwningUserID = 38383
				checked = true
			}
			userUnitList = append(userUnitList, userUnit)
		}
	}

	// 分批批量插入 common_unit_data
	batchSize := 200
	for i := 0; i < len(commonList); i += batchSize {
		end := i + batchSize
		if end > len(commonList) {
			end = len(commonList)
		}
		batch := commonList[i:end]
		_, err = userEng.Insert(&batch)
		CheckErr(err)
	}

	// 分批批量插入 user_unit_data
	for i := 0; i < len(userUnitList); i += batchSize {
		end := i + batchSize
		if end > len(userUnitList) {
			end = len(userUnitList)
		}
		batch := userUnitList[i:end]
		_, err = userEng.Insert(&batch)
		CheckErr(err)
	}

	err = userEng.Commit()
	CheckErr(err)

	log.Println("卡片数据同步完成！")
	return nil
}

func CheckErr(err error) {
	if err != nil {
		panic(err)
	}
}
