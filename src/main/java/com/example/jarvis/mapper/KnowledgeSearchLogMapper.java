package com.example.jarvis.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeSearchLogMapper {

	@Insert("INSERT INTO knowledge_search_log (query, result_count, doc_ids, doc_titles) " +
			"VALUES (#{query}, #{resultCount}, #{docIds}, #{docTitles})")
	void insertLog(@Param("query") String query,
			@Param("resultCount") int resultCount,
			@Param("docIds") String docIds,
			@Param("docTitles") String docTitles);

	@Select("SELECT query, COUNT(*) AS trace_count, MAX(created_at) AS last_seen " +
			"FROM knowledge_search_log WHERE result_count = 0 " +
			"GROUP BY query ORDER BY trace_count DESC, last_seen DESC")
	List<Map<String, Object>> selectBlindSpots();

	@Select("SELECT doc_ids FROM knowledge_search_log WHERE result_count > 0 AND doc_ids IS NOT NULL")
	List<Map<String, Object>> selectAllReferencedDocIds();

	@Select("SELECT DISTINCT conversation_id FROM eval_candidate WHERE source = 'chat'")
	List<Long> selectDislikeConversationIds();
}
