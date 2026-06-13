package com.gk.study.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 推荐日志实体
 * 记录推荐系统的曝光、点击、屏蔽事件
 */
@Data
@TableName("b_recommend_log")
public class RecommendLog implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;

    @TableField
    public String userId;       // 用户id

    @TableField
    public String thingId;      // 资源id

    @TableField
    public String type;         // 事件类型: exposure(曝光), click(点击), block(屏蔽)

    @TableField
    public String reason;       // 推荐原因

    @TableField
    public String createTime;   // 事件时间
}
