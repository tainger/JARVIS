package com.example.jarvis.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 核心配置
 *
 * 设计要点：
 * 1. 无状态 JWT 认证（不使用 Session）
 * 2. CSRF 禁用（前后端分离 + JWT 模式）
 * 3. CORS 允许前端域名跨域访问
 * 4. 登录/注册/静态资源/H2 控制台等路径白名单放行
 * 5. 其余接口默认需要认证
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true, jsr250Enabled = true)
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;

	public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
	}

	/**
	 * BCrypt 密码编码器（强度因子 10，约 100ms/次，安全与性能平衡）
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(10);
	}

	/**
	 * CORS 跨域配置
	 * 允许所有来源（开发友好），生产环境可配置允许的域名
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration cfg = new CorsConfiguration();
		cfg.setAllowedOriginPatterns(List.of("*"));  // 允许所有来源（含携带 Cookie 的场景用此）
		cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
		cfg.setAllowedHeaders(List.of("*"));
		cfg.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
		cfg.setAllowCredentials(true);
		cfg.setMaxAge(3600L);  // 预检请求缓存 1 小时

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cfg);
		return source;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				// CORS
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				// 禁用 CSRF（无状态 JWT，不需要）
				.csrf(csrf -> csrf.disable())
				// 禁用默认的登录/登出页面（我们用 REST API）
				.formLogin(form -> form.disable())
				.httpBasic(basic -> basic.disable())
				.logout(logout -> logout.disable())
				// 无状态 Session：完全依赖 JWT
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// 请求授权规则
				.authorizeHttpRequests(auth -> auth
						// ===== 白名单（不需要登录） =====
						// 认证接口（登录、注册）
						.requestMatchers("/api/auth/**").permitAll()
						// H2 数据库控制台（仅开发用）
						.requestMatchers("/h2-console/**").permitAll()
						// 健康检查等
						.requestMatchers("/actuator/**").permitAll()
						// ===== 其他全部需要认证 =====
						.anyRequest().authenticated()
				)
				// 未认证（无 token）统一返回 401 JSON，而不是默认 403
				.exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
					response.setStatus(401);
					response.setContentType("application/json;charset=UTF-8");
					response.getWriter().write(
							"{\"error\":\"未登录或登录已过期\",\"detail\":\"请重新登录\"}");
				}))
				// H2 控制台用了 X-Frame-Options: DENY 会导致 iframe 打不开，开发时允许
				.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
				// 把 JWT 过滤器加到 UsernamePasswordAuthenticationFilter 之前
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

}
