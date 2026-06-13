package com.gk.study.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gk.study.entity.RecommendLog;
import com.gk.study.mapper.RecommendLogMapper;
import com.gk.study.service.RecommendLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RecommendLogServiceImpl extends ServiceImpl<RecommendLogMapper, RecommendLog> implements RecommendLogService {

    @Autowired
    RecommendLogMapper mapper;

    @Override
    public void log(String userId, String thingId, String type, String reason) {
        RecommendLog log = new RecommendLog();
        log.setUserId(userId);
        log.setThingId(thingId);
        log.setType(type);
        log.setReason(reason);
        log.setCreateTime(String.valueOf(System.currentTimeMillis()));
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
        QueryWrapper<RecommendLog> qw = new QueryWrapper<>();
        qw.eq("user_id", userId)
          .eq("thing_id", thingId)
          .eq("type", "click");
        return mapper.selectCount(qw) > 0;
    }
}
