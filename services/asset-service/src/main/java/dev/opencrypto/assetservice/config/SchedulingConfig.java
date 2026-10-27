package dev.opencrypto.assetservice.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduled jobs + ShedLock (a row in the {@code shedlock} table acts as a cluster-wide mutex). */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingConfig {
 @Bean
 LockProvider lockProvider(DataSource dataSource) {
  return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
   .withJdbcTemplate(new JdbcTemplate(dataSource))
   .usingDbTime()
   .build());
 }
}
