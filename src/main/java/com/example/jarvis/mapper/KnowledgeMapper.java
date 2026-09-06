package com.example.jarvis.mapper;

import java.util.List;

import com.example.jarvis.model.KnowledgeChunk;
import com.example.jarvis.model.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeMapper {

	List<KnowledgeDocument> findAllDocuments();

	KnowledgeDocument findDocumentById(@Param("id") Long id);

	List<KnowledgeDocument> findByStatus(@Param("status") String status);

	int insertDocument(KnowledgeDocument document);

	int updateStatus(@Param("id") Long id, @Param("status") String status,
			@Param("errorMessage") String errorMessage);

	/**
	 * CAS 认领任务：将 status 从 processing 改为 embedding，同时记录开始时间。
	 * 返回影响行数，1 表示认领成功，0 表示已被其他线程认领。
	 */
	int claimForEmbedding(@Param("id") Long id);

	/**
	 * 重置卡死的 embedding 任务为 processing（超过指定分钟数仍在 embedding 状态）。
	 * 返回重置的条数。
	 */
	int resetStuckEmbedding(@Param("timeoutMinutes") int timeoutMinutes);

	int updateChunkProgress(@Param("id") Long id, @Param("progress") int progress);

	int updateChunkEmbedding(@Param("documentId") Long documentId, @Param("seq") int seq,
			@Param("embedding") String embedding, @Param("dim") int dim);

	int deleteDocument(@Param("id") Long id);

	List<KnowledgeChunk> findAllChunks();

	List<KnowledgeChunk> findChunksByDocumentId(@Param("documentId") Long documentId);

	int insertChunk(KnowledgeChunk chunk);

	int deleteChunksByDocumentId(@Param("documentId") Long documentId);

}
