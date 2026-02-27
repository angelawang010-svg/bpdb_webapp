# BlogPlatformDB Migration — Brainstorming Session Notes

## Decisions Made

| Choice          | Decision                     |
|-----------------|------------------------------|
| Framework       | Spring Boot                  |
| Java version    | Java 21 (LTS)               |
| Build tool      | Gradle                       |
| Database        | PostgreSQL                   |
| API style       | REST API                     |
| Authentication  | Session-based                |
| Scope           | Back-end only (for now)      |
| Architecture    | Package-by-Feature with Layers |
| Deployment      | Self-hosted, ~few thousand users |

## Where We Left Off

We are in the **design presentation phase** (step 4 of 6 in the brainstorming process).

- Completed: project context exploration, clarifying questions, approach selection
- **Just finished:** Presented **Section 1: Project Structure** (folder/package layout)
- **Next step:** Review Section 1, then continue with remaining design sections:
  - Entity/data model design (mapping the 17 SQL Server tables to JPA entities)
  - API endpoint design (REST routes)
  - Business logic migration (stored procedures, triggers, views → Java services)
  - Security and auth design
  - Testing strategy

## Important Context

- The existing project is a SQL Server database with 17 tables, 8 views, 8 functions, 5 stored procedures, and 4 triggers
- Schema includes: users, roles, authors, posts, comments, likes, tags, categories, payments, subscriptions, notifications, images, saved/read posts, and audit logging
- Business rules to preserve: users must read a post before commenting, 250-char comment limit, soft deletes on posts, VIP upgrade with payment, subscriber notifications on new posts
- Comments in the original SQL are in German
- The design doc has not been written yet — that happens after all sections are approved
