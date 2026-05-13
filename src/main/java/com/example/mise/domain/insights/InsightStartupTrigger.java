package com.example.mise.domain.insights;

import com.example.mise.domain.household.HouseholdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * UC-009 BR-04 (a): On app startup, generate an insight for each existing household
 * if more than 7 days have elapsed since the last insight (or if no insight exists yet).
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
