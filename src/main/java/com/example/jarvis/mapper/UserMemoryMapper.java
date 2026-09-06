package com.example.jarvis.mapper;

import java.util.List;

import com.example.jarvis.model.UserMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户长期记忆数据访问层
 */
@Mapper
public interface UserMemoryMapper {

	/**
	 * 查询用户的所有有效记忆（按最近访问倒序）
	 */
	List<UserMemory> findByUserId(@Param("userId") Long userId);

	/**
	 * 根据ID查询记忆
	 */
	UserMemory findById(@Param("id") Long id);

	/**
	 * 插入新记忆
	 */
	int insert(UserMemory memory);

	/**
	 * 更新向量（向量化后回写）
	 */
	int updateEmbedding(@Param("id") Long id, @Param("embedding") String embedding,
			@Param("dim") Integer dim);

	/**
	 * 更新最近访问时间
	 */
	int updateLastAccessedAt(@Param("id") Long id);

	/**
	 * 软删除记忆
	 */
	int softDelete(@Param("id") Long id);

	/**
	 * 校验记忆是否属于指定用户
	 */
	int countByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

}
