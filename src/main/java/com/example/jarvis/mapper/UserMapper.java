package com.example.jarvis.mapper;

import java.util.List;

import com.example.jarvis.model.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户数据访问层
 */
@Mapper
public interface UserMapper {

	/**
	 * 根据用户名查询用户（登录、注册查重用）
	 */
	SysUser findByUsername(@Param("username") String username);

	/**
	 * 根据ID查询用户
	 */
	SysUser findById(@Param("id") Long id);

	/**
	 * 插入新用户（注册）
	 */
	int insert(SysUser user);

	/**
	 * 更新用户信息（昵称、邮箱、头像等）
	 */
	int update(SysUser user);

	/**
	 * 更新最近登录时间
	 */
	int updateLastLoginAt(@Param("id") Long id);

	/**
	 * 查询所有用户（管理后台）
	 */
	List<SysUser> findAll();

	/**
	 * 用户名是否已存在
	 */
	int countByUsername(@Param("username") String username);

}
