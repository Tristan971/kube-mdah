package moe.tristan.kmdah.service.leader;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import moe.tristan.kmdah.service.gossip.elections.GrantedLeadershipEvent;
import moe.tristan.kmdah.service.gossip.elections.RevokedLeadershipEvent;

@Component
public class LeaderActivities {

    private static final Logger LOGGER = LoggerFactory.getLogger(LeaderActivities.class);
    private static final ScheduledExecutorService EXECUTOR_SERVICE = Executors.newScheduledThreadPool(4);

    private final Set<LeaderActivity> leaderActivities;
    private final Map<String, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();

    public LeaderActivities(Set<LeaderActivity> leaderActivities) {
        this.leaderActivities = leaderActivities;
    }

    @EventListener(GrantedLeadershipEvent.class)
    public void grantedLeadership() {
        LOGGER.info("Starting leader activities...");
        startJobs();
        LOGGER.info("Started leader activities!");
    }

    @EventListener(classes = {
        RevokedLeadershipEvent.class,
        ContextClosedEvent.class
    })
    public void revokedLeadership() {
        LOGGER.info("Stopping leader activities if any...");
        stopJobs();
        LOGGER.info("Stopped leader activities!");
    }

    private void startJobs() {
        leaderActivities.forEach(activity -> {
            ScheduledFuture<?> job = EXECUTOR_SERVICE.scheduleAtFixedRate(
                () -> {
                    try {
                        activity.run();
                    } catch (Throwable e) {
                        LOGGER.error("Activity {} threw an error", activity.getName(), e);
                    }
                },
                activity.getInitialDelay().toSeconds(),
                activity.getPeriod().toSeconds(),
                TimeUnit.SECONDS
            );
            jobs.put(activity.getName(), job);
            LOGGER.info(
                "Started activity [{}] (initial delay: {}s, period: {}s)",
                activity.getName(),
                activity.getInitialDelay().toSeconds(),
                activity.getPeriod().toSeconds()
            );
        });
    }

    private void stopJobs() {
        if (jobs.isEmpty()) {
            LOGGER.info("Either this wasn't a leader, or all leader activities were already stopped.");
            return;
        }

        leaderActivities.forEach(activity -> {
            ScheduledFuture<?> job = jobs.get(activity.getName());
            if (job == null) {
                LOGGER.debug("Activity [{}] cannot be stopped because it had no previously started job!", activity.getName());
            } else {
                try {
                    job.cancel(true); // stop scheduler
                    LOGGER.info("[{}] unscheduled", activity.getName());
                } catch (Throwable e) {
                    LOGGER.error("[{}] Failed unscheduling!", activity.getName(), e);
                }

                try {
                    activity.stop(); // execute teardown logic of activity
                    LOGGER.info("[{}] stopped", activity.getName());
                } catch (Throwable e) {
                    LOGGER.error("[{}] Failed stopping!", activity.getName(), e);
                }
            }
        });
        jobs.clear();
    }

}
