package com.gk.study.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class RecommendItem implements Serializable {
    public Thing thing;
    public String reason;
    public double score;

    public RecommendItem() {
    }

    public RecommendItem(Thing thing, String reason, double score) {
        this.thing = thing;
        this.reason = reason;
        this.score = score;
    }
}
