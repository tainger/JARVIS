package com.example.jarvis.controller;

import java.util.Map;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.jarvis.model.UserProfile;
import com.example.jarvis.service.ProfileExtractionService;
import com.example.jarvis.service.UserProfileService;

@RestController
@RequestMapping("/api/profile")
public class UserProfileController {

	private final UserProfileService profileService;
	private final ProfileExtractionService extractionService;

	public UserProfileController(UserProfileService profileService,
			ProfileExtractionService extractionService) {
		this.profileService = profileService;
		this.extractionService = extractionService;
	}

	@GetMapping
	public UserProfile getProfile() {
		return profileService.getProfile(currentUserId());
	}

	@PutMapping
	public UserProfile updateProfile(@RequestBody Map<String, String> body) {
		return profileService.saveProfile(currentUserId(),
				body.get("techStack"),
				body.get("frequentTopics"),
				body.get("answerStyle"),
				body.get("projectContext"));
	}

	@PostMapping("/extract")
	public UserProfile extractProfile() {
		return extractionService.extract(currentUserId());
	}

	private Long currentUserId() {
		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		if (principal instanceof Long id) {
			return id;
		}
		if (principal instanceof Integer id) {
			return id.longValue();
		}
		throw new IllegalStateException("无法获取当前用户身份，请重新登录");
	}
}
