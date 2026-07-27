package com.ourcrowd.pressmonitor.config;

import com.ourcrowd.pressmonitor.scheduler.AutowiringSpringBeanJobFactory;
import com.ourcrowd.pressmonitor.scheduler.DailyMonitorJob;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;

/**
 * Wires the daily monitoring run through Quartz instead of Spring's simple @Scheduled: a
 * durable JobDetail for DailyMonitorJob, plus a cron CronTrigger built from
 * monitoring.daily-cron, and a custom job factory so Quartz-created job instances actually
 * get their @Autowired dependencies from the Spring context. Quartz instantiates jobs
 * itself, so that autowiring doesn't happen for free — see AutowiringSpringBeanJobFactory
 * for how we make it happen.
 *
 * One thing to watch for: Quartz cron expressions are stricter than Spring's. Exactly one
 * of the day-of-month and day-of-week fields has to be "?" (Spring lets you use "*" for
 * both). monitoring.daily-cron defaults to "0 0 7 * * ?", not "0 0 7 * * *" — if you
 * override it, make sure it's valid Quartz syntax or the app will fail to start.
 */
@Configuration
public class QuartzConfig {

    // The name Quartz refers to this job by.
    public static final String DAILY_MONITOR_JOB = "dailyMonitorJob";

    @Bean
    public AutowiringSpringBeanJobFactory jobFactory() {
        return new AutowiringSpringBeanJobFactory();
    }

    // Spring Boot already auto-configures the SchedulerFactoryBean — this just swaps in our
    // own job factory.
    @Bean
    public SchedulerFactoryBeanCustomizer schedulerFactoryBeanCustomizer(AutowiringSpringBeanJobFactory jobFactory) {
        return schedulerFactoryBean -> schedulerFactoryBean.setJobFactory(jobFactory);
    }

    @Bean
    public JobDetailFactoryBean dailyMonitorJobDetail() {
        JobDetailFactoryBean factory = new JobDetailFactoryBean();
        factory.setJobClass(DailyMonitorJob.class);
        factory.setName(DAILY_MONITOR_JOB);
        factory.setDescription("Collects new press mentions and alerts on new coverage.");
        // Keeps the JobDetail registered independent of its trigger's lifecycle — also
        // needed so scheduler.triggerJob(...) can find it by key.
        factory.setDurability(true);
        return factory;
    }

    @Bean
    public CronTriggerFactoryBean dailyMonitorTrigger(JobDetail dailyMonitorJobDetail, MonitoringProperties props) {
        CronTriggerFactoryBean trigger = new CronTriggerFactoryBean();
        trigger.setJobDetail(dailyMonitorJobDetail);
        trigger.setCronExpression(props.getDailyCron());
        trigger.setName("dailyMonitorTrigger");
        // If the app happened to be down at the scheduled time, run the job once, right
        // away, the moment the scheduler starts back up — rather than silently waiting for
        // the next scheduled day (which is what DO_NOTHING would do). This only does
        // anything useful now that the job store is JDBC-backed (see application.yml):
        // that's what makes the missed fire durable across the restart in the first place,
        // instead of being lost the moment the old JVM exited.
        trigger.setMisfireInstruction(CronTrigger.MISFIRE_INSTRUCTION_FIRE_ONCE_NOW);
        return trigger;
    }
}
