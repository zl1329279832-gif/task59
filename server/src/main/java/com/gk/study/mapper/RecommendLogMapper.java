package com.gk.study.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gk.study.entity.RecommendLog;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface RecommendLogMapper extends BaseMapper<RecommendLog> {

    List<Map<String, Object>> getRecommendStats();

    int countByType(String type);
}
