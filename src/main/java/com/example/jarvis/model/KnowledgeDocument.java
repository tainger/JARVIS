package com.example.jarvis.model;

import java.time.LocalDateTime;

public class KnowledgeDocument {

	private Long id;

	private String title;

	private String fileName;

	private String content;

	private int chunkCount;

	private String status;

	private String errorMessage;

	private int chunkProgress;

	private int chunkTotal;

	private LocalDateTime embeddingStartedAt;

	private LocalDateTime createdAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public int getChunkCount() {
		return chunkCount;
	}

	public void setChunkCount(int chunkCount) {
		this.chunkCount = chunkCount;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public int getChunkProgress() {
		return chunkProgress;
	}

	public void setChunkProgress(int chunkProgress) {
		this.chunkProgress = chunkProgress;
	}

	public int getChunkTotal() {
		return chunkTotal;
	}

	public void setChunkTotal(int chunkTotal) {
		this.chunkTotal = chunkTotal;
	}

	public LocalDateTime getEmbeddingStartedAt() {
		return embeddingStartedAt;
	}

	public void setEmbeddingStartedAt(LocalDateTime embeddingStartedAt) {
		this.embeddingStartedAt = embeddingStartedAt;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
