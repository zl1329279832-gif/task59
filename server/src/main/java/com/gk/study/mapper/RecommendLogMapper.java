package com.gk.study.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gk.study.entity.RecommendLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface RecommendLogMapper extends BaseMapper<RecommendLog> {

    /**
     * 按事件类型统计推荐日志数量
     */
    List<Map<String, Object>> getRecommendStats();

    /**
     * 统计某用户在某时间段内的某类型事件数
     */
    int countByType(@Param("type") String type);
}
