package dev.haris.system;

import dev.haris.settings.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The home city's weather for the first-launch status greeting; 204 when there is no city or no answer. */
@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final SettingsService settings;
    private final WeatherService weather;

    public WeatherController(SettingsService settings, WeatherService weather) {
        this.settings = settings;
        this.weather = weather;
    }

    /** {@code lang} (en|ar) only picks the language of the place name. */
    @GetMapping("/now")
    public ResponseEntity<WeatherService.Now> now(@RequestParam(defaultValue = "en") String lang) {
        return weather.now(settings.get().homeCity(), lang)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
