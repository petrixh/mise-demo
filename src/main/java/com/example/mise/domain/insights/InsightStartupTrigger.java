package com.example.mise.domain.insights;

import com.example.mise.domain.household.HouseholdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * UC-009 BR-04 (a): On app startup, generate an insight for each existing household
 * when {@link InsightService#shouldTriggerStartup} returns true.
 *
 * <p>The trigger fires when any of the following hold:
 * <ul>
 *   <li>No insight exists yet for the household.
 *   <li>The most-recent insight is older than 7 days.
 *   <li>All existing insights are dismissed (queue is empty) — dismissal is
 *       per-insight, not a 7-day global snooze.
 * </ul>
 *
 * <p><b>Dev reset / retrigger</b>: to force the banner to reappear for testing,
 * mark every insight dismissed via the H2 console at {@code /h2-console}:
 * <pre>{@code UPDATE insight SET dismissed = TRUE, dismissed_at = NOW() WHERE dismissed = FALSE;}</pre>
 * Then restart the app — the startup trigger will detect an empty undismissed queue
 * and generate a fresh insight. Alternatively, delete all rows ({@code DELETE FROM insight;})
 * to exercise the "no insight ever" path.
 *
 * <p>Wrapped in try/catch so a failure here never prevents app boot.
 */
@Component
public class InsightStartupTrigger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InsightStartupTrigger.class);

    private final HouseholdService householdService;
    private final InsightService insightService;

    public InsightStartupTrigger(HouseholdService householdService,
                                 InsightService insightService) {
        this.householdService = householdService;
        this.insightService = insightService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            householdService.findHousehold().ifPresent(hh -> {
                try {
                    if (insightService.shouldTriggerStartup(hh.getId())) {
                        var insight = insightService.generate(hh.getId());
                        insight.ifPresentOrElse(
                                i -> log.info("UC-009 startup insight generated for household {}: {}",
                                        hh.getId(), i.getBody()),
                                () -> log.debug("UC-009 startup: no insight generated (insufficient history) for household {}",
                                        hh.getId())
                        );
                    } else {
                        log.debug("UC-009 startup: insight trigger window has not elapsed for household {}", hh.getId());
                    }
                } catch (Exception e) {
                    log.warn("UC-009 startup insight generation failed for household {}: {}", hh.getId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            // Never fail app boot for insight generation
            log.warn("UC-009 InsightStartupTrigger failed: {}", e.getMessage());
        }
    }
}
