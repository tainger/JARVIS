package com.example.jarvis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置。
 *
 * 自定义线程名前缀 + 拒绝策略 + 异常统一捕获，方便排查 @Async 问题。
 *
 * 排查要点：
 * 1. 日志中出现 "async-kg-1" 等线程名 → @Async 生效
 * 2. 日志中出现 "http-nio-8080-exec-x" → @Async 未生效（自调用或代理丢失）
 * 3. 异步方法抛异常时，统一走 AsyncExceptionHandler，打印 ERROR 日志，不会静默丢失
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

	private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

	/**
	 * 知识库导入专用线程池：单线程串行，避免并发打崩 Ollama。
	 * 线程名前缀：async-kg-，方便在日志里一眼识别。
	 */
	@Bean("knowledgeExecutor")
	public ThreadPoolTaskExecutor knowledgeExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("async-kg-");
		executor.setKeepAliveSeconds(60);
		// 队列满了就由调用方线程执行，不丢任务
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(120);
		executor.initialize();
		log.info("知识库异步线程池已初始化：线程名前缀 = async-kg-, 核心线程 = 1, 队列 = 100");
		return executor;
	}

	@Override
	public Executor getAsyncExecutor() {
		// 默认异步执行器也用同一个，保持行为一致
		return knowledgeExecutor();
	}

	@Override
	public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
		return new AsyncExceptionHandler();
	}

	/**
	 * 异步任务未捕获异常处理器 — 防止 void 异步方法抛异常后静默丢失。
	 * 统一打 ERROR 日志，包含方法名、参数、异常栈，方便排查。
	 */
	static class AsyncExceptionHandler implements AsyncUncaughtExceptionHandler {

		private static final Logger log = LoggerFactory.getLogger(AsyncExceptionHandler.class);

		@Override
		public void handleUncaughtException(Throwable ex, Method method, Object... params) {
			log.error("异步任务异常 — 方法: {}, 参数: {}, 异常: {}",
					method.getName(),
					params == null ? "[]" : params.length + " 个参数",
					ex.getMessage(), ex);
		}
	}
}
