package com.gk.study.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * 推荐结果项
 * 包含资源信息 + 推荐理由
 */
@Data
public class RecommendItem implements Serializable {
    /** 资源对象 */
    public Thing thing;

    /** 推荐理由，如"同类收藏较多""浏览过相近分类""已购资源的进阶内容" */
    public String reason;

    /** 推荐得分（内部排序用，不强制返回给前端） */
    public double score;

    public RecommendItem() {}

    public RecommendItem(Thing thing, String reason, double score) {
        this.thing = thing;
        this.reason = reason;
        this.score = score;
    }
}
