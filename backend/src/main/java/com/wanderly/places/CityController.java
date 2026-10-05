package com.wanderly.places;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cities")
public class CityController {

    private final CityDirectory directory;

    public CityController(CityDirectory directory) {
        this.directory = directory;
    }

    /** Instant search over bundled destinations (safe to call on every keystroke). */
    @GetMapping
    public List<CityDirectory.City> search(@RequestParam(defaultValue = "") String q,
                                           @RequestParam(defaultValue = "8") int limit) {
        return directory.search(q, Math.clamp(limit, 1, 20));
    }

    /** Any city, town or village in India via Photon (OpenStreetMap). Safe for type-ahead: debounced client-side, cached here. */
    @GetMapping("/suggest")
    public List<CityDirectory.City> suggest(@RequestParam String q) {
        return directory.suggest(q);
    }
}
