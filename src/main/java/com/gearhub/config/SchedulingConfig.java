package com.gearhub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Tarefas agendadas (coleta da aba Games) e execução em segundo plano. */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {
}
