package com.gk.study.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gk.study.entity.RecommendFeedback;
import com.gk.study.mapper.RecommendFeedbackMapper;
import com.gk.study.service.RecommendFeedbackService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
class RecommendFeedbackServiceImpl extends ServiceImpl<RecommendFeedbackMapper, RecommendFeedback> implements RecommendFeedbackService {
    @Autowired
    RecommendFeedbackMapper mapper;

    @Override
    public void block(String userId, String thingId, Long classificationId) {
        RecommendFeedback feedback = new RecommendFeedback();
        feedback.setUserId(userId);
        feedback.setThingId(thingId);
        feedback.setClassificationId(classificationId);
        feedback.setCreateTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        mapper.insert(feedback);
    }

    @Override
    public List<String> getBlockedThingIds(String userId) {
        QueryWrapper<RecommendFeedback> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        List<RecommendFeedback> list = mapper.selectList(queryWrapper);
        return list.stream().map(RecommendFeedback::getThingId).collect(Collectors.toList());
    }

    @Override
    public List<Long> getBlockedClassificationIds(String userId) {
        QueryWrapper<RecommendFeedback> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        queryWrapper.isNotNull("classification_id");
        List<RecommendFeedback> list = mapper.selectList(queryWrapper);
        return list.stream()
                .map(RecommendFeedback::getClassificationId)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean isBlocked(String userId, String thingId) {
        QueryWrapper<RecommendFeedback> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("thing_id", thingId);
        return mapper.selectCount(queryWrapper) > 0;
    }
}
