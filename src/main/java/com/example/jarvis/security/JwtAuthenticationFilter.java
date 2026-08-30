package com.example.jarvis.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.List;

/**
 * JWT 认证过滤器
 *
 * 从 HTTP Header Authorization: Bearer <token> 中提取并校验 JWT，
 * 校验通过后将用户信息写入 Spring Security Context，
 * 后续 Controller 可通过 SecurityContextHolder 或 @AuthenticationPrincipal 获取。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

	private static final String AUTH_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtUtil jwtUtil;

	public JwtAuthenticationFilter(JwtUtil jwtUtil) {
		this.jwtUtil = jwtUtil;
	}

	@Override
	protected void doFilterInternal(
			@NonNull HttpServletRequest request,
			@NonNull HttpServletResponse response,
			@NonNull FilterChain filterChain
	) throws ServletException, IOException {

		String token = extractToken(request);

		if (token != null && jwtUtil.isValid(token)) {
			Long userId = jwtUtil.getUserId(token);
			String username = jwtUtil.getUsername(token);
			String role = jwtUtil.getRole(token);

			if (userId != null && username != null) {
				// 构造 Spring Security 认证对象（role 前加 "ROLE_" 以兼容 hasRole 表达式）
				var authorities = role == null
						? List.of(new SimpleGrantedAuthority("ROLE_USER"))
						: List.of(new SimpleGrantedAuthority("ROLE_" + role));

				var authentication = new UsernamePasswordAuthenticationToken(
						userId,            // principal: 存用户ID，Controller 可直接使用
						null,              // credentials: 已通过 token 校验，不需要密码
						authorities        // 权限/角色列表
				);
				authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

				// 写入 Security Context，本次请求后续都可以获取到用户身份
				SecurityContextHolder.getContext().setAuthentication(authentication);

				log.debug("Authenticated user: id={}, username={}, role={}", userId, username, role);
			}
		}

		filterChain.doFilter(request, response);
	}

	/**
	 * 从请求头中提取 Bearer Token（去掉前缀）
	 */
	private String extractToken(HttpServletRequest request) {
		String header = request.getHeader(AUTH_HEADER);
		if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
			return header.substring(BEARER_PREFIX.length());
		}
		return null;
	}

}
