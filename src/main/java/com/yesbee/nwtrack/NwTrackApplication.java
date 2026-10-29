package com.yesbee.nwtrack;

import com.yesbee.nwtrack.config.NwTrackProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(NwTrackProperties.class)
@EnableScheduling
public class NwTrackApplication {
    public static void main(String[] args) {
        SpringApplication.run(NwTrackApplication.class, args);
    }
}
