package io.deltaflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DeltaFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeltaFlowApplication.class, args);
    }
}

