package com.example.jarvis.security;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 工具类
 * 负责生成、解析、校验 JWT Token
 *
 * 算法：HMAC-SHA256（HS256）
 */
@Component
public class JwtUtil {

	private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

	/** JWT 签名密钥（至少 256 bit，建议 >= 32 字符） */
	@Value("${auth.jwt.secret:jarvis-super-secret-key-change-in-production-please-1234567890}")
	private String secret;

	/** Token 过期时间（秒），默认 7 天 */
	@Value("${auth.jwt.expire-seconds:604800}")
	private long expireSeconds;

	/**
	 * 生成签名密钥
	 */
	private Key getSigningKey() {
		return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * 生成 JWT Token
	 *
	 * @param userId   用户ID
	 * @param username 用户名
	 * @param role     用户角色
	 * @return JWT token 字符串
	 */
	public String generateToken(Long userId, String username, String role) {
		Date now = new Date();
		Date expiry = new Date(now.getTime() + expireSeconds * 1000L);

		return Jwts.builder()
				.subject(String.valueOf(userId))
				.claim("username", username)
				.claim("role", role)
				.issuedAt(now)
				.expiration(expiry)
				.signWith(getSigningKey())
				.compact();
	}

	/**
	 * 从 Token 中解析 Claims
	 *
	 * @param token JWT token
	 * @return Claims 或 null（解析失败）
	 */
	public Claims parseClaims(String token) {
		try {
			return Jwts.parser()
					.setSigningKey(getSigningKey())
					.build()
					.parseClaimsJws(token)
					.getBody();
		} catch (ExpiredJwtException e) {
			log.warn("JWT token expired: {}", e.getMessage());
		} catch (UnsupportedJwtException e) {
			log.warn("JWT unsupported token: {}", e.getMessage());
		} catch (MalformedJwtException e) {
			log.warn("JWT malformed token: {}", e.getMessage());
		} catch (SignatureException e) {
			log.warn("JWT signature validation failed: {}", e.getMessage());
		} catch (IllegalArgumentException e) {
			log.warn("JWT token empty or null");
		}
		return null;
	}

	/**
	 * Token 是否有效（可解析 & 未过期）
	 */
	public boolean isValid(String token) {
		return parseClaims(token) != null;
	}

	/**
	 * 从 Token 中提取用户ID
	 */
	public Long getUserId(String token) {
		Claims c = parseClaims(token);
		if (c == null || c.getSubject() == null) return null;
		try {
			return Long.parseLong(c.getSubject());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * 从 Token 中提取用户名
	 */
	public String getUsername(String token) {
		Claims c = parseClaims(token);
		return c == null ? null : c.get("username", String.class);
	}

	/**
	 * 从 Token 中提取角色
	 */
	public String getRole(String token) {
		Claims c = parseClaims(token);
		return c == null ? null : c.get("role", String.class);
	}

	public long getExpireSeconds() {
		return expireSeconds;
	}

}
