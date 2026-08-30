package com.example.jarvis.model;

import java.time.LocalDateTime;

/**
 * RAG 评测候选池条目：坏 case 先入池，triage（转正/丢弃）后进标注集。
 */
public class EvalCandidate {

	private Long id;

	private String question;

	private String questionNorm;

	private String note;

	private String expectedDoc;

	private String source;

	private String chatRef;

	private String status;

	private LocalDateTime createdAt;

	private LocalDateTime triagedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getQuestion() {
		return question;
	}

	public void setQuestion(String question) {
		this.question = question;
	}

	public String getQuestionNorm() {
		return questionNorm;
	}

	public void setQuestionNorm(String questionNorm) {
		this.questionNorm = questionNorm;
	}

	public String getNote() {
		return note;
	}

	public void setNote(String note) {
		this.note = note;
	}

	public String getExpectedDoc() {
		return expectedDoc;
	}

	public void setExpectedDoc(String expectedDoc) {
		this.expectedDoc = expectedDoc;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public String getChatRef() {
		return chatRef;
	}

	public void setChatRef(String chatRef) {
		this.chatRef = chatRef;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getTriagedAt() {
		return triagedAt;
	}

	public void setTriagedAt(LocalDateTime triagedAt) {
		this.triagedAt = triagedAt;
	}
}
