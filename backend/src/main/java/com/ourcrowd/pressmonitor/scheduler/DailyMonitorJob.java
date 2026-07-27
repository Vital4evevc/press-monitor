package com.ourcrowd.pressmonitor.scheduler;

import com.ourcrowd.pressmonitor.llm.OllamaClient;
import com.ourcrowd.pressmonitor.service.PipelineRunner;
import com.ourcrowd.pressmonitor.service.RunResult;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Runs the monitoring pipeline once a day, scheduled through Quartz — see config.QuartzConfig
 * for the JobDetail/cron Trigger wiring (the cron comes from monitoring.daily-cron).
 */
public class DailyMonitorJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(DailyMonitorJob.class);

    @Autowired
    private PipelineRunner pipeline;

    @Autowired
    private OllamaClient ollama;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("=== Daily press monitoring job triggered (Quartz) ===");
        if (!ollama.isReachable()) {
            log.error("Ollama server is not reachable — skipping run. "
                    + "Start it with `ollama serve` and pull the model first.");
            return;
        }
        try {
            RunResult result = pipeline.runFullPipeline();
            log.info("Daily job finished: {} new mention(s).", result.newMentionCount());
        } catch (Exception e) {
            log.error("Daily job failed: {}", e.getMessage(), e);
            // Let Quartz see the failure too, instead of swallowing it — it'll show up in
            // trigger/job history, and Quartz's default misfire/refire policies can react to it.
            throw new JobExecutionException(e);
        }
    }
}
