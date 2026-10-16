package pt.isep.sidis.flightops.monitoring;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** /dashboard opens the dashboard page (src/main/resources/static/dashboard.html). */
@Configuration
public class DashboardConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/dashboard", "/dashboard.html");
    }
}
