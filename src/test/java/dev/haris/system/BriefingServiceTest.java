package dev.haris.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The status greeting already gives the weather, so the daily briefing no longer asks for it. */
class BriefingServiceTest {

    @Test
    void thePromptSkipsTheWeatherAndKeepsTheRest() {
        assertThat(BriefingService.PROMPT).doesNotContainIgnoringCase("weather")
                .contains("today's date", "reminders", "web_search", "memory", "Under 120");
    }
}
