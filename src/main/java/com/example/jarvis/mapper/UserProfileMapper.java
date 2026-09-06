package com.example.jarvis.mapper;

import com.example.jarvis.model.UserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserProfileMapper {

	UserProfile findByUserId(@Param("userId") Long userId);

	int insert(UserProfile profile);

	int update(UserProfile profile);

	int incrementConversationCount(@Param("userId") Long userId);
}
