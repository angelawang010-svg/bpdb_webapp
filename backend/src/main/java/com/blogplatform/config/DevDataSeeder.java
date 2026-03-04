package com.blogplatform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final JdbcTemplate jdbcTemplate;

    public DevDataSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE username = 'admin'", Integer.class);
        if (count != null && count > 0) {
            log.info("Dev admin user already exists, skipping seed");
            return;
        }

        // BCrypt hash with work factor 12 — dev-only, never use in production
        jdbcTemplate.update("""
                INSERT INTO user_account (username, email, password_hash, role, email_verified)
                VALUES ('admin', 'admin@blogplatform.com',
                        '$2a$12$LJ3m4ys3uz0b/tMkgqHUZeJ0SJyKfxBVOKFqW8GbMFmJN7gmPVqtG',
                        'ADMIN', TRUE)
                """);

        jdbcTemplate.update("""
                INSERT INTO user_profile (account_id, first_name, last_name)
                SELECT account_id, 'System', 'Admin' FROM user_account WHERE username = 'admin'
                """);

        log.info("Dev admin user seeded successfully");
    }
}
