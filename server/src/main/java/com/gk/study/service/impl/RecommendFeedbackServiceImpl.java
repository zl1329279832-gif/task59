package com.gk.study.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gk.study.entity.RecommendFeedback;
import com.gk.study.mapper.RecommendFeedbackMapper;
import com.gk.study.service.RecommendFeedbackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RecommendFeedbackServiceImpl extends ServiceImpl<RecommendFeedbackMapper, RecommendFeedback> implements RecommendFeedbackService {

    @Autowired
    RecommendFeedbackMapper mapper;

    @Override
    public void block(String userId, String thingId, Long classificationId) {
        // 去重：如果已经屏蔽过，不再重复插入
        if (isBlocked(userId, thingId)) {
            return;
        }
        RecommendFeedback fb = new RecommendFeedback();
        fb.setUserId(userId);
        fb.setThingId(thingId);
        fb.setClassificationId(classificationId);
        fb.setCreateTime(String.valueOf(System.currentTimeMillis()));
        mapper.insert(fb);
    }

    @Override
    public List<String> getBlockedThingIds(String userId) {
        QueryWrapper<RecommendFeedback> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        List<RecommendFeedback> list = mapper.selectList(qw);
        return list.stream().map(RecommendFeedback::getThingId).collect(Collectors.toList());
    }

    @Override
    public List<Long> getBlockedClassificationIds(String userId) {
        QueryWrapper<RecommendFeedback> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        List<RecommendFeedback> list = mapper.selectList(qw);
        return list.stream()
                .map(RecommendFeedback::getClassificationId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean isBlocked(String userId, String thingId) {
        QueryWrapper<RecommendFeedback> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).eq("thing_id", thingId);
        return mapper.selectCount(qw) > 0;
    }
}
