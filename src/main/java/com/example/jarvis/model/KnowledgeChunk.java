package com.example.jarvis.model;

public class KnowledgeChunk {

	private Long id;

	private Long documentId;

	private int seq;

	private String content;

	/** JSON float 数组形式存储的向量；null 表示未成功向量化 */
	private String embedding;

	private Integer dim;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getDocumentId() {
		return documentId;
	}

	public void setDocumentId(Long documentId) {
		this.documentId = documentId;
	}

	public int getSeq() {
		return seq;
	}

	public void setSeq(int seq) {
		this.seq = seq;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getEmbedding() {
		return embedding;
	}

	public void setEmbedding(String embedding) {
		this.embedding = embedding;
	}

	public Integer getDim() {
		return dim;
	}

	public void setDim(Integer dim) {
		this.dim = dim;
	}

}
