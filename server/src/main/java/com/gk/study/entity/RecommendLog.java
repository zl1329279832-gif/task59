package com.gk.study.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

@Data
@TableName("b_recommend_log")
public class RecommendLog implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    @TableField
    public String userId;
    @TableField
    public String thingId;
    @TableField
    public String type; // expose, click, block
    @TableField
    public String reason;
    @TableField
    public String createTime;
}
