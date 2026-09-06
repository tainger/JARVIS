package com.example.jarvis.model;

import java.time.LocalDateTime;

public class UserProfile {

	private Long id;
	private Long userId;
	private String techStack;
	private String frequentTopics;
	private String answerStyle;
	private String projectContext;
	private String rawSummary;
	private int conversationCount;
	private LocalDateTime updatedAt;
	private LocalDateTime createdAt;

	public boolean hasContent() {
		return (techStack != null && !techStack.isBlank())
				|| (frequentTopics != null && !frequentTopics.isBlank())
				|| (answerStyle != null && !answerStyle.isBlank())
				|| (projectContext != null && !projectContext.isBlank());
	}

	public String toPromptBlock() {
		StringBuilder sb = new StringBuilder("\n\n【用户画像】\n");
		if (techStack != null && !techStack.isBlank())
			sb.append("技术栈：").append(techStack).append("\n");
		if (frequentTopics != null && !frequentTopics.isBlank())
			sb.append("常问主题：").append(frequentTopics).append("\n");
		if (answerStyle != null && !answerStyle.isBlank())
			sb.append("回答风格：").append(answerStyle).append("\n");
		if (projectContext != null && !projectContext.isBlank())
			sb.append("项目上下文：").append(projectContext).append("\n");
		sb.append("\n请根据以上用户画像调整回答的深度和风格。");
		return sb.toString();
	}

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getUserId() { return userId; }
	public void setUserId(Long userId) { this.userId = userId; }
	public String getTechStack() { return techStack; }
	public void setTechStack(String techStack) { this.techStack = techStack; }
	public String getFrequentTopics() { return frequentTopics; }
	public void setFrequentTopics(String frequentTopics) { this.frequentTopics = frequentTopics; }
	public String getAnswerStyle() { return answerStyle; }
	public void setAnswerStyle(String answerStyle) { this.answerStyle = answerStyle; }
	public String getProjectContext() { return projectContext; }
	public void setProjectContext(String projectContext) { this.projectContext = projectContext; }
	public String getRawSummary() { return rawSummary; }
	public void setRawSummary(String rawSummary) { this.rawSummary = rawSummary; }
	public int getConversationCount() { return conversationCount; }
	public void setConversationCount(int conversationCount) { this.conversationCount = conversationCount; }
	public LocalDateTime getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
