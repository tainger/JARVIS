package com.example.jarvis.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 对话分析聚合查询数据访问层。
 * 所有方法返回 Map，由 Service 层做类型转换。
 */
@Mapper
public interface AnalyticsMapper {

	/** 总览统计：会话数、trace 数、工具调用数、平均步数、截断数、dislike 数 */
	Map<String, Object> selectSummary(@Param("days") Integer days);

	/** 工具调用频率：工具名 + 调用次数 + 平均耗时 + 截断次数 */
	List<Map<String, Object>> selectToolFrequency();

	/** 每日趋势：日期 + 会话数 + 工具调用数 + 截断数 + dislike 数 */
	List<Map<String, Object>> selectDailyTrend(@Param("days") Integer days);

	/** 技能使用分布：按工具组合推断技能，返回技能名 + 会话数 */
	List<Map<String, Object>> selectSkillDistribution();
}
