package br.com.fixflow.shared.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class HealthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ConfigurableApplicationContext context;

    @Test
    void exposesPublicProbesAndCorrelationHeadersWithoutDetails() throws Exception {
        for (String path : new String[]{"/health", "/health/liveness", "/health/readiness"}) {
            mvc.perform(get(path).header("X-Request-Id", "probe-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist())
                    .andExpect(header().string("X-Request-Id", "probe-123"))
                    .andExpect(header().string("X-Correlation-Id", "probe-123"));
        }
    }

    @Test
    void readinessCanRefuseTrafficWhileLivenessRemainsUp() throws Exception {
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
            mvc.perform(get("/health/readiness")).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/health/liveness")).andExpect(status().isOk());
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }
}
