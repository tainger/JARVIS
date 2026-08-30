package com.example.jarvis.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway 数据库迁移 —— 显式配置（绕过 Spring Boot 4 / Flyway 11 的 AutoConfig 条件匹配问题）
 *
 * 为什么不直接依赖 FlywayAutoConfiguration？
 *   Spring Boot 4.0.7 + Flyway 11.14.1 组合下，启动日志显示 AutoConfig 未匹配（0 条 Flyway 相关日志）。
 *   本显式配置保持和 application.properties 中 spring.flyway.* 语义一致，并通过 @Bean(initMethod="migrate")
 *   保证 Spring 容器启动阶段就执行迁移（早于任何业务 Bean 的 @PostConstruct）。
 *
 * 执行顺序保障：
 *   1. Spring Boot 创建 DataSource（HikariPool）
 *   2. Flyway Bean 初始化 → initMethod=migrate → baseline（如需要）+ 跑 V1/V2...
 *   3. 其他 Bean（如 KnowledgeService）初始化 → @PostConstruct 此时表已齐全。
 *      （配合 KnowledgeService 上的 @DependsOnDatabaseInitialization 更稳妥）
 */
@Configuration
public class FlywayConfig {

	@Value("${spring.flyway.locations:classpath:db/migration}")
	private String locations;

	@Value("${spring.flyway.encoding:UTF-8}")
	private String encoding;

	@Value("${spring.flyway.baseline-on-migrate:true}")
	private boolean baselineOnMigrate;

	@Value("${spring.flyway.baseline-version:0}")
	private String baselineVersion;

	@Value("${spring.flyway.baseline-description:<< Flyway baseline >>}")
	private String baselineDescription;

	/**
	 * 创建 Flyway 实例；Spring 在 Bean 初始化后立刻调用 migrate()。
	 * 对已有数据库：先 baseline（version=0）→ 跑 V1__init.sql（所有 CREATE TABLE IF NOT EXISTS 幂等跳过）。
	 * 对全新空库：无需 baseline → 直接跑 V1。
	 */
	@Bean(name = "flyway", initMethod = "migrate")
	public Flyway flyway(DataSource dataSource) {
		return Flyway.configure()
				.dataSource(dataSource)
				.locations(locations.split(","))
				.encoding(encoding)
				.baselineOnMigrate(baselineOnMigrate)
				.baselineVersion(baselineVersion)
				.baselineDescription(baselineDescription)
				// 发现 Flyway 校验失败时不自动 Repair（默认 false，防止 checksum 变更时隐式改历史）
				// 需要 Repair 时在 Controller/CLI 调用 Flyway.repair() 显式执行
				.load();
	}
}
