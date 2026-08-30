package com.example.jarvis.service;

import com.example.jarvis.dto.AuthResponse;
import com.example.jarvis.dto.LoginRequest;
import com.example.jarvis.dto.RegisterRequest;
import com.example.jarvis.mapper.UserMapper;
import com.example.jarvis.model.SysUser;
import com.example.jarvis.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户认证服务（登录 + 注册）
 */
@Service
public class UserService {

	private static final Logger log = LoggerFactory.getLogger(UserService.class);

	/** 角色常量 */
	public static final String ROLE_USER = "USER";
	public static final String ROLE_ADMIN = "ADMIN";
	/** 状态常量 */
	public static final String STATUS_ACTIVE = "ACTIVE";
	public static final String STATUS_DISABLED = "DISABLED";

	private final UserMapper userMapper;
	private final PasswordEncoder passwordEncoder;
	private final JwtUtil jwtUtil;

	public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
		this.userMapper = userMapper;
		this.passwordEncoder = passwordEncoder;
		this.jwtUtil = jwtUtil;
	}

	// ======================================================================
	// 注册
	// ======================================================================

	/**
	 * 用户注册
	 *
	 * @param req 注册请求
	 * @return 注册成功返回 AuthResponse（含 token + 用户信息）
	 * @throws IllegalArgumentException 参数校验失败
	 */
	@Transactional
	public AuthResponse register(RegisterRequest req) {
		// 1. 参数校验
		validateRegister(req);

		// 2. 用户名唯一性检查
		if (userMapper.countByUsername(req.username()) > 0) {
			throw new IllegalArgumentException("用户名 [" + req.username() + "] 已被占用");
		}

		// 3. 构造用户对象并加密密码
		SysUser user = new SysUser();
		user.setUsername(req.username().trim());
		user.setPasswordHash(passwordEncoder.encode(req.password()));
		// 昵称：传了就用，没传就和用户名一样
		user.setNickname(StringUtils.hasText(req.nickname()) ? req.nickname().trim() : req.username().trim());
		user.setEmail(StringUtils.hasText(req.email()) ? req.email().trim() : null);
		user.setRole(ROLE_USER);   // 默认为普通用户，后续可由管理员升级为 ADMIN
		user.setStatus(STATUS_ACTIVE);
		user.setAvatarUrl(null);

		// 4. 写入数据库
		int affected = userMapper.insert(user);
		if (affected <= 0 || user.getId() == null) {
			throw new IllegalStateException("用户注册失败：数据库未返回主键");
		}

		log.info("User registered: id={}, username={}", user.getId(), user.getUsername());

		// 5. 更新登录时间（首次注册视为一次登录）
		userMapper.updateLastLoginAt(user.getId());

		// 6. 生成 JWT 并返回
		return buildAuthResponse(user);
	}

	// ======================================================================
	// 登录
	// ======================================================================

	/**
	 * 用户登录
	 *
	 * @param req 登录请求
	 * @return 登录成功返回 AuthResponse
	 * @throws IllegalArgumentException 用户名不存在 / 密码错误 / 账户禁用
	 */
	@Transactional
	public AuthResponse login(LoginRequest req) {
		// 1. 参数校验
		if (!StringUtils.hasText(req.username())) {
			throw new IllegalArgumentException("用户名不能为空");
		}
		if (!StringUtils.hasText(req.password())) {
			throw new IllegalArgumentException("密码不能为空");
		}

		// 2. 根据用户名查用户
		SysUser user = userMapper.findByUsername(req.username().trim());
		if (user == null) {
			throw new IllegalArgumentException("用户名或密码错误");
		}

		// 3. 检查账户状态
		if (STATUS_DISABLED.equals(user.getStatus())) {
			throw new IllegalArgumentException("账户已被禁用，请联系管理员");
		}

		// 4. 校验密码（BCrypt 恒定时间比较，防止时序攻击）
		if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
			throw new IllegalArgumentException("用户名或密码错误");
		}

		// 5. 更新最近登录时间
		userMapper.updateLastLoginAt(user.getId());

		log.info("User logged in: id={}, username={}", user.getId(), user.getUsername());

		// 6. 生成 JWT 并返回
		return buildAuthResponse(user);
	}

	// ======================================================================
	// 辅助方法
	// ======================================================================

	/**
	 * 根据ID查询用户（其他服务用）
	 */
	public SysUser findById(Long userId) {
		return userMapper.findById(userId);
	}

	/**
	 * 参数校验：注册请求
	 */
	private void validateRegister(RegisterRequest req) {
		String username = req.username() == null ? null : req.username().trim();
		String password = req.password();

		if (!StringUtils.hasText(username)) {
			throw new IllegalArgumentException("用户名不能为空");
		}
		if (username.length() < 3 || username.length() > 32) {
			throw new IllegalArgumentException("用户名长度必须在 3-32 个字符之间");
		}
		// 只允许字母、数字、下划线、点
		if (!username.matches("^[A-Za-z0-9_.]+$")) {
			throw new IllegalArgumentException("用户名只能包含字母、数字、下划线和点");
		}

		if (!StringUtils.hasText(password)) {
			throw new IllegalArgumentException("密码不能为空");
		}
		if (password.length() < 6 || password.length() > 64) {
			throw new IllegalArgumentException("密码长度必须在 6-64 个字符之间");
		}

		// 邮箱格式校验（填了才校验）
		String email = req.email() == null ? null : req.email().trim();
		if (StringUtils.hasText(email)) {
			if (!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
				throw new IllegalArgumentException("邮箱格式不正确");
			}
		}
	}

	/**
	 * 组装认证响应（JWT + 用户信息）
	 */
	private AuthResponse buildAuthResponse(SysUser user) {
		String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
		return new AuthResponse(
				token,
				"Bearer",
				jwtUtil.getExpireSeconds(),
				AuthResponse.UserVO.from(user)
		);
	}

}
