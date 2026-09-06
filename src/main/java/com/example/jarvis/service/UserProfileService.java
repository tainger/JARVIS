package com.example.jarvis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.jarvis.mapper.UserProfileMapper;
import com.example.jarvis.model.UserProfile;

@Service
public class UserProfileService {

	private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

	private static final int EXTRACT_INTERVAL = 5;

	private final UserProfileMapper profileMapper;
	private final ProfileExtractionService extractionService;

	public UserProfileService(UserProfileMapper profileMapper,
			ProfileExtractionService extractionService) {
		this.profileMapper = profileMapper;
		this.extractionService = extractionService;
	}

	public UserProfile getProfile(Long userId) {
		return profileMapper.findByUserId(userId);
	}

	public UserProfile saveProfile(Long userId, String techStack, String frequentTopics,
			String answerStyle, String projectContext) {
		UserProfile profile = profileMapper.findByUserId(userId);
		if (profile == null) {
			profile = new UserProfile();
			profile.setUserId(userId);
			profile.setTechStack(techStack);
			profile.setFrequentTopics(frequentTopics);
			profile.setAnswerStyle(answerStyle);
			profile.setProjectContext(projectContext);
			profile.setConversationCount(0);
			profileMapper.insert(profile);
		} else {
			profile.setTechStack(techStack);
			profile.setFrequentTopics(frequentTopics);
			profile.setAnswerStyle(answerStyle);
			profile.setProjectContext(projectContext);
			profileMapper.update(profile);
		}
		return profileMapper.findByUserId(userId);
	}

	/**
	 * 对话完成后递增计数，每 5 次触发异步画像提取。
	 * 如果用户没有画像记录，先创建一条空的。
	 */
	public void onConversationComplete(Long userId) {
		UserProfile profile = profileMapper.findByUserId(userId);
		if (profile == null) {
			profile = new UserProfile();
			profile.setUserId(userId);
			profile.setConversationCount(1);
			profileMapper.insert(profile);
			return;
		}

		profileMapper.incrementConversationCount(userId);
		int newCount = profile.getConversationCount() + 1;

		if (newCount % EXTRACT_INTERVAL == 0) {
			extractionService.extractAsync(userId);
		}
	}
}
