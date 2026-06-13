package com.gk.study.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gk.study.entity.RecommendLog;
import com.gk.study.mapper.RecommendLogMapper;
import com.gk.study.service.RecommendLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
class RecommendLogServiceImpl extends ServiceImpl<RecommendLogMapper, RecommendLog> implements RecommendLogService {
    @Autowired
    RecommendLogMapper mapper;

    @Override
    public void log(String userId, String thingId, String type, String reason) {
        RecommendLog log = new RecommendLog();
        log.setUserId(userId);
        log.setThingId(thingId);
        log.setType(type);
        log.setReason(reason);
        log.setCreateTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        mapper.insert(log);
    }

    @Override
    public List<Map<String, Object>> getStats() {
        return mapper.getRecommendStats();
    }

    @Override
    public int countByType(String type) {
        return mapper.countByType(type);
    }

    @Override
    public boolean hasClick(String userId, String thingId) {
        QueryWrapper<RecommendLog> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId)
                .eq("thing_id", thingId)
                .eq("type", "click");
        return mapper.selectCount(queryWrapper) > 0;
    }
}
