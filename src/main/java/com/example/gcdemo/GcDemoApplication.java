package com.example.gcdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * G1 GC Lab 的 Spring Boot 启动入口。
 *
 * <p>该应用不是常规业务服务，而是一个可观测的 JVM 教学实验台：HTTP 请求会主动制造
 * 分配、晋升、Humongous、Mixed GC、Full GC、类卸载等场景。GC 参数必须在 JVM
 * 启动阶段通过 {@code scripts/run.sh} 传入，不能由 Spring 配置动态修改。</p>
 *
 * <p>启用 {@code @EnableScheduling} 是为了定时轮询 GC 日志；启用
 * {@code @ConfigurationPropertiesScan} 是为了绑定 {@code demo.*} 安全限制。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GcDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(GcDemoApplication.class, args);
    }
}
