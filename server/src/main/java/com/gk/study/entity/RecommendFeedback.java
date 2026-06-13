package com.gk.study.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 推荐反馈实体
 * 记录用户对推荐结果的"不感兴趣"操作
 */
@Data
@TableName("b_recommend_feedback")
public class RecommendFeedback implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;

    @TableField
    public String userId;       // 用户id

    @TableField
    public String thingId;      // 资源id

    @TableField
    public Long classificationId; // 资源所属分类id（冗余存储，便于按分类降权）

    @TableField
    public String createTime;   // 反馈时间
}
