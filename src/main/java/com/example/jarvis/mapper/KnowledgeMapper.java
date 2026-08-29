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

	int insertDocument(KnowledgeDocument document);

	int deleteDocument(@Param("id") Long id);

	List<KnowledgeChunk> findAllChunks();

	List<KnowledgeChunk> findChunksByDocumentId(@Param("documentId") Long documentId);

	int insertChunk(KnowledgeChunk chunk);

	int deleteChunksByDocumentId(@Param("documentId") Long documentId);

}
